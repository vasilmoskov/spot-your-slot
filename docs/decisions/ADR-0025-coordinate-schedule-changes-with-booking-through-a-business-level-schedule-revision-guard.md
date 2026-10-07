# ADR-0025: Coordinate schedule changes with booking through a Business-level schedule revision guard

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-10-06
- **Recorded date:** 2026-10-06
- **Related issues:** #18
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

A booking validates a slot against schedule data read through its repeatable-read
snapshot (ADR-0023). Consider this order: the booking takes its snapshot; an owner
commits a Business closure, time off, a working-day override, additional working
periods, or a recurring-schedule change; the booking inserts and commits using its
older view. Neither repeatable read nor the Appointment exclusion constraint
prevents it: the constraint concerns only Appointment rows, and the booking reads
schedule tables without locking them. The existing schedule mutations take the
Business and the StaffMember rows `FOR SHARE`; the booking also takes those
`FOR SHARE`, and shared locks do not conflict. A guest could therefore receive a
confirmation after the owner has already saved a closure for that time.

Making the schedule mutation lock `FOR UPDATE` would not help by itself: the
booking's snapshot is taken before it waits, and a lock without a row update
creates no new row version, so no serialization error follows. Serializable
isolation would not detect it either, because "booking, then schedule change" is
a valid serial order.

## Constraints

- The semantics of availability (ADR-0013) do not change.
- `workforce` cannot depend on `scheduling` (`scheduling` already depends on
  `workforce`, ADR-0016); both already depend on `business`.
- The schedule version of ADR-0012 and the exception versions of ADR-0014/0015 stay
  independent and unchanged; editing an exception must not make an owner's stale
  weekly schedule version fail.
- The existing lock order must be preserved (ADR-0011, ADR-0012, ADR-0015).
- Bookings must not block each other.

## Options considered

### A. Accept the window

Cheapest. The guarantee would be weaker: a schedule change that commits inside the
booking's (short) transaction may not be observed, and a guest could receive a
confirmation after the owner saved the change. Rejected as the exact guarantee below
is achievable.

### B. A Business-level schedule revision guard. Selected.

One revision row per Business. Every availability-affecting schedule mutation bumps
it with an `UPDATE` (an exclusive row lock plus a new row version); every booking
locks it `FOR SHARE`.

### C. Serializable booking plus impact checks on the schedule side

If schedule mutations also read blocking Appointments (to warn or reject), a real
read-write cycle exists that SERIALIZABLE can detect. It needs the impact feature,
changes ADR-0016, and adds false-positive aborts. Deferred; a long-term alternative.

### Granularity

A per-StaffMember guard would need a Business-wide second guard for closures and a
row per StaffMember. Schedule edits are rare and the target Businesses are small, so
one row per Business is simpler and has negligible contention.

## Decision

**The exact guarantee.** No Appointment commits after a committed schedule change
that the Appointment's validation did not observe, and no schedule change commits
while a booking that validated against older schedule data is still open. The
conflict is resolved by a `40001` on the booking, which retries in a completely new
transaction (ADR-0023), or by the schedule mutation waiting until the booking
commits.

**Mechanism.** A Business-owned **schedule revision** row (`business_id` primary
key, a `bigint` revision, UTC timestamp) stored in a table created by the Phase 3
migration (`V12`, see the implementation notes). The `business` module publishes a narrow
contract (names in the implementation notes) to bump the row and to lock it in shared mode; no module
reads the table directly. Existing Businesses are backfilled in the migration; a new
Business receives its row in the transaction that creates the Business (a trigger; see the
implementation notes). A missing row is a sanitized technical failure and never a
silent success.

- **Bump:** one `UPDATE … SET revision = revision + 1` per accepted mutation, in the
  mutation's transaction, after the StaffMember lock (when the mutation has one) and
  before the aggregate mutation. A rejected or rolled-back mutation does not advance
  the revision.
- **Booking:** after choosing the StaffMember, lock the revision `FOR SHARE`. If the
  row was updated after the booking's snapshot, the lock fails with `40001`; if an
  uncommitted bump is in flight, the booking waits and then fails with `40001` or
  proceeds if the mutation rolled back.
- **Order:** a booking that already holds the shared lock blocks a later bump until
  it commits, so the schedule change commits after the Appointment.

**Total lock order for every path:** Business lifecycle row → Membership row →
StaffMember row → schedule revision row → Service row → Customer, Appointment, and
aggregate rows. Rows of one kind are locked in deterministic UUID order. The only
change to an existing documented order is the insertion of the revision step after
the StaffMember lock in the weekly-schedule and exception mutations; ADR-0012 and
ADR-0015 are amended accordingly.

