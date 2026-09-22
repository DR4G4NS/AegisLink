/*
 * Aegis Linux Portal/PipeWire capture bridge.
 *
 * This is intentionally a short-lived helper, one process per capture
 * session. It owns the portal session and the PipeWire stream, copies only a
 * bounded raw frame into a pipe, and never implements WebRTC or a codec.
 * stdout is a private length-delimited frame stream; diagnostics are stderr.
 */
#define _GNU_SOURCE

#include <dbus/dbus.h>
#include <pipewire/pipewire.h>
#include <spa/param/video/format-utils.h>
#include <spa/param/video/raw-utils.h>
#include <spa/pod/builder.h>
#include <spa/utils/defs.h>

#include <errno.h>
#include <inttypes.h>
#include <limits.h>
#include <netinet/in.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define PORTAL_BUS_NAME "org.freedesktop.portal.Desktop"
#define PORTAL_OBJECT_PATH "/org/freedesktop/portal/desktop"
#define PORTAL_SCREENCAST_IFACE "org.freedesktop.portal.ScreenCast"
#define PORTAL_REQUEST_IFACE "org.freedesktop.portal.Request"
#define PORTAL_SESSION_IFACE "org.freedesktop.portal.Session"
#define MAX_FRAME_BYTES (64u * 1024u * 1024u)
#define MAX_FRAME_DIMENSION 8192u
#define PORTAL_TIMEOUT_MS 120000
#define FRAME_MAGIC 0x41454753u /* AEGS */
#define FRAME_VERSION 1u

static void report_dbus_error(const char *stage, DBusError *error) {
    if (error != NULL && dbus_error_is_set(error)) {
        fprintf(stderr, "aegis portal %s: %s\n", stage, error->message);
    } else {
        fprintf(stderr, "aegis portal %s failed\n", stage);
    }
}

static char *copy_string(const char *value) {
    return value == NULL ? NULL : strdup(value);
}

static dbus_bool_t append_dict_entry_string(
        DBusMessageIter *array,
        const char *key,
        const char *value) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    if (!dbus_message_iter_open_container(array, DBUS_TYPE_DICT_ENTRY, NULL, &entry)) return FALSE;
    if (!dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key)) return FALSE;
    if (!dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "s", &variant)) return FALSE;
    if (!dbus_message_iter_append_basic(&variant, DBUS_TYPE_STRING, &value)) return FALSE;
    if (!dbus_message_iter_close_container(&entry, &variant)) return FALSE;
    return dbus_message_iter_close_container(array, &entry);
}

static dbus_bool_t append_dict_entry_bool(
        DBusMessageIter *array,
        const char *key,
        dbus_bool_t value) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    if (!dbus_message_iter_open_container(array, DBUS_TYPE_DICT_ENTRY, NULL, &entry)) return FALSE;
    if (!dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key)) return FALSE;
    if (!dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "b", &variant)) return FALSE;
    if (!dbus_message_iter_append_basic(&variant, DBUS_TYPE_BOOLEAN, &value)) return FALSE;
    if (!dbus_message_iter_close_container(&entry, &variant)) return FALSE;
    return dbus_message_iter_close_container(array, &entry);
}

static dbus_bool_t append_dict_entry_uint32(
        DBusMessageIter *array,
        const char *key,
        dbus_uint32_t value) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    if (!dbus_message_iter_open_container(array, DBUS_TYPE_DICT_ENTRY, NULL, &entry)) return FALSE;
    if (!dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key)) return FALSE;
    if (!dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "u", &variant)) return FALSE;
    if (!dbus_message_iter_append_basic(&variant, DBUS_TYPE_UINT32, &value)) return FALSE;
    if (!dbus_message_iter_close_container(&entry, &variant)) return FALSE;
    return dbus_message_iter_close_container(array, &entry);
}

static dbus_bool_t finish_dict(DBusMessageIter *outer, DBusMessageIter *array) {
    return dbus_message_iter_close_container(outer, array);
}

