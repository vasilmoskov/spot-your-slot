# ADR-0002: Use PostgreSQL as the transactional system of record

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-08-12
- **Recorded date:** 2026-08-25
- **Related issues:** #1, #2, #3, #4, #5, #8
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

SpotYourSlot needed an authoritative store for identity, tenancy, Business
configuration, and the foreseeable booking domain. The model contains durable
relationships, uniqueness and lifecycle rules, concurrent administrative and
booking operations, and changes that must succeed or fail atomically.

The product foundation selected one shared PostgreSQL database and stated that
PostgreSQL would be authoritative. This retrospective record explains that
selection without claiming that the repository contains a documented comparison
of every database alternative.

## Evidenced constraints

Repository evidence at the decision date establishes that:

- the system was one multi-tenant product with a relational model spanning
  Businesses, Memberships, users, Services, StaffMembers, Customers, and
  Appointments;
- referenced records needed common-ownership, uniqueness, status, range, and
  lifecycle invariants;
- booking would require transactional availability revalidation and database-
  enforced protection against overlapping Appointments;
- durable security-token and session lifecycles would be persisted rather than
  held only in application memory; and
- one shared database was part of the modular-monolith architecture.

Detailed Business-scoped tenancy, migration immutability, optimistic
concurrency, and database-specific testing are owned by ADR-0003, ADR-0004,
ADR-0007, and ADR-0009 respectively.

## Options considered

PostgreSQL is the only option directly documented as selected in the historical
repository. The other options below are a retrospective alternatives assessment,
not evidence that they were debated on 2026-08-12.

### PostgreSQL

PostgreSQL provides relational modelling, foreign keys, transactions, rich
constraints and indexes, row and advisory locking, and types and extensions
suited to the planned temporal booking model. It fits the selected Spring,
Flyway, Docker, and Testcontainers ecosystem. Its costs include operating an
external database, learning its concurrency semantics, and greater portability
effort when PostgreSQL-specific SQL is used.

### MySQL or MariaDB — retrospective assessment

MySQL and MariaDB are mature relational systems with broad tooling, hosting,
and operational familiarity. They could support much of the core relational
model. Choosing either would require validating or redesigning the planned
PostgreSQL-specific constraints, indexes, range behavior, locking SQL, and
migration assumptions. The repository does not show that this comparison was
made at the original decision date.

### MongoDB — retrospective assessment

A document database can offer flexible schemas and natural persistence for
self-contained aggregates, with established replication and scaling options.
SpotYourSlot's planned model has many durable relationships and cross-record
invariants for which relational foreign keys, constraints, and transactions are
a direct fit. A document design would move or reshape some enforcement and
query responsibilities rather than simply replacing the database engine.

### Embedded or in-memory database — retrospective assessment

An embedded or in-memory database is not selected as the production system of
record. Although some embedded engines support durable and concurrent access,
they would not provide the required production equivalence for the PostgreSQL-
specific constraints, locking behavior, transaction semantics, and operational
model selected by the project. Such databases may still be appropriate for
narrow non-production scenarios where those semantics are irrelevant.

## Decision

Use PostgreSQL as SpotYourSlot's authoritative transactional system of record.
The backend and PostgreSQL jointly enforce business operations: application
services define use-case and authorization rules, while transactions,
constraints, indexes, and appropriate locks protect durable state.

PostgreSQL alone does not prevent every concurrency anomaly. Correctness depends
on the selected transaction boundaries, database constraints and locking,
isolation behavior, and application-level concurrency rules working together.

Flyway manages PostgreSQL schema evolution. The detailed policy for immutable,
forward-only migrations belongs to ADR-0004.

## Rationale

The selected relational model benefits from primary and foreign keys,
referential actions, uniqueness, check constraints, and atomic changes across
related rows. PostgreSQL supports those needs and provides explicit concurrency
tools already used by identity and Business persistence, including row locks,
transaction-scoped advisory locks, partial unique indexes, and atomic mutation
statements.

The foundation also anticipated PostgreSQL range and exclusion-constraint
support for conflict-safe booking. That booking constraint is forward-looking
and is not yet implemented; its detailed design remains subject to its later
phase and tests.

PostgreSQL's operational maturity and ecosystem breadth support this choice,
but that is retrospective analysis rather than a documented original
motivation. Current code and tests show implemented capabilities, not why the
database was first selected.

## Tradeoffs and disadvantages

- The application depends on an external stateful service that requires
  provisioning, credentials, monitoring, backup, recovery, and upgrades.
- PostgreSQL-specific constraints, partial indexes, range features, advisory
  locks, and locking SQL increase correctness and expressiveness while reducing
  database portability.
- One transactional database is a shared availability and capacity dependency
  for the current application.
- Schema and query design require PostgreSQL expertise, especially around
  transactions, locks, indexes, and production performance.
