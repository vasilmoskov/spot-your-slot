# ADR-0009: Test persistence and concurrency against real PostgreSQL

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-08-12
- **Recorded date:** 2026-08-25
- **Related issues:** #1, #2, #3, #4, #5, #6, #8
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

SpotYourSlot relies on PostgreSQL behavior for schema constraints, indexes,
transactions, conditional updates and row locks. A mock can show that
application code requested an operation, and another relational engine can
exercise general SQL concepts, but neither establishes how the project's SQL
and concurrency rules behave on PostgreSQL.

The test strategy therefore needs a deliberate boundary: keep
database-independent behavior fast and isolated, while verifying persistence
and concurrency claims against the actual selected database engine in a clean,
reproducible environment.

## Evidenced constraints

Repository evidence establishes that:

- PostgreSQL is the transactional system of record;
- Flyway owns schema creation and evolution;
- database constraints and application logic jointly enforce important
  invariants;
- tests involving persistence, tenant isolation or concurrency must use real
  PostgreSQL when database behavior matters;
- concurrent operations must be coordinated deterministically through separate
  transactions or connections rather than arbitrary sleeps; and
- the test suite remains layered, with unit, web, architecture, integration and
  frontend tests serving different purposes.

ADR-0002 owns the PostgreSQL selection, ADR-0004 owns immutable migration
history, ADR-0006 owns security-token lifecycle guarantees, and ADR-0007 owns
Business optimistic concurrency. This record owns only database-test fidelity.
Production deployment, load testing, backup and restore, high availability and
disaster recovery remain outside its scope.

## Options considered

Real PostgreSQL Testcontainers is the only approach directly documented as
selected for PostgreSQL-dependent behavior. The other options below are a
retrospective assessment, not evidence that they were debated on 2026-08-12.

### Mock repositories and persistence boundaries only — retrospective assessment

Mocks make application tests fast, focused and easy to drive through rare
responses. They are valuable for validating orchestration, call order and error
translation. They cannot execute SQL or prove database constraints, transaction
boundaries, locking, isolation or driver behavior.

### In-memory relational substitute such as H2 — retrospective assessment

An in-process database can start quickly, require no container runtime and
provide useful feedback for portable relational behavior. Dialect, type,
constraint, index, locking and transaction differences can let
PostgreSQL-specific defects pass or reject valid PostgreSQL SQL. It is not
treated as equivalent evidence for behavior owned by PostgreSQL.

### Shared manually managed PostgreSQL test database — retrospective assessment

A persistent shared database can amortize startup cost, allow manual inspection
and closely match a chosen PostgreSQL version. It also introduces external
credentials, schema drift, stale data, cleanup dependencies and interference
between developers or concurrent test runs.

### Containerized PostgreSQL managed by the test suite

Testcontainers provisions the pinned PostgreSQL engine on demand and gives the
Spring test context isolated connection details. This exercises the real SQL
dialect and server behavior without a manually maintained shared database. It
requires an available compatible container runtime and incurs image-download,
startup and resource costs.

### Dedicated production-like environment — retrospective assessment

A controlled environment can validate deployed networking, managed-database
configuration and broader operational integration. It is slower, costlier,
stateful and harder to isolate, and it risks coupling routine tests to external
availability. It remains useful for separately approved staging, migration,
load or operational verification, not as the routine persistence-test
foundation.

## Decision

Use real PostgreSQL, provisioned through Testcontainers, for automated tests
whose claims depend on PostgreSQL persistence or concurrency semantics. Run
Flyway through the Spring Boot test context against a disposable database and
keep Hibernate in schema-validation mode.

Keep database-independent domain rules, validation and orchestration decisions
in fast unit tests. Use mocked collaborators and focused MVC tests where the
claim concerns application interaction or HTTP mapping rather than database
behavior. Do not substitute either mocks or an in-memory database for evidence
about actual SQL, constraints, indexes, PostgreSQL types, locks, transactions or
concurrent writes.

## Test boundaries and execution model

The shared `PostgresIntegrationTest` base declares a static JUnit-managed
`PostgreSQLContainer` using `postgres:18.4-bookworm`. Dynamic properties provide
its JDBC URL, username and password to each Spring test context. The repository
does not enable Testcontainers reuse, so tests do not intentionally depend on a
persistent reused container. `@DirtiesContext` closes each concrete integration
test class's Spring context afterward.

Spring Boot starts Flyway for these contexts. The test profile sets
`ddl-auto: validate`, so Hibernate does not replace migration ownership.
`BusinessSchemaIntegrationTests` directly verifies that an empty disposable
database records successful Flyway versions 1, 2 and 3. Schema tests inspect
metadata and exercise PostgreSQL constraints rather than merely checking Java
models.