static char *send_request_and_get_path(
        DBusConnection *connection,
        DBusMessage *message,
        DBusError *error) {
    DBusMessage *reply = dbus_connection_send_with_reply_and_block(
        connection, message, PORTAL_TIMEOUT_MS, error);
    dbus_message_unref(message);
    if (reply == NULL) return NULL;
    if (dbus_message_get_type(reply) == DBUS_MESSAGE_TYPE_ERROR) {
        report_dbus_error("request", error);
        dbus_message_unref(reply);
        return NULL;
    }
    DBusMessageIter iter;
    const char *path = NULL;
    if (!dbus_message_iter_init(reply, &iter) ||
            dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_OBJECT_PATH) {
        fprintf(stderr, "aegis portal request returned no handle\n");
        dbus_message_unref(reply);
        return NULL;
    }
    dbus_message_iter_get_basic(&iter, &path);
    char *copy = copy_string(path);
    dbus_message_unref(reply);
    return copy;
}

static int wait_for_response(
        DBusConnection *connection,
        const char *request_path,
        const char *stage,
        DBusMessage **response_out) {
    struct timespec deadline;
    if (clock_gettime(CLOCK_MONOTONIC, &deadline) != 0) return -errno;
    deadline.tv_sec += PORTAL_TIMEOUT_MS / 1000;
    deadline.tv_nsec += (PORTAL_TIMEOUT_MS % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) {
        deadline.tv_sec++;
        deadline.tv_nsec -= 1000000000L;
    }

    DBusMessage *response = NULL;
    while (response == NULL) {
        struct timespec now;
        if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -errno;
        int64_t remaining_ms =
            (int64_t)(deadline.tv_sec - now.tv_sec) * 1000L +
            (int64_t)(deadline.tv_nsec - now.tv_nsec) / 1000000L;
        if (remaining_ms <= 0) break;
        int wait_ms = remaining_ms < 100 ? (int)remaining_ms : 100;
        dbus_connection_read_write(connection, wait_ms);
        while ((response = dbus_connection_pop_message(connection)) != NULL) {
            const char *response_path = dbus_message_get_path(response);
            if (response_path != NULL &&
                    dbus_message_is_signal(response, PORTAL_REQUEST_IFACE, "Response") &&
                    strcmp(response_path, request_path) == 0) {
                break;
            }
            dbus_message_unref(response);
            response = NULL;
        }
    }
    if (response == NULL) {
        fprintf(stderr, "aegis portal %s response timed out or was cancelled\n", stage);
        return -ETIMEDOUT;
    }
    *response_out = response;
    return 0;
}

static int response_status_and_dict(
        DBusMessage *response,
        DBusMessageIter *dict_out,
        DBusError *error) {
    DBusMessageIter iter;
    if (!dbus_message_iter_init(response, &iter) ||
            dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_UINT32) {
        fprintf(stderr, "aegis portal returned a malformed response status\n");
        return -EPROTO;
    }
    dbus_uint32_t status = 1;
    dbus_message_iter_get_basic(&iter, &status);
    if (status != 0) {
        fprintf(stderr, "aegis portal request was denied or cancelled (status=%u)\n", status);
        return -EACCES;
    }
    if (!dbus_message_iter_next(&iter) ||
            dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_ARRAY) {
        fprintf(stderr, "aegis portal response has no result dictionary\n");
        return -EPROTO;
    }
    dbus_message_iter_recurse(&iter, dict_out);
    (void)error;
    return 0;
}

static int dict_find_object_path(DBusMessageIter *dict, const char *wanted, char **value_out) {
    DBusMessageIter cursor = *dict;
    while (dbus_message_iter_get_arg_type(&cursor) == DBUS_TYPE_DICT_ENTRY) {
        DBusMessageIter entry;
        dbus_message_iter_recurse(&cursor, &entry);
        const char *key = NULL;
        dbus_message_iter_get_basic(&entry, &key);
        if (key != NULL && strcmp(key, wanted) == 0 && dbus_message_iter_next(&entry)) {
            DBusMessageIter variant;
            dbus_message_iter_recurse(&entry, &variant);
            int value_type = dbus_message_iter_get_arg_type(&variant);
            if (value_type == DBUS_TYPE_OBJECT_PATH || value_type == DBUS_TYPE_STRING) {
                const char *path = NULL;
                dbus_message_iter_get_basic(&variant, &path);
                if (path == NULL || !dbus_validate_path(path, NULL)) return -EPROTO;
                *value_out = copy_string(path);
                return *value_out == NULL ? -ENOMEM : 0;
            }
        }
        if (!dbus_message_iter_next(&cursor)) break;
    }
    return -ENOENT;
}

