# ADR-0029: Calculate Business availability with a separate policy and serialize Appointment mutations with ordered locks, optimistic versions, and bounded retry

## Metadata

- **Status:** Accepted (design approved by the product owner on 2026-10-14; this record has not yet had an independent review; the PostgreSQL behaviors marked as inference are proven by the named phase tests before the phase that relies on them is accepted; nothing is implemented)
- **Decision date:** 2026-10-14
- **Recorded date:** 2026-10-14
- **Related issues:** #19 and #21
- **Supersedes:** None
- **Amends in part:** [ADR-0013](ADR-0013-define-availability-interval-precedence-grid-and-dst-semantics.md) (the engine takes its notice and horizon as inputs), [ADR-0016](ADR-0016-orchestrate-availability-through-published-contracts-and-a-scheduling-owned-busy-interval-seam.md) (a second busy-interval method and a Business-side entry), [ADR-0023](ADR-0023-book-appointments-in-one-repeatable-read-transaction-with-ordered-locks-and-bounded-whole-transaction-retry.md) (the "own approved orchestration" for a manual caller), [ADR-0025](ADR-0025-coordinate-schedule-changes-with-booking-through-a-business-level-schedule-revision-guard.md) (the lock order names two more levels and the guard gains a consumer). Each keeps its text and carries an amendment note.
- **Companion:** [ADR-0028](ADR-0028-administer-appointments-through-a-private-owner-only-api-with-immutable-visit-identity-and-business-cancellation.md) decides the operations, roles, lifecycle, fields, and cancellation.
- **Superseded by:** None

## Context and problem

Issue #19 requires that Business-created, moved, and reassigned Appointments obey the same conflict rules as a guest booking, that concurrent attempts cannot leave overlapping `CONFIRMED` Appointments for one StaffMember, and that a failed change leaves the original unchanged. Issue #18 delivered this only for guest creation. Three facts of the committed code shape the design:

1. `AvailabilityQuery` is a public-policy view. It requires an `ACTIVE` Business, adds a two-hour minimum notice, and offers 30 dates; the engine hard-codes both (`AvailabilityEngine`, `AvailabilityPolicy`). It derives the duration from the **current** Service rows, and the busy source cannot ignore a particular Appointment, so an Appointment moved by 15 minutes would see its own old interval as busy.
2. `GuestBookingService` rejects any caller transaction and cannot be composed (ADR-0023: a manual caller "must call it the same way or introduce its own approved orchestration").
3. The database exclusion constraint is the final overlap arbiter, but only an `INSERT`, and the `CANCELLED → CONFIRMED` `UPDATE`, are proven against it (`AppointmentSchemaIntegrationTests`). A time-changing or StaffMember-changing `UPDATE`, and an `UPDATE` that must not conflict with the row's own old interval, are not yet proven in this repository.

The design must say, for create, reschedule and reassign, cancel, and note edit separately, which transaction runs, which rows are locked in which order, when the Appointment state is read, how the moved Appointment is ignored, and how a stale version, a state conflict, and a lost slot stay distinct.

## Constraints

- Tenant isolation and owner authorization derive from the authenticated context (ADR-0028).
- `AvailabilityQuery` joins only a REPEATABLE READ or SERIALIZABLE transaction and `ScheduleRevisionGuard.lockShared` requires one too (ADR-0016, ADR-0025).
- Locks form one total order; no mutation may invert it (ADR-0025). Rows of one kind are locked by one `ORDER BY id` statement, in PostgreSQL's unsigned `uuid` order (ADR-0027 section 7), never pre-sorted in Java.
- A transaction that threw is never continued; a retry is a completely new transaction (ADR-0020, ADR-0023).
- The guest booking attempt runner is not refactored by this work; its behavior and tests stay as they are.
- No sleeps in tests; concurrency is ordered by latches and PostgreSQL lock-wait evidence (`docs/testing-strategy.md`).
- Zero buffers, a 15-minute grid, instants stored in UTC (ADR-0013, ADR-0022).

## Options considered

### Availability for Business operations

1. **Reuse the public availability unchanged.** The owner could not book a walk-in within two hours or beyond 30 days, and could not move an Appointment into its own interval. Rejected.
2. **Bypass availability and let the exclusion constraint alone decide.** The constraint knows nothing of working periods, exceptions, or the grid. Rejected.
3. **The same engine and data with an explicit policy, an explicit duration, and an ignored Appointment. Selected.** Working periods, exceptions, grid, DST rules and conflict detection stay authoritative; only the policy inputs differ.

### Isolation and serialization of create, reschedule, and reassign

1. **SERIALIZABLE.** More false-positive aborts, and it cannot detect the schedule race that the revision row exists for (ADR-0023, ADR-0025). Rejected.
2. **A pessimistic `FOR UPDATE` on the Appointment, or an advisory lock per StaffMember, before validating.** It serializes unrelated work and contradicts "bookings do not block each other" (ADR-0023). Rejected as a first step; it remains the follow-up if contention is measured.
3. **REPEATABLE READ with ordered shared locks, a conditional `UPDATE` carrying the expected version, and the exclusion constraint as the final arbiter. Selected.** The same pattern as guest booking, plus the version guard for the one row that is updated.

### Cancel and note edit

1. **The same repeatable-read runner.** Correct, but it adds a retry loop and snapshot machinery to a single conditional `UPDATE` that needs no availability and no revision guard.
2. **A short transaction in the default isolation (READ COMMITTED), like Customer administration (ADR-0021). Selected.** One conditional `UPDATE` decides; a zero-row result is classified by a read in the same transaction.

### Idempotency of a manual create

