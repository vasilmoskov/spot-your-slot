# SpotYourSlot — Recurring Staff Working Schedules Backend

Status: Completed — Phases 1 through 5
GitHub issue: #13 — Build recurring staff working schedules backend
Parent issue: #10 — Add business services, staff, and working schedules
Depends on: #11 — Business services backend; #12 — Staff management and
Service assignments backend

## Task purpose

Issue #13 adds the backend domain, persistence, authorization, validation,
concurrency, and authenticated API for recurring weekly StaffMember working
schedules. These schedules are future availability inputs. This task does not
calculate availability or booking slots.

The work remains inside the existing `workforce` module. The future
`scheduling` module will interpret these local weekly periods together with the
authoritative Business timezone.

## Approved scope

An authorized Business owner can:

- retrieve a StaffMember's recurring weekly schedule;
- atomically replace the complete desired week;
- configure zero or more periods on each weekday;
- configure split working days;
- clear one weekday by omitting its periods; and
- clear the complete week with an empty desired set.

The authenticated endpoint contract is:

- `GET /api/business/staff-members/{staffMemberId}/working-schedule`; and
- `PUT /api/business/staff-members/{staffMemberId}/working-schedule`.

The PUT request contains only `expectedVersion` and the complete desired
period list. It does not accept Business identity or timezone. The response
exposes StaffMember ID, authoritative Business timezone, ordered periods,
independent schedule version, creation time, and update time. It does not
expose Business, user, Membership, role, session, normalized, or persistence
state.

## Explicit exclusions

Issue #13 does not add:

- availability or slot calculation;
- DST slot resolution;
- exceptional dates, holidays, leave, time off, working overrides, or breaks;
- Appointments, booking, Customers, public schedules, or Service buffers;
- frontend configuration or browser E2E;
- StaffMember accounts, invitations, Membership linkage, or new roles;
- rooms, equipment, capacity, or other resources;
- historical schedule versions or audit history; or
- production deployment.

## Approved schedule model

Every StaffMember owns exactly one `staff_working_schedule` aggregate under the
same immutable Business. The aggregate has:

- `businessId` internally;
- `staffMemberId`;
- one independent nonnegative optimistic version;
- UTC creation and update timestamps; and
- zero or more child working periods.

V7 creates an empty version-0 aggregate for every existing StaffMember.
Backfilled creation and update timestamps both use the StaffMember creation
instant. `StaffMemberAdministrationService.create` inserts the new
StaffMember's empty schedule inside the same StaffMember creation transaction.

The schedule version is independent of the StaffMember aggregate version.
StaffMember profile, lifecycle, and Service-assignment changes do not increment
the schedule version. Every accepted complete schedule replacement increments
the schedule version exactly once, including an identical replacement.

## Working-period rules

Each period contains:

- ISO weekday `1` through `7` in PostgreSQL;
- local start time; and
- local end time.

The HTTP API uses `MONDAY` through `SUNDAY` and canonical `HH:mm` times.
Accepted local clock values have one-minute precision from `00:00` through
`23:59`. PostgreSQL stores `time without time zone`; the Business timezone is
authoritative and is not duplicated in schedule tables.

PostgreSQL's special `24:00:00` value is rejected. `23:59` is only the latest
representable boundary and does not represent the rest of the final minute.
Exact-midnight closing is outside the MVP representation. Supporting it later
requires an explicitly approved boundary/domain and API extension compatible
with the Java time model.

Start must be before end. Equal bounds and start-after-end periods are invalid;
the latter is the unsupported overnight shape. Periods on one weekday cannot
overlap. Adjacent half-open periods are valid. Responses order periods by ISO
weekday, start time, then end time.

One request may contain at most 100 periods. This is an application contract;
the database protects durable range, ownership, duplicate, and overlap
invariants.

## V7 schema contract

V7 is the only new migration. V1 through V6 remain byte-for-byte unchanged.

`staff_working_schedule` uses `(business_id, staff_member_id)` as its primary
key and has a restrictive composite foreign key to the owning StaffMember. It
stores version and aggregate timestamps. A backfill inserts one row for each
existing StaffMember.

`staff_working_period` stores the same Business and StaffMember ownership,
weekday, local start, local end, and an internal generated `int4range`.
The range converts each approved local time to integer minutes after midnight
and uses canonical `[start,end)` bounds. This preserves minute precision and
allows adjacency.