Current PostgreSQL-backed test classes include the application context smoke
test; identity and Business schema tests; identity lifecycle and authentication
API tests; Business store and application-service integration tests; the
active-owner locking test; platform orchestration tests; platform Business API
tests; and production-profile configuration tests. They exercise current
features including UUID and `timestamptz` storage, check constraints, PostgreSQL
regular expressions, partial unique indexes, unique-key conflicts, conditional
version updates, transactions, `FOR SHARE` locking and safe API behavior backed
by persisted state.

Concurrency tests use executor threads and coordinated latches. Business store
and application-service races run the competing operations in separate
`TransactionTemplate` transactions and assert one same-version mutation wins.
Identity token issuance and consumption races coordinate two concurrent service
calls and assert the database-backed single-generation or single-use outcome.
The active-owner test records distinct PostgreSQL backend PIDs and queries
`pg_stat_activity` to prove that a Membership update waits while another
transaction holds the qualifying row with `FOR SHARE`. These techniques are
specific evidence for the tested races, not proof against every anomaly.

Pure domain and validation tests remain ordinary JUnit tests. Application unit
tests use mocks for call order and translations. Focused MVC tests validate DTOs
and exception mapping without claiming persistence fidelity. Spring Modulith
tests verify structural module rules rather than database behavior. Frontend
component tests remain outside the backend database boundary.

The GitHub Actions backend job runs `./mvnw --batch-mode verify`; no test
exclusion or separate database service is configured, so the standard Maven
test phase invokes the Testcontainers-backed integration classes. Local and CI
execution therefore require a usable container runtime. Docker availability is
an execution prerequisite, not a property guaranteed by the test design.

## Rationale

Using the selected database engine closes a fidelity gap at the boundary where
correctness depends on that engine. It verifies that migrations actually apply,
that constraints reject invalid persisted states, and that transaction and lock
behavior matches the SQL the application ships.

Disposable container provisioning reduces dependence on developer-managed
state and makes the PostgreSQL version explicit. At the same time, retaining
unit and mocked tests keeps feedback focused and avoids paying container startup
cost for behavior that does not depend on the database.

## Tradeoffs and disadvantages

- A container runtime, sufficient resources and the PostgreSQL image are needed
  locally and in CI.
- Initial image download and container/context startup make integration tests
  slower than unit tests.
- Container failures can obscure whether a problem is application logic,
  environment setup or container infrastructure.
- A disposable database makes post-failure inspection less convenient than a
  persistent shared database unless diagnostics are captured deliberately.
- The tests use one pinned PostgreSQL image but do not reproduce a future
  managed provider's extensions, configuration, topology or operational limits.
- More realistic concurrency tests require careful coordination, timeouts and
  cleanup; real PostgreSQL alone does not make them deterministic.

## Risks and mitigations

Developers may move all tests into the integration layer because the real
database appears more realistic. Keep database-independent rules in unit tests
and require each integration test to identify the database behavior it proves.

Tests may accidentally pass because fixtures share a transaction or connection,
so no genuine race occurs. Concurrent database claims must use separate
transactions or backend connections, explicit coordination and bounded waits;
where relevant, inspect database lock metadata or assert a decisive persisted
outcome.

Version drift can reduce fidelity. Keep the test image pinned and update it
through the controlled version policy. Matching the current local image still
does not prove parity with an unselected production provider.

An unavailable Docker daemon can block the suite. Document the prerequisite and
report the environment failure rather than replacing PostgreSQL silently with a
different engine or weakening the affected assertions.

## Consequences

New migrations need a clean-database application test and focused checks for
their constraints or metadata. New repository operations need PostgreSQL
integration coverage when their correctness depends on SQL or persisted state.
New concurrency claims need coordinated competing transactions and an assertion
that distinguishes the permitted outcome from a lost update, duplicate use or
lock violation.

Unit, web, architecture and frontend tests remain necessary. Testcontainers
does not validate browser journeys, structural module boundaries, production
deployment, scale, failover, storage durability, backup restoration, network
policy or performance. Those concerns require their own tests and operational
evidence.

## Direct historical evidence

- [`docs/testing-strategy.md`](../testing-strategy.md) has required real
  PostgreSQL for database semantics and a layered test strategy since the
  foundation commit `9cf10e0` on 2026-08-12 for issue #1.
- [`docs/tasks/00-product-foundation.md`](../tasks/00-product-foundation.md)
  explicitly requires JUnit, Spring integration tests and real PostgreSQL
  Testcontainers for database-sensitive and concurrent behavior.
- [`docs/tasks/01-repository-bootstrap.md`](../tasks/01-repository-bootstrap.md)
  approved the Testcontainers dependency for future PostgreSQL integration
  tests. [`backend/pom.xml`](../../backend/pom.xml) contains the PostgreSQL,
  Flyway and Testcontainers dependencies selected during issue #2.