1. **An operation identifier with a stored fingerprint.** A future option (see Conditions for revisiting). Because a manual Appointment stores every fact (Customer, StaffMember, start, lines, notes), a comparison of the stored row would be possible and no HMAC key would be needed, but the existing `appointment_idempotency_all_or_none` check, the guest-specific hash and fingerprint semantics, and a new uniqueness rule make it a separate design.
2. **None for the MVP. Selected.** The exclusion constraint prevents a duplicate `CONFIRMED` Appointment for the same StaffMember and interval, so a repeated identical create can only produce a conflict, never a duplicate.

### The runner

1. **Extract the guest runner into a shared one.** Less duplicated code, but it touches the proven guest path and needs its own regression evidence.
2. **A separate Business runner. Selected** (approved). A later extraction needs its own approval and regression evidence.

## Decision

### 1. The Business-side availability policy

| Aspect | Public (ADR-0013, ADR-0016, ADR-0026) | Business-side (this ADR) |
|---|---|---|
| Business status | `ACTIVE` only | `ACTIVE` only for options and mutations; calendar reads do not call availability |
| Minimum notice | two hours, inclusive | none: the earliest start is `now`, inclusive |
| Horizon | 30 Business-local dates | none: a start up to 2100-12-31 (a technical bound, not a booking horizon); one availability read covers at most 31 dates |
| Past | excluded by the notice | a new start must satisfy `!start.isBefore(now)` |
| Grid | 15 minutes on the Business-local wall clock | unchanged |
| Duration | the sum of the current Service durations | create: the sum of the locked current Service durations, which becomes the stored snapshot; reschedule and reassign: the stored `duration_minutes`, never the current Services |
| Services | active, with StaffMember assignment as an intersection | create: active (locked); reschedule and reassign: not checked (ADR-0028 section 6) |
| StaffMember | active and assigned, or "no preference" | explicit; active; assignment as in ADR-0028 section 6 |
| Busy time | every `CONFIRMED` interval | every `CONFIRMED` interval **except** the one Appointment being rescheduled |
| Working periods, exceptions, closures, DST | ADR-0013 | unchanged |
| Clock | read once inside the availability call | read **once by the mutation attempt** and passed in |

**Parameterisation.** The pure engine request gains the minimum notice and the date window as inputs. The public path passes exactly the former constants (two hours, today through today + 29), so its results are unchanged; the existing engine, DST, and availability tests must pass without modification, and a regression test pins the equivalence. The Business path passes zero notice and the requested window. `AvailabilityQuery` gains a Business-side entry that takes the Business, the occupied duration, the StaffMember, the date window, the injected `now`, and an optional ignored Appointment identifier, and that does not look up Services (the application layer has already proven Service and assignment eligibility under locks). It joins the caller's REPEATABLE READ transaction and fails before any read otherwise. `BusyIntervalSource` gains a second abstract method taking the ignored identifier (a defaulted method was rejected in ADR-0016 because a forgotten implementation would hide booked time); the implementation adds `AND id <> :ignored` to its existing single bulk statement. A narrow Workforce read returns an active StaffMember's weekly periods by identifier, independent of Service assignments (a time-only move must not depend on an assignment that was later removed).

**Rules for time.**
- The API accepts only instants with offsets. The interface offers only starts returned by a Business-side availability read; the server never converts a bare wall-clock time.
- A time inside a daylight-saving gap has no instant, is never offered, and cannot be submitted. A repeated time is two distinct instants with distinct offsets, labelled as in the public flow, and each is judged by its own instant (the first occurrence may be past while the second is not).
- A proposed start is acceptable iff it is a start the Business-side calculation offers for its Business-local date and `!start.isBefore(now)`. An existing Appointment is modifiable iff `start_at.isAfter(now)`: an Appointment that starts exactly now has started, while an engine candidate at exactly `now` is allowed because the engine's boundary is inclusive (ADR-0013).
- "Today" and the day boundaries come from the Business timezone read in the same snapshot. The mutation attempt reads the injected clock once.

### 2. Transaction ownership

- **Create and reschedule/reassign** are entered through a Booking-owned `AppointmentAdministration` contract implemented by a non-transactional `BusinessAppointmentAdministrationService`. Like `GuestBookingService`, it rejects an active caller transaction or synchronization before any work, then runs each attempt in a new `TransactionTemplate` transaction (`REQUIRED`, `REPEATABLE_READ`, read-write), up to three attempts, immediately, each completely new. `REQUIRES_NEW`, savepoints, and a defaulted `@Transactional` on the runner are forbidden, as `BookingModuleBoundaryTests` already enforces for the guest runner; a parallel test pins the Business runner.
- **Cancel and note edit** run in one short transaction in the default isolation (READ COMMITTED) opened by the service, exactly as `CustomerAdministrationService` does. They use no availability, no revision guard, and no retry loop.
- Reads (calendar, agenda, history, detail) run in a read-only REPEATABLE READ transaction so appointments, lines, and Customer names come from one snapshot.

### 3. The total lock order

Every path takes a subset of these levels in ascending order and never goes back. Rows of one level are locked by one ordered statement.

| Level | Row | Mode | Taken by |
|---|---|---|---|
| L1 | Business lifecycle | `FOR SHARE` (`UPDATE` by lifecycle and profile mutations) | guest booking, create, reschedule, cancel, note, every administration mutation |
| L2 | the user's Membership | `FOR SHARE` | every Business-side operation; not the guest booking |
| L3 | StaffMember rows | `FOR SHARE` (`UPDATE` by profile, activity, and assignment mutations) | guest booking, create, reschedule, reassign, schedule and exception mutations |
| L4 | Business schedule revision | `FOR SHARE` (`UPDATE` by schedule mutations) | guest booking, create, reschedule, reassign |
| L5 | Service rows | `FOR SHARE` (`UPDATE` by Service administration) | guest booking, create |
| L6 | Customer row | implicit `KEY SHARE` through the foreign key at insert (`UPDATE` by Customer administration, which does not conflict with it) | create |
| L7 | the Appointment row and its exclusion-index entry | exclusive: `INSERT` or conditional `UPDATE` | guest booking, create, reschedule, reassign, cancel, note |

