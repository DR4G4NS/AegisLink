package dev.aegis.remote.desktop.agent

/**
 * Localizes structured agent events without parsing human-readable log text.
 * Unknown/legacy events keep their original diagnostic message.
 */
fun AgentLogEntry.localizedMessage(languageTag: String): String {
    val spanish = languageTag.lowercase().startsWith("es")
    val device = context["deviceName"] ?: context["remoteDeviceId"] ?: ""
    val reason = context["reason"] ?: ""
    localizedPairingServerMessage(spanish)?.let { return it.trim() }
    localizedTrustStoreMessage(spanish)?.let { return it.trim() }
    return when (eventCode) {
        AgentLogEventCode.PairingRequestReceived -> {
            if (spanish) {
                "Solicitud de vinculación recibida de $device"
            } else {
                "Pairing request received from $device"
            }
        }

        AgentLogEventCode.PairingRequestExpired -> {
            if (spanish) {
                "La solicitud de vinculación de $device caducó"
            } else {
                "Pairing request from $device expired"
            }
        }

        AgentLogEventCode.PairingRequestApproved -> {
            if (spanish) {
                "Solicitud de vinculación de $device aprobada"
            } else {
                "Pairing request from $device approved"
            }
        }

        AgentLogEventCode.PairingApprovalFailed -> {
            if (spanish) {
                "No se pudo aprobar la vinculación de $device"
            } else {
                "Failed to approve pairing for $device"
            }
        }

        AgentLogEventCode.PairingRequestRejected -> {
            if (spanish) {
                "Solicitud de vinculación de $device rechazada"
            } else {
                "Pairing request from $device rejected"
            }
        }

        AgentLogEventCode.LocalProtocolAuthorizationFailed -> {
            if (spanish) {
                "Se rechazó una conexión local ($reason)"
            } else {
                "A local connection was rejected ($reason)"
            }
        }

        AgentLogEventCode.DeviceRevoked -> {
            if (spanish) {
                "Acceso Aegis revocado para $device"
            } else {
                "Aegis access revoked for $device"
            }
        }

        AgentLogEventCode.SshKeyRemovalPending -> {
            if (spanish) {
                "La retirada de la clave SSH sigue pendiente para $device (${context["failureCode"].orEmpty()})"
            } else {
                "Managed SSH key removal remains pending for $device (${context["failureCode"].orEmpty()})"
            }
        }

        AgentLogEventCode.SshKeyRemovalCompleted -> {
            if (spanish) "Retirada de la clave SSH confirmada para $device" else "Managed SSH key removal confirmed for $device"
        }

        AgentLogEventCode.DeviceRecordDeleted -> {
            if (spanish) {
                "Registro revocado eliminado definitivamente"
            } else {
                "Revoked device record permanently deleted"
            }
        }

        AgentLogEventCode.DeviceRecordDeletionRefused -> {
            localizedDeletionRefusedMessage(spanish)
        }

        AgentLogEventCode.DeviceRecordDeletionFailed -> {
            if (spanish) {
                "No se pudo eliminar el registro del dispositivo"
            } else {
                "Failed to delete the device record"
            }
        }

        AgentLogEventCode.RelayConfigurationChanged -> {
            localizedRelayConfigurationEvent(spanish)
        }

        AgentLogEventCode.RelayConnectionChanged -> {
            localizedRelayConnectionEvent(spanish)
        }

        AgentLogEventCode.RelayIdentityChanged -> {
            localizedRelayIdentityEvent(spanish)
        }

        AgentLogEventCode.RelaySessionChanged -> {
            localizedRelaySessionEvent(spanish)
        }

        AgentLogEventCode.Generic -> {
            message
        }

        else -> {
            message
        }
    }.trim()
}

private fun AgentLogEntry.localizedPairingServerMessage(spanish: Boolean): String? =
    when (eventCode) {
        AgentLogEventCode.PairingServerStarted -> {
            if (spanish) {
                "Servidor de vinculación local iniciado en ${context["pairingUrl"].orEmpty()}"
            } else {
                "Local pairing server started at ${context["pairingUrl"].orEmpty()}"
            }
        }

        AgentLogEventCode.PairingServerStartFailed -> {
            if (spanish) {
                "No se pudo iniciar el servidor de vinculación local"
            } else {
                "Failed to start the local pairing server"
            }
        }

        else -> {
            null
        }
    }

