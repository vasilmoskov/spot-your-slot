# ADR-0016: Orchestrate availability through published contracts and a Scheduling-owned busy-interval seam

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-09-29
- **Recorded date:** 2026-09-29
- **Related issues:** #16
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

ADR-0013 fixes the pure availability semantics, ADR-0014 stores schedule
exceptions, and ADR-0015 administers them. Nothing yet connects the engine to the
committed Business, Service, StaffMember, recurring-schedule, and exception data.
Issue #16 Phase 4 needs that orchestration, the cross-module contracts it
requires, a place for occupied time that no module can supply yet, and a
transaction design, without a public HTTP endpoint.

## Constraints

- `scheduling` reads Business, Catalog, and Workforce only through narrow
  published interfaces and never touches their stores or records (ADR-0001).
- `scheduling` never depends on the future `booking` module, and Workforce cannot
  depend on `scheduling.domain` (`scheduling` already depends on Workforce).
- The engine is pure and its ADR-0013 semantics do not change.
- PostgreSQL is the source of truth; availability is a current view that reserves
  nothing (ADR-0002, issue #16).
- The fixed MVP policy is a 15-minute grid, a two-hour notice, 30 Business-local
  dates, zero buffers, and no breaks. Business configuration is a follow-up.
- No Appointment table or booking module exists.
- No public endpoint, controller, security rule, rate limiting, or frontend is
  added in this phase.

## Options considered

### Reuse `ServiceReferenceAccess` and `StaffMemberReferenceAccess`

The existing contracts avoid new interfaces. `ServiceReference` carries no
duration, and widening it changes every caller. `StaffMemberReferenceAccess`
takes a `FOR SHARE` lock, returns only the active flag, and requires a write
transaction, which a lock-free read view must not do. Rejected.

### A per-StaffMember Workforce query

Simple, but one query per StaffMember grows with team size. Rejected for one
bulk, joined read.

### Optional or defaulted busy-interval source

`Optional`, `ObjectProvider`, a default method, or `@ConditionalOnMissingBean`
would let a later Booking module be wired without its busy time, silently
double-offering booked slots. Rejected.

### Publish the internal engine records

Fewer records, but it would expose `scheduling.domain` to Booking and couple
callers to engine internals. Rejected for separate published records.

### One read-only repeatable-read orchestration transaction with required contracts

Selected.

## Decision

**Published entry point.** `scheduling.AvailabilityQuery.calculate(businessId,
serviceId, staffMemberIdOrNull)` returns an immutable `AvailabilitySnapshot`
(timezone, `calculatedAt`, occupied duration, slots ordered by start). A non-null
StaffMember requests exactly that member; null requests any eligible member. Each
`AvailabilitySlot` carries start, end, start offset, and naturally ordered
StaffMember IDs. The IDs are internal application information for future
deterministic assignment; this decision makes no public-exposure choice. The
injected `Clock` is read exactly once.

**Contracts.**

- Business: the existing `BusinessScheduleContextAccess.findScheduleContext`.
  A missing, DRAFT, or SUSPENDED Business is one `BusinessNotBookable` failure.
- Catalog: `ServiceAvailabilityAccess.findBookableService(businessId, serviceId)`
  returns `BookableService(id, duration)` only, empty for a missing, foreign, or
  inactive Service. The orchestrator verifies a whole number of minutes from 1
  through 480 before calling the engine.
- Workforce: `StaffAvailabilityAccess.findEligibleForService(businessId,
  serviceId)` runs one joined statement in `StaffAvailabilityReadStore` and
  returns active StaffMembers assigned to the Service with their recurring
  `WorkingPeriod` records, ordered by PostgreSQL `uuid`. A StaffMember without
  periods remains present with an empty list. It takes no lock.
- All three join the caller's transaction (`MANDATORY`) and are read-only.

**Failures.** A sealed `AvailabilityApplicationException` has
`BusinessNotBookable`, `ServiceNotBookable`, `StaffMemberNotEligible` (a missing,
inactive, foreign, or unassigned requested member are indistinguishable), and
`AvailabilityFailure`. Unexpected persistence or access failures, corrupt
published data, invalid busy-interval output, and impossible translation states
become a sanitized `AvailabilityFailure` with a fixed message. There is no HTTP
mapping. No eligible StaffMember and no slots are successful empty snapshots.

**Busy intervals.** `scheduling.BusyIntervalSource.findBusyWindows(businessId,
staffMemberIds, from, to)` is owned by `scheduling`. Windows are half-open,
ordered by start then end, keyed only by requested StaffMembers, and an absent
key means no busy time. A future implementation must join the caller's
transaction and answer all StaffMembers with one bulk query. The orchestrator
requires exactly one bean, rejects unrequested or null keys, null or unordered
windows, and any exception from the source. `scheduling.infrastructure.
NoBookingBusyIntervalSource` is a temporary placeholder that returns no windows
and performs no SQL. It is an ordinary required `@Component`. When a real
implementation appears there are two beans and startup fails until the
placeholder and its wiring test are deleted. The real implementation belongs to
the future Booking/Appointment issue, not to issue #16.

**Transaction and snapshot.** The method is `@Transactional(isolation =
REPEATABLE_READ, readOnly = true)` with the default `REQUIRED` propagation, so a
standalone call creates a repeatable-read, read-only transaction. `REQUIRED`
joins an existing transaction and Spring then silently ignores the declared
isolation, so the effective isolation is enforced as a precondition. Before the
clock or any published access or SQL call, the method inspects the current
transaction and accepts only an active transaction whose isolation is
repeatable-read or serializable. No transaction, an isolation level Spring does
not expose (a transaction started with the driver default), or a weaker level
fails with a fixed, sanitized `AvailabilityFailure`; no transaction detail is
exposed and there is no HTTP mapping. `REQUIRES_NEW` is rejected because future
Booking must be able to have Availability join its transaction, suspending the
caller would change that, and it would need a second pooled connection. Global
`validateExistingTransaction` is rejected because it alters unrelated behavior.
The three expected failures do not roll back a joined transaction.

PostgreSQL establishes the snapshot at the first statement of an accepted
transaction, and all committed database reads in the orchestration observe it.
The method performs no writes and takes no explicit Business, Service,
StaffMember, schedule, or exception lock, but a joined outer transaction may be
read-write; it is not claimed to be read-only. A future busy-interval source is
snapshot-consistent with these reads only if it joins the same accepted
transaction. The result may be stale as soon as it returns; appointment creation
owns its transaction, locking, and final conflict protection and must
revalidate.

**Windows and statements.** `today = LocalDate.ofInstant(calculatedAt, zone)`. The
date window is `today` through `today + 29`. The busy-interval window is
`[today.atStartOfDay(zone), today.plusDays(30).atStartOfDay(zone))` computed with
zone rules, never 30 x 24 hours, so a DST day is 23 or 25 hours. The application
issues four SQL statements: Business context, Service, eligible StaffMembers with
periods, and exceptions through the existing `findOverlappingForStaff`. The busy
call is a fifth bulk read that currently issues no SQL. The count does not grow
with the number of StaffMembers. With no eligible StaffMember the exception and
busy reads are skipped.

**Engine boundary.** Exceptions are translated by the unchanged
`ScheduleExceptionInputs`. The engine is called once with every eligible member
and remains the only owner of notice, horizon, grid, DST, overlap, segment,
deduplication, and ordering rules.

## Rationale

Narrow provider-owned contracts keep stores private and give each read exactly
the fields availability needs. One read-only snapshot gives a coherent view
without the cost and deadlock risk of locking a read. Making the busy source a
required bean turns a forgotten replacement into a startup failure rather than a
silent correctness defect.

## Tradeoffs and disadvantages

- Three contracts and separate published records add types and mapping.
- Listing every eligible StaffMember and filtering a specific one in Scheduling
  reads a small amount of extra data for a specific request.
- A future busy-interval source must join the transaction to be consistent; the
  interface can state this but not enforce it.
- The placeholder makes the current view ignore all appointments until removed.
- Workforce orders by PostgreSQL `uuid` (bytewise) while slot IDs use Java
  `UUID` order; only the latter is a published guarantee.
- A caller that already holds a transaction must start it repeatable-read or
  serializable, or the call fails; a driver-default transaction is rejected even
  if the database default happens to be repeatable-read.

## Risks and mitigations

- **Silent busy-time loss:** the placeholder is a required bean, the wiring test
  and fail-fast test document and prove the behavior, and its Javadoc and this
  record name the removal.
- **Cross-tenant leakage:** every read is Business-scoped, each exception's
  Business is checked, and tests cover Business A/B isolation.
- **Incoherent joined snapshot:** the enforced isolation precondition and
  integration tests with explicit read-committed, default, repeatable-read, and
  serializable outer transactions.
- **N+1 queries:** a test counts application SQL for one and ten StaffMembers.
- **DST regressions:** tests derive transitions from `ZoneRules`.

## Consequences

Future public and booking work adapt `AvailabilityQuery` and supply the real
`BusyIntervalSource`. A public adapter maps the four failures and decides
StaffMember exposure. The current view is not a reservation.

## Evidence

Direct evidence: issue #16; ADR-0001, ADR-0002, ADR-0009, ADR-0012, ADR-0013,
ADR-0014, ADR-0015; `AvailabilityQueryServiceTests`,
`AvailabilityQueryServiceTimeTests`, `AvailabilityQueryServiceIntegrationTests`,
`AvailabilityQueryStatementCountIntegrationTests` (including the transaction-boundary
cases), `BusyIntervalSourceFailFastTests`,
`BusyIntervalSourceWiringIntegrationTests`, and `AvailabilityModuleBoundaryTests`.

## Conditions for revisiting

Revisit for Business-configurable horizon, notice, grid, or buffers; breaks; a
public availability endpoint; a real Appointment source; a measured need to
lock or serialize availability against booking; or team sizes where listing
every eligible StaffMember becomes material.

## Amendment note (issue #18, 2026-10-06)

The decision above is preserved. Issue #18 supplies the real `BusyIntervalSource` in the new `booking` module
(Phase 2), which also deletes `NoBookingBusyIntervalSource` and its wiring test as this ADR requires. The
booking transaction is repeatable-read and read-write and joins `AvailabilityQuery` (ADR-0023). A public
availability endpoint is introduced by ADR-0026 as a narrow adapter that maps the four failures and decides
StaffMember exposure (no StaffMember identifier is attached to a slot). **Implemented in issue #18 Phase 2:**
`BookingBusyIntervalSource` replaced the placeholder, `NoBookingBusyIntervalSource` and its placeholder-only
tests were deleted, and the required-bean fail-fast tests now use test stubs. The statement "There is no public
availability endpoint" remains true until the public API phase. **Phase 3:** `AvailabilityQuery` is unchanged;
the Business schedule revision (ADR-0025) coordinates schedule mutations with the future booking transaction
without touching the availability calculation, its four reads, or its transaction requirement.
