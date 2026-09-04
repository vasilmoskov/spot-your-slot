# ADR-0004: Use immutable forward-only Flyway migrations

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-08-12
- **Recorded date:** 2026-08-25
- **Related issues:** #1, #2, #3, #4, #5, #8
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

SpotYourSlot needs database schema changes to produce the same ordered result
when applied to an empty database or an environment that already contains an
earlier schema version. Once a migration becomes accepted shared history,
changing its contents would make the repository's migration history disagree
with databases that already applied it.

The product foundation selected Flyway to own schema evolution and prohibited
editing applied migrations. This record makes that policy and its limits
explicit without claiming that every alternative was historically debated.

## Evidenced constraints

Repository evidence establishes that:

- Flyway owns every application schema change from the first database change;
- Hibernate validates the schema rather than generating production changes;
- migrations must run against PostgreSQL from an empty database;
- later schema work must preserve earlier accepted migrations and add a new
  versioned migration; and
- migration, restore, and rollback verification remain deployment concerns,
  not capabilities supplied by Flyway alone.

ADR-0002 owns PostgreSQL selection, ADR-0003 owns tenant and Membership
modelling, ADR-0007 will own optimistic concurrency, and ADR-0009 will own the
real-PostgreSQL testing strategy.

## Options considered

Immutable Flyway migrations are the only approach directly documented as
selected in the historical repository. The other options below are a
retrospective assessment, not evidence that they were debated on 2026-08-12.

### Rewrite existing migrations as the desired schema changes — retrospective assessment

Rewriting can keep a new installation's scripts compact and make an unfinished
local change easy to refine. After a migration is shared or applied, however,
rewriting it changes its checksum and can make upgraded databases disagree with
databases built from the revised history.

### Maintain one continually updated baseline — retrospective assessment

A single current schema can make fresh database creation easy to understand and
fast to execute. Without durable upgrade steps, it does not describe how an
existing database reaches that schema, obscures chronology, and moves upgrade
risk into manual comparison or tooling outside the baseline.

### Use immutable forward-only versioned migrations

An ordered history makes clean creation and incremental upgrade reproducible,
reviewable, and compatible with Flyway validation. It also accumulates scripts,
may require corrective migrations for mistakes, and demands deliberate
application/schema compatibility planning.

### Perform production changes manually outside Flyway — retrospective assessment

Manual changes can provide direct control during an exceptional incident and
may be faster for an urgent intervention. As a normal delivery mechanism they
create drift, lack a repeatable repository history, and make audit, recovery,
and subsequent automation harder. Any exceptional intervention must be
controlled and reconciled with versioned migration history.

### Use automatic ORM schema generation in production — retrospective assessment

ORM generation reduces setup for simple models and can be convenient in
disposable development environments. It does not provide the reviewed,
ordered deployment history required here and is a poor owner for
PostgreSQL-specific constraints and carefully staged destructive changes. The
application therefore uses Hibernate schema validation instead.

## Decision

Use Flyway versioned migrations as the sole normal mechanism for application
schema evolution. Once a migration is accepted as shared repository history or
applied to a non-disposable environment, its file is immutable. Express fixes
and subsequent schema changes in a new, forward migration.

Forward-only describes the normal evolution of migration history. It does not
mean that application rollback, database recovery, or compensating action is
impossible, nor does it make them equivalent operations.

## Migration lifecycle and permitted changes

A migration still under local development may be corrected before it becomes
accepted shared history when it has been applied only to disposable local or
test databases. Those databases may be rebuilt from empty to verify the revised
sequence. The review must make that status explicit; this exception must not be
used to rewrite a migration already accepted, committed as shared history, or
applied to a persistent environment.

After that boundary:

- preserve the original migration byte-for-byte;
- add a new version for a correction, compensation, or further evolution;
- validate the full ordered history from an empty PostgreSQL database and the
  relevant upgrade path;
- do not use Flyway metadata repair merely to legitimize an edited migration;
  investigate checksum differences and handle any exceptional metadata repair
  as an explicitly approved operational action; and
- retain failed or partially applied migration handling as an operational
  procedure rather than silently changing history.

Rolling back an application deploy leaves the database schema in place and is
safe only when the older application remains compatible with that schema. A
database rollback is a separate recovery or schema operation. It may involve a
new compensating migration or an explicitly planned restore, depending on data
loss and recovery requirements.

Destructive or irreversible changes require explicit planning. Where staged
deployment is needed, prefer an expand-and-contract sequence: add compatible
schema first, deploy code that can tolerate the transition, migrate data as
needed, and remove obsolete schema in a later migration only after it is safe.
This pattern supports safer changes but does not guarantee zero downtime or
backward compatibility automatically.

## Rationale

Immutable shared history lets Flyway compare resolved migrations with the
checksums recorded by databases that already applied them. It also ensures that
a clean database and an incrementally upgraded database follow the same
reviewed sequence.

