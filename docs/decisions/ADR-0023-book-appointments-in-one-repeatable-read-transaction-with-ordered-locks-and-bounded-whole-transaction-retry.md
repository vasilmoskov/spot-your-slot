# ADR-0023: Book Appointments in one repeatable-read transaction with ordered locks and bounded whole-transaction retry

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-10-06
- **Recorded date:** 2026-10-06
- **Related issues:** #18 (reconciles part of #19)
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

Availability is a current view that reserves nothing (ADR-0013, ADR-0016). A guest
booking must therefore revalidate everything inside the transaction that creates
the Appointment, create or match the Customer without leaving partial data, assign
a StaffMember for "Без предпочитание", survive concurrent bookings and
administrative changes, and retry safely. `AvailabilityQuery` joins the caller's
transaction only if it is repeatable-read or serializable (ADR-0016). Customer
matching requires a caller-owned transaction, never continues after a failure, and
asks the caller to retry the whole outer transaction (ADR-0020). Schedule changes
need coordination (ADR-0025). Idempotency and uncertain outcomes are ADR-0024.

## Constraints

- The booking transaction is repeatable-read, read-write; a joined availability
  call rejects anything weaker (ADR-0016).
- `CustomerIdentification.findOrCreate` requires `MANDATORY` propagation, throws the
  sanitized `CustomerConcurrentConflict` (retryable, new transaction) and
  `CustomerOperationFailure` (not retryable), and marks the caller's transaction
  rollback-only (ADR-0020).
- A failed PostgreSQL statement aborts the transaction; the caller never continues
  in it.
- Booking orchestration owns its transactions: it must be invoked **without** an
  existing transaction (see Decision).
- No sleeps; no `REQUIRES_NEW`; no pessimistic row lock on an optimistic aggregate
  beyond the shared reference locks already used.
- Lock order must be consistent with every existing mutation (ADR-0011, ADR-0012,
  ADR-0015, ADR-0025).
- No client value may bypass eligibility, availability, price, duration, status,
  source, or Business.

## Options considered

### Isolation

1. Read committed with explicit locks: rejected, because `AvailabilityQuery` would
   reject the transaction and the four-statement view would not be one snapshot.
2. Serializable: rejected; it adds false-positive aborts, and it would not detect the
   schedule race (ADR-0025).
3. **Repeatable read with ordered shared locks and an exclusion constraint.
   Selected.**

### Where retry lives

1. A retry loop around a `@Transactional` method: Spring may join an outer
   transaction and would not give a completely new transaction. Rejected.
2. **A retry loop outside a `TransactionTemplate` execution, entered only when no
   transaction is active. Selected.** A template with `REQUIRED` propagation begins a
   new transaction only when none exists and otherwise joins the caller's, so the
   template alone does not guarantee a new transaction per attempt; the entry guard
   below does. `REQUIRES_NEW` stays rejected (it would suspend the caller and needs a
   second pooled connection).

### Assignment for "Без предпочитание"

Use the already approved deterministic rule (`docs/product-spec.md`): among the
StaffMembers that the freshly computed slot lists as free, the fewest
non-cancelled Appointments on the slot's Business-local date, then StaffMember
creation time, then ID. With the two-status model "non-cancelled" means
`CONFIRMED`. The assigned member is shown only after confirmation.

## Decision

**Transaction ownership (fail fast).** The booking orchestration entry point is not
transactional and must be called with **no active transaction and no active
transaction synchronization**. Before any booking work (before any statement, lock,
clock read, availability call, or Customer call) it checks this and, if a caller
transaction is active, rejects the call with a fixed, sanitized technical failure
(an internal-error class, never a booking outcome, never retried). Each attempt then
begins a transaction with the `TransactionTemplate` (repeatable-read, read-write,
`REQUIRED`), which, because the guard proved none exists, always creates a new one;
`REQUIRES_NEW` is not used and no outer transaction is suspended. The retry loop
therefore runs outside every transaction, and each attempt is a separate PostgreSQL
transaction with its own snapshot. The consequence is that the booking use case
cannot be composed into a larger caller transaction; a future caller (for example
manual creation) that needs the same behavior must call it the same way or
introduce its own approved orchestration.

**One attempt** (one new transaction, repeatable-read, read-write). The request has
already passed rate limiting and shape validation and has its canonical form and
fingerprint (ADR-0024). The steps are:

1. **Lock the Business** `FOR SHARE` by slug, any lifecycle status. This is the
   first statement and establishes the snapshot. A Business that does not exist
   yields the collapsed `BUSINESS_PAGE_UNAVAILABLE` after step 2.
2. **Replay lookup** by `(business, attempt hash)`. A matching row with a matching
   fingerprint returns the stored result and ends the attempt. A replay holds **only**
   the Business `FOR SHARE` lock from step 1: it takes no StaffMember, schedule
   revision, or Service lock, performs no availability validation, calls no Customer
   capability, and writes nothing (ADR-0024); a differing fingerprint is a mismatch. If no row exists and the
   Business is not `ACTIVE`, return the collapsed unavailable outcome.
3. **Revalidate availability** by calling `AvailabilityQuery.calculate`, joining
   the transaction. The submitted start instant must be one of the freshly computed
   slots, which revalidates the Business state, the active Service, the active
   assigned StaffMember (a specific one, or the eligible set), notice, horizon,
   grid, DST, schedule exceptions, and committed busy time as of the snapshot. A
   missing start is a slot-unavailable outcome; no Customer is touched.
4. **Choose the StaffMember:** the requested one, or the deterministic assignment
   above. The assignment needs a published StaffMember creation time.
