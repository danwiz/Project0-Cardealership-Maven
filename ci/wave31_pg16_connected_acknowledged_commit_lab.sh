#!/usr/bin/env bash
# DISPOSABLE PostgreSQL16 physical primary/standby lab. No app/production data.
# This is a replication and recovery smoke test, NOT production dealership HA acceptance.
set -Eeuo pipefail
umask 077
test "$CI" = "true"
test "$GITHUB_ACTIONS" = "true"
test -n "$GITHUB_RUN_ID"
test -n "$EXPECTED_HEAD"
mode="${W31_COMMIT_MODE:-}"
case "$mode" in async|remote_write|on|remote_apply) ;; *) echo "invalid synthetic mode" >&2; exit 2 ;; esac
test "$(git rev-parse HEAD)" = "$EXPECTED_HEAD"
command -v docker >/dev/null
command -v python3 >/dev/null
mkdir -p wave31-positive

suffix=$(printf '%s' "$GITHUB_RUN_ID" | tr -cd '0-9')
test -n "$suffix"
network="w31pos-$suffix"
primary="w31-primary-$suffix"
standby="w31-standby-$suffix"
volume="w31-standbydata-$suffix"
owner="labowner"
database="labdb"
password="w21-ci-only-owner-not-production"
replica_password="w21-ci-only-replica-not-production"

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
  -p 127.0.0.1:55421:5432 \
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
  -p 127.0.0.1:55422:5432 \
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
printf '%s\n' "W31: physical replication streaming on disposable PostgreSQL16"

# Compile real application and test fixture using the exact PR SHA.
mvn --batch-mode --no-transfer-progress -DskipTests test-compile
mvn --batch-mode --no-transfer-progress -DskipTests \
  org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=wave31-positive/maven-classpath.txt
test -s wave31-positive/maven-classpath.txt
fixture_cp="target/test-classes:target/classes:$(cat wave31-positive/maven-classpath.txt)"
java -cp "$fixture_cp" com.revature.repository.jdbc.Wave23CommitOutcomeFixture seed-pending 55421
# This workflow checks CONNECTED standby success, not a disconnect fault.
# The three synchronous modes require a recognized synchronous streaming
# standby before exercising the real dealership JDBC transaction.
if test "$mode" = async; then
  test -z "$(primary_sql "SHOW synchronous_standby_names")"
else
  primary_sql "ALTER SYSTEM SET synchronous_standby_names = '*'" >/dev/null
  primary_sql "ALTER SYSTEM SET synchronous_commit = '$mode'" >/dev/null
  primary_sql "SELECT pg_reload_conf()" >/dev/null
  test "$(primary_sql "SHOW synchronous_standby_names")" = '*'
  test "$(primary_sql "SHOW synchronous_commit")" = "$mode"
fi
streaming=0
for attempt in $(seq 1 90); do
  if test "$mode" = async; then
    n=$(primary_sql "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'" 2>/dev/null || true)
  else
    n=$(primary_sql "SELECT count(*) FROM pg_stat_replication WHERE state='streaming' AND sync_state='sync'" 2>/dev/null || true)
  fi
  if test "$n" = 1; then streaming=1; break; fi
  sleep 1
done
test "$streaming" = 1
test "$(standby_sql "SELECT count(*) FROM purchase_requests WHERE request_id=0 AND status='PENDING'")" = 1
printf '%s\n' "W31_CONNECTED_REPLICA_CONFIRMED:$mode"

# The actual application observes a successful COMMIT. This is intentionally
# distinct from Wave23 ACK-loss / Wave27 pre-COMMIT failure windows.
W31_DISPOSABLE_ONLY=true java -cp "$fixture_cp" \
  com.revature.repository.jdbc.Wave31PositiveCommitFixture approve
printf '%s\n' "W31_ACTUAL_DEALERSHIP_CLIENT_COMMIT_SUCCESS:$mode"
primary_lsn=$(primary_sql "SELECT pg_current_wal_lsn()")
positions=$(primary_sql "SELECT COALESCE(write_lsn::text,'0/0') || '|' || COALESCE(flush_lsn::text,'0/0') || '|' || COALESCE(replay_lsn::text,'0/0') FROM pg_stat_replication WHERE state='streaming' LIMIT 1")
IFS='|' read -r standby_write standby_flush standby_replay <<< "$positions"
test -n "$standby_write"
test -n "$standby_flush"
test -n "$standby_replay"
test "$standby_write" != '0/0'
test "$standby_flush" != '0/0'
standby_receive_before=$(standby_sql "SELECT pg_last_wal_receive_lsn()")
standby_replay_before=$(standby_sql "SELECT pg_last_wal_replay_lsn()")
printf '%s\n' "W31_LSN_POSITIONS_CAPTURED:$mode"

