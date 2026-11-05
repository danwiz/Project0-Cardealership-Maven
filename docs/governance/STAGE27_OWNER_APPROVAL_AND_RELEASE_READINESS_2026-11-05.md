# Stage 27 — Owner authorization register and release-readiness review
**Date:** 2026-11-05  
**Repository:** `danwiz/Project0-Cardealership-Maven`  
**Base:** `master` at `a8dce295b9ab45ca6e896035bb81788bd4aff217`  
**Status:** DOCUMENTATION-ONLY / DRAFT REVIEW / **RELEASE NO-GO**

## Authorization scope and limits

The owner expressly authorized the 31 historical source-disposition recommendations and gave release authorization on 2026-11-05. Both are recorded as **OWNER_APPROVED_OUT_OF_BAND** for review traceability. No independently verified signature or GitHub review submission is implied. Release authorization does **not** represent executed CI, acceptance of the 12 distinct destination class replacements, independent security/DBA/QA signoff, or permission to skip release gates.

**Approved scope:** 31 historical dispositions (six master conflicts, fifteen short-branch conflicts, nine short-only files, and one auto-merge semantic test), under their previously documented *proposed resolutions*. Do not import legacy unsafe APIs verbatim; preserve the original source/history and do not eliminate security safeguards. Distinct historical conflict records can reference the same file.

**Pending:** Canonical Option A ADR formal review/signoff, **12 destination-class replacement decisions**, all mandatory release verification gates, reviewer signoffs, and actual integration PR.

## 31 individually identified OWNER-APPROVED source decisions

| ID | Historical path | Owner authorization | Independent review |
| --- | --- | --- | --- |
| A-01 | `src/test/java/com/revature/service/UserLoginServiceCharacterizationTest.java` | Approved 2026-11-05 | Pending |
| M-01 | `.github/workflows/maven-ci.yml` | Approved 2026-11-05 | Pending |
| M-02 | `src/test/java/com/revature/DAOService/DAOServiceCharacterizationTest.java` | Approved 2026-11-05 | Pending |
| M-03 | `src/test/java/com/revature/cardealer/CarDealershipCharacterizationTest.java` | Approved 2026-11-05 | Pending |
| M-04 | `src/test/java/com/revature/cardealer/OfferCharacterizationTest.java` | Approved 2026-11-05 | Pending |
| M-05 | `src/test/java/com/revature/cardealer/PaymentsCharacterizationTest.java` | Approved 2026-11-05 | Pending |
| M-06 | `src/test/java/com/revature/service/RoleLoginServiceCharacterizationTest.java` | Approved 2026-11-05 | Pending |
| S-01 | `.github/workflows/maven-ci.yml` | Approved 2026-11-05 | Pending |
| S-02 | `src/com/revature/cardealer/CarDealership.java` | Approved 2026-11-05 | Pending |
| S-03 | `src/com/revature/cardealer/Offer.java` | Approved 2026-11-05 | Pending |
| S-04 | `src/com/revature/cardealer/OwnedVehicle.java` | Approved 2026-11-05 | Pending |
| S-05 | `src/com/revature/cardealer/PaymentPlan.java` | Approved 2026-11-05 | Pending |
| S-06 | `src/com/revature/service/AdminLoginService.java` | Approved 2026-11-05 | Pending |
| S-07 | `src/com/revature/service/CustomerLoginService.java` | Approved 2026-11-05 | Pending |
| S-08 | `src/com/revature/service/EmployeeLoginService.java` | Approved 2026-11-05 | Pending |
| S-09 | `src/com/revature/service/InMemoryUserAccountRepository.java` | Approved 2026-11-05 | Pending |
| S-10 | `src/com/revature/service/UserLoginService.java` | Approved 2026-11-05 | Pending |
| S-11 | `src/test/java/com/revature/cardealer/CarDealershipCharacterizationTest.java` | Approved 2026-11-05 | Pending |
| S-12 | `src/test/java/com/revature/cardealer/OfferCharacterizationTest.java` | Approved 2026-11-05 | Pending |
| S-13 | `src/test/java/com/revature/service/RoleAuthorizationServiceTest.java` | Approved 2026-11-05 | Pending |
| S-14 | `src/test/java/com/revature/service/RoleLoginServiceCharacterizationTest.java` | Approved 2026-11-05 | Pending |
| S-15 | `src/test/java/com/revature/service/UserLoginServiceCharacterizationTest.java` | Approved 2026-11-05 | Pending |
| SO-01 | `docs/ADMIN-BOOTSTRAP-THREAT-MODEL.md` | Approved 2026-11-05 | Pending |
| SO-02 | `docs/PURCHASE-INTEGRITY-REVIEW.md` | Approved 2026-11-05 | Pending |
| SO-03 | `src/com/revature/service/AuthorizedInventoryService.java` | Approved 2026-11-05 | Pending |
| SO-04 | `src/com/revature/service/AuthorizedPurchaseReviewService.java` | Approved 2026-11-05 | Pending |
| SO-05 | `src/com/revature/service/LegacyOfferPurchaseInventoryAdapter.java` | Approved 2026-11-05 | Pending |
| SO-06 | `src/com/revature/service/PurchaseInventoryPort.java` | Approved 2026-11-05 | Pending |
| SO-07 | `src/test/java/com/revature/cardealer/CarDealershipRoleRevalidationTest.java` | Approved 2026-11-05 | Pending |
| SO-08 | `src/test/java/com/revature/service/AuthorizedInventoryServiceTest.java` | Approved 2026-11-05 | Pending |
| SO-09 | `src/test/java/com/revature/service/AuthorizedPurchaseReviewServiceTest.java` | Approved 2026-11-05 | Pending |