Reschedule and reassign take no L5 lock because they check no Service (ADR-0028 section 6). Cancel and note edit take only L1, L2, and L7. No operation upgrades a shared lock on the same row, and every operation holds at most one Appointment row exclusively. The existing mutations already respect this order (ADR-0025 and the Service, assignment, schedule, and Customer administration paths). **This is an ordering argument, not a proof that no deadlock can occur**: see the residual risk in section 8.

### 4. The statement order of each operation

**Create** (REPEATABLE READ attempt):
1. Lock the Business lifecycle `FOR SHARE` (the first statement; it fixes the snapshot). Missing: `ACCESS_DENIED`; `DRAFT`: `BUSINESS_NOT_ACTIVE`; `SUSPENDED`: `BUSINESS_SUSPENDED`.
2. Lock and authorize the owner Membership `FOR SHARE`.
3. Lock the target StaffMember `FOR SHARE` through the set-based `lockEligibleForBooking` (active and assigned to every selected Service): empty is `APPOINTMENT_STAFF_UNAVAILABLE`.
4. Lock the schedule revision `FOR SHARE` (`ScheduleRevisionGuard.lockShared`).
5. Lock all selected Services `FOR SHARE` in one statement; a missing, foreign, or inactive Service is `APPOINTMENT_SERVICE_UNAVAILABLE`; then compare the reviewed duration and price of each: any difference is `APPOINTMENT_REVIEW_CHANGED`, before availability and every write.
6. Read the Customer reference (plain read): empty is `CUSTOMER_NOT_FOUND`.
7. Business-side availability for the start's Business-local date, with the summed locked duration, the target StaffMember, the single `now`, no ignored Appointment: the start must be offered, else `APPOINTMENT_SLOT_UNAVAILABLE`.
8. Insert the Appointment (source `MANUAL`, `internal_note`, no `customer_note`, no idempotency columns, a fresh public reference) and its lines; read it back in the same transaction.

**Reschedule and reassign** (REPEATABLE READ attempt):
1. and 2. as above.
3. **Read the Appointment** (plain, unlocked read inside the snapshot) and its lines. Missing or foreign: `APPOINTMENT_NOT_FOUND`. `version` differs from `expectedVersion`: `APPOINTMENT_CONCURRENT_UPDATE`. Not `CONFIRMED`, or not `start_at.isAfter(now)`: `APPOINTMENT_STATE_CONFLICT`. Neither the start nor the StaffMember differs from the request: a validation error.
4. Lock the target StaffMember `FOR SHARE`: for a **reassignment** through `lockEligibleForBooking` over the stored Service identifiers (active and assigned to every stored Service); for a **time-only move** through `StaffMemberReferenceAccess.lockReference` (the current StaffMember must still exist and be active, with no assignment check). Failure: `APPOINTMENT_STAFF_UNAVAILABLE`. **[Superseded for the implementation of the time-only lock by the Phase 5 note below: it is the new sanitized `StaffBookingAccess.lockActiveForBooking`, not `StaffMemberReferenceAccess.lockReference`.]**
5. Lock the schedule revision `FOR SHARE`. No Service lock.
6. Business-side availability with the **stored** `duration_minutes`, the target StaffMember, the single `now`, and **this Appointment's identifier as the only ignored one**. The requested start must be offered, else `APPOINTMENT_SLOT_UNAVAILABLE`.
7. Conditional update: `UPDATE appointment SET start_at, end_at, occupied_until, staff_member_id, staff_display_name (refreshed from the locked target only for a reassignment), version = version + 1, updated_at = :now WHERE business_id = :b AND id = :id AND version = :expected AND status = 'CONFIRMED' AND staff_member_id = :readStaff AND start_at = :readStart`, where `end_at = start_at + duration` and `occupied_until = end_at`. The exclusion constraint checks the new tuple.

**Cancel** (default isolation): lock the Business lifecycle `FOR SHARE`; lock and authorize the Membership `FOR SHARE`; read `now` once; `UPDATE appointment SET status = 'CANCELLED', cancelled_at = :now, cancelled_by = 'BUSINESS', version = version + 1, updated_at = :now WHERE business_id AND id AND version = :expected AND status = 'CONFIRMED' AND start_at > :now`. Zero rows: read the row in the same transaction and classify, in this order: missing (`APPOINTMENT_NOT_FOUND`), version differs (`APPOINTMENT_CONCURRENT_UPDATE`), otherwise (`APPOINTMENT_STATE_CONFLICT`: already cancelled or started).

**Internal-note edit** (default isolation): the same two locks, then `UPDATE appointment SET internal_note = :note, version = version + 1, updated_at = :now WHERE business_id AND id AND version = :expected` (no status or time predicate: the note is editable on any Appointment). Zero rows: missing or version differs. An unchanged note is a no-op success that does not advance the version (a Phase 6 detail).

### 5. How the version and the snapshot interact

PostgreSQL fixes the snapshot at the first statement of a REPEATABLE READ attempt, and the plain Appointment read in step 3 is consistent with it. A conditional `UPDATE` of a row that another transaction changed and committed after that snapshot fails with `40001` (`could not serialize access due to concurrent update`) instead of matching zero rows (inference, documented PostgreSQL behavior; proven by the Phase 2 store test). The attempt ends, and the next attempt re-reads the Appointment: its version no longer equals `expectedVersion`, so the result is deterministically `APPOINTMENT_CONCURRENT_UPDATE` and is never retried again. An update that matches zero rows without a `40001` cannot happen inside the snapshot; if it does, it is treated as an internal inconsistency and ends in the generic safe failure.