- Scaling writes or changing database technology later may require significant
  data and application redesign.

## Risks and mitigations

Mitigate availability and data-loss risk with reviewed production hosting,
least-privilege access, monitored capacity, supported versions, backups, and
tested recovery before launch. Those operational controls remain future
production-readiness work.

Mitigate correctness risk by expressing suitable invariants in PostgreSQL,
keeping application transaction boundaries explicit, reviewing lock ordering
and isolation assumptions, and mapping database failures safely. Mitigate
portability risk by keeping SQL inside module persistence boundaries and using
PostgreSQL-specific behavior intentionally rather than accidentally.

Avoid treating the database as a substitute for application authorization or
domain rules. Database enforcement is defense in depth and a final arbiter for
the invariants it can represent.

## Consequences

Production persistence uses PostgreSQL-compatible schema and behavior. Modules
may share a PostgreSQL transaction when one atomic operation requires it under
the modular-monolith architecture, while persistence details remain internal to
their owning modules.

Schema changes use Flyway, Hibernate validates rather than creates the schema,
and database-specific capabilities must be documented and verified. The
detailed reasons for forward-only migrations and real-PostgreSQL integration
tests remain in ADR-0004 and ADR-0009.

Future booking work may use PostgreSQL exclusion constraints and temporal range
operations after its own implementation review. This ADR does not mark that
future behavior as complete.

## Direct historical evidence

- The original [data model](../data-model.md) states that PostgreSQL is
  authoritative and defines relational keys, constraints, indexes, temporal
  types, and the planned booking exclusion constraint. Git commit `9cf10e0`
  first recorded this on 2026-08-12 for issue #1.
- The original [architecture](../architecture.md) selects one shared PostgreSQL
  database, describes PostgreSQL as the final booking-conflict arbiter, and
  assigns Flyway schema ownership.
- The [product foundation task](../tasks/00-product-foundation.md) records
  PostgreSQL, Flyway, transactional booking, and database-enforced overlap
  prevention as approved foundations.
- The [implementation plan](../implementation-plan.md) carries PostgreSQL and
  Flyway through bootstrap, identity, tenancy, booking, and production
  readiness.
- The [repository bootstrap task](../tasks/01-repository-bootstrap.md) requires
  a pinned PostgreSQL container, driver, Flyway integration, health check, and
  persistent local storage. Issue #2 implemented those foundations in the
  [Maven build](../../backend/pom.xml), [Compose configuration](../../compose.yaml),
  and [application configuration](../../backend/src/main/resources/application.yaml).
- The [identity and tenancy task](../tasks/02-identity-and-tenancy.md) requires
  relational constraints, transactional security lifecycles, and PostgreSQL
  persistence. Issue #3 introduced
  [V1](../../backend/src/main/resources/db/migration/V1__identity_and_tenancy.sql)
  and
  [V2](../../backend/src/main/resources/db/migration/V2__enforce_single_active_identity_tokens.sql),
  plus the [identity persistence implementation](../../backend/src/main/java/bg/spotyourslot/identity/infrastructure/IdentityStore.java).
- The [platform Business backend task](../tasks/03a-platform-business-backend.md)
  requires PostgreSQL as the final source of truth. Under parent issue #4,
  issue #5 added
  [V3](../../backend/src/main/resources/db/migration/V3__add_business_profile_fields.sql)
  and the [Business persistence implementation](../../backend/src/main/java/bg/spotyourslot/business/infrastructure/BusinessStore.java).
- The [PostgreSQL integration-test foundation](../../backend/src/test/java/bg/spotyourslot/integration/PostgresIntegrationTest.java)
  and current integration suites demonstrate implemented PostgreSQL behavior.
  They do not prove the historical motivation for selecting PostgreSQL.

## Retrospective inference

PostgreSQL's combination of relational integrity, transactional semantics,
concurrency primitives, temporal features, operational maturity, and ecosystem
is a strong fit for the current and foreseeable booking domain. The comparative
fit against MySQL, MariaDB, MongoDB, or embedded databases is later analysis;
the repository does not document an original evaluation of those alternatives.

Using database-enforced invariants reduces the states that concurrent or faulty
application code can persist, but effectiveness depends on choosing correct
constraints and transaction behavior. Current successful tests establish
present implementation evidence only, not the reasoning held on 2026-08-12.

## Conditions for revisiting

Revisit this decision if measured scale, availability, geographic distribution,
regulatory isolation, hosting constraints, or a materially different data model
cannot be met acceptably with PostgreSQL. Also revisit it if a bounded workload
has requirements better served by a specialized store, while retaining a clear
owner for authoritative transactional state.

Any replacement or additional authoritative store requires an explicit data-
ownership model, consistency semantics, migration and rollback plan, operational
readiness, and evidence that its benefits justify added complexity. This is
forward-looking guidance, not a claim that changing systems of record will be
easy.
