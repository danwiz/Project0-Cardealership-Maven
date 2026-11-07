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
mkdir -p wave27-race

suffix=$(printf '%s' "$GITHUB_RUN_ID" | tr -cd '0-9')
test -n "$suffix"
network="w27race-$suffix"
primary="w27-primary-$suffix"
standby="w27-standby-$suffix"
volume="w27-standbydata-$suffix"
owner="labowner"
database="labdb"
password="w21-ci-only-owner-not-production"
replica_password="w21-ci-only-replica-not-production"

worker_pid=""
cleanup() {
  if test -n "$worker_pid"; then kill "$worker_pid" >/dev/null 2>&1 || true; fi
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
printf '%s\n' "W27: physical replication streaming on disposable PostgreSQL16"

# Compile real application and test fixture using the exact PR SHA.
mvn --batch-mode --no-transfer-progress -DskipTests test-compile
mvn --batch-mode --no-transfer-progress -DskipTests \
  org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=wave27-race/maven-classpath.txt
test -s wave27-race/maven-classpath.txt
fixture_cp="target/test-classes:target/classes:$(cat wave27-race/maven-classpath.txt)"
java -cp "$fixture_cp" com.revature.repository.jdbc.Wave27InFlightCommitFixture seed-pending 55421
# A real purchase approval on old primary has executed SQL but its COMMIT
# message will be blocked in a Java wire proxy before reaching the backend.
seen=0
for attempt in $(seq 1 90); do
  pending=$(standby_sql "SELECT count(*) FROM purchase_requests WHERE request_id=0 AND status='PENDING'" 2>/dev/null || true)
  if test "$pending" = 1; then seen=1; break; fi
  sleep 1
done
test "$seen" = 1
primary_lsn=$(primary_sql "SELECT pg_current_wal_lsn()")
standby_replayed_lsn=$(standby_sql "SELECT pg_last_wal_replay_lsn()")

rm -f wave27-race/release.marker
java -cp "$fixture_cp" com.revature.repository.jdbc.Wave27InFlightCommitFixture hold-commit 55421 \
  >wave27-race/inflight-client.log 2>&1 &
worker_pid=$!
held=0
for attempt in $(seq 1 120); do
  if grep -q '^W27_COMMIT_BLOCKED_BEFORE_BACKEND$' wave27-race/inflight-client.log; then
    held=1
    break
  fi
  if ! kill -0 "$worker_pid" >/dev/null 2>&1; then
    cat wave27-race/inflight-client.log >&2
    exit 1
  fi
  sleep 0.25
done
test "$held" = 1
printf '%s\n' "W27: actual dealership COMMIT frame held, JDBC approval still in flight"
# COMMIT has not reached server; replica must not show a durable sale.
test "$(standby_sql "SELECT count(*) FROM purchase_requests WHERE request_id=0 AND status='PENDING'")" = 1
test "$(standby_sql "SELECT count(*) FROM owned_vehicles")" = 0
test "$(standby_sql "SELECT count(*) FROM payment_plans")" = 0
test "$(standby_sql "SELECT count(*) FROM inventory_listings WHERE listing_id=0 AND stock_quantity=1")" = 1

# Fence the old primary while COMMIT is still held, promote the standby, and
# only then disconnect the original waiting client.
docker kill -s KILL "$primary" >/dev/null
test "$(docker inspect -f '{{.State.Running}}' "$primary")" = false
promoted=$(standby_sql "SELECT pg_promote(true, 30)")
test "$promoted" = t
test "$(standby_sql "SELECT pg_is_in_recovery()")" = f
printf '%s\n' "W27: promoted physical standby while original COMMIT was held"
: > wave27-race/release.marker
wait "$worker_pid"
worker_pid=""
grep -q '^W27_CLIENT_COMMIT_OUTCOME_UNCERTAIN_AFTER_PROMOTION$' wave27-race/inflight-client.log
printf '%s\n' "W27: original JDBC caller classified result UNKNOWN"

# New application on promoted server sees no completed sale, then safely
# approves one pending request and verifies idempotency on a second retry.
java -cp "$fixture_cp" com.revature.repository.jdbc.Wave27InFlightCommitFixture recover 55422
printf '%s\n' "W27: one durable accepted request, ownership and payment plan after replay"

python3 - "$EXPECTED_HEAD" "$version" "$primary_lsn" "$standby_replayed_lsn" <<'PY'
import datetime,json,pathlib,sys
proof={
 "status":"PASS_DISPOSABLE_PRECOMMIT_INTERRUPTED_DURING_PHYSICAL_PROMOTION",
 "candidate_sha":sys.argv[1],
 "postgresql_version_num":int(sys.argv[2]),
 "primary_lsn_before_cutover":sys.argv[3],
 "standby_replay_lsn_before_cutover":sys.argv[4],
 "actual_purchase_approval_started":True,
 "real_backend_commit_frame_held_before_delivery":True,
 "physical_promotion_during_pending_client_commit":True,
 "old_primary_fenced_before_promotion":True,
 "client_outcome_classified_uncertain":True,
 "promoted_leader_pending_before_retry":True,
 "promoted_leader_one_accepted_request_after_retry":True,
 "promoted_ownership_count":1,
 "promoted_payment_plan_count":1,
 "promoted_inventory_stock":0,
 "repeated_approval_idempotent":True,
 "observed_utc":datetime.datetime.now(datetime.timezone.utc).isoformat(),
 "not_verified":[
   "The COMMIT packet was blocked BEFORE reaching old backend; post-COMMIT WAL race is a different scenario",
   "No production fencing/quorum, RPO/RTO, client routing or automatic retry acceptance",
   "No payment/refund concurrency during promotion"
 ]
}
p=pathlib.Path("wave27-race/physical_recovery_evidence.json")
p.write_text(json.dumps(proof,indent=2)+"\n",encoding="utf-8")
print(json.dumps(proof,indent=2))
PY
