# ADR-0014: Store schedule exceptions as versioned aggregates with same-kind date exclusion

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-09-29
- **Recorded date:** 2026-09-29
- **Related issues:** #16
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

ADR-0013 fixes how Business closures, StaffMember time off, working-day
overrides, and additional working periods affect availability. Issue #16 Phase 2
must persist them so later phases can get, list, create, atomically replace, and
hard-delete one owner-visible exception with an expected version. ADR-0013 also
warns that persistence must not forbid every overlap between exception rows: a
closure legitimately overlaps an override, and time off overlaps additional
working periods.

## Constraints

- PostgreSQL is the source of truth for tenant ownership, versions, and
  concurrency (ADR-0002, ADR-0009).
- Periods are local, half-open, whole-minute values from `00:00` through `23:59`
  and are never merged (ADR-0012, ADR-0013).
- Full-day closures and time off are inclusive date ranges; partial blocks and
  both working kinds apply to exactly one local date.
- An override may have zero periods; additional working periods need at least
  one.
- Phase 2 adds no authorization, lifecycle, controller, or orchestration.

## Options considered

### One row per calendar date

Simple constraints, but a long closure becomes many rows, editing a range
rewrites many rows, and aggregate versioning spans rows. Rejected.

### One table with an array, `jsonb`, or multirange of periods

No per-period constraints, and a multirange merges adjacent periods, violating
ADR-0013. Rejected.

### One generic exclusion constraint across all kinds

Forbids legitimate cross-kind overlaps. Rejected.

### Time-precise cross-aggregate conflicts through a third span table

Would reject only true time overlaps, at the cost of a table the store must keep
synchronized. Not chosen for the MVP.

### Parent aggregate with composed period rows and same-kind date exclusion

Selected.

## Decision

`schedule_exception` is the versioned aggregate root: UUID identity, immutable
Business ownership, a nullable StaffMember (absent exactly for
`BUSINESS_CLOSURE`), a checked `kind`, inclusive `first_date`/`last_date`, an
`all_day` flag, a nonnegative `bigint version` starting at 0, and UTC
`created_at`/`updated_at`. `kind` and StaffMember are immutable after creation.
`schedule_exception_period` holds the local periods of one aggregate. The
foreign key from period to exception is `ON DELETE CASCADE`: the periods are
aggregate composition with no lifecycle of their own, not an independently owned
relationship, so a hard delete is one guarded statement. Every other foreign key
restricts.

A generated inclusive `daterange` and two GiST exclusion constraints reject
overlapping date ranges only for the same kind and scope: Business closures for
one Business, and each StaffMember-scoped kind for one StaffMember. One local
date therefore belongs to at most one aggregate of a given kind and scope;
disjoint partial periods on that date belong to one aggregate. Cross-kind and
cross-scope overlaps are allowed and resolved by the engine. Working exceptions
and partial blocks are single-date, so two overrides (or two additional
aggregates) on one date are rejected as ambiguous. A per-aggregate exclusion
constraint on a generated `int4range` rejects duplicate and overlapping periods
while allowing adjacency. Composite foreign keys to `staff_member(business_id,
id)` and `schedule_exception(business_id, id)` prevent cross-Business
references. PostgreSQL rejects `24:00` and sub-minute precision. Only finite
dates are required; no calendar-year bounds are imposed.

PostgreSQL does not count child rows. The scheduling domain content record and
the store enforce: a full-day aggregate has no periods; partial closures and
time off and additional working periods have at least one; an override has zero
or more; periods are sorted; duplicates and overlaps are rejected; adjacent
periods stay separate. No trigger is used, because deferred count triggers add
fragile ordering rules to atomic replacement.

Conditional replace and delete match Business, id, and expected version (and
kind and StaffMember for replace). Their empty or `false` result does not
distinguish a missing aggregate, a stale version, or a concurrent delete. The
future application service reads inside its authorized transaction, reports
not-found only for an empty read, and treats a failed conditional mutation after
a successful read as a concurrent change.

Future mutation lock order remains Business lifecycle row, exact owner
Membership row, StaffMember row where applicable, then the aggregate. The
conditional `UPDATE` or `DELETE` write-locks the aggregate; concurrent inserts
are serialized by the exclusion constraints, not by a check-then-insert query.
SUSPENDED and inactive-StaffMember behavior stays an application concern.

## Rationale

The parent version describes the complete exception the owner reviewed, so
atomic replacement, stale detection, and hard delete need no schema change. Date
exclusion per kind and scope keeps one race-safe database rule while leaving
cross-kind precedence to the engine.

## Tradeoffs and disadvantages

- Two separate partial blocks of one kind on one date must share an aggregate.
- Child-count rules rely on domain, store, and test discipline.
- Two replacements moving aggregates into each other's dates can deadlock;
  PostgreSQL aborts one and the store reports a retryable write conflict.
- Complete replacement rewrites the periods.
- There is no maximum date span or period count in the schema; application
  validation may add them.

## Risks and mitigations

The exclusion constraints, generated ranges, and immutability of their functions
are verified against the pinned PostgreSQL 18.4 image, as is serialization of
concurrent conflicting inserts and replacements without sleeps.

## Consequences

Later phases add administration on top of the store without schema change. Exact
migration integrity of V1 through V8 is asserted by a test.

## Evidence

Issue #16; ADR-0002, ADR-0009, ADR-0012, ADR-0013; the V9 migration and
`ScheduleException*IntegrationTests`.

## Conditions for revisiting

Revisit for time-precise conflicts, a maximum span or period count, an
independently identified period, or a proven need to change kind or StaffMember.
