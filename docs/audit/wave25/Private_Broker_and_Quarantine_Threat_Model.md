# Wave25 — Private Credential Broker and Quarantined Primary Restart

**Candidate only:** Based on Wave24 PR #24 at `343a194f5b737160d71874c965fe7952f2059690`.  
**No deployment, merge, production GRANT, credentials or network changes.**

## Private customer credential change

The new `PrivateCustomerCredentialChangeService` has exactly one public method accepting username, current plaintext password and replacement plaintext password. It returns a boolean, never an encoded account hash or a database credential. The broker obtains a persisted CUSTOMER account using a private reader, verifies its current password with PBKDF2, and calls a separately configured `TrustedCustomerCredentialWriter` with the account snapshot and plaintext. The writer must lock the persisted row and independently recheck role, stored credential and session generation before update.

A separate PostgreSQL16 JUnit fixture supplies two distinct database identities, with the authenticator allowed only SELECT and the writer SELECT plus UPDATE(encoded_credential). It tests failed password, missing account, ADMINISTRATOR impersonation, unchanged password, direct SQL escalation denial, valid rotation, old-password invalidation and stale session rejection.

**Important limitation:** this Java service is a *prototype trusted service facade*, not a separately deployed process or mutually authenticated RPC endpoint. Public registration must not be able to instantiate, call or obtain the writer. Exposing writer DB secrets or bypass SQL access would defeat the Java checks. Real deployment must address service-to-service authentication, secret isolation, rate limiting, account recovery, monitoring and DBA-owned migrations. A stolen SQL writer identity can still issue raw credential updates; no claim of resistance is made.

## PostgreSQL standby and old-primary restart

The new disposable CI lab reproduces real schema-v5 dealership approval/ownership/payment-plan state on primary and physical standby, severs replication connectivity while old primary is writable, and explicitly fences it *before* promotion. It then restarts the stale former primary **with zero Docker network attachments** and checks whether the stale node is locally writable. If so, it is stopped again without ever joining the promoted standby's network.

This proves a bounded hazard: a restarted old PostgreSQL data directory is not automatically a safe member of a new primary timeline. No external client should reach the stale node. The lab does NOT model production fencing/quorum, rejoin/rebuild, simultaneous COMMIT during promotion, service discovery, client routing or measured RPO/RTO.

## Evidence requirements

- 371 full and 142 SECURITY tests, on both Java 11 and 17, with all prior 370/141 method identities retained.
- The named real PG16 broker regression must pass and never be skipped.
- Physical old-primary quarantine workflow must confirm zero network attachments at restart, stale writeability, refencing and exactly-once application state after promoted standby replay.
- Exact candidate Git SHA, original Surefire XML identities, GitHub artifact digests and changes to each gate documented.

All fourteen Stage27 gates remain FORMALLY NOT CLOSED. No production promotion without independent DBA, AppSec, QA, architect, source-rights and release-owner reviews.