static int parse_first_stream_node(DBusMessageIter *dict, uint32_t *node_id_out) {
    DBusMessageIter cursor = *dict;
    while (dbus_message_iter_get_arg_type(&cursor) == DBUS_TYPE_DICT_ENTRY) {
        DBusMessageIter entry;
        dbus_message_iter_recurse(&cursor, &entry);
        const char *key = NULL;
        dbus_message_iter_get_basic(&entry, &key);
        if (key != NULL && strcmp(key, "streams") == 0 && dbus_message_iter_next(&entry)) {
            DBusMessageIter variant;
            dbus_message_iter_recurse(&entry, &variant);
            if (dbus_message_iter_get_arg_type(&variant) != DBUS_TYPE_ARRAY) return -EPROTO;
            DBusMessageIter streams;
            dbus_message_iter_recurse(&variant, &streams);
            if (dbus_message_iter_get_arg_type(&streams) != DBUS_TYPE_STRUCT) return -EPROTO;
            DBusMessageIter stream;
            dbus_message_iter_recurse(&streams, &stream);
            if (dbus_message_iter_get_arg_type(&stream) != DBUS_TYPE_UINT32) return -EPROTO;
            dbus_message_iter_get_basic(&stream, node_id_out);
            return 0;
        }
        if (!dbus_message_iter_next(&cursor)) break;
    }
    return -ENOENT;
}

