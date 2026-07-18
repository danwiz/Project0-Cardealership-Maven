# Car Dealership Console Application

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

- Java
- Maven
- Object-oriented domain and service classes
- Local file serialization
- Log4j API

## Repository layout

```text
src/main/java/        Application source
src/main/resources/   Runtime resources
src/test/java/        Automated tests
pom.xml               Maven project configuration
```

## Build status

The repository is undergoing modernization. The existing Maven configuration targets an older Java generation and includes outdated dependencies. Build and runtime instructions will be finalized after the source audit and compatibility upgrade are complete.

## Modernization roadmap

- Upgrade the Maven compiler and dependency configuration
- Select a supported Java LTS version
- Remove IDE-generated and local-only files
- Add a safe `.gitignore`
- Refactor console, service, persistence, and domain concerns
- Replace broad exception printing with structured error handling
- Add unit tests for core domain and service behavior
- Add GitHub Actions build and test validation
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
