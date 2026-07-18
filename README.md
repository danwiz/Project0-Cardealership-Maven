# Car Dealership Console Application

[![Maven CI](https://github.com/danwiz/Project0-Cardealership-Maven/actions/workflows/maven.yml/badge.svg)](https://github.com/danwiz/Project0-Cardealership-Maven/actions/workflows/maven.yml)

A legacy Java console application that models customer and employee workflows for a small car dealership. The project includes user registration and authentication, vehicle inventory, purchase offers, payment handling, and file-based persistence.

## Project status

This repository is the authoritative modernization target for the original dealership project. It preserves the initial training implementation while the codebase is being upgraded for reproducible builds, automated testing, continuous integration, and professional portfolio presentation.

The application is not yet considered production-ready or pin-ready.

## Current capabilities

- Customer and employee registration and login flows
- Vehicle inventory and dealership offer management
- Customer purchase requests
- Employee offer review and approval workflows
- Payment-related domain behavior
- Java object serialization for local persistence
- Console-based navigation and interaction

## Technology

- Java 17
- Maven
- Object-oriented domain and service classes
- Local file serialization
- Log4j API 2.26.1
- JUnit 4.13.2
- GitHub Actions
- Dependabot

## Repository layout

```text
src/main/java/        Application source
src/main/resources/   Runtime resources
src/test/java/        Automated tests
pom.xml               Maven project configuration
```

## Build and test

Requirements:

- JDK 17
- Maven 3.9 or later

Run the full validation lifecycle:

```bash
mvn --batch-mode --no-transfer-progress verify
```

The GitHub Actions workflow runs Maven validation and verification on pushes and pull requests to `master`. The workflow can also be started manually from the Actions tab.

## Dependency maintenance

Dependabot checks Maven dependencies and GitHub Actions weekly. Automated update pull requests must still pass CI and receive human review before merge.

## Modernization roadmap

- Confirm the first clean Java 17 Maven build
- Remove remaining IDE-generated and local-only files
- Refactor console, service, persistence, and domain concerns
- Replace broad exception printing with structured error handling
- Add unit tests for core domain and service behavior
- Add sample terminal sessions and architecture documentation
- Review authentication and data handling for security risks

## Known limitations

- The current implementation is a legacy training application.
- Persistence is based on local Java serialization rather than a database.
- Input validation and error handling require improvement.
- Some menu and workflow logic is tightly coupled to console input/output.
- The project has not yet completed a clean-build and security review.

## Portfolio gate

This repository should be pinned only after it has:

- a reproducible Maven build;
- passing automated tests and CI;
- a supported Java version;
- clear setup and execution instructions;
- no credentials, private data, generated binaries, or local configuration;
- documented architecture, limitations, and authorship.

## Origin and authorship

This project originated as a Java training assignment and is maintained here as a modernization and software-engineering portfolio exercise by Dane Wisdom.