# Asynchronous success does NOT imply current standby durability. Wait for
# the physical standby to catch up explicitly before fencing in *all* modes.
caught_up=0
for attempt in $(seq 1 90); do
  a=$(standby_sql "SELECT count(*) FROM purchase_requests WHERE request_id=0 AND status='ACCEPTED'" 2>/dev/null || true)
  o=$(standby_sql "SELECT count(*) FROM owned_vehicles" 2>/dev/null || true)
  p=$(standby_sql "SELECT count(*) FROM payment_plans" 2>/dev/null || true)
  stock=$(standby_sql "SELECT count(*) FROM inventory_listings WHERE listing_id=0 AND stock_quantity=0" 2>/dev/null || true)
  if test "$a" = 1 && test "$o" = 1 && test "$p" = 1 && test "$stock" = 1; then
    caught_up=1; break
  fi
  sleep 1
done
test "$caught_up" = 1
standby_replayed_lsn=$(standby_sql "SELECT pg_last_wal_replay_lsn()")
test -n "$standby_replayed_lsn"
printf '%s\n' "W31_PHYSICAL_STANDBY_BUSINESS_REPLAY_CONFIRMED:$mode"

# Explicitly fence old primary before promoting the already-caught-up
# replica. This is a disposable lab, not a witness/quorum controller.
docker kill -s KILL "$primary" >/dev/null
test "$(docker inspect -f '{{.State.Running}}' "$primary")" = false
promoted=$(standby_sql "SELECT pg_promote(true,30)")
test "$promoted" = t
test "$(standby_sql "SELECT pg_is_in_recovery()")" = f
java -cp "$fixture_cp" com.revature.repository.jdbc.Wave23CommitOutcomeFixture recover 55422
printf '%s\n' "W31_POSITIVE_CLIENT_COMMIT_SURVIVES_PROMOTION:$mode"

python3 - "$EXPECTED_HEAD" "$version" "$mode" "$primary_lsn" "$standby_write" "$standby_flush" "$standby_replay" "$standby_receive_before" "$standby_replay_before" "$standby_replayed_lsn" <<'PY'
import datetime,json,pathlib,sys
sha,version,mode,primary,write,flush,replay,received_before,replay_before,replayed=sys.argv[1:]
data={
  "status":"PASS_DISPOSABLE_CONNECTED_STANDBY_POSITIVE_COMMIT",
  "candidate_sha":sha,
  "postgresql_version_num":int(version),
  "policy":mode,
  "primary_current_wal_lsn_after_successful_commit":primary,
  "primary_observed_replica_write_lsn":write,
  "primary_observed_replica_flush_lsn":flush,
  "primary_observed_replica_replay_lsn":replay,
  "standby_receive_lsn_at_ack_snapshot":received_before,
  "standby_replay_lsn_at_ack_snapshot":replay_before,
  "standby_replay_lsn_before_promotion":replayed,
  "real_client_received_successful_commit_response":True,
  "replica_state_before_commit":"streaming sync" if mode!="async" else "streaming async",
  "standby_business_replay_explicitly_confirmed_before_promotion":True,
  "old_primary_fenced_before_promotion":True,
  "promoted_primary_sale_count":1,
  "promoted_primary_vehicle_ownership_count":1,
  "promoted_primary_payment_plan_count":1,
  "promoted_primary_remaining_stock":0,
  "repeated_purchase_approval_idempotent":True,
  "evidence_timestamp_utc":datetime.datetime.now(datetime.timezone.utc).isoformat(),
  "limitations":[
    "async client commit success does not imply remote WAL flush or zero data loss in a partition",
    "positions collected after client success; they are sampled, not atomic commit instant snapshots",
    "business replay was explicitly awaited before old-primary fence and promotion",
    "no external payment charge, production quorum, client route, failback or measured RPO/RTO"
  ]
}
dest=pathlib.Path("wave31-positive")/f"positive_{mode}_evidence.json"
dest.write_text(json.dumps(data,indent=2)+"\n",encoding="utf-8")
print(json.dumps(data,indent=2))
PY
