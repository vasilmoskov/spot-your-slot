# ADR-0012: Use versioned atomic replacement for StaffMember working schedules

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-09-23
- **Recorded date:** 2026-09-23
- **Related issues:** #10, #13
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

Issue #13 adds recurring weekly working periods for a StaffMember. Two owners
can edit the same week from an earlier representation, and a StaffMember can be
deactivated while a schedule update is in progress. Periods within one weekday
must never overlap, including when writes are concurrent or bypass application
validation.

ADR-0011 deliberately leaves working schedules outside the StaffMember version.
The schedule therefore needs its own aggregate boundary, client precondition,
atomic replacement rule, and PostgreSQL overlap guarantee.

## Constraints

- Every StaffMember owns exactly one recurring working-schedule aggregate.
- The schedule belongs immutably to the same Business as the StaffMember.
- Times have one-minute precision, use local clock values from `00:00` through
  `23:59`, and are interpreted in the authoritative Business timezone.
- A week contains zero through 100 periods; split days and adjacent periods are
  valid, while duplicates, overlaps, and overnight periods are invalid.
- Inactive StaffMembers retain readable schedules but cannot receive schedule
  mutations.
- DRAFT and ACTIVE Businesses permit schedule mutation; SUSPENDED Businesses
  are read-only.
- Issue #13 authorizes only an active `BUSINESS_OWNER` Membership for the
  selected Business.
- PostgreSQL is the final source of truth for ownership, versions, and overlap.
- Availability, exceptions, time off, breaks, overrides, booking, frontend, and
  StaffMember account linkage remain outside this decision.

## Options considered

### Reuse the StaffMember aggregate version

This would reuse the issue #12 concurrency token, but schedule edits would
conflict with unrelated profile and Service-assignment edits. ADR-0011 also
explicitly says that working schedules are separate aggregates.

### Mutate individual periods

Period-level create, update, and delete operations could reduce replacement
work. They would expose intermediate weekly states, require concurrency rules
for several related rows, and complicate clearing a weekday or the complete
week atomically.

### Replace the complete week under an independent schedule version

One expected version represents the complete week the owner reviewed. A single
transaction can advance the version, replace all child periods, and roll back
the complete change on any failure. This intentionally makes concurrent edits
to different weekdays conflict.

## Decision

Use one `staff_working_schedule` aggregate for every StaffMember. V7 backfills
an empty version-0 schedule for existing StaffMembers using the owning
StaffMember's creation instant for both schedule timestamps. Phase 2 will make
future StaffMember creation insert its empty schedule in the same transaction.

Use a nonnegative independent `bigint version`, starting at `0`. Complete
replacement requires a nonnegative `expectedVersion`. Every accepted
replacement increments the version and update timestamp exactly once, including
an identical replacement. An empty desired set represents an empty week.

The mutation transaction keeps this lock order:

1. selected Business lifecycle row;
2. exact active owner Membership row;
3. StaffMember row, to stabilize its active state;
4. conditional schedule version update; and
5. complete child-period replacement.

The final schedule update retains `business_id`, `staff_member_id`, and
`expectedVersion` in its PostgreSQL predicate. Any validation, version, or
persistence failure rolls back both the version advance and period replacement.

Persist weekdays as ISO values `1` through `7` and local start/end values as
`time without time zone`. Do not persist the Business timezone in schedule
tables. The later HTTP contract will expose weekdays as `MONDAY` through
`SUNDAY`, times as canonical `HH:mm`, and the live authoritative Business
timezone.

Reject PostgreSQL's special `24:00:00` time value. `23:59` is the latest
representable boundary; it does not stand for the remainder of the final minute
through midnight. Exact-midnight closing is outside the MVP representation.
Supporting it later requires an explicitly approved boundary/domain and API
extension rather than storing a value the Java time model cannot represent.

For database overlap enforcement, generate an `int4range` from each period's
start and end minutes after midnight. The approved one-minute precision makes
this mapping exact. Use canonical half-open `[start,end)` ranges, so equal
boundaries are adjacent rather than overlapping. A GiST exclusion constraint
combines equality for `business_id`, `staff_member_id`, and weekday with the
range overlap operator. The primary key separately makes exact duplicate rows
invalid.

