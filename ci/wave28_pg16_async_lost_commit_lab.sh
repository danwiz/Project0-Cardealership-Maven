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
mkdir -p wave28-async

suffix=$(printf '%s' "$GITHUB_RUN_ID" | tr -cd '0-9')
test -n "$suffix"
network="w28async-$suffix"
primary="w28-primary-$suffix"
standby="w28-standby-$suffix"
volume="w28-standbydata-$suffix"
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
printf '%s\n' "W28: physical replication streaming on disposable PostgreSQL16"

# Compile real application and test fixture using the exact PR SHA.
mvn --batch-mode --no-transfer-progress -DskipTests test-compile
mvn --batch-mode --no-transfer-progress -DskipTests \
  org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=wave28-async/maven-classpath.txt
test -s wave28-async/maven-classpath.txt
fixture_cp="target/test-classes:target/classes:$(cat wave28-async/maven-classpath.txt)"
java -cp "$fixture_cp" com.revature.repository.jdbc.Wave23CommitOutcomeFixture seed-pending 55421
# Explicitly require asynchronous streaming: a primary COMMIT must NOT
# wait for this standby to receive or replay the commit record.
test -z "$(primary_sql "SHOW synchronous_standby_names")"
test "$(primary_sql "SHOW synchronous_commit")" = on
# Prove the *initial* pending request exists on the standby before isolating.
pending=0
for attempt in $(seq 1 90); do
  value=$(standby_sql "SELECT count(*) FROM purchase_requests WHERE request_id=0 AND status='PENDING'" 2>/dev/null || true)
  if test "$value" = 1; then pending=1; break; fi
  sleep 1
done
test "$pending" = 1
test "$(standby_sql "SELECT count(*) FROM owned_vehicles")" = 0
test "$(standby_sql "SELECT count(*) FROM payment_plans")" = 0
baseline_standby_lsn=$(standby_sql "SELECT pg_last_wal_replay_lsn()")

# Disconnect replication while leaving the original primary accessible
# locally on its Docker loopback port. This guarantees no further WAL is
# transported to the physical standby before the synthetic purchase commits.
# Detach the *replica* rather than the primary: primary's published
# localhost:55421 TCP mapping must remain reachable by the real JDBC client.
# Established walsender connection is separately terminated below.
docker network disconnect -f "$network" "$standby"
# A Docker interface disconnect does not necessarily immediately close an
# established PostgreSQL walsender TCP session. Force the disposable sender
# closed, and require pg_stat_replication to lose its streaming peer BEFORE
# any business COMMIT is attempted.
primary_sql "SELECT pg_terminate_backend(pid) FROM pg_stat_replication" >/dev/null || true
printf '%s\\n' "W28: replication sender termination requested after lab network isolation"
test "$(docker inspect -f '{{.State.Running}}' "$primary")" = true
test "$(primary_sql "SELECT pg_is_in_recovery()")" = f
# Wait until the old primary no longer reports a streaming replica.
disconnected=0
for attempt in $(seq 1 60); do
  streamers=$(primary_sql "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'" 2>/dev/null || true)
  if test "$streamers" = 0; then disconnected=1; break; fi
  sleep 0.25
done
test "$disconnected" = 1
printf '%s\n' "W28_ASYNC_REPLICA_DISCONNECTED_BEFORE_BUSINESS_COMMIT"