static int request_session(
        DBusConnection *connection,
        char **session_path_out,
        uint32_t *node_id_out,
        int *pipewire_fd_out) {
    DBusError error;
    dbus_error_init(&error);
    dbus_bus_add_match(
        connection,
        "type='signal',interface='org.freedesktop.portal.Request',member='Response'",
        &error);
    if (dbus_error_is_set(&error)) {
        report_dbus_error("subscribe-responses", &error);
        dbus_error_free(&error);
        return -EIO;
    }
    dbus_connection_flush(connection);
    char token[96];
    snprintf(token, sizeof(token), "aegis_%ld_%ld", (long)getpid(), (long)time(NULL));

    DBusMessage *message = dbus_message_new_method_call(
        PORTAL_BUS_NAME, PORTAL_OBJECT_PATH, PORTAL_SCREENCAST_IFACE, "CreateSession");
    if (message == NULL) return -ENOMEM;
    DBusMessageIter outer;
    DBusMessageIter options;
    dbus_message_iter_init_append(message, &outer);
    if (!dbus_message_iter_open_container(&outer, DBUS_TYPE_ARRAY, "{sv}", &options) ||
            !append_dict_entry_string(&options, "handle_token", token) ||
            !append_dict_entry_string(&options, "session_handle_token", token) ||
            !finish_dict(&outer, &options)) {
        dbus_message_unref(message);
        return -EINVAL;
    }
    char *create_request = send_request_and_get_path(connection, message, &error);
    if (create_request == NULL) {
        report_dbus_error("CreateSession", &error);
        dbus_error_free(&error);
        return -EIO;
    }
    DBusMessage *response = NULL;
    int result = wait_for_response(connection, create_request, "CreateSession", &response);
    free(create_request);
    if (result < 0) {
        dbus_error_free(&error);
        return result;
    }
    DBusMessageIter result_dict;
    result = response_status_and_dict(response, &result_dict, &error);
    if (result == 0) result = dict_find_object_path(&result_dict, "session_handle", session_path_out);
    dbus_message_unref(response);
    response = NULL;
    if (result < 0) {
        dbus_error_free(&error);
        return result;
    }

    message = dbus_message_new_method_call(
        PORTAL_BUS_NAME, PORTAL_OBJECT_PATH, PORTAL_SCREENCAST_IFACE, "SelectSources");
    if (message == NULL) return -ENOMEM;
    dbus_message_iter_init_append(message, &outer);
    const char *session_path = *session_path_out;
    if (!dbus_message_iter_append_basic(&outer, DBUS_TYPE_OBJECT_PATH, &session_path) ||
            !dbus_message_iter_open_container(&outer, DBUS_TYPE_ARRAY, "{sv}", &options) ||
            !append_dict_entry_uint32(&options, "types", 1u) ||
            !append_dict_entry_bool(&options, "multiple", FALSE) ||
            !append_dict_entry_uint32(&options, "cursor_mode", 2u) ||
            !finish_dict(&outer, &options)) {
        dbus_message_unref(message);
        return -EINVAL;
    }
    char *select_request = send_request_and_get_path(connection, message, &error);
    if (select_request == NULL) {
        report_dbus_error("SelectSources", &error);
        dbus_error_free(&error);
        return -EIO;
    }
    result = wait_for_response(connection, select_request, "SelectSources", &response);
    free(select_request);
    if (result == 0) result = response_status_and_dict(response, &result_dict, &error);
    if (response != NULL) {
        dbus_message_unref(response);
        response = NULL;
    }
    if (result < 0) {
        dbus_error_free(&error);
        return result;
    }

    message = dbus_message_new_method_call(
        PORTAL_BUS_NAME, PORTAL_OBJECT_PATH, PORTAL_SCREENCAST_IFACE, "Start");
    if (message == NULL) return -ENOMEM;
    dbus_message_iter_init_append(message, &outer);
    const char *parent = "";
    if (!dbus_message_iter_append_basic(&outer, DBUS_TYPE_OBJECT_PATH, &session_path) ||
            !dbus_message_iter_append_basic(&outer, DBUS_TYPE_STRING, &parent) ||
            !dbus_message_iter_open_container(&outer, DBUS_TYPE_ARRAY, "{sv}", &options) ||
            !append_dict_entry_string(&options, "handle_token", token) ||
            !finish_dict(&outer, &options)) {
        dbus_message_unref(message);
        return -EINVAL;
    }
    char *start_request = send_request_and_get_path(connection, message, &error);
    if (start_request == NULL) {
        report_dbus_error("Start", &error);
        dbus_error_free(&error);
        return -EIO;
    }
    result = wait_for_response(connection, start_request, "Start", &response);
    free(start_request);
    if (result == 0) result = response_status_and_dict(response, &result_dict, &error);
    if (result == 0) result = parse_first_stream_node(&result_dict, node_id_out);
    if (response != NULL) {
        dbus_message_unref(response);
        response = NULL;
    }
    if (result < 0) {
        dbus_error_free(&error);
        return result;
    }

    message = dbus_message_new_method_call(
        PORTAL_BUS_NAME, PORTAL_OBJECT_PATH, PORTAL_SCREENCAST_IFACE, "OpenPipeWireRemote");
    if (message == NULL) return -ENOMEM;
    dbus_message_iter_init_append(message, &outer);
    if (!dbus_message_iter_append_basic(&outer, DBUS_TYPE_OBJECT_PATH, &session_path) ||
            !dbus_message_iter_open_container(&outer, DBUS_TYPE_ARRAY, "{sv}", &options) ||
            !finish_dict(&outer, &options)) {
        dbus_message_unref(message);
        return -EINVAL;
    }
    response = dbus_connection_send_with_reply_and_block(connection, message, PORTAL_TIMEOUT_MS, &error);
    dbus_message_unref(message);
    if (response == NULL) {
        report_dbus_error("OpenPipeWireRemote", &error);
        dbus_error_free(&error);
        return -EIO;
    }
    DBusMessageIter fd_iter;
    if (!dbus_message_iter_init(response, &fd_iter) ||
            dbus_message_iter_get_arg_type(&fd_iter) != DBUS_TYPE_UNIX_FD) {
        fprintf(stderr, "aegis portal returned an invalid PipeWire descriptor\n");
        dbus_message_unref(response);
        dbus_error_free(&error);
        return -EPROTO;
    }
    dbus_message_iter_get_basic(&fd_iter, pipewire_fd_out);
    dbus_message_unref(response);
    dbus_error_free(&error);
    return 0;
}

