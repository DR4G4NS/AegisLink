#!/usr/bin/env bash
# Aegis Remote Control - Cursor Cloud Agent start phase.
#
# Per-boot reconciliation of the local services the Aegis test suites depend on:
#   - PostgreSQL: durable relay identity/session store (RelayPostgresRedisIntegrationTest)
#   - Redis:      one-time challenges, shared rate limits, presence, mailboxes
#   - OpenSSH:    /run/sshd runtime dir for the loopback SSH/SFTP protocol tests
#
# It is idempotent and defensive: it never aborts the boot even when a step
# fails (the platform may run it under `set -e`), it clears a stale PostgreSQL
# pid captured in a snapshot, checks readiness, and then returns.

pg_version="$(ls /etc/postgresql 2>/dev/null | sort -n | tail -1)"
pg_version="${pg_version:-16}"

# --- PostgreSQL (default cluster 'main') ---
# A snapshot taken while PostgreSQL was running captures a stale postmaster.pid;
# remove it only when no live server is present, then (re)start the cluster.
if ! pgrep -x postgres >/dev/null 2>&1; then
  sudo rm -f "/var/lib/postgresql/${pg_version}/main/postmaster.pid" 2>/dev/null || true
fi
sudo pg_ctlcluster "$pg_version" main start >/dev/null 2>&1 || true

for _ in $(seq 1 30); do
  if pg_isready -h 127.0.0.1 -p 5432 >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

# Ensure the integration role/database exist (safe on a fresh cluster). Guard so
# a transient failure can never abort the start phase.
if pg_isready -h 127.0.0.1 -p 5432 >/dev/null 2>&1; then
  if ! sudo -u postgres psql -tc "SELECT 1 FROM pg_roles WHERE rolname='aegis'" 2>/dev/null | grep -q 1; then
    sudo -u postgres psql -c "CREATE ROLE aegis LOGIN PASSWORD 'aegis-integration-only';" >/dev/null 2>&1 || true
  fi
  if ! sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname='aegis_test'" 2>/dev/null | grep -q 1; then
    sudo -u postgres createdb -O aegis aegis_test >/dev/null 2>&1 || true
  fi
fi

# --- Redis ---
redis-cli ping >/dev/null 2>&1 || sudo redis-server --daemonize yes >/dev/null 2>&1 || true

# --- OpenSSH runtime directory for loopback SSH/SFTP tests ---
sudo install -d -m 0755 /run/sshd >/dev/null 2>&1 || true

pg_state="down"; pg_isready -h 127.0.0.1 -p 5432 >/dev/null 2>&1 && pg_state="up"
redis_state="$(redis-cli ping 2>/dev/null || echo down)"
sshd_state="missing"; [ -d /run/sshd ] && sshd_state="ready"
echo "[cloud-start] postgres=$pg_state redis=$redis_state sshd_dir=$sshd_state"

# Never fail the boot.
exit 0