For SO-01 to SO-09, the approved proposal is **historical preservation and behavior mapping**, not unconditional copying of short-only legacy code. For M/S conflicts, the approved proposal favors the frozen, security-hardened modernization source; the final composite workflow and tests must still be independently validated. For A-01, preserve uppercase SECURITY tags and fail-closed account deletion.

Primary historical evidence: [exact conflicts](https://github.com/danwiz/danwiz/blob/audit/portfolio-remediation-plan-20261029/docs/audits/CAR-DEALERSHIP-CONFIRMED-MERGE-TREE-REHEARSAL-2026-10-30.md), [nine short-only dispositions and tag drift](https://github.com/danwiz/danwiz/blob/audit/portfolio-remediation-plan-20261029/docs/audits/CAR-DEALERSHIP-SHORT-ONLY-SOURCE-DISPOSITION-AND-LOGIN-TAG-REVIEW-2026-10-30.md), [Option A draft ADR](https://github.com/danwiz/danwiz/blob/audit/portfolio-remediation-plan-20261029/docs/audits/ADR-CAR-DEALERSHIP-CANONICAL-REPOSITORY-2026-10-29.md).

## Twelve separate Maven destination decisions — NOT yet approved

| ID | Existing destination path | Status |
| --- | --- | --- |
| C01 | `src/main/java/com/revature/DAOService/DAOService.java` | OWNER/ARCHITECT DECISION PENDING |
| C02 | `src/main/java/com/revature/DAOService/DataDAO.java` | OWNER/ARCHITECT DECISION PENDING |
| C03 | `src/main/java/com/revature/cardealer/Car.java` | OWNER/ARCHITECT DECISION PENDING |
| C04 | `src/main/java/com/revature/cardealer/CarDealership.java` | OWNER/ARCHITECT DECISION PENDING |
| C05 | `src/main/java/com/revature/cardealer/Data.java` | OWNER/ARCHITECT DECISION PENDING |
| C06 | `src/main/java/com/revature/cardealer/Offer.java` | OWNER/ARCHITECT DECISION PENDING |
| C07 | `src/main/java/com/revature/cardealer/Payments.java` | OWNER/ARCHITECT DECISION PENDING |
| C08 | `src/main/java/com/revature/cardealer/User.java` | OWNER/ARCHITECT DECISION PENDING |
| C09 | `src/main/java/com/revature/service/AdminLoginService.java` | OWNER/ARCHITECT DECISION PENDING |
| C10 | `src/main/java/com/revature/service/CustomerLoginService.java` | OWNER/ARCHITECT DECISION PENDING |
| C11 | `src/main/java/com/revature/service/EmployeeLoginService.java` | OWNER/ARCHITECT DECISION PENDING |
| C12 | `src/main/java/com/revature/service/UserLoginService.java` | OWNER/ARCHITECT DECISION PENDING |

For each collision, compare existing Maven implementation with the frozen source, decide preserve/replace/refactor/retire, confirm behavioral and serial-data compatibility, document rollback, and obtain review before modifying destination production code.

## Mandatory release acceptance checklist

| Gate | Evidence to obtain | Cleared |
| --- | --- | --- |
| S22-G01 | Canonical Option A ADR: owner direction recorded; formal ADR review/signoff outstanding | **No — gate still open** |
| S22-G02 | 31 source/ancestry dispositions: owner authorization recorded; independent technical adjudication outstanding | **No — gate still open** |
| S22-G03 | 12 Maven destination class replacements: owner/architect decisions outstanding | **No — gate still open** |
| S22-G04 | PR #106/#107 combined workflow and test inventory: final SHA and CI review outstanding | **No — gate still open** |
| S22-G05 | JUnit test preservation: prove full 352-method roster without unapproved regression | **No — gate still open** |
| S22-G06 | Official Java 11 and Java 17 Maven/JUnit 5 runs on exact integrated commit | **No — gate still open** |
| S22-G07 | PostgreSQL 16 migrations, least privilege, transaction and authorization suites | **No — gate still open** |
| S22-G08 | Full SECURITY + lowercase security tag roster with negative tests and no skips | **No — gate still open** |
| S22-G09 | Auth/session revocation and safe snapshot load/restore validation | **No — gate still open** |
| S22-G10 | Production credential/first-admin provisioning and trust boundaries | **No — gate still open** |
| S22-G11 | Production failover, real COMMIT ACK-loss, idempotency and reconciliation | **No — gate still open** |
| S22-G12 | Dependency SCA, SBOM, licensing, provenance and secrets audit | **No — gate still open** |
| S22-G13 | Exact-final-commit signed evidence plus independent AppSec/DBA/QA approvals | **No — gate still open** |
| S22-G14 | Owner release authorization recorded; release publication still contingent on all other gates | **No — gate still open** |

**Test baseline, not release evidence:** the historical frozen source reported 346 tests/JDK; PR #106 336 tests/JDK; PR #107 334/JDK. The local additive proposal contains 352 method annotations / 123 security-selected tests; these have **not** been executed in official CI against the combined destination candidate. Historical test logs do not certify the new source tree.

## Controlled next actions

1. Collect independent review and owner/architect dispositions for C01–C12 and formalize Option A ADR.
2. Reconcile Maven PR #12 (Surefire) and PR #106/#107 content without merging their source PR stacks.
3. Prepare a **separate properly parented code-integration branch** from the verified current Maven master SHA.
4. Open a **separate draft code-integration PR** only after candidate source, destination collisions, provenance, and CI workflow are reviewed.
5. Run immutable-commit Java11/17, PostgreSQL16, full/security JUnit, SCA/SBOM, negative authorization, failure/recovery, and independent-review gates.
6. Do not merge, tag, deploy or announce production readiness until evidence exists for all applicable gates.

**This governance PR itself must remain draft and must not be merged automatically.** It changes no application source, tests, deployed infrastructure, GitHub policy, secrets or release metadata.