static void close_portal_session(DBusConnection *connection, const char *session_path) {
    if (connection == NULL || session_path == NULL) return;
    DBusError error;
    dbus_error_init(&error);
    DBusMessage *message = dbus_message_new_method_call(
        PORTAL_BUS_NAME, session_path, PORTAL_SESSION_IFACE, "Close");
    if (message != NULL) {
        DBusMessage *reply = dbus_connection_send_with_reply_and_block(
            connection, message, 2000, &error);
        if (reply != NULL) dbus_message_unref(reply);
        dbus_message_unref(message);
    }
    dbus_error_free(&error);
}

struct pipewire_capture {
    struct pw_main_loop *main_loop;
    struct pw_context *context;
    struct pw_core *core;
    struct pw_stream *stream;
    struct spa_hook stream_listener;
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t max_width;
    uint32_t max_height;
    uint32_t requested_width;
    uint32_t requested_height;
    uint32_t output_width;
    uint32_t output_height;
    uint32_t output_stride;
    unsigned char *output_pixels;
    uint64_t output_interval_nanos;
    uint64_t last_output_nanos;
    int failed;
};

static int write_all(int fd, const void *data, size_t size) {
    const unsigned char *bytes = (const unsigned char *)data;
    while (size > 0) {
        ssize_t written = write(fd, bytes, size);
        if (written < 0) {
            if (errno == EINTR) continue;
            return -errno;
        }
        if (written == 0) return -EPIPE;
        bytes += written;
        size -= (size_t)written;
    }
    return 0;
}

static int write_u32(uint32_t value) {
    uint32_t network = htonl(value);
    return write_all(STDOUT_FILENO, &network, sizeof(network));
}

static int write_u64(uint64_t value) {
    uint32_t high = htonl((uint32_t)(value >> 32));
    uint32_t low = htonl((uint32_t)(value & UINT32_MAX));
    if (write_all(STDOUT_FILENO, &high, sizeof(high)) < 0) return -EPIPE;
    return write_all(STDOUT_FILENO, &low, sizeof(low));
}

static uint64_t monotonic_nanos(void) {
    struct timespec time_value;
    clock_gettime(CLOCK_MONOTONIC, &time_value);
    return (uint64_t)time_value.tv_sec * 1000000000ull + (uint64_t)time_value.tv_nsec;
}

static void stream_state_changed(
        void *data,
        enum pw_stream_state old_state,
        enum pw_stream_state state,
        const char *error) {
    (void)old_state;
    struct pipewire_capture *capture = data;
    if (state == PW_STREAM_STATE_ERROR) {
        capture->failed = 1;
        fprintf(stderr, "aegis PipeWire stream error: %s\n", error == NULL ? "unknown" : error);
        if (capture->main_loop != NULL) pw_main_loop_quit(capture->main_loop);
    }
}