- [`PostgresIntegrationTest.java`](../../backend/src/test/java/bg/spotyourslot/integration/PostgresIntegrationTest.java)
  defines the PostgreSQL 18.4 container, dynamic datasource properties, test
  profile and class-level context cleanup introduced in issue #3.
- [`application-test.yaml`](../../backend/src/test/resources/application-test.yaml)
  keeps Hibernate in validation mode for integration tests.
- [`V1__identity_and_tenancy.sql`](../../backend/src/main/resources/db/migration/V1__identity_and_tenancy.sql),
  [`V2__enforce_single_active_identity_tokens.sql`](../../backend/src/main/resources/db/migration/V2__enforce_single_active_identity_tokens.sql),
  and [`V3__add_business_profile_fields.sql`](../../backend/src/main/resources/db/migration/V3__add_business_profile_fields.sql)
  are the migration resources applied by the current test context.
- [`IdentitySchemaIntegrationTests.java`](../../backend/src/test/java/bg/spotyourslot/integration/IdentitySchemaIntegrationTests.java)
  and [`BusinessSchemaIntegrationTests.java`](../../backend/src/test/java/bg/spotyourslot/integration/BusinessSchemaIntegrationTests.java)
  exercise migrated schema metadata, constraints, prior-migration checksums and
  the recorded Flyway history.
- [`IdentityLifecycleIntegrationTests.java`](../../backend/src/test/java/bg/spotyourslot/integration/IdentityLifecycleIntegrationTests.java)
  coordinates invitation and reset issuance and consumption races.
- [`BusinessStoreIntegrationTests.java`](../../backend/src/test/java/bg/spotyourslot/business/infrastructure/BusinessStoreIntegrationTests.java)
  and [`BusinessAdministrationServiceIntegrationTests.java`](../../backend/src/test/java/bg/spotyourslot/business/application/BusinessAdministrationServiceIntegrationTests.java)
  exercise persistence behavior and coordinated same-version mutations.
- [`ActiveBusinessOwnerQueryIntegrationTests.java`](../../backend/src/test/java/bg/spotyourslot/identity/application/ActiveBusinessOwnerQueryIntegrationTests.java)
  verifies PostgreSQL row-lock waiting through distinct backend connections and
  server activity metadata.
- [`PlatformBusinessApiIntegrationTests.java`](../../backend/src/test/java/bg/spotyourslot/integration/PlatformBusinessApiIntegrationTests.java)
  verifies the platform HTTP and security boundary against persisted data.
- [`ModuleBoundaryTests.java`](../../backend/src/test/java/bg/spotyourslot/architecture/ModuleBoundaryTests.java)
  and [`BusinessAdministrationServiceTests.java`](../../backend/src/test/java/bg/spotyourslot/business/application/BusinessAdministrationServiceTests.java)
  exemplify architecture and mocked unit-test responsibilities that remain
  outside database-fidelity claims.
- [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) runs the complete
  backend Maven verification on pushes and pull requests. It does not configure
  a manually managed PostgreSQL service.
- Commit `42ec1d0` added the PostgreSQL/Testcontainers dependencies and CI on
  2026-08-13 for issue #2. Commit `ed27ee0` introduced the reusable PostgreSQL
  integration foundation and initial schema, lifecycle, API and concurrency
  coverage on 2026-08-14 for issue #3. Issue #5 subsequently added Business
  persistence, version-race and row-lock coverage under parent issue #4; issue
  #6 extended PostgreSQL-backed authentication regressions.

These sources demonstrate the selected test boundary and current coverage. They
do not prove production equivalence, exhaustive anomaly coverage or that every
retrospective alternative was historically considered.

## Retrospective inference

The comparative assessment of repository mocks, H2, a shared database and a
dedicated production-like environment is later analysis. The repository does
not document those choices as an original deliberation.

Container isolation and the balanced test pyramid are reasonable explanations
for the strategy's continued fit. Current passing tests demonstrate only the
scenarios they execute; they do not establish production load, topology,
durability, high availability, backup recovery or complete concurrency safety.

## Conditions for revisiting

Revisit this decision if container startup materially harms feedback time, the
supported development or CI environments cannot provide a reliable container
runtime, production uses PostgreSQL behavior that the pinned image cannot
represent, or operational verification requires a separately managed
production-like environment.

Any revision must preserve explicit fidelity for the SQL, migration,
constraint, transaction and concurrency behavior the application relies on.
Alternative provisioning may replace Testcontainers, but mocks or a different
database engine must not be reported as equivalent evidence without proving the
relevant semantics.