The period primary key rejects exact duplicate rows. Checks enforce weekday
bounds, minute precision, rejection of the special `24:00:00` value, and start
before end. A restrictive composite foreign key targets the schedule. A GiST
exclusion constraint rejects overlapping minute ranges only when Business,
StaffMember, and weekday are all equal.

V7 explicitly installs PostgreSQL's supplied trusted `btree_gist` extension.
It provides the UUID and `smallint` equality operator classes required beside
the built-in `int4range` overlap operator in the multicolumn GiST exclusion.

Phase 1 tests prove on pinned PostgreSQL 18.4:

- V1 through V7 migrate from empty;
- a schema held at V6 upgrades through V7 with all StaffMembers backfilled;
- empty schedules use version 0 and StaffMember-derived timestamps;
- split and multiple-weekday periods persist in deterministic query order;
- generated ranges preserve exact minute bounds;
- `23:58` through `23:59` is accepted with exact integer-minute bounds;
- `23:00` through `24:00` and any start or end boundary using `24:00` are
  rejected;
- adjacent periods are accepted;
- duplicates, partial overlap, containment, equality, overnight periods,
  sub-minute values, and invalid weekdays are rejected;
- overlap remains scoped by Business, StaffMember, and weekday;
- composite ownership and restrictive deletion are enforced;
- the extension, GiST operator classes, exclusion constraint, columns, checks,
  and indexes match the approved schema; and
- no exception, time-off, break, override, availability, booking, Customer,
  Appointment, or account-linkage table or column is introduced.

## Authorization and lifecycle contract

Every operation derives user and selected Business from the authenticated
server-side context. Access requires an active `BUSINESS_OWNER` Membership for
that exact user and Business. An absent, inactive, or foreign Membership for
the selected Business is rejected by authenticated-context resolution as
`ACTIVE_BUSINESS_REQUIRED`, the same established outcome the session filter
produces before the controller runs. An active Membership for the selected
Business that lacks `BUSINESS_OWNER` authority — `MANAGER` or `STAFF` — is
rejected by application authorization as `ACCESS_DENIED`. `PLATFORM_ADMIN`
alone does not bypass either requirement.

DRAFT and ACTIVE Businesses allow schedule reads and mutations. SUSPENDED
Businesses allow reads and reject mutations. Active and inactive StaffMembers
retain readable schedules. Only an active StaffMember may receive a schedule
mutation. Deactivation and Business suspension preserve all schedule data.

Missing and cross-Business StaffMember identifiers use the same safe not-found
outcome. Requests and responses never carry authoritative Business identity.

## Concurrency and locking contract

Complete replacement uses `expectedVersion`. The transaction acquires locks in
this order:

1. Business lifecycle row;
2. exact qualifying owner Membership row;
3. StaffMember row for stable activity state;
4. conditional schedule version update; and
5. complete child-period replacement.

The schedule update predicate includes Business, StaffMember, and expected
version. Replacement deletes existing periods and inserts the validated desired
set in the same transaction. Any validation, version, ownership, or persistence
failure rolls back the version and periods together. Concurrent same-version
replacements produce one accepted schedule and one safe conflict.

Reads use a consistent transaction snapshot so aggregate metadata and ordered
periods cannot come from different versions. All PostgreSQL lock and concurrency
claims require deterministic separate-transaction tests without sleeps.

ADR-0012 records the durable aggregate, replacement, overlap, and lock-order
decision.

## Safe error contract

The API retains the shared RFC 7807 shape and Bulgarian public details.
Implemented codes are:

| Status | Code |
|---:|---|
| 400 | `VALIDATION_ERROR` |
| 401 | `AUTH_REQUIRED` |
| 403 | `ACTIVE_BUSINESS_REQUIRED` |
| 403 | `ACCESS_DENIED` |
| 404 | `STAFF_MEMBER_NOT_FOUND` |
| 409 | `STAFF_MEMBER_INACTIVE` |
| 409 | `WORKING_SCHEDULE_CONCURRENT_UPDATE` |
| 409 | `BUSINESS_SUSPENDED` |
| 500 | `INTERNAL_ERROR` |

Responses must not expose SQL, constraint names, driver text, stack traces,
rejected personal input, internal identifiers, Membership details, or tenant
existence.

## Implementation phases

### Phase 1 — contract, ADR, and PostgreSQL schema (completed)

- add this task record and ADR-0012;
- add immutable V7 and backfill existing StaffMembers;
- add focused PostgreSQL schema, upgrade, ownership, range, overlap, and index
  tests; and
- update only older schema inventory assertions affected by the new tables.

### Phase 2 — validation and persistence (completed)