static void stream_param_changed(void *data, uint32_t id, const struct spa_pod *param) {
    struct pipewire_capture *capture = data;
    if (id != SPA_PARAM_Format || param == NULL) return;
    struct spa_video_info_raw video = SPA_VIDEO_INFO_RAW_INIT();
    if (spa_format_video_raw_parse(param, &video) < 0) return;
    capture->width = video.size.width;
    capture->height = video.size.height;
    capture->format = video.format;
    if (capture->width == 0 || capture->height == 0 ||
            capture->width > capture->max_width || capture->height > capture->max_height) {
        capture->failed = 1;
        fprintf(stderr, "aegis PipeWire returned an invalid video size\n");
        if (capture->main_loop != NULL) pw_main_loop_quit(capture->main_loop);
        return;
    }
    capture->output_width = capture->width;
    capture->output_height = capture->height;
    if (capture->output_width > capture->requested_width ||
            capture->output_height > capture->requested_height) {
        if ((uint64_t)capture->width * capture->requested_height >=
                (uint64_t)capture->height * capture->requested_width) {
            capture->output_width = capture->requested_width;
            capture->output_height = (uint32_t)(
                (uint64_t)capture->height * capture->requested_width / capture->width);
        } else {
            capture->output_height = capture->requested_height;
            capture->output_width = (uint32_t)(
                (uint64_t)capture->width * capture->requested_height / capture->height);
        }
    }
    if (capture->output_width == 0) capture->output_width = 1;
    if (capture->output_height == 0) capture->output_height = 1;
    capture->output_stride = capture->output_width * 4u;
    uint64_t output_size = (uint64_t)capture->output_stride * capture->output_height;
    if (output_size > MAX_FRAME_BYTES) {
        capture->failed = 1;
        fprintf(stderr, "aegis PipeWire scaled frame exceeded the bounded buffer\n");
        if (capture->main_loop != NULL) pw_main_loop_quit(capture->main_loop);
        return;
    }
    unsigned char *resized = realloc(capture->output_pixels, (size_t)output_size);
    if (resized == NULL) {
        capture->failed = 1;
        fprintf(stderr, "aegis PipeWire could not allocate the scaled frame\n");
        if (capture->main_loop != NULL) pw_main_loop_quit(capture->main_loop);
        return;
    }
    capture->output_pixels = resized;
}

static void stream_process(void *data) {
    struct pipewire_capture *capture = data;
    struct pw_buffer *pw_buffer = pw_stream_dequeue_buffer(capture->stream);
    if (pw_buffer == NULL) return;
    if (pw_buffer->buffer == NULL || pw_buffer->buffer->n_datas == 0) {
        capture->failed = 1;
        fprintf(stderr, "aegis PipeWire returned an empty buffer\n");
        pw_stream_queue_buffer(capture->stream, pw_buffer);
        pw_main_loop_quit(capture->main_loop);
        return;
    }
    struct spa_data *spa_data = &pw_buffer->buffer->datas[0];
    struct spa_chunk *chunk = spa_data->chunk;
    if (spa_data->data == NULL || chunk == NULL || chunk->size == 0 || chunk->stride <= 0 ||
            capture->width == 0 || capture->height == 0) {
        capture->failed = 1;
        fprintf(stderr, "aegis PipeWire returned an unmappable or incomplete frame\n");
        pw_stream_queue_buffer(capture->stream, pw_buffer);
        pw_main_loop_quit(capture->main_loop);
        return;
    }
    uint64_t now = monotonic_nanos();
    if (capture->last_output_nanos != 0 &&
            now - capture->last_output_nanos < capture->output_interval_nanos) {
        pw_stream_queue_buffer(capture->stream, pw_buffer);
        return;
    }
    capture->last_output_nanos = now;
    uint64_t required = (uint64_t)chunk->stride * capture->height;
    if ((uint64_t)chunk->offset + required > spa_data->maxsize || required > MAX_FRAME_BYTES ||
            chunk->size < required) {
        capture->failed = 1;
        fprintf(stderr, "aegis PipeWire frame exceeded the bounded buffer\n");
        pw_stream_queue_buffer(capture->stream, pw_buffer);
        pw_main_loop_quit(capture->main_loop);
        return;
    }
    uint32_t wire_format;
    if (capture->format == SPA_VIDEO_FORMAT_BGRx || capture->format == SPA_VIDEO_FORMAT_BGRA) wire_format = 1u;
    else if (capture->format == SPA_VIDEO_FORMAT_RGBA || capture->format == SPA_VIDEO_FORMAT_RGBx) wire_format = 2u;
    else {
        capture->failed = 1;
        fprintf(stderr, "aegis PipeWire negotiated an unsupported raw format\n");
        pw_stream_queue_buffer(capture->stream, pw_buffer);
        pw_main_loop_quit(capture->main_loop);
        return;
    }
    const unsigned char *frame = (const unsigned char *)spa_data->data + chunk->offset;
    if (capture->output_pixels == NULL) {
        capture->failed = 1;
        fprintf(stderr, "aegis PipeWire output buffer was not initialized\n");
        pw_stream_queue_buffer(capture->stream, pw_buffer);
        pw_main_loop_quit(capture->main_loop);
        return;
    }
    for (uint32_t output_y = 0; output_y < capture->output_height; output_y++) {
        uint32_t source_y = (uint32_t)((uint64_t)output_y * capture->height / capture->output_height);
        const unsigned char *source_row = frame + (size_t)source_y * (size_t)chunk->stride;
        unsigned char *output_row = capture->output_pixels + (size_t)output_y * capture->output_stride;
        for (uint32_t output_x = 0; output_x < capture->output_width; output_x++) {
            uint32_t source_x = (uint32_t)((uint64_t)output_x * capture->width / capture->output_width);
            memcpy(output_row + (size_t)output_x * 4u, source_row + (size_t)source_x * 4u, 4u);
        }
    }
    uint32_t payload = capture->output_stride * capture->output_height;
    int result = write_u32(FRAME_MAGIC);
    if (result == 0) result = write_u32(FRAME_VERSION);
    if (result == 0) result = write_u32(capture->output_width);
    if (result == 0) result = write_u32(capture->output_height);
    if (result == 0) result = write_u32(capture->output_stride);
    if (result == 0) result = write_u32(wire_format);
    if (result == 0) result = write_u32(payload);
    if (result == 0) result = write_u64(now);
    if (result == 0) result = write_all(STDOUT_FILENO, capture->output_pixels, payload);
    pw_stream_queue_buffer(capture->stream, pw_buffer);
    if (result < 0) {
        capture->failed = 1;
        pw_main_loop_quit(capture->main_loop);
    }
}

