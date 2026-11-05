# Car Dealership — Modernization Integration Review

> **Draft code migration. Not production-ready, not a release.** This branch presents a controlled port of the preserved Java training project into the Maven portfolio target. The original repository and historical Git objects remain preserved.

## Project overview

Java console dealership training application with vehicle inventory, customer/staff accounts, purchase requests, reviewer authorization, payment/ownership workflows, persistence and characterization tests. The modernization introduces typed-domain service ports, JDBC/H2/PostgreSQL boundaries, verified sessions, safer credential handling, and transactional purchase designs. These are engineering demonstrations and are **not approved for real customer, payment or financial data**.

## Status and review

The maintainer approved the 31 historical source dispositions and 12 destination-class handling proposals on 2026-11-05. Independent architecture, AppSec and QA review and 14 acceptance/release controls remain open. The separate [governance PR #13](https://github.com/danwiz/Project0-Cardealership-Maven/pull/13) records those approvals. **No merge, deployment or release has been completed.**

Read [Stage 28 integration review](docs/integration/STAGE28_CODE_INTEGRATION_REVIEW_2026-11-05.md) for source commit hashes, provenance, scope and gates.

## Development environment

- Java 11 or Java 17, Maven with supported JUnit 5/Jupiter and Surefire 3.6.0.
- Java 11 bytecode compilation target; Maven project name `cardealer`.
- H2 and PostgreSQL JDBC, with disposable PostgreSQL 16 required for specific security, credential and transaction tests.

For an isolated test-only environment:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

Database integration tests require the documented `DEALERSHIP_PG_TEST_URL`, `DEALERSHIP_PG_TEST_USER`, and `DEALERSHIP_PG_TEST_PASSWORD` **synthetic, local-only** CI variables and a PostgreSQL 16 instance. Do not use live credentials. The pull-request CI runs a disposable PostgreSQL 16 service and attempts full plus security-selected JUnit suites on Java 11/17.

Do not assume green compile or isolated smoke tests imply application production security. Follow the Stage 28 review register and inspect CI evidence tied to the exact PR commit.

## Origin and historical preservation

This is a modernization of an academic Java training application. Original source/attribution, file-serialization behavior, compiled historical artifacts, and original build conventions are retained in Git history and the separately preserved source archive. No rewriting or deletion of original Git history was authorized.