# Wave23's proven wire proxy forwards COMMIT to actual PostgreSQL, observes
# the backend ReadyForQuery, and drops only the client acknowledgment.
# Direct primary readback proves the dealership transaction really committed.
java -cp "$fixture_cp" com.revature.repository.jdbc.Wave23CommitOutcomeFixture drop-reply 55421
primary_lsn=$(primary_sql "SELECT pg_current_wal_lsn()")
standby_lsn=$(standby_sql "SELECT pg_last_wal_replay_lsn()")
difference=$(primary_sql "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '$standby_lsn')::bigint")
test "$difference" -gt 0
printf '%s\n' "W28_ASYNC_PRIMARY_COMMITTED_WITH_STANDBY_WAL_LAG"
# Standby still has the PENDING request, zero ownership/plan and stock=1.
test "$(standby_sql "SELECT count(*) FROM purchase_requests WHERE request_id=0 AND status='PENDING'")" = 1
test "$(standby_sql "SELECT count(*) FROM purchase_requests WHERE request_id=0 AND status='ACCEPTED'")" = 0
test "$(standby_sql "SELECT count(*) FROM owned_vehicles")" = 0
test "$(standby_sql "SELECT count(*) FROM payment_plans")" = 0
test "$(standby_sql "SELECT count(*) FROM inventory_listings WHERE listing_id=0 AND stock_quantity=1")" = 1

# FENCE old primary while its acknowledged-to-server transaction is NOT
# present on standby. Do not reconnect replication or claim zero RPO.
docker kill -s KILL "$primary" >/dev/null
test "$(docker inspect -f '{{.State.Running}}' "$primary")" = false
# Restore the promoted standby's published localhost:55422 endpoint only
# AFTER fencing the former primary, so a new JDBC client can reconcile.
docker network connect "$network" "$standby"
promoted=$(standby_sql "SELECT pg_promote(true,30)")
test "$promoted" = t
test "$(standby_sql "SELECT pg_is_in_recovery()")" = f
# Reconcile under the original request ID before any business retry.
java -cp "$fixture_cp" com.revature.repository.jdbc.Wave27InFlightCommitFixture recover 55422
printf '%s\n' "W28_ASYNC_LOST_PRIMARY_COMMIT_RECONCILED_ON_PROMOTED_STANDBY"

python3 - "$EXPECTED_HEAD" "$version" "$baseline_standby_lsn" "$primary_lsn" "$standby_lsn" "$difference" <<'PY'
import datetime,json,pathlib,sys
proof={
 "status":"PASS_DISPOSABLE_ASYNC_PRIMARY_COMMIT_NOT_REPLICATED",
 "candidate_sha":sys.argv[1],
 "postgresql_version_num":int(sys.argv[2]),
 "pre_partition_standby_replay_lsn":sys.argv[3],
 "post_commit_primary_lsn":sys.argv[4],
 "post_commit_standby_replay_lsn":sys.argv[5],
 "observed_wal_lsn_delta_bytes":int(sys.argv[6]),
 "synchronous_standby_names_empty":True,
 "synchronous_commit_mode":"on (local WAL flush; async physical standby)",
 "replica_network_isolated_before_purchase_commit":True,
 "server_accepted_actual_dealership_COMMIT":True,
 "client_COMMIT_response_suppressed_and_outcome_uncertain":True,
 "primary_had_one_accepted_purchase":True,
 "isolated_standby_still_pending_before_promotion":True,
 "old_primary_fenced_before_promotion":True,
 "promoted_standby_initially_lacked_primary_purchase_commit":True,
 "original_request_reconciled_and_retried_once":True,
 "promoted_purchase_accepted_count":1,
 "promoted_ownership_count":1,
 "promoted_payment_plan_count":1,
 "promoted_inventory_stock":0,
 "observed_utc":datetime.datetime.now(datetime.timezone.utc).isoformat(),
 "not_verified":[
   "WAL LSN delta is NOT itself exact lost transaction bytes or production RPO in time",
   "Primary had locally committed; the disconnected standby intentionally lacked commit WAL",
   "No failback, automatic fencing/quorum, mixed payment flows or production failover",
   "Synthetic business test does NOT authorize loss of acknowledged transactions in production"
 ]
}
p=pathlib.Path("wave28-async/physical_recovery_evidence.json")
p.write_text(json.dumps(proof,indent=2)+"\n",encoding="utf-8")
print(json.dumps(proof,indent=2))
PY