**Audit of every mutation that can affect availability or a booking's
eligibility** (verified against the stores and services; there is **no** Business
working-hours store, so Business-wide availability inputs are lifecycle, timezone, and
`BUSINESS_CLOSURE` exceptions only):

| Mutation | Storage statement(s) | Protection | Bump |
|---|---|---|---|
| Business activation, suspension, reactivation | `UPDATE business` (conditional, versioned) | The booking holds the Business row `FOR SHARE`; a concurrent update waits, or fails the booking with `40001` if committed after the snapshot | No |
| Business profile update (including timezone) | `UPDATE business` | Same | No |
| Service update, deactivate, reactivate (name, price, duration, activity) | `UPDATE service` | The booking holds the Service row `FOR SHARE` | No |
| StaffMember profile update, deactivate, reactivate | `UPDATE staff_member` | The booking holds the StaffMember row `FOR SHARE` | No |
| Service-assignment replacement | `UPDATE staff_member` (aggregate version) plus `DELETE`/`INSERT staff_member_service` | The conditional StaffMember `UPDATE` conflicts with the booking's shared lock; the relationship rows change only under it | No |
| Recurring weekly-schedule replacement | `UPDATE staff_working_schedule`, `DELETE`/`INSERT staff_working_period` | Takes the StaffMember row only `FOR SHARE`, which does not conflict | **Yes** |
| Schedule-exception create, replace, delete (all four kinds: Business closure, StaffMember time off, working-day override, additional working periods) | `INSERT`/`UPDATE`/`DELETE schedule_exception` and its periods | Business and StaffMember rows only `FOR SHARE`; no conflict | **Yes**, for every kind and every operation (deleting a working-day override or additional periods reduces availability, so the rule is uniform) |
| StaffMember creation (with its empty schedule), Service creation | `INSERT` | Adds availability only; cannot invalidate a validated booking | No |
| Business creation | `INSERT business` | Creates the revision row | Creates the row |

Mutation paths that are not listed (identity, platform invitation, Customer
administration) do not read or change availability.

**Module ownership.** `business` owns the table and the contract. `workforce`
(weekly-schedule replacement) and `scheduling` (exception mutations) call it from
their existing transactions. `booking` locks it. No new module dependency cycle is
created.

**Failure mapping.** On the mutation side a `40P01` or `40001` victim already maps to
the existing sanitized concurrent-update conflicts. On the booking side it is a
retryable attempt end.

**Delivery and proof.** Phase 3 proves the guard and the participation of every
mutation: the revision advances exactly once for every accepted mutation in the
table, never for a rejected one; the bump takes an exclusive lock that waits for a
test-held shared lock and vice versa (lock-wait evidence from PostgreSQL, no
sleeps); the migration backfills; a new Business gets its row; the insert-order
rules hold. The complete commit-order tests for booking against each schedule
mutation (below) belong to **Phase 4**, when booking orchestration exists. The
Phase 3 migration and the changes to the existing mutation paths are a scope
expansion beyond the original issue text and are authorized by this decision.

**Phase 4 commit orders to prove (deterministic, latches and lock-wait evidence).**

1. The change commits before the booking snapshot: the slot is unavailable.
2. The booking has its snapshot and availability; the change commits; the booking
   resumes: `40001`, retry, rejection, no Appointment, no Customer.
3. An uncommitted bump is held; the booking waits; the bump then commits (retry and
   rejection) or rolls back (success).
4. The booking holds the shared lock; the mutation waits; the booking commits first.
5. Each of these for the weekly replacement and every exception kind and operation.

A control run without the guard demonstrates that the tests detect the race.

## Rationale

A single exclusively locked, versioned row is the smallest mechanism that turns the
silent write skew into a serialization conflict the booking already knows how to
retry. A Business-wide row serializes a rare owner edit against concurrent bookings
without making bookings wait on each other. Auditing each path against actual
storage statements, instead of assuming shared locks suffice, shows precisely which
paths already conflict through a real row update and which need the guard.

## Tradeoffs and disadvantages

- A new table, a migration, and edits to existing mutation paths and three ADRs.
- A schedule edit serializes against every concurrent booking of the same Business.
- Additional `40001` retries under contention; exhaustion returns a known failure.
- Future availability-affecting mutations must remember to bump (mitigated by the
  audit table and a test that enumerates mutation paths).
- The booking-side order does not provide impact checks for already booked
  appointments; those remain a recorded follow-up.

## Risks and mitigations

