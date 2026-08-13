# SpotYourSlot — Repository Bootstrap

Status: In Progress
GitHub issue: #2 — Bootstrap SpotYourSlot application

## Task purpose

Create the initial buildable and testable application skeleton for SpotYourSlot.

This task covers repository bootstrap only. Do not implement database entities, authentication, authorization, tenant isolation, business workflows, booking functionality, or user interfaces beyond minimal application shells.

## Required context

Before making changes, read completely:

* `AGENTS.md`
* `README.md`
* `docs/product-spec.md`
* `docs/architecture.md`
* `docs/data-model.md`
* `docs/security.md`
* `docs/testing-strategy.md`
* `docs/implementation-plan.md`
* this task file

Treat the approved permanent documentation as authoritative.

If this task conflicts with the permanent documentation, stop and report the conflict instead of silently choosing an interpretation.

## Version policy

Use Java 25 LTS.

Before generating the applications, verify stable versions using official primary documentation.

Select:

* the latest stable GA Spring Boot version that officially supports Java 25;
* a stable Maven version compatible with the selected Spring Boot version;
* the latest suitable Node.js LTS line;
* mutually compatible stable versions of React, TypeScript and Vite;
* the latest stable PostgreSQL major version suitable for local Docker development and compatible with the documented production direction.

Do not use alpha, beta, milestone, release-candidate, snapshot, preview or experimental dependencies.

Pin direct dependency and tool versions where appropriate.

Use non-floating Docker image tags. Do not use `latest`.

Commit generated lockfiles to the repository.

Document the exact selected versions, the compatibility basis and the date on which they were verified.

The developer’s currently installed tools are:

* Temurin Java `25.0.4`;
* Maven `3.9.16`;
* Node.js `24.19.0`;
* npm `11.17.0`;
* Docker `29.7.2`;
* Docker Compose `5.3.1`.

Do not require unnecessary upgrades when these versions satisfy the verified compatibility requirements.

## Repository structure

Create the planned monorepo structure:

```text
spot-your-slot/
├── backend/
├── frontend/
├── docs/
├── .github/
│   └── workflows/
├── compose.yaml
├── .gitignore
├── AGENTS.md
└── README.md
```

Preserve all existing documentation.

Do not rename or delete existing files unless required by this task and clearly justified.

## Backend bootstrap

Create a Java 25 Spring Boot Maven application under `backend/`.

Use:

* Maven;
* package root `bg.spotyourslot`;
* a modular-monolith-friendly package structure;
* Spring Boot Actuator;
* Spring Web;
* Bean Validation;
* Spring Security;
* Spring Data JPA;
* PostgreSQL driver;
* Flyway;
* test dependencies appropriate for Spring Boot;
* Testcontainers for future PostgreSQL integration tests if compatible with the selected stack.

Add only dependencies justified by the approved documentation or by the needs of this bootstrap task.

Do not implement domain entities or database migrations from future phases.

Configure:

* development, test and production Spring profiles;
* environment-variable-based configuration;
* PostgreSQL connectivity for local development;
* a deterministic test configuration;
* a health endpoint through Actuator;
* safe default logging;
* no committed credentials;
* no real email sending.

The backend application must start locally with the documented development setup.

Spring Security may use a minimal temporary bootstrap configuration only as needed for the health check and empty application shell. Clearly mark temporary security behavior and do not present it as the final authentication or authorization implementation.

Use the Maven Wrapper and pin its distribution version.

## Frontend bootstrap

Create a React and TypeScript application using Vite under `frontend/`.

Configure:

* npm;
* committed `package-lock.json`;
* strict TypeScript;
* linting;
* formatting if justified and consistently configured;
* unit-test support with a stable compatible testing stack;
* separate environment configuration without committed secrets;
* a minimal Bulgarian application shell;
* a simple health/status integration approach or documented placeholder.

Do not implement product screens, booking flows, authentication screens or business-specific UI in this phase.

Avoid unnecessary UI frameworks and calendar libraries. Their selection remains unresolved and requires later approval.

The frontend must build and its automated checks must pass.

## Local PostgreSQL and Docker Compose

Create a root `compose.yaml` for local development.

It must:

