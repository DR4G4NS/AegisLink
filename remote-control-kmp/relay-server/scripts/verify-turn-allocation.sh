#!/usr/bin/env bash
set -euo pipefail

compose_file="${AEGIS_TURN_COMPOSE_FILE:-relay-server/docker-compose.integration.yml}"
shared_secret="${AEGIS_TURN_TEST_SHARED_SECRET:-aegis-turn-integration-only}"
turn_host="${AEGIS_TURN_TEST_HOST:-127.0.0.1}"
turn_port="${AEGIS_TURN_TEST_PORT:-3478}"
exec_timeout_seconds="${AEGIS_TURN_TEST_TIMEOUT_SECONDS:-60}"
max_attempts="${AEGIS_TURN_TEST_MAX_ATTEMPTS:-3}"
username="$(($(date +%s) + 600)):ci-allocation"

run_turn_client() {
  timeout "$exec_timeout_seconds" docker compose -f "$compose_file" exec -T coturn \
    turnutils_uclient -v -y -c -n 1 -u "$username" -W "$shared_secret" -p "$turn_port" "$turn_host" \
    2>&1
}

output=""
exec_status=1
for attempt in $(seq 1 "$max_attempts"); do
  set +e
  output="$(run_turn_client)"
  exec_status=$?
  set -e
  printf '%s\n' "$output"

  if [[ "$exec_status" -eq 124 ]]; then
    echo "TURN allocation attempt $attempt timed out after ${exec_timeout_seconds}s" >&2
  elif [[ "$exec_status" -ne 0 ]]; then
    echo "TURN allocation attempt $attempt exited with status $exec_status" >&2
  elif grep -q "allocate sent" <<<"$output" && \
      ! grep -Eqi "unauthorized|allocation mismatch|cannot create allocation|error [4-6][0-9][0-9]" <<<"$output"; then
    exit 0
  else
    echo "TURN allocation attempt $attempt did not report a successful allocation" >&2
    exec_status=1
  fi

  if [[ "$attempt" -lt "$max_attempts" ]]; then
    docker compose -f "$compose_file" restart coturn
    docker compose -f "$compose_file" up -d --wait coturn
    sleep 2
    username="$(($(date +%s) + 600)):ci-allocation"
  fi
done

exit "$exec_status"