V7 installs the PostgreSQL-supplied trusted `btree_gist` extension because the
multicolumn GiST exclusion needs equality operator classes for UUID and
`smallint`. Schema tests must verify the extension, operator classes, generated
expression, range behavior, and exclusion constraint against the pinned
PostgreSQL 18.4 image.

## Rationale

The version corresponds to the complete weekly state displayed to the owner.
Atomic replacement gives clearing and split-day editing one transaction boundary
and prevents partially accepted weeks. Keeping the schedule version separate
avoids conflicts with StaffMember profile and assignment changes.

Integer minutes avoid assuming a built-in range type over `time without time
zone`. They preserve every approved API value without rounding. PostgreSQL's
canonical discrete `int4range` and `&&` overlap operator express the invariant
directly, while `[)` permits adjacency.

## Tradeoffs and disadvantages

- Concurrent changes to separate weekdays still conflict.
- Every accepted no-op replacement changes version and update time.
- Complete replacement rewrites child rows.
- Every future StaffMember creation path must also create the empty aggregate.
- The schema depends on the supplied `btree_gist` extension and PostgreSQL GiST
  behavior.
- Exact-midnight closing is not representable in the MVP schedule model.
- Business timezone changes alter interpretation of the same local periods;
  historical timezone snapshots are not retained.

## Risks and mitigations

An implementation could check the version in Java and replace periods after an
unconditional update. Keep the expected version in the final SQL update and use
coordinated PostgreSQL tests to prove one winner.

A schedule mutation could race with StaffMember deactivation. Lock the
StaffMember after the established Business and Membership locks, check activity
under that lock, and test both race orders without sleeps.

An incorrect time conversion could round seconds, accept PostgreSQL's special
`24:00:00`, or treat adjacency as overlap. Database checks reject non-minute and
special 24-hour values, and PostgreSQL integration tests assert generated
bounds, the latest representable boundary, adjacency, duplicates, partial
overlap, containment, and weekday isolation.

Cross-Business references or incomplete predicates could leak or corrupt tenant
data. Composite restrictive foreign keys and tenant-prefixed keys supplement
application authorization, and every persistence operation remains scoped by
Business and StaffMember.

## Consequences

Schedule reads return a real empty aggregate at version `0`; they never create
state. Schedule responses expose their own version and timestamps rather than
the StaffMember version. Clients reload and reconsider after a concurrent-update
response instead of automatically overwriting newer state.

Future availability code consumes local weekly periods together with the live
Business timezone. It must define DST behavior separately. Exceptions, leave,
time off, breaks, overrides, and historical schedule versions require later
approved models and do not enter V7.

## Evidence

- Issue #13 records the approved aggregate, lifecycle, tenant, validation,
  atomicity, API, and testing requirements.
- [ADR-0001](ADR-0001-use-a-modular-monolith.md) requires narrow published
  module contracts and internal persistence.
- [ADR-0002](ADR-0002-use-postgresql-as-the-transactional-system-of-record.md)
  makes PostgreSQL the durable concurrency and constraint arbiter.
- [ADR-0003](ADR-0003-use-business-scoped-memberships-for-multi-tenancy.md)
  requires server-derived Business context and same-Business relationships.
- [ADR-0004](ADR-0004-use-immutable-forward-only-flyway-migrations.md) requires
  a new immutable migration and preservation of V1 through V6.
- [ADR-0009](ADR-0009-test-persistence-and-concurrency-against-real-postgresql.md)
  requires real PostgreSQL for migration, constraint, lock, and concurrency
  claims.
- [ADR-0011](ADR-0011-use-staff-aggregate-versioning-for-service-assignments.md)
  establishes the adjacent lock-order convention and explicitly excludes
  working schedules from the StaffMember aggregate version.

## Conditions for revisiting

Revisit if measured schedule conflicts are frequent, partial merge semantics
become safe and necessary, schedules require sub-minute precision or an
exact-midnight boundary, historical timezone interpretation becomes a
requirement, or future schedule exceptions need a wider transactional aggregate.

Any replacement must define the client precondition, atomic database guard,
StaffMember lifecycle race, tenant ownership, overlap guarantee, timezone
semantics, retry policy, and deterministic PostgreSQL verification.