- **A missed mutation path:** the audit above and a Phase 3 test that every
  availability-affecting store statement runs behind a bump.
- **Deadlock between guard and other locks:** one total lock order; `40P01` retried.
- **Lock upgrade deadlock:** the guard is never upgraded from shared to exclusive in
  one transaction, and the Business row is never updated by a schedule mutation.
- **Existing schedule UI behavior:** the bump does not touch any versioned aggregate
  and adds no new response field.

## Consequences

Phase 3 delivers the migration, the `business` contract, and the participation of
the weekly-schedule and exception paths; no booking exists yet. Phase 4 adds the
booking lock and the commit-order tests. ADR-0012, ADR-0015, and ADR-0016 receive
amendment notes; `architecture.md` and `security.md` document the order.

## Evidence

Direct evidence: `StaffWorkingScheduleAdministrationService.replace` (locks Business,
Membership, then StaffMember `FOR SHARE`, then the schedule update);
`ScheduleExceptionAdministrationService` (same plus conditional aggregate
mutation); the `INSERT`/`UPDATE`/`DELETE` statements in `BusinessStore`,
`ServiceStore`, `StaffMemberStore`, `StaffWorkingScheduleStore`, and
`ScheduleExceptionStore`; ADR-0011, ADR-0012, ADR-0013, ADR-0014, ADR-0015,
ADR-0016. Inference: that `SELECT … FOR SHARE` of a row updated after a
repeatable-read snapshot raises `40001` is documented PostgreSQL behavior and is
proven by the Phase 4 tests, not yet observed in this repository.

## Implementation notes (Phase 3, 2026-10-06)

Implemented in `V12__add_business_schedule_revision.sql`, the `business` contracts and service, and the
participation of the two schedule mutation paths. No decision above changed; these settle the Proposed names
and record the clarifications and the one tightening so the accepted text is not silently rewritten.

- **Names (the Proposed items are settled).** Table `business_schedule_revision` (`business_id` primary key,
  `revision bigint NOT NULL DEFAULT 0` with `CHECK (revision >= 0)`, `updated_at timestamptz NOT NULL`).
  Constraints `business_schedule_revision_pkey`, `business_schedule_revision_business_fk` (`REFERENCES
  business(id) ON DELETE RESTRICT`, like every other Business-owned table) and
  `business_schedule_revision_nonnegative`; no other index. Published contracts in the `business` module root:
  `ScheduleRevisionBump.advance(businessId)` (returns the new revision) and
  `ScheduleRevisionGuard.lockShared(businessId)` (returns the protected revision), implemented only by
  `business.application.ScheduleRevisionService`, plus the cause-free `ScheduleRevisionConcurrentConflict`
  (`40001`, `40P01`) and `ScheduleRevisionFailure` (everything else). The bump and the guard are separate
  interfaces so a consumer holds only the capability it needs; `business.infrastructure.ScheduleRevisionStore`
  is the only reader and writer of the table. `business` gains no module dependency.
- **Initialization.** The migration backfills one row at revision `0` for every existing Business, stamped with the
  Business's `created_at`. A new Business receives its row from an `AFTER INSERT ... FOR EACH ROW` trigger on
  `business` (`business_create_schedule_revision`, function `create_business_schedule_revision`), that is, in the
  same statement and transaction as the Business insert, whichever code path inserts it (the application, a
  fixture, or a future import). This resolves "the transaction that creates the Business" in the strongest form:
  a committed Business without a revision row is impossible, and a rolled-back insert leaves no row. The function
  is not `SECURITY DEFINER` and resolves the table through the session search path, like every other statement.
  A row that is nevertheless missing (for example removed by hand) is a sanitized `ScheduleRevisionFailure`
  from both operations and never recreated silently.
- **Bump placement.** Weekly replacement: `StaffWorkingScheduleService.replace` bumps after input validation,
  schedule lookup, and the version check, and immediately before the conditional schedule update; the outer
  `StaffWorkingScheduleAdministrationService` already holds Business, Membership, and StaffMember in that order.
  Exceptions: `ScheduleExceptionAdministrationService` create, replace, and delete bump after the StaffMember lock
  (StaffMember-scoped kinds only) and immediately before the aggregate statement. **Tightening:** replace and delete
  now compare the stored version with the expected version before the bump and raise the same
  `ConcurrentUpdate` the conditional statement would, so a request that is already stale never takes the revision's
  exclusive lock. A conflict lost to a concurrent writer after the check is still the conditional statement's
  `ConcurrentUpdate`; the whole transaction (including the bump) rolls back. The bump's `40001`/`40P01` victim is
  mapped to the same `ConcurrentUpdate`; `ScheduleRevisionFailure` propagates to the generic sanitized `500`.
  Responses, versions, and HTTP contracts are unchanged and expose no revision.
