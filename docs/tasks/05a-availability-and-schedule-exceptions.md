# SpotYourSlot — Availability, Schedule Exceptions, and Slot Calculation

Status: In progress — Phases 1–4 implemented; Phase 4 awaiting review
GitHub issue: #16 — Build availability, schedule exceptions, and slot calculation engine
Depends on: #11, #12, #13
Decision records: [ADR-0013](../decisions/ADR-0013-define-availability-interval-precedence-grid-and-dst-semantics.md),
[ADR-0014](../decisions/ADR-0014-store-schedule-exceptions-as-versioned-aggregates-with-same-kind-date-exclusion.md),
[ADR-0015](../decisions/ADR-0015-administer-schedule-exceptions-through-a-versioned-business-owner-api.md),
[ADR-0016](../decisions/ADR-0016-orchestrate-availability-through-published-contracts-and-a-scheduling-owned-busy-interval-seam.md)

## Task purpose

Calculate the appointment start times a Business can genuinely offer and let a
Business owner maintain temporary schedule changes. Issue #16 is Strict risk.
Each phase needs separate explicit approval before persistent changes.

## Approved architecture

A new `scheduling` module owns availability calculation, Business closures,
StaffMember time off, working-day overrides, and additional working periods.
Later orchestration may depend only on narrow published contracts from
Business, Catalog, and Workforce. `scheduling` never depends on `booking`; the
`BusyIntervalSource` interface is owned by `scheduling` and the real
implementation belongs to the future Booking/Appointment issue. No `booking`
module or appointment code exists yet; a temporary placeholder supplies no busy
time (Phase 4).

## Approved semantics

Recorded in ADR-0013: effective working periods, blocking precedence,
half-open intervals, unmerged segments, the wall-clock 15-minute grid,
minimum notice, the 30-date horizon, DST gap and overlap resolution, and
per-StaffMember calculation with deterministic aggregation.

Fixed MVP policy values: 15-minute grid, two-hour minimum notice inclusive,
Business-local dates today through today + 29, zero buffers.

## Phases

| Phase | Outcome | Status |
|---|---|---|
| 1 | Task record, ADR-0013, `scheduling` skeleton, pure engine, unit tests | Implemented; awaiting review |
| 2 | Exception schema and persistence (V9, ADR-0014) | Implemented; awaiting review |
| 3 | Exception administration API (ADR-0015) | Implemented; awaiting review |
| 4 | Availability orchestration and published contracts (ADR-0016) | Implemented; awaiting review |
| 5 | Business-owner interface | Not started; separate approval |
| 6 | Documentation and acceptance | Not started |

Phase numbering follows the approved Phase 1 direction (persistence is Phase 2)
and supersedes the earlier orientation numbering that placed Business settings
first. Business-configurable horizon and minimum notice are a recorded
follow-up and need their own approval.

## Phase 1 scope

Delivered: `bg.spotyourslot.scheduling` and `scheduling.domain`, the pure
`AvailabilityEngine`, immutable input and output records, and JUnit tests.

Explicitly excluded: Flyway migrations, persistence, controllers or any HTTP
contract, frontend, dependencies, changes to Business, Catalog, Workforce, or
Identity code, appointments, a public availability endpoint, Business
configuration of horizon, notice, or buffers, and any GitHub mutation.

## Domain types (internal to `scheduling.domain`)

- `AvailabilityEngine` — `calculate(request)` aggregates every StaffMember;
  `calculateStarts(request, staff)` calculates one.
- `AvailabilityRequest` — zone, occupied duration, `now`, Business closures,
  eligible StaffMembers.
- `StaffAvailabilityInput` — recurring periods, overrides, additional periods,
  time off, busy intervals.
- `LocalPeriod` (whole-minute precision and start before end are enforced by the
  value object), `LocalBlock` (`FullDays`, `PartialDay`), `BusyInterval`.
- `AvailableSlot` — start, end, start offset, StaffMember IDs.
- `AvailabilityPolicy` — fixed MVP constants; `DayTimeline` is package-private.

Phase 1 published nothing outside the module; Phase 4 adds the published
contract described below.

## Phase 2 scope

Delivered: `V9__add_schedule_exceptions.sql`, ADR-0014, immutable stored-aggregate
records and the pure `ScheduleExceptionInputs` translator in `scheduling.domain`,
the internal `ScheduleExceptionStore` in `scheduling.infrastructure`, and
PostgreSQL schema, store, and concurrency tests. Explicitly excluded:
administration service, authorization, controllers, frontend, availability
orchestration, `BusyIntervalSource`, and Business configuration.

