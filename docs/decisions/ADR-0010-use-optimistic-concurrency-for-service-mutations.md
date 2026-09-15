# ADR-0010: Use optimistic concurrency for Service mutations

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-09-15
- **Recorded date:** 2026-09-15
- **Related issues:** #10, #11
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

Two Business owners can read the same Service version and submit incompatible
updates or lifecycle actions. Unconditional writes would silently replace one
accepted intent. Concurrent creates or renames can also target the same
Business-scoped normalized name.

ADR-0007 chooses optimistic concurrency for Business mutations and explicitly
does not govern future domain records. Services therefore need their own
reviewed policy, including interaction with tenant authorization, Business and
Membership locks, lifecycle predicates, and normalized-name uniqueness.

## Constraints

- A Service belongs immutably to one Business and every operation derives that
  Business from the selected authenticated session context.
- Only an active `BUSINESS_OWNER` Membership authorizes issue #11 operations.
- DRAFT and ACTIVE Businesses permit mutations; SUSPENDED Businesses permit
  reads only.
- Existing-Service mutations carry `expectedVersion` and return the
  authoritative updated representation.
- PostgreSQL is the final source of truth for version and unique-name races.
- Failures must not expose SQL, constraint names, or driver details.

## Options considered

### Last-write-wins

This has the smallest persistence contract but can silently discard an owner's
accepted update or apply a lifecycle action based on stale state.

### Pessimistic Service-row locking

This serializes work inside a transaction but cannot safely span the time a
human spends editing a browser form. It adds waiting without revealing that the
submitted representation was stale.

### Optimistic version checking

This makes the state reviewed by the caller explicit and lets the final SQL
statement decide whether it is still current. Clients must handle visible
conflicts and reload before reconsidering an update.

### Serializable transactions

This can protect wider invariants but introduces serialization failures and
does not directly express the stale-form contract. It is broader than the
single-Service mutation problem.

## Decision

Give every Service a nonnegative `bigint version`, starting at `0`. Require a
nonnegative `expectedVersion` for update, deactivate, and reactivate. Creation
has no expected version.

After tenant-safe authorization and validation, execute one atomic PostgreSQL
`UPDATE ... RETURNING` whose predicate includes:

```sql
WHERE business_id = :businessId
  AND id = :serviceId
  AND version = :expectedVersion
```

Lifecycle mutations also include the required current `active` state. Every
successful mutation sets `version = version + 1`, updates `updated_at` once,
and returns the authoritative Service. An empty result after the preliminary
tenant-safe read becomes HTTP 409 `SERVICE_CONCURRENT_UPDATE`. A lifecycle state
already known to be invalid becomes the distinct 409
`SERVICE_INVALID_LIFECYCLE`.

Use a normal PostgreSQL unique constraint on
`(business_id, normalized_name)` as the final arbiter for concurrent create and
rename races. Translate that constraint to `SERVICE_NAME_CONFLICT`, including
when an inactive Service reserves the key. Do not automatically retry either a
version conflict or a name conflict.

Service mutation authorization runs in one transaction and holds the
qualifying active Membership and selected Business rows with the established
shared-lock pattern. These locks keep authorization and Business status stable
through the mutation. They complement the Service version predicate and do not
replace it.

## Rationale

Service administration is a human-driven, low-contention workflow where
long-held locks are impractical. A returned version identifies the exact state
the caller reviewed, and a conditional update closes the race between an
application read and write. The separate unique constraint protects a
cross-row invariant that a per-Service version cannot cover.

## Tradeoffs and disadvantages

- Every existing-Service mutation client must retain and submit a version.
- Owners can encounter conflicts and must reload and reconsider their intent.
- A single version reports that some field changed without supporting automatic
  field-level merging.
- The preliminary read adds work but does not remove the need for the final
  atomic predicate.
- Shared Business and Membership locks introduce short waits and require a
  consistent lock order.
- Unique conflicts must be classified safely without coupling the public API to
  database error text.

## Risks and mitigations

An implementation could validate `expectedVersion` in Java and then issue an
unconditional update. Integration tests must coordinate separate transactions
and prove that two same-version mutations produce exactly one winner.

Lock-order mistakes could deadlock Business lifecycle, Membership changes, and
Service mutations. Reuse one documented Business-then-Membership lock order and
exercise the relevant races against real PostgreSQL.

An exception mapper could leak SQL or misclassify unrelated integrity errors.
Inspect SQL state and the known constraint internally, map only the approved
constraint, and return stable safe Bulgarian problems.

## Consequences

Service responses expose `version`. Update and lifecycle request records expose
`expectedVersion`. Successful clients replace local state with the returned
representation; conflicted clients reload.

Every future Service mutation must participate in this version or receive a
separate reviewed concurrency rule. Assignment, scheduling, availability, and
Appointment concurrency remain outside this ADR.

## Evidence

Direct Phase 1 evidence:

- [The issue #11 task](../tasks/04a-business-services-backend.md) records the
  approved API, lifecycle, tenant, locking, and concurrency contracts.
- [V5](../../backend/src/main/resources/db/migration/V5__add_business_services.sql)
  creates the Service version and Business-scoped normalized-name constraint.
- [Service schema integration tests](../../backend/src/test/java/bg/spotyourslot/integration/ServiceSchemaIntegrationTests.java)
  prove the unique constraint and inactive-name reservation against the pinned
  PostgreSQL 18.4 image.
- [ADR-0007](ADR-0007-use-optimistic-concurrency-for-business-mutations.md)
  documents the established Business approach and its explicit exclusion of
  future Service records.
- [ADR-0009](ADR-0009-test-persistence-and-concurrency-against-real-postgresql.md)
  requires real-PostgreSQL concurrency verification.

The expected low contention of Service administration is a product assumption,
not a measured production fact.

## Conditions for revisiting

Revisit if measured conflicts are frequent, Service changes span aggregates,
safe merging becomes possible, standard HTTP ETags are required, or correctness
requires a longer pessimistic critical section. Any replacement must define the
client precondition, atomic database guard, tenant and lifecycle interaction,
name-race behavior, retry policy, and deterministic PostgreSQL tests.
