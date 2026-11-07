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
mkdir -p wave28-sync

suffix=$(printf '%s' "$GITHUB_RUN_ID" | tr -cd '0-9')
test -n "$suffix"
network="w28sync-$suffix"
primary="w28s-primary-$suffix"
standby="w28s-standby-$suffix"
volume="w28s-standbydata-$suffix"
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
printf '%s\n' "W28: physical replication streaming on disposable PostgreSQL16"

# Compile real application and test fixture using the exact PR SHA.
mvn --batch-mode --no-transfer-progress -DskipTests test-compile
mvn --batch-mode --no-transfer-progress -DskipTests \
  org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=wave28-sync/maven-classpath.txt
test -s wave28-sync/maven-classpath.txt
fixture_cp="target/test-classes:target/classes:$(cat wave28-sync/maven-classpath.txt)"
java -cp "$fixture_cp" com.revature.repository.jdbc.Wave23CommitOutcomeFixture seed-pending 55421
# Ensure both physical replica and PENDING dealership purchase are in sync
# before switching this *disposable* primary to synchronous remote_apply.
observed=0
for attempt in $(seq 1 90); do
  pending=$(standby_sql "SELECT count(*) FROM purchase_requests WHERE request_id=0 AND status='PENDING'" 2>/dev/null || true)
  if test "$pending" = 1; then observed=1; break; fi
  sleep 1
done
test "$observed" = 1
standby_before=$(standby_sql "SELECT pg_last_wal_replay_lsn()")

primary_sql "ALTER SYSTEM SET synchronous_standby_names = '*'" >/dev/null
primary_sql "ALTER SYSTEM SET synchronous_commit = 'remote_apply'" >/dev/null
primary_sql "SELECT pg_reload_conf()" >/dev/null
test "$(primary_sql "SHOW synchronous_standby_names")" = '*'
test "$(primary_sql "SHOW synchronous_commit")" = remote_apply
recognized=0
for attempt in $(seq 1 90); do
  state=$(primary_sql "SELECT count(*) FROM pg_stat_replication WHERE state='streaming' AND sync_state='sync'" 2>/dev/null || true)
  if test "$state" = 1; then recognized=1; break; fi
  sleep 1
done
test "$recognized" = 1
printf '%s\n' "W28_SYNC_PHYSICAL_STANDBY_REMOTE_APPLY_CONFIRMED"

# Losing a synchronous standby must not produce a successful COMMIT reply;
# a real dealership approval transaction is launched on the old primary.
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
disconnected=0
for attempt in $(seq 1 60); do
  streamers=$(primary_sql "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'" 2>/dev/null || true)
  if test "$streamers" = 0; then disconnected=1; break; fi
  sleep 0.25
done
test "$disconnected" = 1

java -cp "$fixture_cp" com.revature.repository.jdbc.Wave28SynchronousCommitWorker approve \
  >wave28-sync/sync-client.log 2>&1 &
worker_pid=$!
begin=0
for attempt in $(seq 1 120); do
  if grep -q '^W28_SYNCHRONOUS_APPROVAL_START$' wave28-sync/sync-client.log; then
    begin=1; break
  fi
  if ! kill -0 "$worker_pid" >/dev/null 2>&1; then
    cat wave28-sync/sync-client.log >&2; exit 1
  fi
  sleep 0.25
done
test "$begin" = 1

sync_wait=0
for attempt in $(seq 1 120); do
  n=$(primary_sql "SELECT count(*) FROM pg_stat_activity WHERE application_name='W28SyncApproval' AND wait_event='SyncRep'" 2>/dev/null || true)
  if test "$n" -ge 1 2>/dev/null; then sync_wait=1; break; fi
  if ! kill -0 "$worker_pid" >/dev/null 2>&1; then
    cat wave28-sync/sync-client.log >&2; exit 1
  fi
  sleep 0.25