5. **Lock** the chosen StaffMember `FOR SHARE` and re-verify it is active and
   assigned; lock the Business's schedule revision `FOR SHARE` (ADR-0025); lock the
   Service `FOR SHARE` and re-verify it is active. A row changed after the snapshot
   makes the lock fail with `40001`; the attempt ends and is retried.
6. **Customer:** `CustomerIdentification.findOrCreate`. `InvalidIdentity` is a
   validation outcome and `IdentityConflict` the generic guest outcome
   (ADR-0020); neither wrote anything. The attempt rolls back.
7. **Insert** the Appointment in one statement from the locked rows (snapshots,
   price, duration, source `ONLINE`, status `CONFIRMED`, a fresh public reference).

**Lock order** (a total order shared with every mutation): Business lifecycle row,
Membership row, StaffMember row, Business schedule revision row, Service row, then
Customer, Appointment, and aggregate rows. The booking skips Membership. Assignment
replacement (StaffMember, then Service) and schedule mutations (StaffMember, then
revision) already follow it; Service administration takes only Business,
Membership, and Service. The complete audit is in ADR-0025.

**Guarantees.** Everything between the first statement and the commit is one
transaction, so a lost overlap race, an identity conflict, a unique violation, or
any later failure rolls back the Customer insert with it; no partial Customer or
Appointment can remain. The database exclusion constraint, not availability, is the
final overlap arbiter. A StaffMember deactivation, assignment change, Service
change, or Business lifecycle or timezone change either waits for the booking's
shared lock or, if committed after the snapshot, fails the lock with `40001`.

**Bounded retry.** At most three attempts in total, immediate, with no backoff:
the lock wait or constraint wait has already ordered the retry behind the winner.
Each attempt is a completely new transaction and reuses the same attempt ID and the
same canonical payload and fingerprint. A transaction that threw is never
continued. The following end the current attempt and trigger a retry in a new
transaction: SQLState `40001`, `40P01`, `23P01` (exclusion), a unique violation on
the attempt hash, a unique violation on the public reference (a fresh reference is
generated), and `CustomerConcurrentConflict`. After `23P01` the next attempt
recomputes availability, so a specific-StaffMember overlap normally becomes a slot
rejection; an "any" booking may pick another member. If the final attempt still ends
in `23P01` the outcome is slot-unavailable. Anything else, and exhausted retries
otherwise, is a **proven rollback** and a known failure (ADR-0024). `CustomerOperationFailure`
and every unexpected pre-commit exception are not retried.

**Deferred.** Buffers, break handling, a configurable horizon or notice, and a
manual-creation path are outside this decision.

## Rationale

One transaction removes partial-state classes. Shared locks serialize the state
changes that must stop new bookings (deactivation, assignment, Service, lifecycle)
without blocking concurrent bookings. Recomputing availability on every attempt
makes every retry decision independent of a stale view. A retry loop outside the
transaction is the only way to satisfy the Customer capability's
"completely new transaction" requirement.

## Tradeoffs and disadvantages

- Repeatable read plus `FOR SHARE` produces `40001` for rare concurrent updates,
  handled by retry; three attempts can be exhausted under sustained contention and
  return a known failure the guest can repeat.
- Availability (four statements) is recomputed on every attempt.
- Every administrative change that must stop bookings needs a row that booking
  locks; this is audited in ADR-0025.
- The assignment rule needs a new published StaffMember creation-time access.

## Risks and mitigations

- **Deadlock:** one total lock order, deterministic order of Service rows, `40P01`
  retried; Phase 4 tests for the orders.
- **Stale assigned member:** the lock and re-verification after assignment.
- **Orphan Customer:** the Customer step is after every cheap rejection and inside
  the same transaction; tests prove zero Customer rows after each failure path.
- **Retry amplification:** three attempts, rate limiting (ADR-0026).
- **Retry inside a caller transaction:** the fail-fast entry guard; Phase 4 tests prove
  that an active caller transaction is rejected before any booking work, that every
  attempt (including each retry after `40001`, `40P01`, `23P01`, a unique violation,
  and `CustomerConcurrentConflict`) runs in a distinct PostgreSQL transaction with its
  own transaction identifier and a fresh snapshot, that an earlier attempt's writes
  (including its Customer insert) are not visible to the next, and that no
  `REQUIRES_NEW` is used.

## Consequences

Phase 4 adds the new `booking` application service (invoked without a transaction), new narrow published contracts
in `business` (slug lock and schedule revision), `catalog` (lockable bookable
Service snapshot and public Service identifier), and `workforce` (public bookable
members and a lockable StaffMember snapshot with creation time), and the module
dependencies `booking → business, catalog, workforce, scheduling, customer,
shared.contact`. Nothing depends on `booking`; `scheduling` never depends on it.
Exact contract names are finalized in Phase 4.

## Evidence

Direct evidence: ADR-0011, ADR-0012, ADR-0013, ADR-0015, ADR-0016, ADR-0020;
`AvailabilityQuery`, `CustomerIdentification`, `StaffMemberReferenceAccess`;
`docs/product-spec.md` (assignment rule); PostgreSQL documentation of `FOR SHARE`
and repeatable-read serialization failures. Inference: the Phase 3 Customer tests
already prove a real `40001` under repeatable read; behavior of `FOR SHARE` on a row
updated after the snapshot is documented PostgreSQL behavior, to be proven by the
Phase 4 tests.

## Conditions for revisiting

Revisit for approved buffers, a measured contention problem, a second application
instance that changes the retry or limiter assumptions, or Customer verification.