private fun AgentLogEntry.localizedTrustStoreMessage(spanish: Boolean): String? =
    when (eventCode) {
        AgentLogEventCode.TrustStoreUnavailable -> {
            if (spanish) {
                "El almacén de confianza no se puede leer; restaura una copia válida o reinicia y vuelve a vincular con consentimiento visible"
            } else {
                "The trust store cannot be read; restore a valid backup or reset and re-pair with visible consent"
            }
        }

        AgentLogEventCode.TrustStoreRecovered -> {
            if (spanish) {
                "Recuperación explícita del almacén de confianza completada; iniciando una nueva vinculación"
            } else {
                "Explicit trust-store recovery completed; starting a new pairing session"
            }
        }

        AgentLogEventCode.TrustStoreRecoveryFailed -> {
            if (spanish) {
                "La recuperación del almacén de confianza falló; las vinculaciones y cambios siguen bloqueados"
            } else {
                "Trust-store recovery failed; pairing and changes remain blocked"
            }
        }

        else -> {
            null
        }
    }

private fun AgentLogEntry.localizedDeletionRefusedMessage(spanish: Boolean): String =
    when (context["outcome"]) {
        "MustRevokeFirst" -> {
            if (spanish) {
                "No se eliminó el registro: primero revoca el acceso"
            } else {
                "Device record was not deleted; revoke access first"
            }
        }

        "NotFound" -> {
            if (spanish) "No se encontró el registro del dispositivo" else "Device record was not found"
        }

        "Unsupported" -> {
            if (spanish) {
                "Este almacén de confianza no admite borrar registros"
            } else {
                "The trust store does not support permanent record deletion"
            }
        }

        "SshKeyRemovalPending" -> {
            if (spanish) {
                "No se eliminó el registro: la retirada de la clave SSH sigue pendiente"
            } else {
                "Device record was not deleted while managed SSH key removal is pending"
            }
        }

        else -> {
            if (spanish) {
                "No se eliminó el registro (${context["outcome"].orEmpty()})"
            } else {
                "Device record was not deleted (${context["outcome"].orEmpty()})"
            }
        }
    }

private fun AgentLogEntry.localizedRelayConfigurationEvent(spanish: Boolean): String =
    when (context["action"]) {
        "remote-access-unavailable" -> {
            if (spanish) "No se puede activar el acceso remoto sin configurar un relay" else "Remote access requires a configured relay"
        }

        "remote-access-changed" -> {
            if (context["enabled"] == "true") {
                if (spanish) "Acceso remoto activado; actualizando el registro" else "Remote access enabled; updating registration"
            } else {
                if (spanish) "Acceso remoto desactivado" else "Remote access disabled"
            }
        }

        "updated" -> {
            if (spanish) "Configuración del relay actualizada" else "Relay configuration updated"
        }

        "removed" -> {
            if (spanish) "Configuración del relay eliminada" else "Relay configuration removed"
        }

        "remote-access-persist-failed" -> {
            if (spanish) "No se pudo guardar el estado del acceso remoto" else "Failed to save remote-access state"
        }

        "update-failed" -> {
            if (spanish) "No se pudo actualizar la configuración del relay" else "Failed to update relay configuration"
        }

        "device-id-persist-failed" -> {
            if (spanish) "No se pudo guardar el ID asignado por el relay" else "Failed to save the assigned relay ID"
        }

        else -> {
            message
        }
    }