- add schedule records and validation;
- add Business-scoped schedule persistence and atomic replacement primitives;
- create an empty schedule during future StaffMember creation; and
- add focused unit and PostgreSQL persistence tests.

### Phase 3 — authorization, lifecycle, and concurrency (completed)

- add narrow published Business schedule-context and Workforce administration
  contracts;
- enforce owner authorization and lifecycle behavior;
- implement the approved lock order and complete-set transaction; and
- add deterministic PostgreSQL authorization, lifecycle, and race coverage.

### Phase 4 — authenticated HTTP API (completed)

- add GET and PUT endpoints under the StaffMember path;
- add exact request/response records and safe error mapping; and
- add controller and PostgreSQL-backed MockMvc coverage.

### Phase 5 — documentation and acceptance (completed)

- synchronize only the necessary permanent backend documentation;
- run complete backend verification; and
- review acceptance criteria, migrations, boundaries, public errors, and final
  scope.

Each phase required separate explicit approval before persistent changes.

## Final acceptance evidence

Every acceptance area from issue #13 is satisfied by the committed
implementation:

1. exactly one `staff_working_schedule` row per StaffMember, primary keyed on
   `(business_id, staff_member_id)`;
2. V7 backfills one empty version-0 schedule per existing StaffMember, and
   `StaffMemberAdministrationService.create` inserts the new StaffMember's
   empty schedule inside the same creation transaction;
3. every schedule read and mutation derives Business from the authenticated
   `BUSINESS_OWNER` Membership; composite restrictive foreign keys tie periods
   to `(business_id, staff_member_id)`;
4. one-minute precision and strict canonical `HH:mm` values from `00:00`
   through `23:59` are enforced by `StrictCanonicalLocalTimeDeserializer`, the
   input validator, and the V7 minute-precision and clock-time-range checks;
5. `StaffWorkingScheduleInputValidator` rejects duplicate periods,
   `WorkingPeriodOverlapValidator` rejects overlaps, the 100-period cap is
   enforced, and the V7 GiST exclusion and primary key repeat both guarantees
   in PostgreSQL;
6. `StaffWorkingScheduleStore.findPeriods` orders by weekday, start time, then
   end time;
7. the schedule version is independent of the StaffMember aggregate version;
8. `StaffWorkingScheduleService.replace` advances the version and replaces all
   periods in one transaction, rolling back together on any failure;
9. `StaffWorkingScheduleAdministrationService` requires an active
   `BUSINESS_OWNER` Membership for the exact Business;
10. DRAFT and ACTIVE Businesses permit reads and mutations; SUSPENDED permits
    reads and rejects mutations with `BUSINESS_SUSPENDED`;
11. inactive StaffMembers retain readable schedules; only an active
    StaffMember may receive a mutation, enforced by `StaffMemberInactive`;
12. the mutation transaction locks the Business lifecycle row, the exact owner
    Membership row, then the StaffMember row, before the conditional schedule
    version update and complete period replacement, verified by
    `StaffWorkingScheduleLockingIntegrationTests`;
13. same-version replacement races and StaffMember-deactivation races are
    covered deterministically without sleeps in the same test class;
14. `BusinessStaffWorkingScheduleController` exposes the exact GET and PUT
    routes under `/api/business/staff-members/{staffMemberId}/working-schedule`;
15. `BusinessStaffWorkingScheduleExceptionHandler` maps every API-reachable
    schedule application failure to the safe RFC 7807 contract below without
    exposing SQL, stack traces, or Membership details.
    `StaffWorkingScheduleNotFound` is intentionally not part of this public
    mapping: the administration flow always requires and loads the
    StaffMember first, and every StaffMember owns an auto-provisioned
    schedule, so that exception is not reachable through the authenticated
    API;
16. `ModuleBoundaryTests` verifies the complete module graph, including
    `workforce`, remains acyclic;
17. no exception, time-off, break, override, availability, booking, Customer,
    or Appointment table, column, or endpoint was introduced.

## Final verification

Phases 1–4 gathered the focused schema, persistence, application, locking, and
controller evidence described throughout this record. Phase 5 is
documentation-only and did not rerun those focused suites; its exact evidence
is `./mvnw --batch-mode verify` (919 tests, 0 failures, 0 errors) and
`git diff --check` (no whitespace errors), plus a git blob-hash comparison
proving V1 through V7 remain byte-for-byte unchanged against the starting
commit, with V7 remaining the only new migration. No production, test,
configuration, dependency, or generated file changed during Phase 5; only
Markdown documentation was edited.
