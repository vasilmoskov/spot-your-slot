# ADR-0011: Use StaffMember aggregate versioning for Service assignments

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-09-18
- **Recorded date:** 2026-09-18
- **Related issues:** #10, #12
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

Issue #12 adds mutable StaffMember profile and lifecycle state together with a
complete set of supported-Service assignments. Two owners can edit the same
StaffMember or its assignments from representations based on the same earlier
state. Independent or unconditional writes could silently overwrite a profile,
lifecycle, or assignment change.

Assignment replacement also crosses the Workforce/Catalog boundary. A Service
that was active when initially inspected can be deactivated before a new
relationship commits. The design must close that race without exposing or
reusing Catalog repositories or persistence records.

## Constraints

- StaffMember ownership is immutable and every operation is scoped by the
  authenticated selected Business.
- Only an active `BUSINESS_OWNER` Membership authorizes issue #12.
- DRAFT and ACTIVE Businesses permit mutations; SUSPENDED Businesses are
  read-only.
- Active and inactive StaffMembers remain administratively configurable.
- Only active Services may be newly assigned.
- An existing inactive-Service assignment may be retained or removed.
- StaffMember and Service deactivation preserve existing relationships.
- Assignment replacement must be atomic and deterministic.
- PostgreSQL is the final source of truth for versions, relationship uniqueness,
  ownership, and locks.
- Catalog persistence remains internal to Catalog.
- Availability, booking, and Appointments remain outside issue #12.

## Options considered

### Unconditional complete-set replacement

This is a small contract and makes the desired state easy to express. It can
silently replace an assignment or StaffMember change accepted after the caller
loaded its form.

### Separate StaffMember and assignment-set versions

Separate versions reduce conflicts between profile and assignment edits. They
also permit an assignment form based on an older StaffMember state to succeed
unless every cross-version interaction is specified. The model would expose two
concurrency tokens for one administrative record and complicate lifecycle races.

### One StaffMember aggregate version

One version makes every StaffMember profile, lifecycle, and assignment mutation
conditional on the complete state the caller reviewed. It intentionally reports
conflicts between disjoint edits and requires clients to reload and reconsider.

### Pessimistic StaffMember locking without an expected version

A row lock can serialize requests after they reach the database, but it cannot
span a person's browser editing interval. It therefore cannot identify that a
submitted representation was already stale.

### Workforce reads Catalog tables directly

Direct SQL or repository reuse could validate Services in the assignment
transaction. It would violate module ownership, couple Workforce to Catalog's
schema implementation, and bypass the published-boundary architecture.

## Decision

Use one nonnegative `bigint` StaffMember version, starting at `0`, as the
optimistic token for profile update, deactivation, reactivation, and complete
Service-assignment replacement. Creation has no expected version.

Every successful mutation increments the StaffMember version exactly once and
updates its timestamp once. A successful assignment replacement increments the
version even when the desired set equals the current set. Two operations using
the same expected version therefore produce exactly one successful mutation.

Assignment replacement conditionally updates the StaffMember with:

```sql
WHERE business_id = :businessId
  AND id = :staffMemberId
  AND version = :expectedVersion
```

It does not predicate on StaffMember activity. Active and inactive StaffMembers
have the same administrative assignment behavior.

After the version guard, Workforce compares the current and desired assignment
sets. It validates Service activity only for additions. Retained inactive
Services are allowed, removal of inactive Services is allowed, and adding or
restoring an inactive Service returns `SERVICE_INACTIVE`.

Workforce resolves and locks additions through a narrow published Catalog
contract. Catalog orders Service IDs deterministically and locks the matching
same-Business rows for share within the outer transaction. A concurrent Service
deactivation either commits first and makes the addition fail, or waits until
the accepted assignment commits. Catalog does not publish its repository,
persistence record, or SQL implementation.

StaffMember version change, Service validation, relationship deletion and
insertion, and response construction occur in one transaction. Any failure
rolls back the complete replacement and the version increment.

The relationship primary key provides a separate database guarantee against
duplicate rows. Composite foreign keys provide same-Business ownership. Neither
constraint depends on endpoint activity, so deactivation can preserve data.

## Rationale

The shared version represents the complete administrative StaffMember state
the caller reviewed. Keeping the final expected-version predicate in PostgreSQL
prevents a lost update between an application read and write. Treating a
same-set replacement as a mutation gives every accepted command the same
one-winner behavior.