* use a pinned PostgreSQL image tag;
* create the local database `spotyourslot`;
* obtain credentials from environment variables;
* provide safe documented local-development defaults through an untracked `.env` file or equivalent approach;
* include a committed `.env.example` without real secrets;
* persist PostgreSQL data in a named Docker volume;
* include a database health check;
* avoid exposing unnecessary services;
* avoid cloud-specific configuration.

Do not add Redis, Kafka, Kubernetes or other excluded infrastructure.

Do not configure production hosting.

## Email behavior

Provide only a non-sending local/test email abstraction or documented placeholder if the application skeleton requires it.

Do not connect to Resend or any external email provider.

Do not request API keys or production credentials.

## Quality and checks

Add commands and configuration so that the following can be run deterministically:

Backend:

```bash
./mvnw test
./mvnw verify
```

Frontend:

```bash
npm ci
npm run lint
npm run test
npm run build
```

Use non-interactive frontend test execution suitable for CI.

Add a minimal automated backend context/health test and minimal frontend smoke test.

Do not weaken or remove valid tests merely to make checks pass.

## Continuous integration

Create a minimal GitHub Actions workflow that:

* runs on pushes and pull requests;
* tests and verifies the backend;
* installs frontend dependencies with `npm ci`;
* runs frontend linting, tests and build;
* uses pinned major or immutable action versions according to a documented dependency policy;
* uses Java 25;
* does not deploy;
* does not require production secrets;
* does not create external resources.

Keep CI understandable and proportionate to the MVP.

## Documentation updates

Update `README.md` with exact instructions for:

* prerequisites;
* selected versions;
* local environment setup;
* starting PostgreSQL;
* starting the backend;
* starting the frontend;
* running all checks;
* stopping local services;
* troubleshooting common setup problems.

Document which ports are used.

Add or update an appropriate permanent technical document with:

* exact selected technology versions;
* verification date;
* official compatibility sources;
* short reasons for each selection;
* version-upgrade policy.

Do not copy large sections of the task prompt into permanent documentation.

## Security requirements

* Never commit credentials, tokens or private keys.
* Do not place real secrets in example files.
* Do not log secrets or database passwords.
* Use environment variables for configurable sensitive values.
* Do not enable permissive production CORS.
* Do not claim that the temporary application shell provides final security.
* Do not implement future authentication or authorization prematurely.
* Do not contact external services at runtime.
* Do not configure production resources.

## Explicit exclusions

Do not implement:

* database domain schema or Flyway business migrations;
* users, memberships or sessions;
* login, logout, invitations or password reset;
* tenant context or tenant authorization;
* platform-admin functionality;
* businesses, services or staff members;
* schedules or availability;
* customers or appointments;
* public booking;
* cancellation;
* notifications or reminders;
* production email;
* application deployment;
* hosting configuration;
* DNS or domains;
* analytics;
* payments;
* any feature belonging to a later phase.

## Acceptance criteria

This task is complete only when:

1. `backend/` contains a Java 25 Spring Boot Maven application using `bg.spotyourslot`.
2. `frontend/` contains a strict React/TypeScript/Vite application.
3. Exact stable compatible versions are selected, pinned where appropriate and documented.
4. Local PostgreSQL runs through the root Compose configuration.
5. No real credentials are committed.
6. The backend starts and exposes a working health endpoint.
7. The frontend starts and displays the minimal Bulgarian application shell.
8. Backend tests and verification pass.
9. Frontend clean install, lint, tests and production build pass.
10. GitHub Actions validates backend and frontend without deployment.
11. README local setup instructions are exact and reproducible.
12. No functionality from Phase 2 or later is implemented.
13. `git diff --check` passes.
14. The repository contains no generated build output or dependency directories that should be ignored.
15. No commit or push has been performed.

## Completion procedure

After implementation:

1. Review every changed file.
2. Run all relevant backend and frontend checks.
3. Run `git diff --check`.
4. Run `git status --short`.
5. Confirm that no secrets or generated build output are tracked.
6. Report every created and modified file.
7. Report the exact versions selected and why.
8. Report every command executed for verification and its result.
9. List assumptions, temporary bootstrap decisions and unresolved questions.
10. Confirm that no later-phase functionality was implemented.
11. Do not commit or push.
12. Stop and wait for human review.