done
test "$sync_wait" = 1
test -z "$(grep 'W28_SYNC_CLIENT_RESULT_UNCERTAIN_AFTER_FENCING' wave28-sync/sync-client.log || true)"
test "$(standby_sql "SELECT count(*) FROM purchase_requests WHERE request_id=0 AND status='PENDING'")" = 1
test "$(standby_sql "SELECT count(*) FROM owned_vehicles")" = 0
test "$(standby_sql "SELECT count(*) FROM payment_plans")" = 0
test "$(standby_sql "SELECT count(*) FROM inventory_listings WHERE listing_id=0 AND stock_quantity=1")" = 1
primary_lsn=$(primary_sql "SELECT pg_current_wal_lsn()")
standby_lsn=$(standby_sql "SELECT pg_last_wal_replay_lsn()")
printf '%s\n' "W28_SYNC_REMOTE_APPLY_COMMIT_BLOCKED_WITH_REPLICA_DISCONNECTED"

# Fence before promotion: this fixture does NOT allow split-brain, and has
# no production client/router/HA controller. The old client must see UNKNOWN.
docker kill -s KILL "$primary" >/dev/null
test "$(docker inspect -f '{{.State.Running}}' "$primary")" = false
# Restore the promoted standby's published localhost:55422 endpoint only
# AFTER fencing the former primary, so a new JDBC client can reconcile.
docker network connect "$network" "$standby"
promoted=$(standby_sql "SELECT pg_promote(true,30)")
test "$promoted" = t
test "$(standby_sql "SELECT pg_is_in_recovery()")" = f
wait "$worker_pid"
worker_pid=""
grep -q '^W28_SYNC_CLIENT_RESULT_UNCERTAIN_AFTER_FENCING$' wave28-sync/sync-client.log

# Despite primary's locally flushed WAL, no successful sync COMMIT was
# returned. The new leader still shows a PENDING business request.
java -cp "$fixture_cp" com.revature.repository.jdbc.Wave27InFlightCommitFixture recover 55422
printf '%s\n' "W28_SYNC_NO_ACK_RECONCILED_AND_RETRIED_ON_PROMOTED_PRIMARY"

python3 - "$EXPECTED_HEAD" "$version" "$standby_before" "$primary_lsn" "$standby_lsn" <<'PY'
import datetime,json,pathlib,sys
proof={
 "status":"PASS_DISPOSABLE_SYNCHRONOUS_REMOTE_APPLY_COMMIT_WAIT",
 "candidate_sha":sys.argv[1],
 "postgresql_version_num":int(sys.argv[2]),
 "initial_standby_replay_lsn":sys.argv[3],
 "primary_lsn_during_SyncRep_wait":sys.argv[4],
 "standby_lsn_during_SyncRep_wait":sys.argv[5],
 "synchronous_standby_names":"*",
 "synchronous_commit":"remote_apply",
 "physical_standby_sync_state_confirmed":True,
 "replication_disconnected_after_initial_catchup":True,
 "actual_dealership_COMMIT_wait_event_SyncRep_observed":True,
 "customer_client_success_before_replica_ack":False,
 "former_primary_fenced_before_promotion":True,
 "former_client_outcome_uncertain":True,
 "new_leader_pending_before_retry":True,
 "new_leader_accepted_after_one_retry":True,
 "one_ownership_count":1,
 "one_payment_plan_count":1,
 "inventory_stock_after_retry":0,
 "observed_utc":datetime.datetime.now(datetime.timezone.utc).isoformat(),
 "not_verified":[
  "A synchronous wait event does not certify production durability or all network failure timings",
  "This fixture does not measure RPO or RTO; role grants, quorum/fencing and failback remain open",
  "The client was blocked before successful synchronous COMMIT acknowledgment"
 ]
}
p=pathlib.Path("wave28-sync/physical_recovery_evidence.json")
p.write_text(json.dumps(proof,indent=2)+"\n",encoding="utf-8")
print(json.dumps(proof,indent=2))
PY
