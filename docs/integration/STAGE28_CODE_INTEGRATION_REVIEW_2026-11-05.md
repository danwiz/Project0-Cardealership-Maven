# Stage 28 — Controlled Maven code integration (draft review)

**Prepared:** 2026-11-05  
**State:** Draft code-integration candidate; NOT merged, released, deployed or production-ready.

## Authority and exact baseline

- Owner authorization: historical decisions M-01..M-06, S-01..S-15, SO-01..SO-09 and A-01 (31 total) approved 2026-11-05.
- Owner authorization: Maven destination class dispositions C01..C12 (12 total) approved 2026-11-05, with independent AppSec/QA/architecture review still required.
- Owner release publication authorized *subject to all acceptance gates*; publication remains **NO-GO** until documented evidence and independent reviewer signoff.
- Governance record: [draft PR #13](https://github.com/danwiz/Project0-Cardealership-Maven/pull/13). It remains a separate unmerged draft.
- Destination parent: `a8dce295b9ab45ca6e896035bb81788bd4aff217` on `master`.
- Source modernization commit: `0cbad314956ad1d36542d30afa6519a1bbfecdce` (tree `c2887034b4ef819f1d0429a25a024c6a1acb35ac`).
- Supplemental compatible tests: PR #106 commit `6c6156ff598ecb8efd8467251c1540ca7d756178`.
- PR #107 CI trigger change is **not** merged wholesale. The candidate workflow is a separately reviewed derivative.

## Source migration

The draft imports 199 exact frozen Git blobs:
- 105 application Java source files moved from historical Eclipse `src/com/revature/` to canonical Maven `src/main/java/com/revature/`.
- 91 characterization/integration Java test files retained under `src/test/java/`.
- 3 PostgreSQL test SQL files retained under `src/test/resources/`.

Three exact PR #106 test blobs are added without rewriting source content (94 proposed Java test files total). This yields 202 individually source-traceable source/test/SQL mappings. All original blob SHAs have been compared to the destination Git tree before commit creation.

Original historical Java copies `Carlot - Copy.java` and `Customer - Copy.java` are preserved in source history but excluded from compilation. The destination's 17 tracked build files under `target/` and its unconditional failing JUnit 4 placeholder `src/test/java/cardealer/EvalService.java` are removed from the draft candidate only; all remain recoverable from the Maven `master` history.

## Build and CI implementation

- Standard Maven source/test directories and project artifact `com.revature:cardealer:0.1.0-SNAPSHOT`.
- JDK source target 11, with Java 11/17 CI matrix.
- JUnit Jupiter 5.11.4, H2 2.3.232 and PostgreSQL JDBC 42.7.14 as documented in frozen source POM.
- Maven Surefire **3.6.0** is pinned to the current destination baseline rather than silently accepting the separate Dependabot 3.6.1 [PR #12](https://github.com/danwiz/Project0-Cardealership-Maven/pull/12). The upgrade remains a separate compatibility/security review.
- Existing Maven CI workflow becomes a controlled Java 11/17 and disposable PostgreSQL 16 matrix; logical decoding is enabled only for the ephemeral CI service, with read-only repository permissions and head-SHA checkout.
- Proposed fail-closed **count-level** verification expects 352 full JUnit methods across 90 classes and 123 security-selected methods across 46 classes (both `SECURITY` and `security`). It rejects missing, duplicate, failing and skipped testcases. Full and security Surefire XML reports are archived separately.
- **Limit:** This stage's CI script checks method counts and uniqueness but does not yet compare all names against the independently frozen Stage 24 exact-method manifest. Cross-JDK evidence, true recovery fault injection and independent test evidence remain open release gates.

## Mandatory review before merge or release

1. Independent review of C01..C12 replacement semantics and the 31 merged-source decisions: role enforcement, authorization/credential migration, transaction atomicity, Offer API, serialization, data and interface compatibility.
2. Verify the exact GitHub PR head and one reproducible integrated commit, its full and security-filtered test-method roster on Java 11 and 17, and passing PostgreSQL 16 tests without skips.
3. Extend to exact named-method manifest validation and reconcile cross-JDK evidence.
4. Exercise real COMMIT ACK loss, failover, durable reconciliation, least privilege, legal/source provenance, SBOM/SCA, secrets, licensing and rollback in a disposable environment.
5. Obtain distinct independent AppSec/DBA/QA release signoffs, address any new CI defects and retain the owner's release-authorization record.
6. Do not merge the draft, tag a release, deploy, provision production secrets, or claim production readiness automatically.

**This code PR is for review and CI evidence only. It does not grant production release approval by itself.**