static const struct pw_stream_events stream_events = {
    PW_VERSION_STREAM_EVENTS,
    .state_changed = stream_state_changed,
    .param_changed = stream_param_changed,
    .process = stream_process,
};

static int run_pipewire_capture(int pipewire_fd, uint32_t node_id, uint32_t max_width, uint32_t max_height, uint32_t fps) {
    struct pipewire_capture capture = {0};
    capture.max_width = MAX_FRAME_DIMENSION;
    capture.max_height = MAX_FRAME_DIMENSION;
    capture.requested_width = max_width;
    capture.requested_height = max_height;
    capture.output_interval_nanos = 1000000000ull / fps;
    pw_init(NULL, NULL);
    capture.main_loop = pw_main_loop_new(NULL);
    if (capture.main_loop == NULL) return -ENOMEM;
    capture.context = pw_context_new(pw_main_loop_get_loop(capture.main_loop), NULL, 0);
    if (capture.context == NULL) return -ENOMEM;
    capture.core = pw_context_connect_fd(capture.context, pipewire_fd, NULL, 0);
    if (capture.core == NULL) {
        fprintf(stderr, "aegis PipeWire could not connect to the portal remote\n");
        return -ECONNREFUSED;
    }
    struct pw_properties *properties = pw_properties_new(
        PW_KEY_MEDIA_TYPE, "Video",
        PW_KEY_MEDIA_CATEGORY, "Capture",
        PW_KEY_MEDIA_ROLE, "Screen",
        NULL);
    capture.stream = pw_stream_new(capture.core, "Aegis Portal Screen Capture", properties);
    if (capture.stream == NULL) return -ENOMEM;
    pw_stream_add_listener(capture.stream, &capture.stream_listener, &stream_events, &capture);

    uint8_t pod_buffer[2048];
    struct spa_pod_builder builder = SPA_POD_BUILDER_INIT(pod_buffer, sizeof(pod_buffer));
    struct spa_rectangle preferred_size = SPA_RECTANGLE(max_width, max_height);
    struct spa_rectangle maximum_size = SPA_RECTANGLE(MAX_FRAME_DIMENSION, MAX_FRAME_DIMENSION);
    struct spa_fraction maximum_rate = SPA_FRACTION(fps, 1);
    const struct spa_pod *params[1];
    params[0] = spa_pod_builder_add_object(
        &builder,
        SPA_TYPE_OBJECT_Format,
        SPA_PARAM_EnumFormat,
        SPA_FORMAT_mediaType, SPA_POD_Id(SPA_MEDIA_TYPE_video),
        SPA_FORMAT_mediaSubtype, SPA_POD_Id(SPA_MEDIA_SUBTYPE_raw),
        SPA_FORMAT_VIDEO_format,
            SPA_POD_CHOICE_ENUM_Id(
                4,
                SPA_VIDEO_FORMAT_BGRx,
                SPA_VIDEO_FORMAT_RGBx,
                SPA_VIDEO_FORMAT_BGRA,
                SPA_VIDEO_FORMAT_RGBA),
        SPA_FORMAT_VIDEO_size,
            SPA_POD_CHOICE_RANGE_Rectangle(&preferred_size, &SPA_RECTANGLE(1, 1), &maximum_size),
        SPA_FORMAT_VIDEO_framerate, SPA_POD_Fraction(&SPA_FRACTION(0, 1)),
        SPA_FORMAT_VIDEO_maxFramerate,
            SPA_POD_CHOICE_RANGE_Fraction(&maximum_rate, &SPA_FRACTION(1, 1), &maximum_rate));
    if (params[0] == NULL) return -EINVAL;
    int result = pw_stream_connect(
        capture.stream,
        PW_DIRECTION_INPUT,
        node_id,
        PW_STREAM_FLAG_AUTOCONNECT | PW_STREAM_FLAG_MAP_BUFFERS,
        params,
        1);
    if (result < 0) {
        fprintf(stderr, "aegis PipeWire stream connection failed: %s\n", strerror(-result));
        return result;
    }
    int loop_result = pw_main_loop_run(capture.main_loop);
    if (capture.stream != NULL) {
        pw_stream_disconnect(capture.stream);
        pw_stream_destroy(capture.stream);
    }
    if (capture.core != NULL) pw_core_disconnect(capture.core);
    if (capture.context != NULL) pw_context_destroy(capture.context);
    if (capture.main_loop != NULL) pw_main_loop_destroy(capture.main_loop);
    free(capture.output_pixels);
    pw_deinit();
    return capture.failed ? -EIO : loop_result;
}