- **Concurrent schedule writers of one Business now serialize on the revision row** before they reach the
  aggregate. Existing behavior is preserved (one winner, same outcomes), but where two writers previously
  waited on the exclusion constraint or a row lock inside the store they now wait one step earlier. The earlier
  deadlock test of two opposite-order replacements inside one caller transaction therefore no longer deadlocks
  (the second writer waits on the revision before it owns any exception row); it was replaced by a test that
  proves the serialization, and a new test proves that a caller that violates the order (revision, then
  StaffMember row) deadlocks and the bump's victim becomes the sanitized `ConcurrentUpdate`.
- **Guard preconditions.** `lockShared` joins the caller's transaction (`MANDATORY`) and additionally requires that
  the transaction was started repeatable-read or serializable (the same rule as `AvailabilityQuery`); a weaker or
  unexposed level fails with `ScheduleRevisionFailure` before any statement, because under read committed
  the lock could not detect a change committed after the booking's reads. The bump needs no snapshot and runs
  in the default isolation where the mutations run.
- **Audit reconciliation.** Every production `INSERT`, `UPDATE`, and `DELETE` statement was inspected. The table
  above is complete and unchanged: the only paths needing the bump are the weekly replacement
  (`staff_working_schedule` update, `staff_working_period` delete and insert) and the schedule exception
  statements (`schedule_exception` and `schedule_exception_period`); there is still no Business working-hours
  store. Lifecycle and timezone remain protected by the Business row lock, Service by the Service row lock, StaffMember
  profile, activity, and assignment by the StaffMember row (assignment replacement updates `staff_member`
  before it changes `staff_member_service`), and the creation statements only add availability.
  `AvailabilityMutationInventoryTests` scans the production sources: it fails when a write statement is
  added, changed, or no longer present without an inventory decision, and when a production caller of a bumped
  store write does not also depend on `ScheduleRevisionBump`.
- **PostgreSQL behavior observed (the inference in Evidence is now proven).** In a repeatable-read transaction, `SELECT ...
  FOR SHARE` of a revision row updated and committed after the snapshot fails immediately with `40001`; an
  uncommitted bump makes the lock wait and then fail with `40001` when the bump commits, or succeed at the old
  revision when it rolls back; a bump of another Business never affects it
  (`ScheduleRevisionIntegrationTests`).
- **Evidence and limits.** Phase 3 proves the guard with a transaction that stands in for a booking and uses only
  the published guard, against each of the thirteen audited mutation paths
  (`ScheduleRevisionMutationIntegrationTests`): a snapshot predating the committed mutation fails the guard;
  a held guard makes the real mutation wait at the revision row (PostgreSQL's `pg_blocking_pids` names the holder
  and the blocked statement is the revision `UPDATE`) and the mutation commits after the stand-in; a control run
  without the guard shows the stale view never learns of the change. It does **not** prove booking orchestration:
  there is no `booking` use of the guard, no Appointment write behind it, no retry, and no booking-side
  rejection. The commit-order tests listed under "Phase 4 commit orders to prove", with real Appointments, Customers, and
  `AvailabilityQuery`, remain Phase 4.

## Implementation notes (Phase 4, 2026-10-06)

The booking side is implemented in `BookingAttemptProcedure`: the guard is taken after the StaffMember locks and
before the Service lock and before availability (see ADR-0023, Phase 4 note). `GuestBookingScheduleRaceIntegrationTests`
proves every commit order with the real orchestration, Appointments, Customers, `AvailabilityQuery`, and the real
mutations, for all 13 audited paths, with a fresh-availability oracle: (1) change committed before the snapshot;
(2) committed after the snapshot and before the guard: `40001`, retry, two distinct PostgreSQL transactions and
snapshots, result equal to the oracle; (3) an uncommitted bump held (the guard waits, shown with
`pg_blocking_pids`), then committed (retry) or rolled back (one attempt, old schedule); (4) the booking holds the
guard and the mutation waits at the revision `UPDATE`, the booking commits first. **Control runs:** with the guard
bypassed (test switch) a booking whose availability predates a weekly replacement or closure commits an
Appointment the fresh view does not offer; with the guard, the same interleaving makes the change wait.

## Conditions for revisiting

Revisit for Business-configurable horizon or notice, breaks, an impact-check feature
(then Option C), a measured contention problem, or new availability-affecting data.
