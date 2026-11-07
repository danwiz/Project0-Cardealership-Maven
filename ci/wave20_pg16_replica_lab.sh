#!/usr/bin/env bash
# DISPOSABLE PostgreSQL16 physical primary/standby lab. No app/production data.
# This is a replication and recovery smoke test, NOT production dealership HA acceptance.
set -Eeuo pipefail
umask 077
test "$CI" = "true"
test "$GITHUB_ACTIONS" = "true"
test -n "$GITHUB_RUN_ID"
test -n "$EXPECTED_HEAD"
test "$(git rev-parse HEAD)" = "$EXPECTED_HEAD"
command -v docker >/dev/null
command -v python3 >/dev/null
mkdir -p wave20-ha

suffix=$(printf '%s' "$GITHUB_RUN_ID" | tr -cd '0-9')
test -n "$suffix"
network="w20ha-$suffix"
primary="w20-primary-$suffix"
standby="w20-standby-$suffix"
volume="w20-standbydata-$suffix"
owner="labowner"
database="labdb"
password="w20-ci-only-owner-not-production"
replica_password="w20-ci-only-replica-not-production"

cleanup() {
  docker rm -f "$standby" "$primary" >/dev/null 2>&1 || true
  docker volume rm "$volume" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

primary_sql() {
  docker exec -e PGPASSWORD="$password" "$primary" psql -X -v ON_ERROR_STOP=1 -qAt \
    -h 127.0.0.1 -U "$owner" -d "$database" -c "$1"
}
standby_sql() {
  docker exec -e PGPASSWORD="$password" "$standby" psql -X -v ON_ERROR_STOP=1 -qAt \
    -h 127.0.0.1 -U "$owner" -d "$database" -c "$1"
}

docker network create "$network" >/dev/null
docker volume create "$volume" >/dev/null
docker run -d --name "$primary" --network "$network" --restart=no \
  -e POSTGRES_USER="$owner" -e POSTGRES_PASSWORD="$password" \
  -e POSTGRES_DB="$database" postgres:16 >/dev/null

ready=0
for attempt in $(seq 1 90); do
  if docker exec "$primary" pg_isready -h 127.0.0.1 -U "$owner" -d "$database" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done
test "$ready" = 1
version=$(primary_sql "SHOW server_version_num")
test "$version" -ge 160000
test "$version" -lt 170000

primary_sql "CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD '$replica_password'" >/dev/null
docker exec -u postgres "$primary" sh -c \
  "printf '%s\\n' 'host replication replicator 0.0.0.0/0 scram-sha-256' >> /var/lib/postgresql/data/pg_hba.conf"
primary_sql "SELECT pg_reload_conf()" >/dev/null

# Dedicated empty Docker volume with postgres ownership. Only lab containers touch it.
docker run --rm --network "$network" --user root \
  -v "$volume:/var/lib/postgresql/data" postgres:16 \
  sh -ec 'chown postgres:postgres /var/lib/postgresql/data; chmod 700 /var/lib/postgresql/data'
docker run --rm --network "$network" --user postgres \
  -v "$volume:/var/lib/postgresql/data" \
  -e PGPASSWORD="$replica_password" postgres:16 \
  pg_basebackup -h "$primary" -U replicator -D /var/lib/postgresql/data -Fp -Xs -R
docker run --rm --network "$network" --user postgres \
  -v "$volume:/var/lib/postgresql/data" \
  -e PRIMARY_HOST="$primary" -e REPL_PASSWORD="$replica_password" postgres:16 \
  sh -ec 'printf "%s:5432:*:replicator:%s\\n" "$PRIMARY_HOST" "$REPL_PASSWORD" > /var/lib/postgresql/data/.pgpass; chmod 600 /var/lib/postgresql/data/.pgpass'

docker run -d --name "$standby" --network "$network" --restart=no \
  -v "$volume:/var/lib/postgresql/data" \
  -e PGPASSFILE=/var/lib/postgresql/data/.pgpass postgres:16 >/dev/null

ready=0
for attempt in $(seq 1 90); do
  if standby_sql "SELECT pg_is_in_recovery()" 2>/dev/null | grep -qx t; then
    ready=1
    break
  fi
  sleep 1
done
test "$ready" = 1

streaming=0
for attempt in $(seq 1 90); do
  value=$(primary_sql "SELECT COUNT(*) FROM pg_stat_replication WHERE state='streaming'")
  if test "$value" = 1; then
    streaming=1
    break
  fi
  sleep 1
done
test "$streaming" = 1
printf '%s\n' "W20: physical replication streaming on disposable PostgreSQL16"

primary_sql "CREATE TABLE synthetic_contract_receipts (contract_id text PRIMARY KEY, sale_amount integer NOT NULL CHECK (sale_amount>0), buyer_name text NOT NULL)" >/dev/null
primary_sql "INSERT INTO synthetic_contract_receipts(contract_id,sale_amount,buyer_name) VALUES ('W20-SYNTHETIC-001',24000,'fixture-buyer')" >/dev/null
primary_lsn=$(primary_sql "SELECT pg_current_wal_lsn()")

# Wait for the actual synthetic durable receipt to replay on the standby.
received=0
for attempt in $(seq 1 90); do
  value=$(standby_sql "SELECT count(*) FROM synthetic_contract_receipts WHERE contract_id='W20-SYNTHETIC-001'" 2>/dev/null || true)
  if test "$value" = 1; then
    received=1
    break
  fi
  sleep 1
done
test "$received" = 1
standby_replayed_lsn=$(standby_sql "SELECT pg_last_wal_replay_lsn()")
printf '%s\n' "W20: synthetic contract receipt has reached standby"

# The primary is deliberately killed/fenced *before* promotion; no partition
# or split-brain test is claimed. Never run this on real machines.
docker kill -s KILL "$primary" >/dev/null
test "$(docker inspect -f '{{.State.Running}}' "$primary")" = false

promoted=$(standby_sql "SELECT pg_promote(true, 30)")
test "$promoted" = t
test "$(standby_sql "SELECT pg_is_in_recovery()")" = f

# Caller replay with an idempotency key must not create a duplicate receipt.
standby_sql "INSERT INTO synthetic_contract_receipts(contract_id,sale_amount,buyer_name) VALUES ('W20-SYNTHETIC-001',24000,'fixture-buyer') ON CONFLICT(contract_id) DO NOTHING" >/dev/null
test "$(standby_sql "SELECT COUNT(*) FROM synthetic_contract_receipts WHERE contract_id='W20-SYNTHETIC-001'")" = 1
test "$(standby_sql "SELECT sale_amount FROM synthetic_contract_receipts WHERE contract_id='W20-SYNTHETIC-001'")" = 24000
test "$(standby_sql "SHOW server_version_num")" -ge 160000

python3 - "$EXPECTED_HEAD" "$version" "$primary_lsn" "$standby_replayed_lsn" <<'PY'
import datetime,json,pathlib,sys
data={
    "status":"PASS_DISPOSABLE_PHYSICAL_REPLICATION_SMOKE",
    "candidate_sha":sys.argv[1],
    "postgresql_version_num":int(sys.argv[2]),
    "primary_lsn_before_crash":sys.argv[3],
    "standby_replay_lsn_before_promotion":sys.argv[4],
    "standby_streaming_verified":True,
    "synthetic_receipt_replayed_before_promotion":True,
    "primary_stopped_before_promotion":True,
    "standby_promoted":True,
    "idempotent_synthetic_receipt_count":1,
    "observed_utc":datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "not_verified":[
        "Dealership production schema/purchase approval after failover",
        "Split-brain fencing under network partition",
        "Unacknowledged COMMIT in failover",
        "Synchronous standby durability RPO and real RTO",
        "Production credentials and HA failover management"
    ]
}
path=pathlib.Path("wave20-ha/physical_recovery_evidence.json")
path.write_text(json.dumps(data,indent=2)+"\n",encoding="utf-8")
print(json.dumps(data,indent=2))
PY
