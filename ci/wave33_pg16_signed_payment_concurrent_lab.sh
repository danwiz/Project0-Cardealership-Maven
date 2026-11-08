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
mkdir -p wave33-pay

suffix=$(printf '%s' "$GITHUB_RUN_ID" | tr -cd '0-9')
test -n "$suffix"
network="w33pay-$suffix"
provider="w33-provider-$suffix"
primary="w33-primary-$suffix"
standby="w33-standby-$suffix"
volume="w33-standbydata-$suffix"
owner="labowner"
database="labdb"
password="w21-ci-only-owner-not-production"
replica_password="w21-ci-only-replica-not-production"

cleanup() {
  docker rm -f "$standby" "$primary" "$provider" >/dev/null 2>&1 || true
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
printf '%s\n' "W33: physical replication streaming on disposable PostgreSQL16"

# Compile real application and test fixture using the exact PR SHA.
mvn --batch-mode --no-transfer-progress -DskipTests test-compile
mvn --batch-mode --no-transfer-progress -DskipTests \
  org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=wave33-pay/maven-classpath.txt
test -s wave33-pay/maven-classpath.txt
fixture_cp="target/test-classes:target/classes:$(cat wave33-pay/maven-classpath.txt)"
java -cp "$fixture_cp" com.revature.repository.jdbc.Wave23CommitOutcomeFixture seed-pending 55421
# A third independent disposable PostgreSQL16 container is a simulated
# external processor. It is NOT a standby or a component of dealership WAL.
# No real payment API is contacted and no actual funds are collected.
docker run -d --name "$provider" --network "$network" --restart=no \
  -p 127.0.0.1:55423:5432 \
  -e POSTGRES_USER=processor_owner \
  -e POSTGRES_PASSWORD=ci-synthetic-independent-processor \
  -e POSTGRES_DB=processor_lab postgres:16 >/dev/null
provider_ready=0
for attempt in $(seq 1 90); do
  if docker exec "$provider" pg_isready -h 127.0.0.1 -U processor_owner -d processor_lab >/dev/null 2>&1; then
    provider_ready=1
    break
  fi
  sleep 1
done
test "$provider_ready" = 1

# Create one ACTUAL dealership sale and payment-plan state before processor
# charge; a separate customer payment will be reconciled after promotion.
W31_DISPOSABLE_ONLY=true java -cp "$fixture_cp" \
  com.revature.repository.jdbc.Wave31PositiveCommitFixture approve
W33_DISPOSABLE_ONLY=true java -cp "$fixture_cp" \
  com.revature.repository.jdbc.Wave33AuthenticatedPaymentFixture prepare

# Require physical standby to have accepted sale and receipt mapping schema
# before any old-primary fencing. The external charge is NOT posted locally.
caught_up=0
for attempt in $(seq 1 90); do
  accepted=$(standby_sql "SELECT count(*) FROM purchase_requests WHERE request_id=0 AND status='ACCEPTED'" 2>/dev/null || true)
  ownership=$(standby_sql "SELECT count(*) FROM owned_vehicles" 2>/dev/null || true)
  plan=$(standby_sql "SELECT count(*) FROM payment_plans WHERE amount_paid=0 AND balance=24000" 2>/dev/null || true)
  receipts=$(standby_sql "SELECT count(*) FROM external_receipt_postings" 2>/dev/null || true)
  if test "$accepted" = 1 && test "$ownership" = 1 && test "$plan" = 1 && test "$receipts" = 0; then
    caught_up=1; break
  fi
  sleep 1
done
test "$caught_up" = 1
primary_lsn=$(primary_sql "SELECT pg_current_wal_lsn()")
standby_replayed_lsn=$(standby_sql "SELECT pg_last_wal_replay_lsn()")
test -n "$primary_lsn"
test -n "$standby_replayed_lsn"

# The provider irrevocably records exactly one synthetic processor request.
# The caller deliberately SUPPRESSES its own success event after that COMMIT,
# simulating loss at application-response handling, NOT a wire-level proxy.
W33_DISPOSABLE_ONLY=true java -cp "$fixture_cp" \
  com.revature.repository.jdbc.Wave33AuthenticatedPaymentFixture charge-ack-lost
test "$(standby_sql "SELECT count(*) FROM payment_transactions")" = 0
test "$(standby_sql "SELECT count(*) FROM external_receipt_postings")" = 0
printf '%s\n' "W33_PROVIDER_CHARGE_DURABLE_SEPARATE_DATABASE_NO_LOCAL_PAYMENT"

# Fence and promote dealership independently of the processor database.
docker kill -s KILL "$primary" >/dev/null
test "$(docker inspect -f '{{.State.Running}}' "$primary")" = false
test "$(docker inspect -f '{{.State.Running}}' "$provider")" = true
promoted=$(standby_sql "SELECT pg_promote(true,30)")
test "$promoted" = t
test "$(standby_sql "SELECT pg_is_in_recovery()")" = f

# Independent provider receipt is read via a SELECT-only SQL principal.
# First application posts the receipt, second tries same idempotency key.
# Payment-plan update and local PAY entry share one DB transaction.
W33_DISPOSABLE_ONLY=true java -cp "$fixture_cp" \
  com.revature.repository.jdbc.Wave33AuthenticatedPaymentFixture reconcile
test "$(standby_sql "SELECT count(*) FROM external_receipt_postings")" = 1
test "$(standby_sql "SELECT count(*) FROM payment_transactions WHERE amount=1250 AND cumulative_paid=1250 AND resulting_balance=22750")" = 1
test "$(standby_sql "SELECT count(*) FROM payment_plans WHERE amount_paid=1250 AND balance=22750")" = 1
test "$(standby_sql "SELECT count(*) FROM owned_vehicles")" = 1
printf '%s\n' "W33_SIGNED_PROVIDER_RECEIPT_POSTED_ONCE_WITH_CONCURRENT_REPLAYS"

python3 - "$EXPECTED_HEAD" "$version" "$primary_lsn" "$standby_replayed_lsn" <<'PY'
import datetime,json,pathlib,sys
proof={
 "status":"PASS_DISPOSABLE_INDEPENDENT_PROVIDER_RECEIPT_RECONCILIATION",
 "candidate_sha":sys.argv[1],
 "postgresql_version_num":int(sys.argv[2]),
 "dealership_primary_lsn_before_fence":sys.argv[3],
 "dealership_standby_replay_lsn_before_promotion":sys.argv[4],
 "independent_processor_postgres_container_survived_dealership_fencing":True,
 "independent_processor_request_id":"processor-req-000001",
 "independent_processor_charge_count":1,
 "separate_provider_commit_happened_before_primary_fence":True,
 "application_provider_ack_suppressed_after_commit":True,
 "original_dealership_local_payment_count":0,
 "promoted_primary_verified_real_ownership_and_payment_plan":True,
 "promoted_primary_posted_payment_count":1,
 "promoted_primary_receipt_register_count":1,
 "promoted_primary_final_plan_paid":1250,
 "promoted_primary_final_plan_balance":22750,
 "replay_did_not_duplicate_provider_charge_or_local_post":True,
 "provider_receipt_reader_has_no_update_rights":True,
 "fixture_receipt_authenticated_with_test_only_hmac":True,
 "provider_signature_amount_and_key_id_tampering_denied":True,
 "two_concurrent_reconciliation_attempts_one_insert":True,
 "third_sequential_replay_no_duplicate":True,
 "provider_key_id":"w33-ci-processor-key-1",
 "observed_utc":datetime.datetime.now(datetime.timezone.utc).isoformat(),
 "not_verified":[
   "Synthetic processor database and fixed credential are not a real payment provider",
   "ACK loss is simulated at the application boundary, not via a real network packet drop",
   "Receipt reader is SELECT-only; test HMAC is not from a real payment provider, and no mTLS or settlement authentication exists",
   "One provider charge with TWO concurrent local reconciliation attempts tested; no refunds/chargebacks, multiple installments or settlement races",
   "No automatic quorum fencing, business RPO/RTO, operational client router or production release approval",
   "Hard-coded test key and signing routine exist only in test source; no production provider key vault, migration, independent signer service or merchant API"
 ]
}
dest=pathlib.Path("wave33-pay/signed_provider_concurrent_reconciliation.json")
dest.write_text(json.dumps(proof,indent=2)+"\n",encoding="utf-8")
print(json.dumps(proof,indent=2))
PY