New corrective migrations preserve what actually happened and make the fix
visible to every environment. Keeping Hibernate in validation mode assigns
schema ownership to one mechanism rather than allowing runtime ORM behavior to
compete with the migration history.

## Tradeoffs and disadvantages

- Migration history grows and may contain intermediate structures that are no
  longer part of the final schema.
- A defect in accepted history needs another migration instead of an invisible
  edit, adding review and operational work.
- Forward corrections can be harder than restoring a fresh database,
  particularly when data has already changed.
- Application versions and schema versions need explicit compatibility
  planning; immutability does not make each migration backward compatible.
- PostgreSQL-specific migration features improve correctness and expressiveness
  while increasing database portability costs.
- A long-lived installation may eventually need an independently designed
  baselining strategy without erasing the authoritative history.

## Risks and mitigations

The main risk is an incompatible or destructive migration reaching a persistent
environment. Mitigate it with small reviewed migrations, PostgreSQL integration
tests, clean-database migration checks, upgrade-path rehearsal where relevant,
application/schema compatibility analysis, and explicit plans for data
transformation and recovery.

Checksum mismatches can also tempt maintainers to edit Flyway history or repair
metadata without understanding the difference. Stop, identify whether the file
or database history changed, and choose an approved forward correction or
operational recovery. Backups, restore testing, recovery objectives, and
deployment rollback procedures remain separate controls; Flyway does not
provide them by itself.

## Consequences

Every accepted schema change receives a new ordered migration. Reviews must
identify compatibility assumptions, destructive effects, and whether the
change can be safely applied before, with, or after its application code.
Persistent environments retain their migration history, while explicitly
disposable development and Testcontainers databases may be rebuilt.

Normal production schema changes must not be performed manually or delegated
to ORM auto-generation. Emergency database work, future baselines, backups,
restores, and deployment rollback require their own approved procedures and
must preserve or reconcile the authoritative migration history.

## Direct historical evidence

- The original [working agreements](../../AGENTS.md) state that Flyway is used
  from the first database change and that applied migrations are never edited.
  Git commit `9cf10e0` first recorded this on 2026-08-12 for issue #1.
- The original [data model](../data-model.md) says PostgreSQL is authoritative
  and Flyway owns every schema change. The original
  [architecture](../architecture.md) also assigns schema evolution to Flyway.
- The [implementation plan](../implementation-plan.md) places the Flyway
  baseline in Phase 2 and leaves migration, restore, and rollback verification
  among later operational work.
- The [repository bootstrap task](../tasks/01-repository-bootstrap.md) added
  Flyway and PostgreSQL support under issue #2 without introducing domain
  migrations.
- The [identity and tenancy task](../tasks/02-identity-and-tenancy.md) requires
  versioned Flyway migrations, real-PostgreSQL migration tests, and Hibernate
  schema validation. Issue #3 implemented
  [V1](../../backend/src/main/resources/db/migration/V1__identity_and_tenancy.sql)
  and
  [V2](../../backend/src/main/resources/db/migration/V2__enforce_single_active_identity_tokens.sql).
- The [platform Business backend task](../tasks/03a-platform-business-backend.md)
  explicitly requires a new migration without editing V1 or V2. Under parent
  issue #4, issue #5 added
  [V3](../../backend/src/main/resources/db/migration/V3__add_business_profile_fields.sql).
- The current [Maven configuration](../../backend/pom.xml) includes Flyway's
  PostgreSQL support, while
  [application configuration](../../backend/src/main/resources/application.yaml)
  enables Flyway and sets Hibernate to validate the schema.
- [Business schema integration tests](../../backend/src/test/java/bg/spotyourslot/integration/BusinessSchemaIntegrationTests.java)
  verify V1-V3 from an empty PostgreSQL database and pin the byte content of V1
  and V2. These tests show the implemented policy and current behavior; they do
  not prove production migration safety or the original comparative reasoning.
- The [testing strategy](../testing-strategy.md) requires real PostgreSQL for
  Flyway and database semantics and identifies backup/restore work separately.

## Retrospective inference

The selected policy reduces environment drift and gives reviewers an auditable
record of how persistent schemas evolve. Comparing it with mutable baselines,
manual production changes, or ORM generation is later analysis; the repository
does not document those alternatives as an original deliberation.

Expand-and-contract staging, checksum incident handling, and future baselining
are forward-looking guidance derived from the decision. Current migrations and
tests demonstrate implemented mechanisms, not proof that these considerations
were the original motivation or that a future production rollout will be safe.

## Conditions for revisiting

Revisit the migration delivery mechanism if schema history becomes too costly
to initialize or operate, deployment topology requires a different ownership
model, or measured release requirements cannot be met with the current Flyway
workflow. A future baseline may shorten fresh setup only through an explicitly
reviewed transition that preserves reliable upgrades and audit history for
existing databases.

Any revision must define schema ownership, environment reconciliation,
application compatibility, data migration, failure recovery, and validation.
It must not rewrite historical events merely to make the final schema appear as
though it always existed.