private fun AgentLogEntry.localizedRelayConnectionEvent(spanish: Boolean): String =
    when (context["action"]) {
        "not-configured" -> if (spanish) "Relay sin configurar; el acceso local sigue disponible" else "Relay not configured; local access remains available"
        "registered" -> if (spanish) "Equipo registrado en el relay como ${context["relayDeviceId"].orEmpty()}" else "PC registered with the relay as ${context["relayDeviceId"].orEmpty()}"
        "registration-failed" -> if (spanish) "No se pudo registrar el equipo en el relay" else "Failed to register the PC with the relay"
        "event-stream-lost" -> if (spanish) "Se perdió el canal de eventos del relay; reintentando" else "Relay event channel lost; retrying"
        "reconnect-exhausted" -> if (spanish) "Se agotaron los reintentos de conexión al relay" else "Relay reconnection attempts exhausted"
        "reregistered" -> if (spanish) "Conexión de eventos del relay restablecida" else "Relay event connection restored"
        "reregistration-failed" -> if (spanish) "Falló un reintento de registro en el relay" else "Relay re-registration attempt failed"
        else -> message
    }

private fun AgentLogEntry.localizedRelayIdentityEvent(spanish: Boolean): String =
    when (context["action"]) {
        "rotation-started" -> if (spanish) "Rotando la identidad criptográfica del relay" else "Rotating the relay cryptographic identity"
        "rotation-completed" -> if (spanish) "Identidad del relay rotada y confirmada" else "Relay identity rotated and confirmed"
        "rotation-pending" -> if (spanish) "La rotación quedó pendiente de confirmación segura" else "Identity rotation remains safely pending"
        "rotation-failed" -> if (spanish) "No se pudo rotar la identidad del relay" else "Failed to rotate the relay identity"
        "revoked" -> if (spanish) "Identidad del relay revocada" else "Relay identity revoked"
        else -> message
    }

private fun AgentLogEntry.localizedRelaySessionEvent(spanish: Boolean): String {
    val sessionId = context["sessionId"].orEmpty()
    val source = context["sourceLabel"] ?: context["sourceRelayDeviceId"].orEmpty()
    return when (context["action"]) {
        "rejected-remote-disabled" -> if (spanish) "Sesión $sessionId rechazada: acceso remoto desactivado" else "Session $sessionId rejected: remote access is disabled"
        "auto-approved" -> if (spanish) "Sesión $sessionId aprobada automáticamente para $source" else "Session $sessionId auto-approved for $source"
        "auto-approval-failed" -> if (spanish) "Falló la aprobación automática de la sesión $sessionId" else "Auto-approval failed for session $sessionId"
        "requested" -> if (spanish) "Solicitud de sesión relay recibida de $source" else "Relay session requested by $source"
        "approved", "decision-approved" -> if (spanish) "Sesión relay $sessionId aprobada" else "Relay session $sessionId approved"
        "rejected", "decision-rejected" -> if (spanish) "Sesión relay $sessionId rechazada" else "Relay session $sessionId rejected"
        "decision-failed" -> if (spanish) "No se pudo responder a la sesión relay $sessionId" else "Failed to decide relay session $sessionId"
        else -> message
    }
}

