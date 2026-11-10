# Wave34.22 — Deterministic PostgreSQL16 SQL Boundary Acceptance

**STATUS: PROPOSED / NOT EXECUTED / REVIEW ONLY / DRAFT / UNMERGED / PRODUCTION NO-GO.**

## Source custody
- Repository: \`danwiz/Project0-Cardealership-Maven\`.
- Frozen parent: Wave34.21-B draft PR #55, exact SHA \`db5dcf00ec9f3a9455f031fa8523548e310a4ade\`; its successful 4,000-repeat test evidence is retained as previous evidence only.
- Exactly five permitted additions: the W3422 test, disposable helper, custody/roster checker, CI workflow, and this runbook.
- All W34.15–W34.21 previously reviewed Java/SQL fixtures, \`pom.xml\`, application Java and schema-v5 runtime migration are frozen. Test reads W34.15 and W34.16 SQL from the repository unmodified into an isolated randomly named \`w3422_<UUID>\` PG16 schema, never into production schemas.
- Test data uses placeholder identifiers/hashes and is **not** finance-approved, cryptographically signed bank/GL evidence or production activity.

## Acceptance scenarios and boundaries

| ID | Check | Bounded actual oracle |
| --- | --- | --- |
| W3422-01 | Same settlement batch competing at unique key before A commits | B's backend is observed blocking on A through \`pg_blocking_pids\`; after A commits, B exact readback returns 0 new rows; one review, 3 sources, 1 audit |
| W3422-02 | Different settlement batch reuses maker UUID | B is observed blocked by A; after A commits, B returns fail-closed, no phantom rows |
| W3422-03 | Same settlement ID changes amount/digest or maker approval | Original row persists; altered request denied |
| W3422-04 | Exact synthetic GL event under concurrent unique-key lock | B blocked until A commits; one event, 2 balanced lines, no repeated posting |
| W3422-05 | Different GL event reuses provider ID, sequence or approval | Provider collision observed as an actual PostgreSQL blocker; all three identity conflicts denied without GL drift |
| W3422-06 | Deferred GL balance enforcement, incomplete or mismatched lines | SQLSTATE 23514 at COMMIT; entire synthetic transaction rolls back |
| W3422-07 | PostgreSQL controlled advisory-lock deadlock | Explicit observed waiting edge, SQLSTATE 40P01 victim, survivor commits, no partial synthetic financial rows |
| W3422-08 | Exact readback replay versus changed digest/amount | Exact replay accepted without extra rows; changed request denied |

Eight test methods are expected in the **full** JUnit roster, increasing the prior baseline to **450 methods in 126 classes** per Java 11 and Java 17. The independent selected SECURITY roster remains **151 methods in 64 classes**. These are targets, **not PASS claims until exact-head Actions results are verified**.

The success of the first, second, fourth, fifth and seventh interleavings is not established by arbitrary sleep duration. A separate observing connection checks \`pg_blocking_pids(waitingPID)\` under a bounded timeout. Transaction IDs and backend PID availability are test-specific; PG backend PIDs are preserved as small diagnostic metadata. Only **selected** interleavings are controlled; this is **not exhaustive deterministic scheduling** of every SQL or Java thread instruction. Test helper enforces CI-only exact localhost PostgreSQL16, a disposable flag, a random namespaced schema, and cleanup via \`DROP SCHEMA ... CASCADE\`.

## Explicitly not implemented in this milestone
- **W3422-09:** independently operated witness validation, external KMS/HSM and revocation/rotation against an unrelated administrator domain — **NOT RUN / requires independent custody**.
- **W3422-10:** real external delivery receiver, durable inbox and external effect idempotency after crash-before-ACK — **NOT RUN / requires independently controlled receiver**.
- Actual maker/checker finance authority, policy, provider/bank/GL source attestations, PVA-01..16, live funds, accounting periods, production deployments, HA and failover are not addressed.
- A passing JUnit suite does not substitute for independent QA/AppSec verification or Stage27/Phase16/12 Wave34.19 release gates and human signoffs.

## CI execution and retention
1. Compare immutable parent and PR-head SHA, exactly five changed paths, frozen file blob SHAs and non-release language.
2. On JDK11 and JDK17 with real disposable PostgreSQL16, run full baseline, check 450/126 method/class roster and eight exact-head manifests, then rerun SECURITY selection 151/64 separately. Keep failure and skipped method information.
3. Save full and SECURITY JUnit XML, key-free manifests and source-control evidence using SHA-labeled artifacts. If jobs fail, retain failed logs; do **not** report a successful milestone.
4. Independently review test quality, lock graph, negative-path coverage, schema cleanup, and whether the original issues #51 and #53 are sufficiently narrowed. Do not close those issues without review.
5. Protected \`master\` stays unchanged; PR remains draft, unmerged and undeployed.

**PRODUCTION NO-GO.** This documentation is test-only and does not authorize release, PR merge, payments, schema promotion or issue closure.