## Phase 3 scope

Delivered: the private Business-owner API under
`/api/business/schedule-exceptions` (list by inclusive date window, get, create,
atomic replace with `expectedVersion`, and hard delete with `expectedVersion`)
for the four exception kinds; the `scheduling` published
`ScheduleExceptionAdministration`; bounded application validation; strict
`yyyy-MM-dd` and `HH:mm` JSON parsing; the narrow published
`workforce.StaffMemberReferenceAccess`; `DELETE` in the credentialed CORS
methods; and the tests listed in `docs/testing-strategy.md`.

Final validation limits (fixed MVP technical safety boundary, unrelated to the
booking horizon): dates `2000-01-01` through `2100-12-31`, at most 366 dates for
a full-day span, at most 24 periods, and at most 93 dates in a list window.

Lock order: Business lifecycle row, exact owner Membership row, StaffMember row
(StaffMember-scoped kinds), then the conditional aggregate statement. Inactive
StaffMembers are read-only, so deleting their exception requires temporary
reactivation. The list is unpaginated and assumes the small-Business MVP; that
is a revisit condition if StaffMember volume or response size becomes material.

Explicitly excluded: availability orchestration, a public availability endpoint,
`BusyIntervalSource`, appointments, frontend, Business-configurable notice,
horizon, grid, or buffers, and any migration.

## Phase 4 scope

Delivered (ADR-0016): an internal published contract
`scheduling.AvailabilityQuery.calculate(businessId, serviceId,
staffMemberIdOrNull)` returning an immutable `AvailabilitySnapshot` (timezone,
`calculatedAt`, occupied duration, slots with start, end, start offset, and
naturally ordered StaffMember IDs); the published
`catalog.ServiceAvailabilityAccess` and `workforce.StaffAvailabilityAccess`
(one joined query); the reused `BusinessScheduleContextAccess`; the sealed
`AvailabilityApplicationException` family (`BusinessNotBookable`,
`ServiceNotBookable`, `StaffMemberNotEligible`, `AvailabilityFailure`); the
Scheduling-owned `BusyIntervalSource`; and the temporary
`NoBookingBusyIntervalSource`.

The orchestration creates a read-only repeatable-read transaction when called
standalone; inside an existing transaction it requires that transaction to be
repeatable-read or serializable and otherwise fails before any read. It performs
no writes or explicit locks (a joined outer transaction may be read-write), reads
the clock once, and issues four application SQL statements (Business,
Service, eligible StaffMembers with periods, exceptions) regardless of team
size; the busy call is a bulk read that currently issues none. The date window is
today through today + 29 and the busy-interval window uses zone-rule midnights.
The engine and `ScheduleExceptionInputs` are unchanged.

`NoBookingBusyIntervalSource` is a required ordinary bean. The real
implementation belongs to the future Booking/Appointment issue, not to issue #16;
when it is added, two beans make startup fail until the placeholder and its
wiring test are deleted. The real implementation must join the caller's
transaction and answer all StaffMembers with one bulk query.

Availability is a current view that reserves nothing. Booking must revalidate.
DRAFT and SUSPENDED Businesses are not bookable through this contract.

Explicitly excluded: any controller, public DTO, security rule, slug lookup, rate
limiting, CORS or CSRF change, frontend, booking, Appointments, migrations,
dependencies, and Business-configurable horizon, notice, grid, or buffers. No
public availability HTTP endpoint exists; a future public-profile/booking phase
adapts the contract.

## Acceptance evidence (Phase 1)

Unit tests in `backend/src/test/java/bg/spotyourslot/scheduling/domain/` cover
the approved matrix. Exact counts and results are in the Phase 1 report.
`ModuleBoundaryTests` verifies the module graph remains acyclic.

## Later-phase constraints

Persistence must not add a generic exclusion constraint prohibiting every
overlap between exception rows of one Business, StaffMember, and date. An
override or additional period legitimately overlaps a closure or time off.
Only semantically conflicting rows of the same effect and scope may be
constrained. Exception administration, owner authorization, versioning, and
lock ordering follow ADR-0012 conventions and are decided in ADR-0015.

## Follow-ups

- Business-configurable booking horizon and minimum notice.
- Configurable Service or StaffMember buffers.
- Owner warning when an exception affects a confirmed future appointment.
- Public availability endpoint, rate limiting, and the StaffMember-exposure
  decision (future public-profile/booking phase).
- Replace `NoBookingBusyIntervalSource` with the real Booking implementation.