fun DesktopAgentState.localizedRelayConfigurationMessage(languageTag: String): String? {
    val code = relayConfigurationMessageCode ?: return relayConfigurationMessage
    val spanish = languageTag.lowercase().startsWith("es")
    return when (code) {
        RelayConfigurationMessageCode.NotConfigured -> {
            if (spanish) {
                "Configura un relay para habilitar el acceso remoto"
            } else {
                "Configure a relay to enable remote access"
            }
        }

        RelayConfigurationMessageCode.Loaded -> {
            if (spanish) "Configuración del relay cargada" else "Relay configuration loaded"
        }

        RelayConfigurationMessageCode.ValidationFailed -> {
            localizedRelayValidationMessage(spanish)
        }

        RelayConfigurationMessageCode.Saving -> {
            if (spanish) "Guardando configuración del relay…" else "Saving relay configuration…"
        }

        RelayConfigurationMessageCode.SavedConnecting -> {
            if (spanish) {
                "Configuración guardada. Conectando con el relay…"
            } else {
                "Configuration saved. Connecting to the relay…"
            }
        }

        RelayConfigurationMessageCode.SaveFailed -> {
            if (spanish) {
                "No se pudo guardar la configuración del relay"
            } else {
                "Failed to save the relay configuration"
            }
        }

        RelayConfigurationMessageCode.Removing -> {
            if (spanish) "Quitando configuración del relay…" else "Removing relay configuration…"
        }

        RelayConfigurationMessageCode.Removed -> {
            if (spanish) {
                "Relay eliminado. Aegis sigue disponible en la red local."
            } else {
                "Relay removed. Aegis remains available on the local network."
            }
        }

        RelayConfigurationMessageCode.RemoveFailed -> {
            if (spanish) {
                "No se pudo quitar la configuración del relay"
            } else {
                "Failed to remove the relay configuration"
            }
        }

        RelayConfigurationMessageCode.Connected -> {
            if (spanish) "Relay conectado" else "Relay connected"
        }

        RelayConfigurationMessageCode.ConnectionFailed -> {
            if (spanish) {
                "No se pudo conectar con el relay"
            } else {
                "Failed to connect to the relay"
            }
        }

        RelayConfigurationMessageCode.IdentityRotating -> {
            if (spanish) "Rotando la identidad del relay…" else "Rotating the relay identity…"
        }

        RelayConfigurationMessageCode.IdentityRotated -> {
            if (spanish) "Identidad del relay rotada y confirmada" else "Relay identity rotated and confirmed"
        }

        RelayConfigurationMessageCode.IdentityRotationPending -> {
            if (spanish) {
                "IDN-1011: la rotación permanece preparada; vuelve a intentarlo para confirmar el mismo cambio"
            } else {
                "IDN-1011: rotation remains prepared; retry to confirm the same change"
            }
        }

        RelayConfigurationMessageCode.IdentityRotationFailed -> {
            if (spanish) "IDN-1012: no se pudo rotar la identidad del relay" else "IDN-1012: failed to rotate the relay identity"
        }
    }
}

fun DesktopAgentState.localizedAutostartMessage(languageTag: String): String? {
    val code = autostartMessageCode ?: return autostartMessage
    val spanish = languageTag.lowercase().startsWith("es")
    return when (code) {
        AutostartMessageCode.Path -> {
            autostartMessageContext["path"] ?: autostartMessage
        }

        AutostartMessageCode.Unsupported -> {
            if (spanish) {
                "El inicio automático no está disponible en esta plataforma"
            } else {
                "Autostart is not available on this platform"
            }
        }

        AutostartMessageCode.PackagedCommandRequired -> {
            if (spanish) {
                "Instala Aegis antes de activar el inicio automático"
            } else {
                "Install Aegis before enabling autostart"
            }
        }

        AutostartMessageCode.UpdateFailed -> {
            if (spanish) {
                "No se pudo cambiar el inicio automático"
            } else {
                "Failed to update autostart"
            }
        }
    }
}

private fun DesktopAgentState.localizedRelayValidationMessage(spanish: Boolean): String {
    val reason =
        relayConfigurationMessageContext["reason"]
            ?.let { runCatching { RelayValidationReason.valueOf(it) }.getOrNull() }
    return when (reason) {
        RelayValidationReason.UrlRequired -> {
            if (spanish) "La URL del relay es obligatoria" else "Relay URL is required"
        }

        RelayValidationReason.InvalidUrl -> {
            if (spanish) "La URL del relay no es válida" else "Relay URL is invalid"
        }

        RelayValidationReason.CompleteHttpUrlRequired -> {
            if (spanish) {
                "Usa una URL completa que empiece con http:// o https://"
            } else {
                "Use a complete URL beginning with http:// or https://"
            }
        }

        RelayValidationReason.CredentialsOrFragmentNotAllowed -> {
            if (spanish) {
                "La URL no debe contener credenciales ni fragmentos"
            } else {
                "The URL must not contain credentials or fragments"
            }
        }

        RelayValidationReason.InvalidDeviceId -> {
            if (spanish) {
                "El ID solo puede contener letras, números, punto, guion, guion bajo o dos puntos"
            } else {
                "The ID may contain only letters, numbers, dots, hyphens, underscores, or colons"
            }
        }

        null -> {
            if (spanish) "Revisa la configuración del relay" else "Check the relay configuration"
        }
    }
}