### 6. Ignoring only the Appointment being moved

The busy-window statement excludes exactly the supplied identifier. Every other Appointment of the StaffMember, including adjacent and overlapping ones, stays busy. The exclusion constraint on `UPDATE` compares the new tuple with every other live tuple and, as PostgreSQL's exclusion check skips the row's own old version, does not treat the row as conflicting with itself (inference, proven by the Phase 2 test that shifts an Appointment by 15 minutes inside its own interval).

### 7. Concurrent scenarios

| Scenario | What happens | Typed result |
|---|---|---|
| Two simultaneous moves of **one** Appointment | The second conditional `UPDATE` waits for the first's row lock, then fails with `40001`; its retry re-reads a newer version | winner succeeds; loser `APPOINTMENT_CONCURRENT_UPDATE` |
| Reassign **A→B** and **B→A** (two Appointments) | Each takes shared locks only on its **target** StaffMember; shared locks never block each other, so there is no reversed pair of exclusive locks. If the two intervals overlap, each attempt's availability sees the other's still-committed old interval as busy and is refused; if they do not overlap, nothing waits | `APPOINTMENT_SLOT_UNAVAILABLE` or both succeed |
| Move versus create into the same StaffMember interval | The exclusion constraint arbitrates; the loser gets `23P01`, retries, and its availability now sees the winner | loser `APPOINTMENT_SLOT_UNAVAILABLE`; no partial row |
| Move versus guest booking | Same, with the guest result mapped by ADR-0023 | same |
| Cancel versus move of one Appointment | The cancel's conditional `UPDATE` and the move's serialize on the row; the later one finds a newer version or a changed status | `APPOINTMENT_CONCURRENT_UPDATE` or `APPOINTMENT_STATE_CONFLICT` |
| Cancel versus cancel (or, later, a Customer cancellation, #35) | Exactly one matches the predicate `status = 'CONFIRMED'`; the other reads the new state | the loser `APPOINTMENT_STATE_CONFLICT`; the winner's attribution is kept |
| Cancel versus create into the freed interval | The create's snapshot still sees the Appointment as busy | create `APPOINTMENT_SLOT_UNAVAILABLE`; the owner repeats it |
| Note edit versus move | Serialized on the row by the version | the loser `APPOINTMENT_CONCURRENT_UPDATE` |
| Schedule or exception change versus create or reschedule | The revision guard: a change committed after the snapshot fails the shared lock with `40001`; an in-flight change makes the attempt wait | the retry validates against the new schedule |
| StaffMember deactivation, assignment change, Service change, Business suspension or timezone change versus create or reschedule | The shared L1, L3, and L5 locks conflict with the administration `UPDATE`; a change after the snapshot fails the lock with `40001` | the retry sees the new state (`APPOINTMENT_STAFF_UNAVAILABLE`, `APPOINTMENT_SERVICE_UNAVAILABLE`, `BUSINESS_SUSPENDED`) |
| Customer contact update versus create | The foreign-key `KEY SHARE` does not conflict with a non-key update | no wait |

### 8. Failures, retries, and the residual deadlock risk

| Source | SQLState | Typed failure | Retried (create, reschedule, reassign) | Result |
|---|---|---|---|---|
| Lock of a StaffMember, revision, or Service row changed after the snapshot; conditional `UPDATE` of a changed row | `40001` | concurrency conflict | yes, in a new transaction | after the retry's re-read, `APPOINTMENT_CONCURRENT_UPDATE` or the new eligibility result; exhaustion: `APPOINTMENT_TEMPORARILY_UNAVAILABLE` |
| Deadlock (including the exclusion-index case below) | `40P01` | concurrency conflict | yes | exhaustion: `APPOINTMENT_TEMPORARILY_UNAVAILABLE` |
| Exclusion violation | `23P01` | overlap conflict | yes (the retry recomputes availability) | exhaustion after an overlap: `APPOINTMENT_SLOT_UNAVAILABLE` |
| Duplicate public reference (create) | `23505` | duplicate reference | yes, with a fresh reference | |
| Foreign-key, check, or other data violation | `23503`, `23514`, `22xxx` | invalid data or unknown reference | no | the generic safe `INTERNAL_ERROR`: unreachable behind validation and locks |
| Anything else | any | unexpected | no | the generic safe `INTERNAL_ERROR` |

Validation, authorization, lifecycle, not-found, version, state, eligibility, review-changed, and slot-not-offered results are deterministic and never retried. At most three attempts, immediate, with no backoff; every attempt is a distinct PostgreSQL transaction with its own snapshot. Cancel and note edit have no retry: a lock or serialization failure maps to `APPOINTMENT_CONCURRENT_UPDATE` and the interface reloads.

**Residual risk, stated plainly.** The ordered locks do not make the system deadlock-free. Two writers that insert or move into overlapping ranges for the same StaffMember each insert their index entry before either completes the exclusion check, and PostgreSQL then resolves the cycle with `40P01` after `deadlock_timeout` (one second by default), as ADR-0023 recorded for guest bookings. The intended behavior is: exactly one of the two commits, the victim's transaction rolls back completely, its retry in a new transaction sees the winner and ends as `APPOINTMENT_SLOT_UNAVAILABLE`, and a burst of contenders can exhaust three attempts and end as `APPOINTMENT_TEMPORARILY_UNAVAILABLE`. This is a latency and message-quality limitation, not an integrity failure. PostgreSQL may choose any participant of a cycle as the victim, so a test that needs a particular victim must fix it (the existing deterministic technique sets a long `deadlock_timeout` for the participant that must not be chosen). A contention optimization (a per-StaffMember lock) needs its own decision and measurement.

### 9. Commit uncertainty

For the REPEATABLE READ operations the classification of ADR-0023 and ADR-0024 is reused by a separate implementation in the Business runner, not by sharing the guest code: it follows the completion status that Spring reports to a synchronization registered in the attempt's own transaction, never the content of an exception. `COMMITTED` followed by a failed callback is `APPOINTMENT_OUTCOME_UNCERTAIN` and is never retried; `ROLLED_BACK` is a proven rollback (retried only for `40001` or `40P01`); `UNKNOWN` proves a rollback only through a server-reported `40001`, `40P01`, or `25P02`. After a normal completion one read outside any transaction verifies the outcome: for a create the Appointment exists; for a reschedule or reassign the row has `version = expectedVersion + 1` and the requested start and StaffMember (`version = expectedVersion` proves a silent rollback: `APPOINTMENT_TEMPORARILY_UNAVAILABLE`; any other state is `APPOINTMENT_OUTCOME_UNCERTAIN`, and the interface reloads). Cancel and note edit, being single conditional updates, have no separate classification: an unexpected failure is the generic safe error, and the interface treats every undocumented answer as uncertain and reloads the authoritative state.

### 10. No manual idempotency in the MVP (recorded limitation)

Manual creation has no operation identifier and no stored fingerprint, and no column is added for one. The frontend sends a create at most once per user action (a synchronous single-flight guard, `docs/ui-design-guidelines.md` section 21), never repeats it automatically after an uncertain result, reloads the authoritative calendar after any failure, and tells the owner to **verify whether the Appointment exists before trying again**. The exclusion constraint guarantees that a repeated identical create cannot produce a second `CONFIRMED` Appointment for the same StaffMember and interval; the only possible harm is a confusing `APPOINTMENT_SLOT_UNAVAILABLE` after a lost response. Reschedule, reassign, cancel, and note edit carry `expectedVersion`, so a repeat fails deterministically instead of repeating its effect.

### 11. Distinct results

| Result | HTTP | Meaning | Never confused with |
|---|---|---|---|
| `APPOINTMENT_CONCURRENT_UPDATE` | 409 | the Appointment's version differs from `expectedVersion`, directly or after a lost row race | a slot or state problem |
| `APPOINTMENT_STATE_CONFLICT` | 409 | the version matches but the state forbids the operation (already cancelled, or started) | a stale version |
| `APPOINTMENT_SLOT_UNAVAILABLE` | 409 | the requested start is not offered, is past, or lost the overlap race | a stale version |
| `APPOINTMENT_STAFF_UNAVAILABLE`, `APPOINTMENT_SERVICE_UNAVAILABLE`, `APPOINTMENT_REVIEW_CHANGED` | 409 | the eligibility or reviewed-terms rules of ADR-0028 | |
| `BUSINESS_NOT_ACTIVE`, `BUSINESS_SUSPENDED` | 409 | lifecycle | |
| `APPOINTMENT_NOT_FOUND`, `CUSTOMER_NOT_FOUND` | 404 | missing or foreign (identical) | |
| `APPOINTMENT_TEMPORARILY_UNAVAILABLE` | 503 | proven rollback, retries exhausted | |
| `APPOINTMENT_OUTCOME_UNCERTAIN` | 503 | the commit may have succeeded | |

The precedence is: authentication and authorization, lifecycle, existence, version, state, eligibility, reviewed terms, then the slot. A conflict response never reveals another Customer, a note, or an Appointment detail; the owner learns who occupies a time from the refreshed calendar, which is authorized data. The Bulgarian messages are Proposed until the rendered human review.

## Rationale

The Business-side policy keeps every availability rule and changes only the inputs that differ between a guest and the owner, so conflict detection has one implementation. The ordered shared locks, the schedule-revision guard, and the exclusion constraint are the guest booking's proven mechanisms; the only new coordination is the expected-version predicate on the single row that is updated, and the snapshot rule of PostgreSQL turns a concurrent change of that row into a retry whose re-read yields a deterministic, distinct stale-version result. Cancel and note edit need none of the availability machinery and keep the short-transaction pattern that every administration service already uses.

## Tradeoffs and disadvantages

- A second runner duplicates the transaction, retry, and classification logic of the guest runner until a separately evidenced extraction.
- The engine, the busy-source contract, and `AvailabilityQuery` change (a Strict cross-module change), and the busy-source stubs in tests must implement the second method.
- A time-only move validates the StaffMember's schedule but not the Service assignment, so the calendar can hold visits that current configuration could not create.
- The exclusion-index deadlock risk and its one-second latency remain; the owner may see `APPOINTMENT_TEMPORARILY_UNAVAILABLE` under a burst.
- A lost response to a create can show a misleading conflict; the owner must check the calendar.
- A note edit advances the version, so another open form for the same Appointment must reload.

## Risks and mitigations

- **Public availability regression through the engine change.** The public path passes the former constants; the existing engine, DST, availability, and statement-count tests run unchanged, plus an equivalence test.
- **Lock-order drift.** Lock-order observation with `FOR UPDATE SKIP LOCKED` probes per operation, as the guest tests do, and an ArchUnit-style source check that the Business runner uses the published locking contracts.
- **A missed write classification.** `AvailabilityMutationInventoryTests` fails on any unclassified `INSERT`, `UPDATE`, or `DELETE`; the new `UPDATE appointment` statements are classified as Appointment lifecycle writes behind the shared guard (reschedule, reassign) or as freeing time (cancel).
- **Wrong "past" decisions around daylight-saving time.** Tests derive the transitions from `ZoneRules`: a gap start, both occurrences of a repeated start, a start one second before and exactly at `now`, and an Appointment starting exactly at `now`.
- **Inference not holding.** The `40001` behavior of a conditional `UPDATE`, the absence of self-conflict, and the effect of the new CHECK ordering are proven by named Phase 2 tests before any dependent phase starts; if one fails, this ADR is revisited before implementation continues.

## Consequences

- `BusyIntervalSource` has a second abstract method, `AvailabilityQuery` a Business-side entry, the engine request carries the policy, and Workforce publishes a read by StaffMember identifier. `BookingBusyIntervalSource`, `AvailabilityQueryService`, `AvailabilityEngine`, their stubs, and `BusyIntervalSourceFailFastTests` change.
- `AppointmentStore` gains conditional reschedule, cancel, and note updates; its Javadoc statement that "every write is a single statement" is already amended by ADR-0027 and is amended again.
- `AvailabilityMutationInventoryTests` gains the new classifications; `BookingModuleBoundaryTests` gains the `identity`, `customer`, and `scheduling` read edges; `ScheduleRevisionModuleBoundaryTests` gains the Business runner as a second guard consumer; `CustomerModuleBoundaryTests` allows the new read contract.
- The error taxonomy and messages enter `docs/security.md`. The problem codes, route names, and Bulgarian wording are working names; their exact contracts are finalised and tested in the implementing HTTP or interface phase, and the wording stays Proposed until the rendered human review (Phase 11, an approval gate before Phase 12).
- `docs/testing-strategy.md` records the mandatory evidence listed below.

## Evidence

Direct evidence: ADR-0013, ADR-0016, ADR-0020, ADR-0023, ADR-0024, ADR-0025, ADR-0027; `AvailabilityEngine` (the hard-coded notice and horizon), `AvailabilityQueryService` (the repeatable-read precondition, the current-Service duration, the single clock read, and the busy-window construction), `BookingAttemptProcedure` and `GuestBookingService` (the lock order, the entry guard, retry, and commit classification), `AppointmentStore` (no update exists), `BookingBusyIntervalSource`, `StaffBookingAccess`, `StaffMemberReferenceAccess`, `CustomerAdministrationService` (the short-transaction pattern); `AppointmentOverlapConcurrencyIntegrationTests`, `GuestBookingConcurrencyIntegrationTests`, `GuestBookingScheduleRaceIntegrationTests` (the latch, `pg_stat_activity`, and `pg_blocking_pids` technique and the observed exclusion-index deadlock); `AppointmentSchemaIntegrationTests` (the `CANCELLED → CONFIRMED` guard).

Inference, each to be proven by the named test: a conditional `UPDATE` of a row changed after a REPEATABLE READ snapshot fails with `40001`; the exclusion check on `UPDATE` ignores the row's own old tuple; a time-changing and a StaffMember-changing `UPDATE` are guarded by the constraint; shared locks on target StaffMembers cannot form a reversed pair; a cancel's wait on a move's row lock cannot form a cycle because the cancel holds only L1 and L2.

**Claims to be proven and where**

| Claim | Phase | Evidence |
|---|---|---|
| a time-changing and a StaffMember-changing `UPDATE` conflict with another live Appointment; a shift inside its own interval does not; a failed `UPDATE` leaves the row and every other row unchanged | 2 | store and schema tests with the existing latch harness |
| a conditional `UPDATE` after a concurrent commit fails with `40001` and the retry classifies it as stale | 2 and 5 | store test; service test with distinct transaction identifiers |
| only the moved Appointment is ignored; an adjacent or overlapping neighbor still blocks | 2 | availability integration test |
| the public availability results are unchanged by the engine parameters | 2 | existing suites unchanged plus an equivalence test |
| the lock order of each operation | 4, 5, 6 | `FOR UPDATE SKIP LOCKED` probes at each hook point |
| the pairwise scenarios of section 7, including both reassign orders | 4, 5, 6 | latches and lock-wait evidence, no sleeps |
| the residual exclusion-index deadlock is retried and leaves one winner and no partial data | 4 and 5 | a controlled victim, as in the guest tests |
| gap, repeated hour, past instant, instant at `now`, start of a started Appointment | 2, 4, 5 | derived from `ZoneRules` |
| commit-uncertainty classification | 4 and 5 | real `afterCommit` failures and injected statuses, labelled |

## Conditions for revisiting

Revisit for measured contention (a per-StaffMember lock), an operation-id idempotency design for manual creation (an operation identifier hashed and stored with a fingerprint of the stored facts, unique per Business, mismatch as a distinct 409, replay returning the current detail; it needs its own columns and constraint relaxation), shared extraction of the attempt runner, a Business-configurable horizon or notice, buffers, Manager or Staff access, Customer cancellation (#35), `COMPLETED`/`NO_SHOW`, restoring a cancelled Appointment, or drag-and-drop rescheduling.

## Implementation note (issue #19 Phase 2, 2026-10-14)

The decision above is unchanged. Phase 2 implemented the Business-side availability entry (`AvailabilityQuery.calculateForBusiness`, returning `BusinessAvailability`), the Workforce read (`StaffAvailabilityAccess.findActiveWithWeeklyPeriods`), the second busy-source method, the engine's notice and window inputs, and the conditional store updates, and proved three of the inferences recorded under Evidence on real PostgreSQL: a conditional `UPDATE` of a row changed after a repeatable-read snapshot fails with `40001` (while the same race at READ COMMITTED matches zero rows); the exclusion check does not conflict with the row's own old tuple (a shift inside its own interval succeeds), and a time-changing and a StaffMember-changing `UPDATE` are refused by the constraint with the row unchanged; and a CHECK is evaluated before the exclusion constraint. One refinement: under repeatable read, `40001` is raised for a concurrently changed row even without the version predicate; the predicate protects READ COMMITTED and a stale expected version without a race. The Business entry reads no clock (the caller supplies the one instant). The remaining claims of the table (lock order, the controlled exclusion-index deadlock, commit classification) belong to Phases 4 to 6. The details and limitations are in the Phase 2 record of [task 09a](../tasks/09a-business-calendar-and-appointment-management.md).

## Implementation note (issue #21 Phase 3, 2026-10-14)

The decision above is preserved. The read operations of ADR-0028 run as one read-only `REPEATABLE READ` transaction each (section 2 named it for reads). The first statement, the Business context read, fixes the snapshot, and every later statement (the owner check, the reference checks, the count, the rows, the Service lines, the Customer names or contact, and the working-time facts) joins it; the transaction test commits a change from another transaction at a chosen point between two statements and shows the read is unaffected, and the same test fails when the isolation is lowered to `READ COMMITTED`. The reads take no lock, write nothing, and add no guard consumer. The maximum statement counts are bounded and independent of the number of Appointments, Services, Customers, and StaffMembers (calendar 7, agenda 6 or 7 with a filter, detail 5, Customer history 6; fewer for an empty result). Details and limits are in the Phase 3 record of [task 09a](../tasks/09a-business-calendar-and-appointment-management.md).

## Implementation note (issue #19 Phase 4, 2026-10-15)

The decision above is preserved; its accepted text is not rewritten. Phase 4 implemented the create operation with a separate, non-transactional `BusinessAppointmentAdministrationService` (the entry guard before any clock read or statement, one new `REQUIRED`, `REPEATABLE_READ`, read-write transaction per attempt, at most three attempts, no backoff) and a separate attempt body; the guest runner is untouched. The statement order is the lock order of section 3: the Business schedule context (`FOR SHARE`, the first statement), the owner Membership (`FOR SHARE`), then, after the plain Customer reference read, the eligible StaffMember, the schedule revision, and all selected Services; the Customer foreign key takes its implicit `KEY SHARE` at the insert and the Appointment and its lines are written last. The Customer read is a plain read and not a lock level. After the locks: Service availability, StaffMember eligibility, the reviewed terms, **one clock read**, the Business-side availability for the Business-local date of the requested instant, the offered-slot check, the public reference generated immediately before persistence, the insert, and a read-your-write as the last statement of the transaction.

**Sanitized lock conflicts (refines sections 8 and 9; supersedes any reading of the runner as inspecting persistence exceptions).** The two locks whose owners did not yet publish a sanitized conflict now do: `BusinessScheduleContextAccess.lockScheduleContextSanitized` (module `business`) and `SelectedBusinessOwnerAccess.lockAndAuthorizeSanitized` (module `identity`) take the same `FOR SHARE` locks as `lockScheduleContext` and `lockAndAuthorize`, and report a PostgreSQL serialization failure or deadlock as a fixed, cause-free `ConcurrentConflict` and every other failure as a fixed, cause-free `Failure`; the owning module is the only place that reads an SQLState. The older methods are unchanged for their other callers. The Business runner and its attempt body import no `java.sql`, driver, Spring `dao`, or `jdbc` type and read no SQLState or cause chain (a boundary test pins it), and retry exactly the published sanitized conflicts of the Business schedule context, owner Membership, StaffMember, schedule revision, and Service locks, the Appointment persistence concurrency failure, a duplicate generated public reference, and an Appointment overlap. **Commit classification (refines section 9):** Spring's completion report is authoritative. `COMMITTED` followed by a failure is `APPOINTMENT_OUTCOME_UNCERTAIN` and is never retried; `ROLLED_BACK` was first recorded here as a proven rollback reported as `APPOINTMENT_TEMPORARILY_UNAVAILABLE` and not retried **(superseded by the correction note below: it is retried)**; `UNKNOWN` or no report is uncertain whatever its content. A normal completion receives one primary-key verification read outside any transaction: a missing row is a known rollback (`APPOINTMENT_TEMPORARILY_UNAVAILABLE`), a failed read is uncertain. A failure before the body returns that is not an approved conflict is the fixed `INTERNAL_ERROR`. Exhaustion after an overlap on the last attempt is `APPOINTMENT_SLOT_UNAVAILABLE`, any other exhaustion `APPOINTMENT_TEMPORARILY_UNAVAILABLE`. The proofs, with real versus injected evidence separated, are in the Phase 4 record of [task 09a](../tasks/09a-business-calendar-and-appointment-management.md).

## Correction note (issue #19 Phase 4 review, 2026-10-15)

The Phase 4 implementation note above first stated that a proven commit-phase rollback is not retried. That contradicted the bounded whole-transaction retry decided in sections 2 and 8, and it is corrected here; the earlier sentence is kept as the dated record of the first review. **Completion rule of the Business runner (sections 8 and 9, as implemented):** the completion status Spring reports for the attempt's own transaction is the only evidence, and no exception cause, SQLState, driver exception, or Spring `DataAccessException` is inspected.

| Body result and completion | Result | Retried |
|---|---|---|
| Rejection (any deterministic outcome) | the typed rejection | no |
| `Created`, normal return, row found by the verification read | created | n/a |
| `Created`, normal return, verification row absent | `APPOINTMENT_TEMPORARILY_UNAVAILABLE` (a known rollback) | no |
| `Created`, normal return, verification read fails | `APPOINTMENT_OUTCOME_UNCERTAIN` | no |
| `Created`, then a failure, completion `COMMITTED` | `APPOINTMENT_OUTCOME_UNCERTAIN` | never |
| `Created`, then a failure, completion `ROLLED_BACK` | a proven rollback: the complete operation runs again | yes, in a fresh `REPEATABLE_READ` transaction |
| `Created`, then a failure, completion `UNKNOWN` or no report | `APPOINTMENT_OUTCOME_UNCERTAIN` | never |
| Sanitized pre-commit conflicts, overlap | retried as before; three overlaps end `APPOINTMENT_SLOT_UNAVAILABLE` | yes |

**Why `ROLLED_BACK` is safe to retry:** Spring reports `ROLLED_BACK` only after it rolled the transaction back instead of issuing a commit, so the database holds none of that attempt's Appointment or Service lines, and a new attempt, with a fresh transaction identifier and snapshot, cannot duplicate anything. **Why `COMMITTED` is never retried:** the visit exists, and with no manual idempotency (section 10) a repeat could only collide with it or mislead the owner; the owner reloads and verifies. **Why `UNKNOWN` and no report are uncertain:** the commit operation itself failed or was never reported, so the server may have committed; only an independent report could prove otherwise, and none exists without reading an exception. **Why a normal completion whose verification row is absent is a known rollback without automatic retry:** PostgreSQL answers `COMMIT` of an aborted transaction with a silent `ROLLBACK` that the driver and JPA report as success (observed), so the single primary-key read outside any transaction is the evidence; it proves the absence but the transaction completed normally, which is a different, unexpected situation than a reported rollback, so it is reported and not repeated automatically. **Limit:** at most three complete transaction attempts in total; three proven rollbacks end `APPOINTMENT_TEMPORARILY_UNAVAILABLE` and a fourth attempt never occurs. **Neutral guard wording:** the entry guard failure shared with guest booking now reads "Booking orchestration requires no active caller transaction"; it stays internal, sanitized, and cause-free and is never exposed (HTTP maps it to the generic `INTERNAL_ERROR`).

## Implementation note (issue #19 Phase 5, 2026-10-16)

The decision above is preserved; its accepted text is not rewritten. Phase 5 implemented the reschedule and StaffMember reassignment on the same Business runner as the creation. `BusinessAppointmentAdministrationService` now serves both operations: the entry guard, the identity checks, at most three completely new `REPEATABLE_READ` transactions, the retry classification, and the completion-status-only commit classification of the Phase 4 correction note are one implementation, and each operation supplies only its attempt body (`BusinessAppointmentCreationProcedure`, `BusinessAppointmentRescheduleProcedure`). The guest runner is untouched.

**Statement order (section 4 as implemented).** The Business schedule context `FOR SHARE` (the first statement), the owner Membership `FOR SHARE`, the lifecycle and domain-bound decisions, the plain Appointment read with its lines, then **one clock read**, the pre-lock refusals of the pure domain (`AppointmentTransitions.rescheduleRefusal`: a stale version, a state that is not `CONFIRMED`, an Appointment that has started, a request that changes nothing), the target StaffMember `FOR SHARE`, the schedule revision `FOR SHARE`, the domain `reschedule` (which adds only the start-before-now decision), the Business-side availability for the stored duration that ignores only this Appointment, the conditional `UPDATE` carrying the expected version, and a read-your-write as the last statement of the body. No Service and no Customer row is locked or read. A successful attempt issues 16 statements whatever the number of Services. The start-before-now decision is made after the eligibility locks (and so after `APPOINTMENT_STAFF_UNAVAILABLE`) to keep the precedence of section 11: version, state, eligibility, then the slot.

**Deviation recorded: the time-only lock.** Section 4 names `StaffMemberReferenceAccess.lockReference` for a time-only move. That contract throws a cause-carrying `StaffMemberReferenceFailure`, which the Business runner must not inspect (Phase 4 note). Phase 5 therefore adds `StaffBookingAccess.lockActiveForBooking(businessId, staffMemberId)` to the existing sanitized contract: one `FOR SHARE` statement over an active StaffMember of the Business with no assignment check, a serialization failure or deadlock reported as the published `ConcurrentConflict`, anything else as the published `Failure`, and an empty result for a missing, foreign, or inactive StaffMember. A reassignment keeps `lockEligibleForBooking` over the stored Service identifiers. Naming the current StaffMember is a time-only move, not a reassignment.

**Request without a change.** A request that names neither `start` nor `staffMemberId`, or whose values equal the stored ones, is a validation error (`400 VALIDATION_ERROR`) judged after the version and the state, writes nothing, and never advances the version: the pinned behavior of "same values as before".

**Version race.** As recorded in the Phase 2 note and proven again through the real orchestration, a conditional `UPDATE` of a row changed after the snapshot fails with `40001`; the retry runs in a new transaction, re-reads the newer version, and ends `APPOINTMENT_CONCURRENT_UPDATE` (a cancellation or note edit that committed first gives the same result for the old expected version, and `APPOINTMENT_STATE_CONFLICT` for the new one). An update that matches no row inside the snapshot is treated as an internal inconsistency (`INTERNAL_ERROR`).

**Verification (section 9 as implemented).** After a normal completion one primary-key read outside any transaction decides: the row at the written version with the requested start and StaffMember is a confirmed success; the row still at the expected version proves a silent rollback (`APPOINTMENT_TEMPORARILY_UNAVAILABLE`, not retried, as for a creation whose row is absent); every other state, including a row that another transaction changed again before the read, one that cannot be found, or a failed read, is `APPOINTMENT_OUTCOME_UNCERTAIN`. A reported success therefore always means that the change was found in the database.

**Limits.** The clock instant must not precede the Appointment's creation (`updated_at` must not precede `created_at`): a skewed clock fails the attempt as the generic `INTERNAL_ERROR` rather than writing an inconsistent row. The proofs, with real versus injected evidence separated, are in the Phase 5 record of [task 09a](../tasks/09a-business-calendar-and-appointment-management.md).