int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "usage: aegis_pipewire_portal_capture MAX_WIDTH MAX_HEIGHT FPS\n");
        return 64;
    }
    char *end = NULL;
    unsigned long max_width = strtoul(argv[1], &end, 10);
    if (end == argv[1] || *end != '\0') return 64;
    unsigned long max_height = strtoul(argv[2], &end, 10);
    if (end == argv[2] || *end != '\0') return 64;
    unsigned long fps = strtoul(argv[3], &end, 10);
    if (end == argv[3] || *end != '\0' || max_width == 0 || max_height == 0 || fps == 0 ||
            max_width > MAX_FRAME_DIMENSION || max_height > MAX_FRAME_DIMENSION || fps > 240) return 64;
    DBusError error;
    dbus_error_init(&error);
    DBusConnection *connection = dbus_bus_get(DBUS_BUS_SESSION, &error);
    if (connection == NULL) {
        report_dbus_error("connect-session-bus", &error);
        dbus_error_free(&error);
        return 70;
    }
    char *session_path = NULL;
    uint32_t node_id = 0;
    int pipewire_fd = -1;
    int result = request_session(connection, &session_path, &node_id, &pipewire_fd);
    if (result == 0) result = run_pipewire_capture(
        pipewire_fd, node_id, (uint32_t)max_width, (uint32_t)max_height, (uint32_t)fps);
    close_portal_session(connection, session_path);
    free(session_path);
    dbus_connection_unref(connection);
    return result == 0 ? 0 : 70;
}