The published Catalog lock protects the active-Service rule at commit time
without breaking modular-monolith encapsulation. Applying that rule only to
additions preserves inactive relationships exactly as the approved lifecycle
requires.

## Tradeoffs and disadvantages

- Profile, lifecycle, and assignment edits can conflict even when they affect
  different fields.
- Every mutation client must retain and submit the current version.
- Same-set replacement changes the version and update timestamp.
- Assignment replacement performs a preliminary read, one versioned update,
  Catalog validation/locking, and relationship reconciliation.
- Shared row locks can introduce short waits with concurrent Service lifecycle
  changes.
- The lock order must remain documented and consistent as later modules appear.

## Risks and mitigations

An implementation could check the version in Java and then update
unconditionally. Keep `business_id`, ID, and `expectedVersion` in the final
`UPDATE ... RETURNING`, and prove one winner with separate PostgreSQL
transactions.

An implementation could incorrectly add `active = true` to assignment mutation
SQL or reject an inactive StaffMember in application code. Tests must exercise
addition, retention, removal, and same-set replacement for both active and
inactive StaffMembers.

An implementation could reject every desired inactive Service, making it
impossible to preserve an existing relationship. Compute additions from the
current set first and apply active-Service validation only to that difference.

A Service can be deactivated after an unlocked active-state read. The Catalog
boundary must lock additions in deterministic UUID order within the mutation
transaction, and coordinated PostgreSQL tests must exercise both race outcomes.

Cross-module shortcuts could erode module ownership. Spring Modulith verification
and code review must reject Workforce dependencies on Catalog application,
infrastructure, repositories, or persistence records.

## Consequences

StaffMember responses expose one authoritative `version`. Profile, lifecycle,
and assignment requests carry `expectedVersion`. After success, clients replace
all relevant local state with the authoritative response; after conflict, they
reload instead of automatically overwriting.

StaffMember activity remains an operational flag rather than an administrative
write restriction. Future availability and booking work will exclude inactive
StaffMembers from availability and new booking, but issue #12 implements no
availability or Appointment behavior.

Future StaffMember mutations must participate in the aggregate version or
receive a separately reviewed concurrency rule. Working schedules are separate
aggregates under issue #13 and do not inherit this decision automatically.

## Evidence

### Direct evidence

- [Issue #12 task contract](../tasks/04b-staff-management-and-service-assignments-backend.md)
  records the approved inactive-StaffMember, assignment, tenant, API, and
  concurrency behavior.
- [ADR-0003](ADR-0003-use-business-scoped-memberships-for-multi-tenancy.md)
  requires server-derived Business context and same-Business relationships.
- [ADR-0004](ADR-0004-use-immutable-forward-only-flyway-migrations.md) requires
  immutable forward schema evolution.
- [ADR-0009](ADR-0009-test-persistence-and-concurrency-against-real-postgresql.md)
  requires real PostgreSQL for lock, constraint, and concurrency claims.
- [ADR-0010](ADR-0010-use-optimistic-concurrency-for-service-mutations.md)
  establishes the adjacent Service expected-version and lock-order precedent
  while explicitly leaving assignment concurrency outside its scope.
- [V6](../../backend/src/main/resources/db/migration/V6__add_staff_members_and_service_assignments.sql)
  creates the StaffMember version and same-Business relationship constraints.
- [Phase 1 schema tests](../../backend/src/test/java/bg/spotyourslot/integration/StaffSchemaIntegrationTests.java)
  verify the persistence shape and activity-independent relationships.

### Inference

Staff administration is expected to have low enough contention for optimistic
conflicts to be acceptable. That is a product assumption rather than measured
production evidence. The shared version favors a clear stale-state contract
over minimizing all conflicts.

## Conditions for revisiting

Revisit if measured conflicts are frequent, assignment sets become very large,
safe field-level merging is introduced, clients require HTTP ETags, or a future
operation spans StaffMember and other aggregates in a way the current version
cannot represent.

Any replacement must define the client precondition, atomic database guard,
inactive-StaffMember behavior, Service activity race, module boundary, retry
policy, and deterministic PostgreSQL verification. It must not restore silent
lost updates or make StaffMember inactivity an accidental administrative lock.
