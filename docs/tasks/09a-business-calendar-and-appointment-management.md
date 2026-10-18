# SpotYourSlot — Business Calendar and Appointment Management (Issues #19 and #21)

Status (current, 2026-10-18): **Phases 1 to 8 and the Phase 9 backend prerequisite are committed (`HEAD` `8c80b79503553831439791b271f7a65f222da70f` includes the Appointment-scoped reschedule option reads, issue #21; its CI run finished as `failure` in the `e2e` job only and is advisory). Phase 9 (the owner-only reschedule and StaffMember reassignment frontend, issue #21) is implemented in the working tree and awaits review (nothing staged or committed); Phases 10 to 12 are not started.** *(Superseded status text: the previous version of this line said that the Phase 9 backend prerequisite awaited review in the working tree; it was committed as `8c80b79`.)* Issues #19 and #21 are one combined delivery and both are In Progress on the Project board. The product owner alone edits GitHub (issue texts, comments, states, Project status); this repository work never does. The decisions are recorded in [ADR-0028](../decisions/ADR-0028-administer-appointments-through-a-private-owner-only-api-with-immutable-visit-identity-and-business-cancellation.md) and [ADR-0029](../decisions/ADR-0029-calculate-business-availability-with-a-separate-policy-and-serialize-appointment-mutations-with-ordered-locks-optimistic-versions-and-bounded-retry.md); this record holds the plan, the acceptance mapping, the contract and migration plan, the phase plan, and the exact proposed GitHub amendment texts.

GitHub issues: #19 — Prevent double booking and handle booking conflicts; #21 — Build the Business calendar and appointment management
Depends on: #16, #17, #18, #20 (all closed). Related, not part of this work: #22 (notifications), #35 (Customer self-service cancellation), #36 (Customer self-service rescheduling), #37 (Customer-specific durations).
Decision records: ADR-0028, ADR-0029; amends in part ADR-0013, ADR-0016, ADR-0022, ADR-0023, ADR-0025, ADR-0027 (each through an amendment note).

## Task purpose

Give the Business owner a reliable operational calendar of the Appointments the Business receives (online and manual), let the owner create, reschedule, reassign, and cancel them without ever causing a double booking, and complete the Business-side conflict criteria of Issue #19. Production public booking must not go live before this exists (ADR-0026). The work is **Strict**: migrations, authorization, tenant isolation, transaction boundaries, concurrency, locking, and cross-module contracts. Every implementation phase below needs its own explicit approval before persistent changes.

## Relationship of #19 and #21 (approved)

- #18 (closed) delivered guest-booking overlap protection and conflict recovery.
- #19 remains open because its Business-side criteria are incomplete.
- #21 delivers the remaining Business-side consumers and the evidence.
- No independent code phase is required before #21.
- #19 closes first, after the final Business-side browser E2E evidence; #21 closes immediately afterwards.
- Both issues stay In Progress during the shared implementation.
- The user alone performs every GitHub amendment and status change.

## Verified starting facts (2026-10-14)

| Fact | Evidence |
|---|---|
| `HEAD` is `f5a4393e9e319fa86b56b7c796704501c0b5ba76`, clean, equal to `origin/main`; CI for that exact commit succeeded | https://github.com/vasilmoskov/spot-your-slot/actions/runs/38537101002 |
| #18 and #20 closed and Done; #19 and #21 open and In Progress | `gh issue view` (read-only) |
| `V1` to `V13` are unchanged (no migration file was modified or deleted since it was added) | `git log --diff-filter=MD` over the migration folder is empty |
| The `appointment` table has the overlap exclusion on `CONFIRMED` rows; only `INSERT` and the `CANCELLED → CONFIRMED` `UPDATE` are proven against it | `V11`; `AppointmentSchemaIntegrationTests.theConstraintAlsoGuardsUpdatesThatMakeARowBlockingAgain` |
| No time-changing, StaffMember-changing, or self-shift `UPDATE` is proven; `AppointmentStore` has no update | `AppointmentStore` |
| No Appointment cancellation, internal note, or attribution exists | `V11` to `V13`, `AppointmentStore` |
| The engine hard-codes the two-hour notice and the 30-date horizon; the busy source cannot ignore an Appointment; the duration comes from the current Service rows | `AvailabilityEngine` lines 54 and 58, `AvailabilityQueryService`, `BusyIntervalSource` |
| `GuestBookingService` rejects a caller transaction and cannot be composed | ADR-0023, `GuestBookingService` |
| Every private API is owner-only; `MANAGER` and `STAFF` receive `ACCESS_DENIED`; no Membership-to-StaffMember link exists | `security.md`, `MembershipRole`, `V1`, `V6` |
| `Промени в графика` is the second tab of the `Работно време` destination, not a sidebar label | `ScheduleTabs.tsx`, `ShellNavigation.tsx` |
| There is no calendar or drag-and-drop dependency | `frontend/package.json` |
| The public booking limiter allows 10 bookings per address and Business per 15 minutes | ADR-0026 |

## Approved decisions

| # | Decision | Where |
|---|---|---|
| D1 | Operations: calendar reads, manual creation, rescheduling to a new start, reassignment, internal-note edit, Business-side cancellation, Customer appointment history. No restore, Service or Customer replacement, price, duration, or snapshot change | ADR-0028 sections 3 and 11 |
| D1a | Manual creation is one atomic visit of one to five distinct Services with one concrete StaffMember chosen by the owner from those eligible for every Service (no automatic assignment), a reviewed-terms check, and a whole-visit conflict guarantee | ADR-0028 section 4 |
| D2 | Immutable visit identity; wrong Customer or Service means cancel and then create anew, explained in the interface as two operations | ADR-0028 section 3 |
| D4 | `BUSINESS_OWNER` only; Manager, Staff, and Membership linkage are a separate future capability | ADR-0028 section 1 |
| D5 | `ACTIVE` reads and mutates; `DRAFT` and `SUSPENDED` are read-only; `BUSINESS_NOT_ACTIVE` for `DRAFT` | ADR-0028 section 2 |
| D9 | A separate Business-side availability policy with the stored duration and an ignore-self seam; the time rules | ADR-0029 section 1 |
| D10 | Zero buffers, unmodelled | ADR-0028 section 15 |
| D11 | Eligibility after creation: create requires active Services and an active, assigned StaffMember; a time-only move keeps the same StaffMember, which must still be active, without a Service or assignment check (a later-deactivated Service or a removed assignment does not block it; an inactive StaffMember does); a reassignment needs an active, currently assigned target; cancel and note need none | ADR-0028 section 6 |
| D12 | The Customer is an explicit same-Business identifier; no `findOrCreate` | ADR-0028 section 5 |
| D13 | No manual idempotency columns in the MVP; single-flight, no automatic repeat, reload, and verify | ADR-0029 section 10 |
| D14, D15 | `start_at <= now` cannot be moved, reassigned, or cancelled; cancellation attribution `cancelled_at` and `cancelled_by` with `UNKNOWN` for old rows; no reason | ADR-0028 sections 7 and 8 |
| D16 | `internal_note`, 500 code points, private, always editable by the owner while `ACTIVE` | ADR-0028 section 9 |
| D22 | `version` is concurrency metadata only; no "changed" badge; no `schedule_changed_at` | ADR-0028 section 10 |
| D20 | Calendar reads: at most 31 Business-local dates; overlap inclusion; `(start_at, id)`; agenda sizes 10, 25, and 50 with fixed order | ADR-0028 section 12 |
| D21 | Customer history is a Booking-owned, server-paginated, newest-first read included in #21 | ADR-0028 section 11 |
| D19 | Transactions, lock order, retry, and the conflict taxonomy | ADR-0029 sections 2 to 11 |
| D23 | `booking → identity` narrow edge; a separate Business runner; a new HTTP adapter module | ADR-0028 section 13; ADR-0029 section 2 |
| D24 | `График` navigation; the existing `Промени в графика` tab inside `Работно време` is kept unless the rendered human review proves it confusing; Proposed wording until the human review | ADR-0028 section 14 |
| D25 | Drag-and-drop moves to a separate future issue; the form is canonical | ADR-0028 section 15 |

## Business-side API surface (proposed; each route is finalised in the named phase)

Route names, problem codes, and Bulgarian wording in this record and in ADR-0028 and ADR-0029 are **working names**. The exact contracts are finalised and tested in the HTTP or interface phase that implements them.

All routes derive the Business and the user from the authenticated context; none accepts a Business identifier. Mutations require CSRF and `expectedVersion` (except create). Responses are `no-store` with a fixed problem `instance` and no echoed identifier or submitted value.

| Route | Purpose | Phase |
|---|---|---|
| `GET /api/business/calendar?from=&to=[&staffMemberId=]` | Appointments overlapping at most 31 dates, their lines, Customer display names, and the working time of each StaffMember for the range (the grid) | 3 |
| `GET /api/business/appointments?from=&to=[&staffMemberId=]&page=&size=` | The agenda: the same facts, server-paginated, fixed chronological order | 3 |
| `GET /api/business/appointments/{id}` | The detail: status, source, lines, totals, notes, Customer contact (owner only), `version` | 3 |
| `GET /api/business/customers/{customerId}/appointments?page=&size=` | The Customer history: minimum facts, newest first | 3 |
| `GET /api/business/appointment-options?serviceId=…` and `GET /api/business/appointment-options/availability?serviceId=…&staffMemberId=…&date=…` | The StaffMembers who may perform the selected Services with their summed duration, and the authoritative starts of one explicit StaffMember on one Business-local date for a create (advisory, reserves nothing; the reschedule form uses the separate Appointment-scoped pair `GET /api/business/appointments/{appointmentId}/reschedule-options` and `…/availability` of the Phase 9 backend prerequisite, which take the stored duration and ignore only that Appointment; see the "Phase 9 backend prerequisite record") | deferred out of Phase 4; implemented as the discovered Phase 8 prerequisite (see its record) |
| `POST /api/business/appointments` | Create | 4 |
| `POST /api/business/appointments/{appointmentId}/reschedule` | Move and/or reassign in one atomic request (final in Phase 5; see the Phase 5 record) | 5 |
| `POST /api/business/appointments/{id}/cancel` | Cancel | 6 |
| `PUT /api/business/appointments/{id}/internal-note` | Edit the internal note | 6 |

## Contract and module changes (planned)

- `scheduling`: the engine request takes the minimum notice and the date window; `AvailabilityQuery` gains a Business-side entry (Business, occupied duration, StaffMember, window, injected `now`, optional ignored Appointment); `BusyIntervalSource` gains a second abstract method; a published working-time overview per StaffMember and date. The public path passes the former constants and its results do not change.
- `workforce`: a published read of an active StaffMember's weekly periods by identifier, independent of Service assignments.
- `customer`: narrow published read contracts for display names and for the detail's contact data (same-Business, no lock); the module still does not depend on `booking`.
- `booking`: published `AppointmentAdministration` (create, reschedule, cancel, note) and `AppointmentCalendar` (reads); a separate non-transactional Business runner; narrow use of the published `identity` types; the new `UPDATE` statements.
- A new HTTP adapter module (working name `calendar`) that depends only on the published contracts of `booking`.
- Pinned tests that change with these edges (none is weakened): `BookingModuleBoundaryTests`, `CustomerModuleBoundaryTests`, `ScheduleRevisionModuleBoundaryTests`, `AvailabilityMutationInventoryTests`, `BusyIntervalSourceFailFastTests`.

## Migrations (`V14` implemented and committed in Phase 2; `V15` implemented in the Phase 3 working tree on measured evidence, awaiting review and commit)

**`V14__add_appointment_administration.sql` (Phase 2; implemented, see the Phase 2 record below).** The SQL, the invariants, and the test adaptations are in [ADR-0028 section 8](../decisions/ADR-0028-administer-appointments-through-a-private-owner-only-api-with-immutable-visit-identity-and-business-cancellation.md). It adds `cancelled_at`, `cancelled_by`, and `internal_note`, backfills `UNKNOWN` for rows already `CANCELLED`, adds the strict consistency checks, and ends with a verification block that fails the migration on any inconsistent row. Required evidence: a V13-to-V14 upgrade test over `CONFIRMED` and `CANCELLED` rows (the old cancelled rows become `UNKNOWN` with a null time, every other column identical), each allowed and each forbidden attribution combination, a migration that fails and leaves nothing on a corrupt fixture, the V1 to V13 hashes unchanged, and a fresh install equal to an upgraded one.

**`V15` (Phase 3; implemented as `V15__add_appointment_read_indexes.sql` with the first and third candidates only; the measurements and the decision are in the Phase 3 record).** Indexes ship only with the read query that uses them and only after `EXPLAIN ANALYZE` with representative synthetic data (about 10,000 and 50,000 Appointments over several Businesses, as the Customer search measurement did):

| Candidate | Query it would serve | Decision rule |
|---|---|---|
| `(business_id, start_at, id)` | range and agenda reads: `start_at >= :from - 480 minutes AND start_at < :to` (480 is the checked maximum duration) then `occupied_until > :from`, ordered by `(start_at, id)` | create it if the plan with it beats the plan without it |
| `(business_id, staff_member_id, start_at, id)` | a StaffMember-filtered read that must also see cancelled rows (the partial exclusion index covers only `CONFIRMED`) | create it only if the first index plus a filter measures too slow; otherwise it is redundant |
| `(business_id, customer_id, start_at DESC, id DESC)` | Customer history | create it if history is measured slow without it |

The existing unique and exclusion indexes already serve lookups by identifier and reference and the busy-window query; none of these candidates duplicates them. A candidate that is not justified by the measurement is not created, and the evidence of each decision is recorded in the Phase 3 record.

## Tests that change with the persistence and availability work (planned)

- Every test or fixture that writes `status = 'CANCELLED'` by raw SQL or builds a cancelled row from `AppointmentFixtures.row` (at least the guest-booking, replay, visit, public-API, availability, and schema tests) sets the attribution through a fixture helper; the strict constraint is not relaxed.
- `AppointmentSchemaIntegrationTests`: the exact column, constraint, index, and migration-list assertions, and `theConstraintAlsoGuardsUpdatesThatMakeARowBlockingAgain` (it must also clear the attribution so the exclusion constraint, not the new CHECK, is what it asserts).
- The migration lists of the Business, Customer, schedule-exception, working-schedule, and schedule-revision schema tests, and their "latest version" counts.
- `BusyIntervalSourceFailFastTests` and any `BusyIntervalSource` stub: the second method.
- `AvailabilityMutationInventoryTests`: the new `UPDATE appointment` statements are classified (reschedule and reassign behind the shared revision guard; cancel and note edit do not reduce availability).
- Boundary tests listed above.
- The raw-SQL cancellation fixtures found by the final test review (the list is in the Phase 1 record): each is moved to one fixture helper that sets the attribution.
- `AppointmentFixtures.row` and `insertRow` (the column key set), `BookingHookConfiguration` (its `AvailabilityQuery` wrapper gains the Business-side method), `BookingTestHooks.Point` and `TransactionLog` (they observe only threads named `booking-`, so the Business runner needs its own hook points or prefix), and the positional `Appointment` and `NewAppointment` constructors used by `AppointmentDomainTests`, `AppointmentVisitDomainTests`, and the store tests (compatibility constructors or fixture helpers, as ADR-0027 did).
- `AppointmentStoreFailureTranslationTests` (the pinned count of 25 checks and 6 foreign keys) gains the `V14` checks.
- The guest-path regression suites that must run whenever a scheduling, transaction, or Appointment-persistence contract changes are listed in the Phase 1 record.

## Acceptance mapping

### Issue #19 (remaining criteria)

| #19 criterion | Delivered by |
|---|---|
| No overlapping blocking Appointments for one StaffMember, including through `UPDATE` | Phase 2 (the `UPDATE` proofs) |
| Guest and Business-created Appointments follow the same rules | Phases 2 and 4 |
| Creating, moving, reassigning, or changing rechecks conflicts | Phases 4 and 5 |
| A failed update leaves the original unchanged | Phases 2 and 5 |
| Business-side conflict feedback is safe, understandable, and recoverable | HTTP in Phases 4 to 6, interface in Phases 8 and 9, evidence in Phase 12 |
| No other Customer's or private data in a conflict response | Phases 4 to 6, 12 |
| Business input cannot bypass protection | Phases 4 to 6 |
| Timezone and daylight-saving correctness in Business-side operations | Phases 2, 4, 5, then 8, 9, 12 |
| Customer versus Business, Business versus Business, and move versus create races | Phases 4 to 6 |

### Issue #21 (after the amendments below)

| #21 area | Delivered by |
|---|---|
| Selected-Business isolation; owner combined calendar and StaffMember filter | Phases 3 and 7 |
| Day, week, and agenda views; working, booked, available, and non-working time; timezone shown | Phases 3 and 7 |
| Appointment facts, source, and detail with owner-only Customer contact | Phases 3 and 7 |
| Customer appointment history on the Customer detail | Phases 3 and 7 |
| Manual create, including several Services | Phases 4 and 8 |
| Form reschedule and reassign; revalidation; unchanged on failure | Phases 5 and 9 |
| Cancellation with confirmation, history preserved, time released | Phases 6 and 10 |
| Internal-note edit | Phases 6 and 10 |
| `SUSPENDED` and `DRAFT` read-only; platform administrators, other Businesses, unauthenticated users, Managers, and Staff denied | Phases 3 to 6, 12 |
| Keyboard, mobile, 200% zoom, long names | Phases 7 to 11 |
| Existing public booking and Business configuration unchanged | Phase 12 |
| Customer cancellation coordination | the cancel predicate is ready; the Customer side is #35 |

## Phase plan

Every phase is its own change with its own approval, is verified before the next starts, and keeps every earlier test green. Backend phases 2 to 6 are Strict; frontend phases 7 to 10 are Standard; 11 and 12 are review and browser verification. Phase 11 is the formal rendered human-review phase and an explicit **approval gate**: Phase 12 must not start until the product owner's approval is recorded in this record. After Phases 7 and 10 the report only notifies the product owner that the read-only slice (Phase 7) or all forms (Phase 10) have become visually testable and gives the exact manual steps; those notifications are not approvals. **Drag-and-drop is not part of any phase.**

| Ph | Title | Owner |
|---|---|---|
| 1 | Decisions, ADRs, task record, permanent documents | #21 (the body names #19) |
| 2 | `V14`, domain transitions, persistence mutations, parameterised Business availability, ignore-self seam | #19 |
| 3 | Calendar and Customer-history read APIs, measured `V15` indexes | #21 |
| 4 | Manual multi-Service create and Business conflict mapping (the options route is deferred, see the Phase 4 record) | #19 |
| 5 | Form reschedule and StaffMember reassignment | #19 |
| 6 | Cancellation and internal-note edit | #21 |
| 7 | Read-only frontend: navigation, views, filters, details, Customer history | #21 |
| 8 | Manual-create frontend | #21 |
| 9 | Form reschedule and reassign frontend | #21 |
| 10 | Cancellation and internal-note frontend | #21 |
| 11 | Rendered human visual review | #21 |
| 12 | Browser E2E, documentation reconciliation, final evidence | #21 for the specs; documents #19 first, then #21 |

### Phase 1 — decisions and documentation (this phase)

- **Scope:** ADR-0028, ADR-0029, this record, amendment notes, the permanent-document reconciliation. **Exclusions:** all code, migrations, tests, configuration, GitHub, and archives.
- **Files:** documents only.
- **Migration:** none. **Verification:** `git diff --check`, trailing-whitespace and final-newline checks, relative-link and table-column checks, contradiction searches, and a scope check that every changed path is documentation; no application suite is run.
- **Concurrency evidence:** none (design only; the evidence plan is in ADR-0029).
- **Gate:** the product owner's review. **Commit:** one documentation commit, subject ending `(#21)`.

### Phase 2 — `V14`, domain, persistence, parameterised availability (#19)

- **Scope:** `V14`; the Appointment domain gains `cancelledAt`, `cancelledBy`, `internalNote` and pure transition rules for reschedule, cancel, and note edit; `AppointmentStore` gains conditional reschedule, cancel, and note updates and reads the new columns; the engine takes notice and window as inputs; `AvailabilityQuery` gains the Business-side entry; `BusyIntervalSource` gains the ignored-Appointment method and `BookingBusyIntervalSource` implements it; a Workforce read by StaffMember identifier; the fixture helper; the test adaptations above. **Exclusions:** any HTTP, orchestration, calendar read, index, or frontend.
- **Likely files:** `V14__add_appointment_administration.sql`; `booking/domain`, `booking/infrastructure/AppointmentStore.java`, `BookingBusyIntervalSource.java`; `scheduling/domain/AvailabilityEngine.java`, `AvailabilityRequest.java`, `AvailabilityPolicy.java`, `scheduling/AvailabilityQuery.java`, `BusyIntervalSource.java`, `AvailabilityQueryService.java`; `workforce`; the tests listed.
- **Migration:** `V14`.
- **Focused verification:** the `V14` upgrade and schema tests; domain tests; store tests including each transition and each forbidden combination; the availability equivalence and the Business-policy tests (zero notice, a start beyond 30 dates, a past start, a gap, both occurrences of a repeated hour, ignore-self adjacent and overlapping neighbors). **Full:** `./mvnw --batch-mode verify`.
- **Concurrency evidence:** a time-changing and a StaffMember-changing `UPDATE` against a live neighbor (`23P01`, original row unchanged); a shift inside the row's own interval succeeds; a conditional `UPDATE` after a concurrent commit fails with `40001`; a failed update changes no other row; all ordered by latches and `pg_stat_activity`.
- **Gate:** Strict: read-only plan and explicit approval before edits; review before the commit. **Commit:** `feat: add Appointment administration persistence and Business availability (#19)`.

### Phase 3 — read APIs and measured indexes (#21)

- **Scope:** the `AppointmentCalendar` contract, the new adapter module with the four read routes of the API table, the Customer and working-time read contracts, the narrow `booking → identity` edge, and `V15` only for the indexes the `EXPLAIN ANALYZE` evidence justifies. **Exclusions:** every mutation, the options route, and the frontend.
- **Likely files:** the new module, `booking` (reads), `customer`, `scheduling`, `identity` use, `V15`, boundary tests.
- **Migration:** `V15` (indexes, possibly none).
- **Focused verification:** the role matrix (owner, Manager, Staff, platform-only, unauthenticated, foreign Business); Business A/B isolation with byte-identical 404s; `DRAFT`, `ACTIVE`, `SUSPENDED` reads; exact key sets and privacy sentinels (responses, errors, logs); the 31-date limit and the date bounds; overlap inclusion at both range edges; a daylight-saving day; ordering and stable ties; pagination sizes 10, 25, 50 and invalid values; statement counts independent of the number of Appointments and StaffMembers; history order, minimum fields, no notes; the `EXPLAIN ANALYZE` evidence. **Full:** `./mvnw --batch-mode verify`.
- **Concurrency evidence:** a read-only repeatable-read read returns appointments, lines, and names from one snapshot even when a commit lands between its statements.
- **Gate:** Strict. **Commit:** `feat: add the Business calendar and Customer history read APIs (#21)`.

### Phase 4 — manual multi-Service create (#19)

- **Scope (amended 2026-10-15: the options route is deferred, see the Phase 4 record):** the create operation of `AppointmentAdministration`, the non-transactional Business runner and attempt procedure (a separate class; the guest runner is not touched), ~~the options route~~, `POST /api/business/appointments`, the full error taxonomy for create, and the HTTP mapping. **Exclusions:** reschedule, cancel, note edit, idempotency columns, and the frontend.
- **Verification:** unit tests of the runner (entry guard before any work, one new transaction per attempt, retry classification, commit-phase classification with injected statuses labelled as such); PostgreSQL integration tests of the success path (lines in profile order, summed totals, snapshots from locked rows), the eligibility matrix, the reviewed-terms check with zero writes, a foreign or missing Customer, the `DRAFT` and `SUSPENDED` rejections, the role matrix, isolation, the instant rules and daylight-saving cases, and privacy sentinels. **Full:** `./mvnw --batch-mode verify`.
- **Concurrency evidence:** create versus create, create versus guest booking, the loser leaves no row and no Customer change, lock order observed with `FOR UPDATE SKIP LOCKED` probes at the hook points, a lock-changed-after-snapshot `40001` retry in a distinct transaction, the controlled exclusion-index deadlock retry.
- **Gate:** Strict. **Commit:** `feat: create Appointments for the Business owner with conflict protection (#19)`.

### Phase 5 — reschedule and reassign (#19)

- **Scope (amended 2026-10-16: the options route stays deferred, see the Phase 4 record; the Phase 5 record holds the result):** the reschedule and reassignment operation, `POST …/reschedule`. **Exclusions:** cancel, note, the options route, drag-and-drop, frontend.
- **Verification:** time-only move with a Service deactivated later, with an assignment removed later, with the current StaffMember inactive (rejected); reassignment to an unassigned or inactive target (rejected) and to an assigned one (accepted, snapshot refreshed); the stored duration is used even if the Service duration changed; price, duration, lines, Customer, and source unchanged; started and cancelled Appointments rejected; a no-change request rejected; a failed move leaves the row byte-identical.
- **Concurrency evidence:** every scenario of the ADR-0029 section 7 table for these operations, including both reassign orders, two moves of one Appointment, move versus create, move versus guest booking, move versus cancel, and schedule, StaffMember, Service, and Business changes, all with latches and lock-wait evidence; a stale `expectedVersion` versus a state conflict versus a lost slot asserted as three distinct results.
- **Gate:** Strict. **Commit:** `feat: reschedule and reassign Appointments with conflict protection (#19)`.

### Phase 6 — cancellation and internal-note edit (#21)

- **Scope (amended 2026-10-16: the routes and the transaction policy are final in the Phase 6 record):** `POST …/cancel`, `PUT …/internal-note`, the attribution, the started-Appointment rule. **Exclusions:** reasons, restore, frontend.
- **Verification:** cancellation attribution and the `V14` invariants through the real service; before-start only; repeat and stale attempts; a cancelled Appointment stops blocking and the time is offered again; the note on future, past, confirmed, and cancelled Appointments, only while `ACTIVE`; privacy of the note in responses, logs, and errors.
- **Concurrency evidence:** cancel versus cancel, cancel versus move, note versus move, with latches; the cancel predicate is shown to be the same one a Customer cancellation (#35) will use.
- **Gate:** Strict. **Commit:** `feat: cancel Appointments and edit the internal note (#21)`.

### Phase 7 — read-only frontend (#21)

- **Scope:** the `График` destination; routes with safe URL state (view, date or week, StaffMember identifier, page, size); the day and week grids built with plain React and CSS (no dependency); the agenda with the §15.1 exceptions; the read-only detail; the Customer history section on the Customer detail; lifecycle notices; stale-response and Business-switch handling. **Exclusions:** every form, mutation, and drag-and-drop.
- **Verification:** component tests with deferred promises for stale and out-of-order responses, a fake clock and a fixed timezone, a daylight-saving day, long names, route parsing and normalization, the Business switch, role and lifecycle states, and no write to browser storage; `npm run lint`, `npm run build`, the full `npm test`. The existing `Промени в графика` tab and its occurrences are not changed in this phase.
- **Concurrency evidence:** the frontend ignores a superseded request, a late response of a previous Business, and an obsolete range.
- **Gate:** Standard; the report notifies that the read-only calendar is visually testable and gives the manual steps; this notification is not the visual approval, which is Phase 11. **Commit:** `feat: add the read-only Business calendar and Customer history (#21)`.

### Phase 8 — manual-create frontend (#21)

*(Implemented 2026-10-16; the exact record is the "Phase 8 record" at the end of this file. The plan text below is kept as approved.)*

- **Scope:** the create form (Customer selection or creation through the Customer capability, one to five Services, StaffMember, the authoritative starts, an optional internal note, a review, the single-flight guard, the unsaved-changes guard), the conflict and uncertain-outcome recovery of ADR-0029 section 10. **Verification:** validation and focus, action order, duplicate-submit protection, no automatic repeat after an uncertain outcome, reload of the authoritative calendar after any failure, the repeated-hour disambiguation, wrapped errors. **Gate:** Standard. **Commit:** `feat: add the manual Appointment form (#21)`.

### Phase 9 — reschedule and reassign frontend (#21)

*(Implemented 2026-10-18; the exact record is the "Phase 9 record" at the end of this file. Its backend prerequisite, the Appointment-scoped reschedule option reads, is committed and recorded in the "Phase 9 backend prerequisite record". The plan text below is kept as approved.)*

- **Scope:** the reschedule form, the stale-version reload, the state and slot conflict messages kept distinct, the explanation of what cannot be changed. **Verification:** as Phase 8, plus each of the three conflict results. **Gate:** Standard. **Commit:** `feat: add the reschedule and reassign form (#21)`.

### Phase 10 — cancellation and internal-note frontend (#21)

- **Scope:** the cancel confirmation (safe action first and focused, destructive never focused by default, an explanation that creating a replacement is a separate action and that the cancellation cannot be undone), the internal-note editor. **Verification:** dialog rules of UI guide section 19, duplicate-submit protection, the stale and state results. **Gate:** Standard; the report notifies that all forms are visually testable; this notification is not the visual approval, which is Phase 11. **Commit:** `feat: add Appointment cancellation and the internal-note editor (#21)`.

### Phase 11 — rendered human visual review (#21)

- **Scope:** an isolated review stack and fixtures created only through supported APIs. The walkthrough covers, at desktop, tablet, and mobile widths (about 1280, 1024, 800, 375, and 320 px) and at 200% zoom: keyboard-only operation; long Customer, Service, and StaffMember names; the day, week, and agenda views; the read-only `DRAFT` and `SUSPENDED` states; every form, conflict, and dialog; wrapped validation errors; a daylight-saving date; and the Customer history. The Bulgarian wording and the two navigation labels are assessed in the rendered interface: `График` (the new sidebar destination) and `Промени в графика` (the existing tab inside `Работно време`). Developer-rendered evidence and human approval are recorded separately.
- **Approval gate:** this is the formal human-review phase. It ends only when the product owner's explicit visual approval is recorded in this record. **Phase 12 must not start until that approval is recorded.** If the review asks for changes, Phase 11 stays open: the corrections are applied and verified, the affected views are rendered again, and approval is requested again. The tab is renamed only if the rendered review proves the two labels confusing, and then every occurrence is changed together in that correction. Approved decisions update `docs/ui-design-guidelines.md`.
- **Commit:** `docs: record the approved Business calendar interface (#21)` and any correction commit.

### Phase 12 — browser E2E, reconciliation, final evidence

- **Precondition:** the product owner's visual approval of Phase 11 is recorded in this record. Without it Phase 12 does not start.
- **Scope:** Playwright journeys on the unmodified isolated runner with fixtures created only through supported APIs: view, create (one and several Services), conflict recovery, reschedule, reassign, cancel, note, Customer history, isolation, platform administrator and non-owner denial, `DRAFT` and `SUSPENDED`, mobile, privacy of the URL and storage, and the existing journeys unchanged. The fixtures count against the public booking limiter (10 per address and Business) and prefer the manual-create API where a guest booking is not the subject.
- **Verification:** the backend `verify`, the frontend suites, two consecutive complete `./scripts/run-e2e.sh` runs, and the exact-HEAD CI result, reported separately as local and CI.
- **Commits:** the E2E specs `(#21)`; then the reconciliation of the conflict-protection evidence `(#19)`; then the calendar reconciliation `(#21)`, in that order, so that #19 can be closed first and #21 immediately afterwards by the product owner.

## Closure

#19 may close only after the complete Business-side implementation and its browser E2E evidence (Phase 12), with the acceptance mapping above filled with evidence. #21 closes immediately afterwards. The product owner performs both closures and every board change.

## User interface notes

- **Terminology:** the new destination is `График`. `Промени в графика` is today the second tab of `Работно време` (not a sidebar destination) and **remains** so unless the rendered Phase 11 review proves it confusing. The two labels occur at different navigation levels and are assessed together in that review; no rename is pre-planned. The weekly-schedule wording (`Седмични графици`, `Редактирай графика`, `Изчисти графика`, `Копирай графика`) is unchanged unless the human review decides otherwise.
- **§15.1:** the agenda has a fixed chronological order that is not user-sortable and a server-side pagination with the sizes 10, 25, and 50 and the page and size in the URL; the day and week grids are layouts. Both are recorded exceptions in the guide's new section 15.3. The deterministic default order is `(start_at, id)`; the tie-breaker ends in the Appointment identifier; pagination is server-side because a range may hold many Appointments; filtering by StaffMember and the range are in the URL; the mobile pattern is cards in the same order; an empty page after a mutation is recovered by the standard replace-history rule.
- **Privacy:** Customer names and any private value never appear in a URL, history state, storage, or title.
- **Dates:** the calendar uses the timezone returned by the API; the browser timezone never decides a day.
- **Wording:** every Bulgarian error, confirmation, and label is Proposed until the rendered human review.

## Contradictions found and how they are resolved

| Contradiction | Resolution |
|---|---|
| #19 and #21 each depend on the other | #21 delivers the remaining #19 criteria; #19 closes first |
| #19 and #21 list multi-Service appointments as out of scope | Delivered by #18; manual creation supports one to five Services (ADR-0028 section 4) |
| #19 requires buffers | Zero buffers, unmodelled; deferred |
| #19 says overlap comparisons use the Business timezone | UTC instants for overlap; the timezone governs slot generation and display |
| #19 lets a "Service" or "duration" change | Not supported; cancel and recreate |
| #21 and the product specification give Managers and Staff calendar access | Owner-only; Manager and Staff are a separate future capability |
| #21 requires a Staff schedule while excluding Membership linkage | The requirement is removed |
| Existing private APIs allow `DRAFT` mutations; #21 says `DRAFT` has no mutations | `DRAFT` is read-only for Appointments (ADR-0028 section 2) |
| Documents say Customer cancellation belongs to #13 | #13 is the recurring working schedules issue; Customer self-service cancellation is #35 (task 08a corrected) |
| Several documents say Customer history "remains with #21"; #21's body does not list it | Included in #21 (ADR-0028 section 11) |
| #21 requires drag-and-drop | Removed; a separate future issue |
| #21 shows "changed" and "completed" states; the lifecycle has two statuses | Not displayed in the MVP |
| The product specification lets staff cancellation include a reason | No reason in the MVP |
| The product specification and implementation plan mention complete and no-show for staff | Deferred; `COMPLETED`/`NO_SHOW` are not introduced |
| #21 excludes "Conflict-protection implementation" and "Availability calculation implementation" but needs Business-side conflict handling | The exclusions are narrowed (amendment text below) |
| The brief called `Промени в графика` a navigation label | It is a tab inside `Работно време`; it is kept unless the rendered review proves it confusing (ADR-0028 section 14) |
| An earlier audit said no test proves the exclusion on `UPDATE` | One does, for `CANCELLED → CONFIRMED`; the unproven parts are the time-changing, StaffMember-changing, and self-shift `UPDATE`s (ADR-0029 evidence) |

## Risks and explicitly deferred capabilities

- **Risks:** the cross-module contract changes and the pinned boundary tests (Strict); the exclusion-index deadlock latency under a burst (documented, retried); the engine parameterisation touching the public path (guarded by unchanged tests); the raw-SQL test adaptations; the calendar having no "changed" indicator.
- **Deferred:** drag-and-drop (a future issue); Manager and Staff access and Membership linkage; Customer self-service cancellation (#35) and rescheduling (#36); Customer-specific durations (#37); notifications (#22); `COMPLETED` and `NO_SHOW`; event and audit history; a cancellation reason; restoring a cancelled Appointment; editing the Services or Customer of a visit; manual idempotency; buffers; a Business-configurable horizon or notice; contention optimisation.

## Proposed GitHub amendments (for the product owner to apply manually; not applied)

Each block replaces or is inserted into the named section of the issue body. Headings are quoted as they appear in the current issue text.

### Issue #19

**Replace "Depends on" with:**

```text
## Depends on

* Staff management and Service assignments (delivered)
* Working schedules (delivered)
* Availability and slot calculation (delivered)
* The approved Appointment model (delivered by #18)
* Guest appointment booking (delivered by #18)
* Business-side appointment administration, delivered together with #21; this issue's remaining Business-side criteria are proven by that work
```

**Insert after "Depends on":**

```text
## Current state and delivery (amended 2026-10-14)

Guest booking (#18) delivered the database overlap protection (a PostgreSQL exclusion constraint on CONFIRMED Appointments of one StaffMember), the schedule-revision guard, transactional revalidation, idempotent creation, safe public conflict feedback, and refreshed availability. The remaining Business-side criteria (manual creation, rescheduling, StaffMember change, Business-side conflict feedback, and Business-user races) are delivered through #21 together with their browser evidence. No separate implementation is required before #21. This issue closes first, after that evidence exists, and #21 closes immediately afterwards.

## Multiple Services in one visit (amended 2026-10-14)

A visit of one to five distinct Services (ADR-0027, #18) is one Appointment: one StaffMember performs it in one continuous interval, and idempotency and the request fingerprint cover the complete Service selection. The whole stored interval is validated and protected against overlap as one unit. Conflict protection applies to the stored visit, never to independent Service bookings.
```

**In "Scope", replace the bullet "Include approved Service preparation and recovery buffers in conflict calculation where applicable." with:**

```text
* The MVP uses zero buffers: the occupied period equals the visit duration. Preparation and recovery buffers are deferred and need a forward migration and a separate decision.
```

**In "Scope", replace the sub-bullets "the StaffMember is changed; the Service duration or appointment period is changed where allowed." with:**

```text
  * the start time is changed (the end and the occupied period derive from the stored visit duration);
  * the StaffMember is changed.
```

**In "Conflict rules", replace the two bullets about buffers ("An appointment ending exactly when another appointment begins does not overlap unless an approved buffer extends the effective period." and "Applicable preparation and recovery buffers are part of the StaffMember's occupied period.") with:**

```text
* An appointment ending exactly when another appointment begins does not overlap (buffers are zero in the MVP).
* The occupied period is the complete stored visit duration, the sum of its Service durations.
```

**In "Time-boundary behavior", delete the example "10:00–10:30 with a 10-minute recovery buffer occupies time through 10:40." and replace the paragraph "All comparisons use the authoritative Business timezone while preserving an unambiguous stored instant." with:**

```text
Overlap is evaluated on UTC instants as half-open ranges [start, occupied_until). The Business timezone governs slot generation, date boundaries and display. A daylight-saving transition must not create an ambiguous or duplicate booking.
```

**In "Business-created appointments", replace its first sentence with:**

```text
The same conflict rules apply when the Business owner creates, moves, or reassigns an appointment received by phone, in person, or through another approved channel. In the MVP only the Business owner administers appointments; Manager and Staff access is a separate future capability.
```

**Replace the list in "Appointment changes" ("StaffMember; Service; start time; end time or derived duration; blocking appointment status; applicable buffers.") with:**

```text
* StaffMember;
* start time (the end and the occupied period derive from the stored visit duration);
* status, to cancelled (which releases the time; restoring a cancelled appointment is not supported).

Changing the Services, the Customer, the duration, or the price of a stored appointment is not supported: cancel it and create a new one.
```

**In "Acceptance criteria", replace "Service duration and approved buffers are included in the occupied period." with:**

```text
* The complete visit duration (the sum of its Service durations) is included in the occupied period; buffers are zero in the MVP.
```

**Replace "Product decisions to resolve during implementation" with:**

```text
## Product decisions

Resolved: only CONFIRMED appointments occupy time (ADR-0022); there is no pending state; the boundary model is half-open; buffers are zero and deferred; repeated guest submissions are recognised by an attempt identifier and a fingerprint (ADR-0024); entered data is kept only in memory (ADR-0026); there is no override in the MVP; Business-side conflicts are communicated by distinct safe errors for a stale version, a state conflict, and an unavailable time (ADR-0029); rescheduling changes the start time and the StaffMember only.
Deferred: nearest-alternative suggestions, recurring appointments, an authorized exceptional override.
```

**In "Out of scope", delete "Multiple Services within one appointment." and add:**

```text
* Preparation and recovery buffers.
* Changing the Services or the Customer of a stored appointment.
* Manager and Staff access to Business-side appointment administration.
```

**At the end of "Completion", add:**

```text
Business-side completion is proven by the browser evidence of the combined delivery with #21.
```

### Issue #21

**In "Goal", replace the second paragraph's "Business owners, managers, and StaffMembers must be able to see the appointments relevant to their role" with, and add after the paragraph:**

```text
The Business owner must be able to see the appointments of the Business, understand when each StaffMember is occupied or available, create appointments received outside the public booking flow, inspect appointment details, safely reschedule appointments, and cancel them when necessary.

Manager and Staff calendar access is a separate future capability.
```

**In "Scope", replace "Allow authorized Business users to:" and its sub-list with:**

```text
- Allow the Business owner to:
  - view the Appointments of the Business;
  - inspect appointment details;
  - create appointments received by phone, in person, through a message, or another approved offline channel, each with one to five distinct Services performed by one StaffMember;
  - move an appointment to another valid start time;
  - change the assigned StaffMember;
  - edit the internal note;
  - cancel an eligible appointment;
  - see the appointment source (online or manual);
  - see a Customer's appointment history on the Customer detail.
```

**In "Scope", replace "Support both form-based and drag-and-drop appointment rescheduling." with:**

```text
- Form-based creation and rescheduling are the canonical, accessible operations. Drag-and-drop rescheduling is not part of this issue and will be tracked separately.
```

**In "Calendar presentation", replace "which appointments are upcoming, past, changed, completed, or cancelled according to the approved lifecycle;" with:**

```text
- which appointments are upcoming, past, or cancelled (derived from the lifecycle and the Business-timezone clock; changed and completed states are not shown in the MVP);
```

**Replace "Appointment changes: Changes may include:" list with:**

```text
Changes are limited to:

- moving the appointment to another valid start time;
- changing the assigned StaffMember;
- editing the internal note.

Changing the Services, the Customer, the duration, or the price of an appointment is not supported. The interface explains that the appointment must be cancelled and a new one created, and that these are two separate actions. Customer contact corrections use the Customer capability and never change the appointment's Customer.

A time-only move that keeps the same StaffMember validates the schedule and conflicts and does not re-check Service activity or assignment, so a visit the Business must honour is not lost. A reassignment requires the target StaffMember to be active and currently assigned to every stored Service. An appointment that has already started cannot be moved, reassigned, or cancelled.
```

**In "Form-based rescheduling", replace "a different active and assigned Service where permitted." with nothing (delete the line) and replace "a different eligible StaffMember where permitted;" with "a different eligible StaffMember;".**

**Delete the whole "Drag-and-drop rescheduling" section.** In "Concurrency behavior" delete the bullets "two users drag or move appointments toward overlapping time;" and "a drag-and-drop preview becomes stale before confirmation." In "Responsive and accessible UX" delete "Every drag-and-drop operation must have an equivalent keyboard-accessible form action." In "Product decisions to resolve during implementation" delete the two drag-and-drop bullets.

**Replace "Cancellation" with:**

```text
Business-side cancellation is CONFIRMED to CANCELLED only, allowed only before the appointment starts, never a deletion, and without a free-text reason. It requires explicit confirmation, identifies the appointment being cancelled, records when and by whom it was cancelled (the Business), preserves the appointment record and its history, releases the occupied time, shows safe Bulgarian success or failure feedback, and handles stale or repeated attempts safely. It coordinates with simultaneous Customer self-service cancellation (#35) through the same status check. The confirmation explains that creating a replacement is a separate action and that the cancellation cannot be undone.
```

**Replace "Role behavior" with:**

```text
Only an active BUSINESS_OWNER Membership of the selected Business can view the calendar and administer appointments: view the complete calendar, filter by StaffMember, inspect appointment and Customer details, create, reschedule, reassign, and cancel eligible appointments, and edit the internal note.

MANAGER, STAFF, PLATFORM_ADMIN alone, unauthenticated users, and Customers receive no calendar data. A StaffMember record without an authenticated account and active Membership gets no application access.

Manager and Staff calendar access, including the optional link between a Membership and a StaffMember, is a separate future capability.
```

**Replace "Business lifecycle behavior" with:**

```text
- ACTIVE Businesses can read and manage appointments and receive public bookings.
- DRAFT Businesses show a read-only calendar preview, allow no create, reschedule, reassign, cancel, or note edit (a specific safe rejection, distinct from the SUSPENDED one), and receive no public bookings.
- SUSPENDED Businesses preserve data, remain readable, allow no appointment mutation, and receive no public bookings.
- Reactivation restores owner management without data loss.
- The calendar states clearly when it is read-only.
```

**Add a section after "Appointment details":**

```text
## Customer appointment history

The Customer detail shows that Customer's appointments of the same Business, newest first, with server-side pagination (page sizes 10, 25, and 50). Each row shows only the date and time, the status, the source, the Service names, and the StaffMember, and opens the appointment detail through the same authorization. It shows no note and no contact data. The Customer module does not depend on the Booking module.
```

**In "Acceptance criteria", replace "An authenticated StaffMember can view at least their own schedule according to the approved access model." with:**

```text
- MANAGER, STAFF, and platform-only sessions receive no calendar data.
```

**Replace "Eligible future appointments can be moved, reassigned, or updated safely through a form." with:**

```text
- Eligible future appointments can be moved or reassigned safely through a form, and the internal note of an appointment can be edited.
- A Customer's appointment history is available on the Customer detail.
- A Business owner can create an appointment of one to five distinct Services performed by one StaffMember.
```

**Delete the four acceptance criteria about drag-and-drop** ("Eligible appointments can be moved through drag-and-drop…", "Drag-and-drop uses the same authoritative backend…", "Cancelling or rejecting a drag-and-drop move…", "Drag-and-drop has an equivalent keyboard-accessible…"); **replace "`DRAFT` Businesses remain blocked from public booking." with:**

```text
- DRAFT Businesses show a read-only calendar and remain blocked from public booking.
```

**Replace "Product decisions to resolve during implementation" with:**

```text
## Decisions (recorded in ADR-0028 and ADR-0029)

Resolved: the initial status of a Business-created appointment is CONFIRMED; completed and no-show are not introduced in the MVP; editable information is the start time, the StaffMember, and the internal note; the price and the duration never change; DRAFT is read-only; the sources are ONLINE and MANUAL; started appointments cannot be moved, reassigned, or cancelled; Business-side cancellation is allowed only before the start and has no reason; a concurrent update or conflict is reported by a distinct safe error and the calendar reloads the authoritative state; the agenda has a fixed chronological order.
Open for the rendered human review: the exact day and week layout, whether appointments are grouped by StaffMember or time, and the Bulgarian wording.
Deferred: printing, exporting, or sharing calendar data.
```

**Replace "Explicit exclusions" entries as follows:** delete "Multiple Services in one appointment." and "Real-time push synchronization…" stays; replace "Conflict-protection implementation." with "The database overlap protection itself (delivered by #18); Business-side conflict handling is part of this issue."; replace "Availability calculation implementation." with "Changes to the public availability rules (the separate Business-side availability policy is part of this issue)."; and add:

```text
- Drag-and-drop rescheduling (a separate future issue).
- Manager and Staff calendar access and Membership-to-StaffMember linkage.
- Changing the Services or the Customer of an appointment.
- Cancellation reasons and restoring a cancelled appointment.
- Completed and no-show states.
```

**In "Completion", replace "create, inspect, reschedule, drag, reassign, and cancel eligible appointments" with "create, inspect, reschedule, reassign, and cancel eligible appointments".**

## Phase 1 record (2026-10-14; documentation only; awaiting the product owner's review)

Base `HEAD` `f5a4393e9e319fa86b56b7c796704501c0b5ba76` plus the uncommitted documentation working tree (nothing staged). No application code, migration, test, configuration, dependency, or GitHub state changed.

### Files created and amended

Created (3): ADR-0028, ADR-0029, and this record.

Amended (19), by amendment notes and targeted reconciliation, preserving historical phase evidence: ADR-0013, ADR-0016, ADR-0022, ADR-0023, ADR-0025, ADR-0027 (an amendment note each), the ADR index, `README.md`, `docs/architecture.md`, `docs/data-model.md`, `docs/product-spec.md`, `docs/security.md`, `docs/testing-strategy.md`, `docs/implementation-plan.md`, `docs/product-roadmap.md`, `docs/ui-design-guidelines.md` (a navigation note and the new section 15.3), and tasks 07a, 08a (including the correction of a Customer-cancellation reference from #13 to #35), and 08b (current-status pointers only).

### What the completed reading changed in the records

The sources that were previously only searched were read line by line before the first edit: all of ADR-0025, all of task 08a (including the Phase 3 to 8 records), all of `AvailabilityQueryService`, `AppointmentStore`, `GuestBookingService`, `BookingAttemptProcedure`, `AppointmentSchemaIntegrationTests`, `AppointmentOverlapConcurrencyIntegrationTests`, `GuestBookingConcurrencyIntegrationTests`, `BookingBusyIntervalSourceIntegrationTests`, `BookingAvailabilityIntegrationTests`, `AppointmentFixtures`, `BookingModuleBoundaryTests`, `AvailabilityMutationInventoryTests`, and the opening of `CustomerModuleBoundaryTests` and `ScheduleRevisionModuleBoundaryTests`. None contradicted an approved decision. What they added:

- The Business-side availability entry must take the caller's single `now` and must not look up Services: `AvailabilityQueryService` reads its own clock once and derives the duration from the current Service rows (ADR-0029 section 1).
- `lockEligibleForBooking` checks activity and assignment but not Service activity, so it serves create and reassignment as is; a time-only move needs `StaffMemberReferenceAccess.lockReference` and a new Workforce schedule read, because `findEligibleForServices` filters by assignment (ADR-0029 sections 1 and 4).
- `BookingModuleBoundaryTests` pins the exact dependency set and the exact root types of `booking`; `ScheduleRevisionModuleBoundaryTests` pins the guard consumers; `AvailabilityMutationInventoryTests` fails on any unclassified `INSERT`, `UPDATE`, or `DELETE`; `CustomerModuleBoundaryTests` pins the Customer dependencies and its single controller mapping. All change with the planned edges (listed above).
- `AppointmentSchemaIntegrationTests` pins the exact columns, constraints, indexes, and migration list, and its `CANCELLED → CONFIRMED` test would assert the wrong constraint once the new CHECK exists (ADR-0028 section 8).
- `CustomerReferenceAccess` returns only an identifier, so display names and contact data need new narrow Customer read contracts.
- The audit of the interface showed that `Промени в графика` is a tab of `Работно време`, not a sidebar label (ADR-0028 section 14).
- An earlier statement of the audit, that no test proves the exclusion on `UPDATE`, was too broad; the precise gap is recorded in the starting facts.

### Existing test evidence reviewed (final Phase 1 review)

This is a review of existing evidence. No test was edited, and the statement is limited to the suites named here; it does not claim that every repository test was read.

**Read in full.**

- Overlap exclusion and Appointment persistence: `AppointmentSchemaIntegrationTests`, `AppointmentOverlapConcurrencyIntegrationTests`, `AppointmentStoreIntegrationTests`, `AppointmentServiceStoreIntegrationTests`, `AppointmentStoreFailureTranslationTests`, `AppointmentStoreFailures`, `AppointmentServiceMigrationIntegrationTests`, `AppointmentDomainTests`, `AppointmentVisitDomainTests`, `StaffAssignmentPolicyTests`.
- Guest booking create, retry, replay, and rejection: `GuestBookingServiceTests`, `GuestBookingIntegrationTests`, `GuestBookingRejectionIntegrationTests`, `GuestBookingReplayIntegrationTests`, `GuestBookingKeyRotationIntegrationTests`, `GuestBookingVisitIntegrationTests`.
- Races, schedule revision, and transaction completion: `GuestBookingConcurrencyIntegrationTests`, `GuestBookingScheduleRaceIntegrationTests`, `GuestBookingCommitFailureIntegrationTests`, `BookingContractsIntegrationTests`.
- Availability and busy time: `BookingAvailabilityIntegrationTests`, `BookingAvailabilitySelectionIntegrationTests`, `BookingBusyIntervalSourceIntegrationTests`, `BookingBusyIntervalSourceWiringIntegrationTests`.
- Public HTTP: `PublicBookingApiIntegrationTest`, `PublicBookingCreationApiIntegrationTests`, `PublicBookingResultMappingApiIntegrationTests`, `PublicBookingVisitApiIntegrationTests`, `PublicBookingAvailabilityApiIntegrationTests`.
- Support: `AppointmentFixtures`, `BookingIntegrationTest`, `BookingHookConfiguration`, `BookingTestHooks`, `TransactionLog`, `RecordingJpaTransactionManager`.
- Module boundaries: `BookingModuleBoundaryTests`, `PublicBookingModuleBoundaryTests`, `ScheduleRevisionModuleBoundaryTests`, `AvailabilityModuleBoundaryTests`, `CustomerModuleBoundaryTests`, `PublicProfileModuleBoundaryTests`, `ModuleBoundaryTests`, `AvailabilityMutationInventoryTests`.
- Frontend conflict recovery: `BookingJourney.states.test.tsx`, `BookingJourney.submission.test.tsx`, `BookingJourney.visit.test.tsx`, `attempt.test.ts`, `api.test.ts`.
- Final guest-booking browser journeys: `guest-booking-core`, `guest-booking-consistency`, `guest-booking-selection`, `guest-booking-time`, `guest-booking-simulated-transport`, and `guest-booking-experience` (all `.spec.ts`), `request-audit.spec.ts`, and `e2e/support/booking.ts`.

**Not read in full, and why that does not affect the records:** the frontend `BookingJourney.flow`, `.edit`, and `.calendar` tests and the `calendar`, `dates`, `money`, `selection` unit tests (step navigation and the date picker of the public journey, not conflict recovery); `BookingRateLimiterTests` and the public rate-limit, body-limit, and session-security API classes (the public abuse limiter, which this work does not change); the fingerprint and normalization tests (`FingerprintEncoding*`, `NormalizedBookingRequestTests`, `BookingAttemptIdTests`, `RequestFingerprinterTests`, `FingerprintKeyRingTests`, `BookingFingerprintConfigurationTests`; the public idempotency algorithm, which manual creation does not use); `ConcurrencyTestSupport` beyond its use in the suites above; the availability engine and Scheduling, Workforce, and Catalog suites (Phase 2 reads those before editing the engine).

**Findings.** None contradicts or changes ADR-0028, ADR-0029, or the phase plan. They sharpen the adaptation lists, which are recorded above under "Tests that change", and add the following.

1. **Raw-SQL cancellation fixtures.** These write `status = 'CANCELLED'` directly and would violate the strict `V14` check: `GuestBookingReplayIntegrationTests`, `GuestBookingIntegrationTests` (the `confirmedAppointment` helper), `PublicBookingVisitApiIntegrationTests`, `BookingAvailabilityIntegrationTests`, `AppointmentServiceStoreIntegrationTests`, `BookingBusyIntervalSourceIntegrationTests`, `AppointmentOverlapConcurrencyIntegrationTests`, `BookingContractsIntegrationTests`, and `AppointmentSchemaIntegrationTests`. One fixture helper sets the attribution.
2. **Boundary tests pin exact sets, and every change is deliberate.** `BookingModuleBoundaryTests` pins the dependency set of `booking` (exactly Business, Catalog, Workforce, Scheduling, Customer, and `shared.contact`), that only `publicbooking` depends on it (repeated in `ScheduleRevisionModuleBoundaryTests`), the exact root types, that `booking` has no controller, and a source check of `GuestBookingService` only. `ScheduleRevisionModuleBoundaryTests` pins the guard's consumers to the revision service and `BookingAttemptProcedure` and one declared method per contract. `CustomerModuleBoundaryTests` pins the Customer dependencies, its single controller, and the exact published root types. The new edges of ADR-0028 section 13, the new adapter module, the Business runner as a second guard consumer, and an analogous source check for the Business runner are therefore explicit test changes.
3. **The write inventory.** `AvailabilityMutationInventoryTests` fails on any unclassified `UPDATE appointment`; the new statements are classified, and the `callers == 5` bump count is unaffected because reschedule takes the guard and does not bump.
4. **Public-path invariants that must stay unchanged when the engine and busy-interval seam are parameterised:** the guest runner's one REPEATABLE READ transaction per attempt with no suspension; exactly two clock reads for a new Customer, one for a matched one, and none for a replay; the observed lock order (Business, StaffMember, revision, Service) and that a replay takes only the Business `FOR SHARE` lock; three bounded attempts; the deadlock and uncertain-outcome mappings; and the HTTP contract (key sets, fixed `instance`, `no-store`, no `Set-Cookie`, the precedence of the review-changed result).
5. **Replay after cancellation.** A cancelled Appointment replays to the guest with the `CANCELLED` status. After `V14` the store also loads `internal_note` and `cancelled_by`; the guest result and the replay must not carry either, and a sentinel test is added in Phase 2 (ADR-0028 sections 8 and 9).
6. **Test infrastructure to reuse.** The two-transaction race helper (a latch, then `pg_stat_activity` showing a `Lock` wait), `awaitBlockedBy` and `awaitWaiterBlockedBy` with `pg_blocking_pids`, `FOR UPDATE SKIP LOCKED` probes at hook points, `SET LOCAL deadlock_timeout` to fix a deadlock victim, `holdBeforeCommit`, the counting `DataSource` proxy (for the statement-count claims of Phase 3), and `RecordingJpaTransactionManager`. The three-contender burst accepts the documented deadlock-victim result, and the Business-side burst tests assert the same tolerance rather than a stronger claim.
7. **Frontend.** The public journey is independent of the private routes. Its code-to-message mapping and the `BOOKING_*` codes are untouched by the `APPOINTMENT_*` taxonomy, which is a separate Business-side map, and its history-state, storage, and cookie privacy checks are the pattern for the Business pages.

**Guest-path regression suites that must run whenever a scheduling, transaction, or Appointment-persistence contract changes** (focused set first, then the full verification): the `GuestBooking*` integration suites (`Integration`, `Concurrency`, `ScheduleRace`, `Visit`, `Replay`, `Rejection`, `CommitFailure`, `KeyRotation`, and `GuestBookingServiceTests`); `AppointmentOverlapConcurrencyIntegrationTests`, the Appointment store, schema, and migration suites; `BookingAvailabilityIntegrationTests`, `BookingAvailabilitySelectionIntegrationTests`, the `BookingBusyIntervalSource` suites, and `BookingContractsIntegrationTests`; the `PublicBooking*ApiIntegration` suites (availability, creation, visit, result mapping); the module-boundary and mutation-inventory tests; and, when the public journey or its HTTP contract could be affected, the `BookingJourney.*` and `attempt` and `api` unit tests and the `guest-booking-*` browser specs.

### Decisions confirmed at the final review

1. A time-only move requires the current StaffMember to remain active. A Service deactivated later or an assignment removed later does not block a time-only move with the same active StaffMember; an inactive StaffMember does (ADR-0028 section 6).
2. Manual creation requires the owner to select one concrete eligible StaffMember. There is no automatic assignment for the owner (ADR-0028 section 4).
3. Route names, problem codes (including `BUSINESS_NOT_ACTIVE`), and Bulgarian wording are working names; their exact contracts are finalised and tested in the implementing HTTP or interface phase.
4. Roadmap row 12 stays In progress with "decisions recorded; no code yet": documentation and architecture work count as started work.
5. Human visual approval is an explicit gate: Phase 11 is the formal review phase and Phase 12 does not start until the approval is recorded.
6. The `Промени в графика` tab is kept; the new destination is `График`; a rename is considered only if the rendered review proves the labels confusing.

### Verification (executed; documentation only; repeated after the final review corrections)

- `git diff --check`: clean (exit 0).
- A script (outside the repository) over every changed and new Markdown file checked final newlines, trailing whitespace on added lines, relative Markdown links, and table column counts against the previous version: 0 failures (22 changed paths, all Markdown).
- A scope check: every changed or new path is a `.md` file under `docs/` or `README.md`; no `backend/`, `frontend/`, migration, script, or configuration path changed.
- Searches for contradictions and stale status: no document states a rename of the `Промени в графика` tab as planned or final; no remaining "interpretation" or "to confirm" item; every Phase 7 and Phase 10 notification is stated to be not an approval; Phase 12 is blocked on the recorded Phase 11 approval; the only remaining `#13` cancellation mentions are the correction notes; every drag-and-drop mention states it is out of scope for #21; the manual `findOrCreate` option is decided away in ADR-0022's note; roadmap row 12 reads "In progress (decisions recorded; no code yet)".
- The application suites were deliberately not run (documentation only). Nothing was staged, committed, or pushed, and no GitHub state was changed. A documentation-only review archive was written beside the repository after these corrections; its name and checksum are in the review report (the archive contains this record, so they cannot be recorded here).

### Stopping point

Phase 1 stops here for review. Phase 2 needs a read-only plan and explicit approval before any edit.

## Phase 2 record (2026-10-14; implemented and verified; status update 2026-10-15: committed as `ad5a5a5`)

Base `HEAD` `0ed0e27b64121717667b9ed2ec15fd6616d37891` (clean, equal to `origin/main`; CI of that commit was still running at the preflight, which is advisory). Scope: issue #19, Strict. No HTTP, controller, orchestration runner, calendar read, `V15`, frontend, Manager or Staff access, drag-and-drop, or GitHub change.

### What was built

**`V14__add_appointment_administration.sql`** (V1 to V13 untouched: the SHA-256 of each is pinned in the V14 migration test and equals `HEAD`).

- Three nullable columns without defaults, after `service_count`: `cancelled_at timestamptz`, `cancelled_by varchar(16)`, `internal_note varchar(500)`. Adding them is a metadata-only change.
- Backfill: `UPDATE appointment SET cancelled_by = 'UNKNOWN' WHERE status = 'CANCELLED'`. `version`, `updated_at`, and every other column are untouched; `cancelled_at` stays null.
- `SET CONSTRAINTS ALL IMMEDIATE` after the backfill (see defect 2), then three constraints: `appointment_cancellation_consistent` (`CONFIRMED`: no time and no actor; `CANCELLED`/`BUSINESS`: a time; `CANCELLED`/`UNKNOWN`: no time; written with `IS NOT DISTINCT FROM`), `appointment_cancelled_at_valid` (finite and not before `created_at`), and `appointment_internal_note_plain_text` (the `customer_note` rule).
- A verification block that fails the migration on any `CONFIRMED` row with an attribution or any `CANCELLED` row that is not `UNKNOWN` with a null time. It deliberately does not judge `internal_note`: a valid note is accepted on `CONFIRMED` and `CANCELLED` rows alike (tested on `CONFIRMED`, Business-cancelled, and legacy rows).

**What the evidence establishes about rewriting (and what it does not).** The migration test asserts the `appointment` table's `relfilenode` is unchanged, which proves there was no full table-file replacement. It does not prove that no tuple was rewritten, and the backfill is a real `UPDATE`: the same test shows that the `xmin` of every pre-existing `CANCELLED` row changed (a new tuple version was written) while the `xmin` of every `CONFIRMED` row is identical. Page-level effects (HOT, bloat, free space) were not measured and nothing is claimed about them. The `ALTER TABLE` and the `UPDATE` take the usual table-level locks inside the one Flyway transaction.

**Domain** (`booking.domain`, JDK-only). `Appointment` gains `cancelledAt`, `cancelledBy` (`AppointmentCancelledBy { BUSINESS, UNKNOWN }`), and `internalNote`, mirrors the V14 invariant, and keeps compatibility constructors. `NoteText` is the neutral note policy used by both `NormalizedBookingRequest` and `AppointmentTransitions`; `NoteTextTests` proves it behaves identically to the extracted code (a verbatim copy of the old implementation over a seeded corpus), and a boundary test shows the transitions know nothing of the guest request. `AppointmentTransitions` holds the pure rules: `reschedule` (time-only or reassign; the stored duration; the target's snapshot name only on a real reassignment), `cancel` (`BUSINESS`, the attempt instant), and `editInternalNote` (any status or start; a blank note clears it). Refusals in the ADR-0029 order: stale version, not confirmed, already started, no change, start before now (and an invalid note first for the note). `NewAppointment.internalNote` is deferred to Phase 4.

**Store** (`AppointmentStore`). `reschedule`, `cancel`, and `updateInternalNote` are one conditional `UPDATE ... RETURNING` each, scoped by Business and carrying `version = :expectedVersion` (the reschedule also the read status, StaffMember, and start; the cancel also `status = 'CONFIRMED'` and `start_at > :now`). Zero rows is an empty `Optional` and does not abort the transaction. The returned version must be exactly one newer, otherwise an internal inconsistency. The three new check names are translated to `InvalidData` (28 checks, 6 foreign keys). `findBlockingWindows` gains an ignored-Appointment form that appends only `AND id <> :ignoredAppointmentId`; the four-argument statement text is unchanged.

**Availability.** `AvailabilityRequest` carries `minimumNotice`, `firstDate`, and `dateCount`; the five-argument constructor derives exactly the former public values, so every existing engine test compiled and passed unmodified. `AvailabilityPolicy` keeps the public constants and adds the Business ones. `BusyIntervalSource` gains a second abstract method (non-null ignored id). `AvailabilityQuery.calculateForBusiness(businessId, occupiedDuration, staffMemberId, firstDate, lastDate, now, ignoredAppointmentIdOrNull)` returns the new `BusinessAvailability` record. `StaffAvailabilityAccess.findActiveWithWeeklyPeriods(businessId, staffMemberId)` is the Workforce read. These names are final.

### Public-path equivalence

`PublicAvailabilityCharacterisationTests` pins 140 scenarios (4 zones including Sofia and half-hour-offset St. John's, 7 instants around daylight-saving transitions, 5 durations, two StaffMembers with overrides, additional periods, time off, closures, and 40 seeded busy intervals each) by count and SHA-256 of the full slot listing. The expectations in `golden/public-availability.txt` were recorded from the unmodified engine before it was changed, and the test passed on the unmodified code and again after. In addition: the unchanged engine, DST, input-validation, exception-input, service, and statement-count suites pass; the public path still uses five statements and the four-argument busy statement (asserted to contain no `id <>`); and mutation controls (below) show that changing the notice or the horizon fails the characterisation.

### Business availability and ignore-self evidence

`BusinessAvailabilityIntegrationTests` (PostgreSQL): a start exactly at `now` is offered, `now` between grid points starts at the next grid point, nothing earlier is offered, a date 90 days away is offered where the public policy offers none, the supplied duration decides the slot end, the window is at most 31 dates inside 2000-01-01 to 2100-12-31, the clock is read zero times, the schedule is found with the Service assignment removed and the Service inactive, an inactive, missing, or foreign StaffMember and a non-ACTIVE Business are typed refusals, a time-off day and the spring gap and both occurrences of the repeated hour behave per `ZoneRules`, and ignore-self: the Appointment blocks its own time unless named; only the named one is omitted while adjacent and overlapping neighbours still block; another StaffMember's, an unknown, or a foreign identifier changes nothing. `AvailabilityQueryStatementCountIntegrationTests` shows four statements (Business, StaffMember with periods, exceptions, busy), none on `service` or on the assignment table, and the same transaction-isolation precondition as the public method. The unit tests with mocks show no Service lookup, the correct busy method per case, and caller errors rejected before any read.

### Concurrency evidence (real PostgreSQL; nothing injected)

`AppointmentUpdateConcurrencyIntegrationTests` (12 tests), ordered by latches and `pg_stat_activity`/`pg_blocking_pids` only: two time-changing moves into one interval (the loser is observed blocked by the winner, then `OverlapConflict` with its row byte-identical, or succeeds if the winner rolls back); move versus insert and insert versus move; two reassignments into one StaffMember's interval; a reassignment not blocked by another StaffMember's time; a repeatable-read conditional update after a concurrent commit fails with `40001` (once with no lock wait, ordered by latches, and once observed waiting on the holder's row lock), while the same race at READ COMMITTED matches zero rows; two cancellations have exactly one winner whose attribution is kept. `AppointmentAdministrationStoreIntegrationTests` (17 tests) adds the non-concurrent proofs: a time-changing and a StaffMember-changing `UPDATE` into a live neighbour fail with the original and the neighbour byte-identical (JSON and `xmin`), a shift of 15 minutes inside the row's own interval succeeds (the exclusion check does not conflict with the row's own old tuple), a failed statement leaves the transaction aborted (`25P02`), a failed move rolls back an earlier update of the same transaction, and a zero-row update leaves the transaction usable. Not in Phase 2 (planned for Phases 4 to 6): the lock-order probes, the controlled exclusion-index deadlock, commit-uncertainty classification, and the service-level cancel-versus-move and note-versus-move races.

### Privacy

Replay of a cancelled Appointment whose `internal_note` is a sentinel and whose `cancelled_by` is `BUSINESS`: the guest result (an explicit record whitelist, asserted by reflection), its `toString`, captured logs of the whole root logger (service level) and captured output (HTTP level), the HTTP body and headers contain neither the note, the actor, the time, nor the column names. Translated store errors have fixed messages and no cause; the new update records and `Appointment` redact `toString`.

### Defects and plan assumptions discovered

1. **The ADR-0028 CHECK passed a `CANCELLED` row with no actor** (a `CHECK` accepts `NULL`); `V14` uses `IS NOT DISTINCT FROM`, ADR-0028 carries a correction and an amendment note, and a test evaluates the original expression to `NULL`.
2. **The backfill `UPDATE` failed the migration** (`cannot ALTER TABLE ... because it has pending trigger events`) because it queued the deferred V13 whole-visit trigger; fixed with `SET CONSTRAINTS ALL IMMEDIATE`, which also judges the V13 rule on the touched rows.
3. **PostgreSQL evaluates CHECKs in name order**, so a status outside the domain is now reported by `appointment_cancellation_consistent` before `appointment_status_valid`; five status cases of `AppointmentSchemaIntegrationTests` were adapted (the status rule is still asserted to exist).
4. **The Phase 1 fixture list was incomplete.** The audited direct-SQL cancellation sites were: `PublicBookingVisitApiIntegrationTests`, `GuestBookingReplayIntegrationTests`, `GuestBookingVisitIntegrationTests`, `PublicBookingCreationApiIntegrationTests`, `AppointmentServiceStoreIntegrationTests`, `BookingAvailabilityIntegrationTests`, `AppointmentServiceSchemaIntegrationTests` (two), `AppointmentSchemaIntegrationTests` (four), `AppointmentOverlapConcurrencyIntegrationTests`, `BookingBusyIntervalSourceIntegrationTests`, `BookingContractsIntegrationTests`, and `GuestBookingIntegrationTests`; all now use `AppointmentFixtures` (`cancelled`, `cancelledLegacy`, `cancel`, `cancelByReference`, `cancelAllConfirmed`, `cancelLegacy`).
5. **More tests pinned the latest migration** than the lists: `CustomerSchemaIntegrationTests` and `ScheduleExceptionSchemaIntegrationTests` compare `max(version)`, and five `migrate(..., null)` calls of `AppointmentServiceMigrationIntegrationTests` now target `"13"`. A first full run found the two count failures; they were corrected.
6. **Under repeatable read PostgreSQL reports `40001` for a concurrently changed row whether or not the version predicate is present**; the predicate is what protects READ COMMITTED and a stale expected version without a race. The mutation controls show which tests detect its loss (a source-shape test and the READ COMMITTED race).
7. `BusyIntervalSource` stopped being a functional interface; `BookingModuleBoundaryTests` kept every existing assertion (no new booking root type, dependency, or guard consumer; one test was added for the neutral note policy) and `ScheduleRevisionModuleBoundaryTests` needed no change; `AvailabilityMutationInventoryTests` changed (`APPOINTMENT_LIFECYCLE`, and a test that pins the shape of the three `UPDATE appointment` statements, because the inventory key cannot tell them apart).

### Mutation and control checks

Each mutation was applied to the working tree, the named suites were run, and the file was restored: a nullable actor (plain comparison in the CHECK) failed the schema matrix; accepting an invalid combination in the database (legacy branch without the null time) failed the matrix, and removing the domain invariant failed the domain tests; the ignore predicate dropped, or replaced by `false`, failed the busy-source, availability, and statement tests; the public notice changed to one hour, and the horizon to 29 dates, failed the characterisation; the Business notice set to two hours failed the Business availability tests; losing the version predicate of each of the three updates failed the inventory shape test and the store tests (the reschedule also the READ COMMITTED race), and a reschedule that does not advance the version failed the store and concurrency tests.

### Verification (executed)

Focused suites first, then one complete `./mvnw --batch-mode verify`: 3633 tests, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS`. An earlier complete run reported the two migration-count failures described above, which were corrected before the final run. New or extended test classes include (tests run in each, from the final run): `PublicAvailabilityCharacterisationTests` 1, `AppointmentAdministrationMigrationIntegrationTests` 8, `AppointmentAdministrationSchemaIntegrationTests` 31, `AppointmentTransitionsTests` 14, `AppointmentAdministrationDomainTests` 5, `NoteTextTests` 4, `AppointmentAdministrationStoreIntegrationTests` 17, `AppointmentUpdateConcurrencyIntegrationTests` 12, `BusinessAvailabilityIntegrationTests` 19, `AvailabilityPolicyParametersTests` 7, `AvailabilityQueryServiceBusinessTests` 9. No application suite outside the backend was run (no frontend change). Local result only; the exact-HEAD CI of the later commit is a separate report.

### Stopping point

Phase 2 stops here for review. Phase 3 needs its own read-only plan and explicit approval.

## Phase 3 record (2026-10-14; implemented and verified locally; status update 2026-10-15: committed as `860c1e9`, CI of that commit succeeded)

Base `HEAD` `ad5a5a55ca9b280f6ff9d12ed5ebfa9e618edf82` (Phase 2, clean, equal to `origin/main`). **CI observed at the start (advisory, read-only):** run 38619441380 for that commit had `frontend` and `backend` successful and `e2e` in progress; it did not affect the work. The same change also amends the durable workflow instructions (`AGENTS.md`, `CLAUDE.md`, `docs/testing-strategy.md`): green CI is advisory, not a hard gate, for starting the next phase. Scope: issue #21, Strict. No mutation endpoint, options route, create, reschedule, reassign, cancel, note-edit operation, frontend, drag-and-drop, or Manager or Staff access.

### Finalised HTTP contracts

All four routes are `GET`, owner-only, derive the Business and the user only from the authenticated context (a `businessId` parameter is ignored), need no CSRF token, and answer `Cache-Control: no-store`. Dates are canonical `YYYY-MM-DD`, inclusive Business-local, `to >= from`, at most 31 dates, within 2000-01-01 to 2100-12-31. `page` is 0-based, `size` is 10, 25, or 50 (default 10); a page past the end is an empty list with the real total. Timestamps are ISO-8601 with the Business-local offset; prices are exact two-decimal numbers.

| Route | Query | Success keys (in order) |
|---|---|---|
| `GET /api/business/calendar` | `from`, `to`, optional `staffMemberId` | `businessStatus, timezone, from, to, days[date,start,end], staffMembers[id,displayName,active,workingIntervals[start,end],closures[date,kind]], appointments[id,status,source,start,end,staffMemberId,staffDisplayName,customerId,customerDisplayName,durationMinutes,services[name,durationMinutes]]` |
| `GET /api/business/appointments` | as above plus `page`, `size` | `businessStatus, timezone, from, to, items[same row as appointments], page, size, total` |
| `GET /api/business/appointments/{appointmentId}` | none | `businessStatus, timezone, id, reference, status, source, start, end, staffMemberId, staffDisplayName, customer[id,displayName,phone,email], services[position,name,durationMinutes,price], durationMinutes, price, customerNote, internalNote, cancellation (null unless cancelled)[cancelledAt,cancelledBy], version, createdAt, updatedAt` |
| `GET /api/business/customers/{customerId}/appointments` | `page`, `size` | `timezone, items[id,status,source,start,end,staffDisplayName,serviceNames[]], page, size, total` |

Orders: calendar and agenda by `(start_at, id)`; Customer history by `(start_at DESC, id DESC)`; Service lines by stored position; calendar StaffMembers by normalized display name then identifier; closures and intervals by date and start. `status` is `CONFIRMED` or `CANCELLED`, `source` is `ONLINE` or `MANUAL`, `cancelledBy` is `BUSINESS` or `UNKNOWN`, `businessStatus` is `DRAFT`, `ACTIVE`, or `SUSPENDED`, and a closure `kind` is `BUSINESS_CLOSURE` or `STAFF_TIME_OFF`.

**Authorization and lifecycle.** Anonymous is `401 AUTH_REQUIRED`; no selection, platform authority alone, and an inactive, missing, or foreign Membership are `403 ACTIVE_BUSINESS_REQUIRED`; an active Manager or Staff is `403 ACCESS_DENIED`; `DRAFT`, `ACTIVE`, and `SUSPENDED` are all readable. Failures: `400 VALIDATION_ERROR` ("Проверете въведените данни.", no field named, no value echoed), `404 APPOINTMENT_NOT_FOUND` ("Часът не е намерен."), `404 CUSTOMER_NOT_FOUND` ("Клиентът не е намерен."), `404 STAFF_MEMBER_NOT_FOUND` ("Членът на екипа не е намерен."), `500 INTERNAL_ERROR`. **Fixed problem instances** (the Customer API convention: the route family's base path, never an id or the raw path): `/api/business/calendar`, `/api/business/appointments` (agenda and detail), `/api/business/customers` (Customer history). A missing and a foreign identifier of the same resource type give byte-identical responses; different resource types have different codes and messages by design. The default `about:blank` type is omitted from the body.

**Inactive StaffMembers.** The unfiltered calendar lists every active StaffMember and an inactive one only when it has at least one visible Appointment in the window; an explicit filter may name an inactive same-Business StaffMember (its calendar and Appointments are returned); an inactive StaffMember has empty intervals and no closure markers. Tested: an active StaffMember without Appointments, an inactive one with an Appointment in the range, an inactive one without, an inactive one with an Appointment only outside the range, the filter on an inactive one, and missing and foreign filters.

**Working-time facts.** Per StaffMember the net working intervals as instants (the override or the recurring periods plus additional periods, resolved through the zone rules, minus partial Business closures and partial time off), and a closure marker exactly for a date covered by a full-day Business closure (which wins) or a full-day StaffMember time off, whether or not the person would otherwise work that day; a full-day exception also hides additional periods of that date, as in the availability engine. A partial exception is only the net intervals. A daylight-saving gap contributes no time and a repeated hour both occurrences; a day is 23 or 25 hours. This is not an availability engine: no notice, horizon, grid, Service duration, or Appointment is applied, and nothing claims the frontend can derive bookability from it.

### Modules

New `calendar` module (`calendar.web`: three controllers, one shared problem builder with three thin advices, the wire records) depending only on the published root types of `booking` and the published `identity` context type. `booking` publishes `AppointmentCalendar`, `AppointmentCalendarRecords`, and `AppointmentCalendarException`, adds the internal `AppointmentReadStore` and `AppointmentCalendarService`, and uses exactly three identity types (`AuthenticatedBusinessContext`, `SelectedBusinessOwnerAccess`, `SelectedBusinessRequired`). `customer` publishes `CustomerDirectory` (display names for an id set, one contact), `workforce` publishes `StaffCalendarAccess`, and `scheduling` publishes `WorkingTimeOverview` over a pure `WorkingTimeCalculator`. Boundary tests changed deliberately: `BookingModuleBoundaryTests` (dependency set with `identity`, the exact root types, the adapters that may depend on `booking`, an exact-identity-types test, a read-only-service shape test), `ScheduleRevisionModuleBoundaryTests` (the adapters list), `CustomerModuleBoundaryTests` (the published contract), `BusinessCustomerAuthorizationApiIntegrationTests` (the one deliberate extra route under the Customer path), and the new `CalendarModuleBoundaryTests`.

### Transactions and statement counts

Each operation is one read-only `REPEATABLE READ` transaction; its first statement (the Business context) fixes the snapshot. The maximum statements, asserted by `BusinessCalendarTransactionIntegrationTests` through the published contract (so the session filter's statements are not counted), and independent of the number of Appointments, Services, Customers, and StaffMembers (checked with 1 and with 36 five-line visits for 36 Customers and 7 StaffMembers):

| Operation | Maximum (something found) | Empty or past the end |
|---|---|---|
| Calendar | 7 (context, owner, Appointments, StaffMembers, exceptions, lines, Customer names) | 5 |
| Agenda | 6 (context, owner, count, page, lines, names); 7 with a StaffMember filter | 3; 4 with a filter |
| Detail | 5 (context, owner, row, lines, Customer contact) | 3 when missing |
| Customer history | 6 (context, owner, Customer reference, count, page, lines) | 4 |

Snapshot consistency is proved with a one-shot hook on the recording `DataSource` that runs a commit on another thread, joined before the read continues, at a chosen point between two statements (no sleep): between count and page (three new Appointments and a cancellation), between the Appointments and the Customer names (a rename and a new Appointment), before the StaffMember read (a rename and a deactivation), before the Customer contact (a rename), and before the history count and the history lines. Every read returned the state of its first statement, and the next read the committed state. The same tests fail when the isolation is lowered to `READ COMMITTED` (six failures).

### `V15` decision (measured; deviation from the guidance thresholds explained)

Data: deterministic synthetic Appointments, 8 Businesses (60% in one), 12 StaffMembers in the large Business with one at about 29% of its load, 3,000 Customers with one holding about 2,100 Appointments at 50,000 rows, about 15% cancelled (a third legacy `UNKNOWN`), one Service line each, confirmed rows non-overlapping. Production SQL from `AppointmentReadStore`; `EXPLAIN (ANALYZE, BUFFERS)`, the first execution reported separately from the median of seven repeats. All statements filter by `business_id` in every plan. Existing indexes: the Business-leading unique indexes (`business_id, id`, `public_reference`, `booking_attempt_hash`), and the confirmed-only exclusion index, which cannot serve a read that must also show cancelled rows. At 50,000 Appointments (table 13.7 MB, existing indexes 15.1 MB), large Business 30,000 rows:

| Read (result rows) | Baseline median / buffers | Shipped (`V15`) median / buffers | Notes |
|---|---|---|---|
| Calendar week, all staff (287) | 1.50 ms / 1,030 | 0.063 ms / 27 | baseline reads the whole Business through the unique-index prefix and sorts |
| Calendar 31 dates (1,274) | 1.55 ms / 1,030 | 0.25 ms / 66 | |
| Calendar week, small Business (20) | 0.088 ms / 71 | 0.007 ms / 20 | |
| Week, heaviest StaffMember (83) | 1.29 ms / 1,030 | 0.033 ms / 27 | |
| Agenda page 0, size 10 | 1.39 ms / 1,030 | 0.007 ms / 13 | `LIMIT` stops early on the index order |
| Agenda page 20, size 50 | 1.44 ms / 1,030 | 0.26 ms / 66 | |
| Agenda count, 31 dates | 1.27 ms / 1,030 | 0.12 ms / 66 | |
| History page 0, typical Customer | 1.29 ms / 1,674 | 0.006 ms / 13 | baseline is a sequential scan of the whole table across every tenant |
| History page 0, heaviest Customer | 1.45 ms / 1,674 | 0.007 ms / 10 | |
| History deep page (offset 1,000 of 2,132) | 1.54 ms / 1,674 | 0.59 ms / 916 | bitmap scan and top-N sort; the deepest case, still bounded |
| History count, heaviest Customer | 1.21 ms / 1,674 | 0.29 ms / 916 | |

At 10,000 Appointments the same reads cost 0.25 to 0.32 ms / 207 to 335 buffers unindexed and 0.004 to 0.06 ms / 4 to 25 buffers with the indexes. Going from 10,000 to 50,000 multiplied every unindexed buffer count by about 5 (207 to 1,030, 335 to 1,674) while the indexed counts stayed flat, i.e. the unindexed window read grows with a Business's lifetime Appointments and the unindexed history grows with every tenant's Appointments. That growth, not the (sub-millisecond, fully cached) absolute times at these sizes, is why the indexes are justified. **Decision: create `appointment_business_start_idx (business_id, start_at, id)` and `appointment_business_customer_start_idx (business_id, customer_id, start_at DESC, id DESC)`; reject `(business_id, staff_member_id, start_at, id)`.** The StaffMember candidate improves a filtered week from 27 to 12 buffers and a filtered month from 66 to 7 (0.03 to 0.012 ms, 0.07 to 0.009 ms) once the first index exists, which is bounded by the window rather than by lifetime volume, not unsafe, and not worth its 3.9 MB and extra write cost. Sizes at 50,000: first index 2.97 MB, third 3.89 MB (rejected one 3.89 MB). Write cost (all three candidates, so an upper bound for the two shipped): bulk insert of 10,000 Appointments 397 to 429 ms (+8%), 300 single visit inserts 133 to 161 ms (about +0.09 ms each); cancel and note updates touch no indexed column, and a reschedule already rewrites the start in the exclusion index. After `V15`, no plan of the measured queries uses a sequential scan, and the lines read keeps using the `appointment_service` indexes led by `appointment_id` (for very large id lists in a dataset where the Business holds most lines the planner may use the Business-led index instead; recorded, no index added). Phase 3 added exactly one migration, `V15`; `V1` to `V14` are byte-identical (SHA-256 pinned in `AppointmentReadIndexMigrationIntegrationTests`).

### Evidence

New or extended suites (tests run in the final complete run): `BusinessCalendarReadApiIntegrationTests` 21, `BusinessCalendarSecurityApiIntegrationTests` 18, `BusinessCalendarTransactionIntegrationTests` 11, `AppointmentReadStoreIntegrationTests` 6, `AppointmentReadExplainIntegrationTests` 3, `AppointmentReadIndexMigrationIntegrationTests` 4, `WorkingTimeCalculatorTests` 14, `WorkingTimeOverviewIntegrationTests` 2, `StaffCalendarAccessIntegrationTests` 4, `CustomerDirectoryIntegrationTests` 4, `AppointmentCalendarRecordsTests` 2, `CalendarModuleBoundaryTests` 7. The migration-version lists and the two `max(version)` assertions moved to 15, the V14 migration tests are pinned to target 14, and the schema test lists the two new indexes. **Mutation controls** (applied, the named suites run, the file restored; every mutant was detected): omitted tenant predicate on the window, the detail, the lines, and the history count; the overlap edge changed to `>=` and `<=`, and the 480-minute prefilter reduced; an inactive StaffMember with an Appointment omitted; every inactive StaffMember included; N+1 loading of lines and of Customer names; `READ COMMITTED` instead of `REPEATABLE READ`; the internal note and the cancellation actor leaked into list rows and into the history; and a missing and a foreign Appointment made distinguishable by dropping the tenant predicate of the detail.

**Verification (executed).** Focused suites while developing; the final complete `./mvnw --batch-mode verify`: 3731 tests, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS` (3633 after Phase 2). An earlier complete run reported one failure, the Customer endpoint-inventory test, which correctly noticed the new route under the Customer path; it was updated deliberately (the one extra pattern is asserted exactly). `git diff --check` is clean; no changed Java file has a compressed empty body or a wildcard import; the frontend was not touched and no frontend suite was run. Local result only; the exact-HEAD CI of the later commit is a separate report.

### Deviations, defects, and limitations

1. The plan's count for a page past the end was 4 and the code issues 3: the page read is skipped when the count shows nothing to read; the Javadoc and this record state 3 (4 with a filter).
2. The unfiltered calendar reads the Appointments before the StaffMembers (the inclusion rule needs their StaffMembers), so the order differs from the plan's list; the filtered calendar reads the StaffMember first so a missing filter is a `404` before any Appointment read. Both stay at 7.
3. A text-block concatenation without a separating newline broke the StaffMember read; the API tests caught it immediately. A first draft of the API tests used a phone number the Customer domain rejects, which surfaced as a `500` through the sanitizing path and led to a fixture correction, not a product change.
4. The "cold" measurement is the first execution after loading and analyzing: a truly cold cache cannot be forced in a container, the operating-system cache may be warm, and all absolute times are sub-2 ms because the data fits in memory; the argument rests on buffer counts and their growth.
5. The data has one Service line per Appointment and a single Service per Business in the large datasets; five-line visits are covered functionally, not by the measurement.
6. The responses use the current Business timezone for local labels; a later timezone change keeps instants but changes labels. No upper bound is placed on `page`; a huge page costs a count and returns an empty list.
7. The `?staffMemberId` filter, the pages, and the dates are in the URL by design (no Customer or private value ever is).

### Stopping point

Phase 3 stops here for review. Phase 4 needs its own read-only plan and explicit approval.

## Phase 4 record (2026-10-15; implemented, verified locally, awaiting the product owner's review; nothing staged or committed; status update 2026-10-16: committed as `260e81d`)

Base `HEAD` `860c1e9ccac93e71c867e8493ccc88557e922983` (Phase 3, clean, equal to `origin/main`). **CI observed at the start (advisory, read-only):** run 38688215167 for that commit completed successfully; it did not delay the work. Scope: issue #19, Strict. The product owner approved the plan and the decisions Q1 to Q8 below before any edit. No migration, no frontend, no options route, no reschedule, reassign, cancel, or note-edit operation, no manual idempotency, and no GitHub change.

### Approved decisions

| # | Decision |
|---|---|
| Q1 | `GET /api/business/appointment-options` is deferred. It needs a plain-read Catalog duration contract, so it is introduced as its own Strict backend step **before the Phase 8 form that consumes it** (and shared by the Phase 5 reschedule form); the API table above says so. Nothing speculative was added to Catalog. |
| Q2 | Every Service claim carries `reviewedServiceName`; name, minutes, and cents are compared exactly with the locked row, paired by Service identifier; any difference is `409 APPOINTMENT_REVIEW_CHANGED` with no write and no indication of which Service. ADR-0028 and ADR-0029 carry implementation notes. |
| Q3 | A missing, foreign, or inactive Service is one `409 APPOINTMENT_SERVICE_UNAVAILABLE`; a missing, foreign, inactive, or unassigned StaffMember one `409 APPOINTMENT_STAFF_UNAVAILABLE`; a missing and a foreign Customer one byte-identical `404 CUSTOMER_NOT_FOUND`. `security.md` and ADR-0028 are amended. |
| Q4 | After authorization and lifecycle: domain bounds, the Business-scoped plain Customer read, the locks in the ADR-0029 order, then Service availability, StaffMember eligibility, the reviewed terms, and the slot. The Customer read is not a lock level. |
| Q5 | The minimal `CreatedAppointment` response (no Customer name, phone, email, or Customer note). |
| Q6 | Typed, fixed, cause-free exceptions in the published `AppointmentAdministration` contract; the guest `BookingResult` is not reused. |
| Q7 | The owning module translates the retryable PostgreSQL failures of the Business schedule-context lock (`BusinessScheduleContextAccess.lockScheduleContextSanitized`) and the owner Membership lock (`SelectedBusinessOwnerAccess.lockAndAuthorizeSanitized`) into published, cause-free `ConcurrentConflict` and `Failure`. The Business runner and attempt body read no SQLState, driver, Spring `dao`, or `jdbc` type (a boundary test pins it). The older lock methods are unchanged for their other callers. |
| Q8 | The adapter reads the shape only (exact keys, canonical UUIDs, integral numbers, an offset-bearing start); the service applies the domain bounds after authorization and lifecycle. |

### Contract

`POST /api/business/appointments`, JSON, CSRF required, owner only, `ACTIVE` Business only. Request keys, exactly: `customerId`, `staffMemberId`, `start` (ISO-8601 with an explicit offset), `services[]` (each exactly `serviceId`, `reviewedServiceName`, `reviewedDurationMinutes`, `reviewedPriceCents`), and optional `internalNote`. Success: `201` with `Location: /api/business/appointments/{id}`, `Cache-Control: no-store`, and the keys `id, reference, status, source, start, end, timezone, staffMemberId, staffDisplayName, customerId, services[position,name,durationMinutes,price], durationMinutes, price, internalNote, version, createdAt` (start and end carry the Business-local offset; prices are exact two-decimal numbers). The stored row is `MANUAL`, `CONFIRMED`, version 0, with null idempotency columns, a null `customer_note`, and the supplied note (normalized with `NoteText`, at most 500 code points). The fixed problem `instance` is `/api/business/appointments`. Failures (all Bulgarian wording Proposed until Phase 11, approved for implementation): `400 VALIDATION_ERROR` "Проверете въведените данни."; `401 AUTH_REQUIRED`; `403 ACTIVE_BUSINESS_REQUIRED`; `403 ACCESS_DENIED` "Нямате достъп до тази операция."; `404 CUSTOMER_NOT_FOUND` "Клиентът не е намерен."; `409 BUSINESS_NOT_ACTIVE` "Бизнесът не е активен. Часове могат да се създават само в активен бизнес."; `409 BUSINESS_SUSPENDED` "Спрян бизнес може само да преглежда данните си."; `409 APPOINTMENT_SERVICE_UNAVAILABLE` "Някоя от избраните услуги вече не е налична."; `409 APPOINTMENT_STAFF_UNAVAILABLE` "Избраният член на екипа не е наличен за тези услуги."; `409 APPOINTMENT_REVIEW_CHANGED` "Услугите са променени. Прегледайте ги отново и опитайте пак."; `409 APPOINTMENT_SLOT_UNAVAILABLE` "Избраният час вече не е свободен."; `503 APPOINTMENT_TEMPORARILY_UNAVAILABLE` "Часът не беше запазен. Опитайте отново след малко."; `503 APPOINTMENT_OUTCOME_UNCERTAIN` "Не е ясно дали часът е запазен. Проверете графика, преди да опитате отново."; `415 UNSUPPORTED_MEDIA_TYPE` "Заявката не може да бъде изпълнена."; `500 INTERNAL_ERROR` "Възникна неочаквана грешка.". Authorization and lifecycle: an active `BUSINESS_OWNER` of the selected Business proceeds; an active `MANAGER` or `STAFF` is `ACCESS_DENIED`; no selection, an inactive or foreign Membership, and a platform administrator alone are `ACTIVE_BUSINESS_REQUIRED`; anonymous is `AUTH_REQUIRED`; `DRAFT` is `BUSINESS_NOT_ACTIVE` and `SUSPENDED` is `BUSINESS_SUSPENDED`, both before any domain validation.

**Stored order (verified, not invented).** ADR-0027 section 2 and `ServiceStore.lockReferencesInProfileOrder` define the execution order as `normalized_name ASC, id ASC`; the plan's phrase "(canonical name, then identifier)" was shorthand and is corrected here. The locking statement itself locks in ascending identifier order (`ORDER BY id ASC FOR SHARE` inside a materialized CTE), the result is returned in the profile order, `appointment_service.position` is that order (1 to n), the headline columns equal line 1, and the response lines use the stored position. The client's list order has no effect (a test creates the same visit from two orders). Claims are paired with locked rows by Service identifier.

### Transaction, locks, retry, uncertainty

`BusinessAppointmentAdministrationService` is not `@Transactional`; its entry guard rejects a caller transaction or synchronization before any clock read or statement; each of at most three attempts is a distinct `REQUIRED`, `REPEATABLE_READ`, read-write transaction with no `REQUIRES_NEW`, savepoint, sleep, or backoff. Statement order: the Business schedule context `FOR SHARE` (the first statement, which fixes the snapshot and supplies status and timezone), the owner Membership `FOR SHARE`, the lifecycle and domain-bound decisions, the plain Customer read, the eligible StaffMember `FOR SHARE`, the schedule revision `FOR SHARE`, all selected Services `FOR SHARE`, the decisions (Service, StaffMember, terms), one clock read (truncated to microseconds), the Business-side availability for the Business-local date of the requested instant in the locked timezone, the offered-slot check, the public reference, the insert of the Appointment and its lines (the Customer foreign key's implicit `KEY SHARE`), and a read-your-write. Lock probes at every hook point show the held rows grow in exactly that order and that the Customer row is not locked before the insert. Retried (a new transaction): the sanitized conflicts of the Business schedule context, owner Membership, StaffMember, schedule revision, and Service locks, the Appointment persistence concurrency failure, a duplicate generated reference, and an overlap. Never retried: authorization, lifecycle, Customer not found, Service or StaffMember unavailable, review changed, an unoffered slot, validation, and a commit that may have happened. Three overlaps end `APPOINTMENT_SLOT_UNAVAILABLE`; any other exhaustion `APPOINTMENT_TEMPORARILY_UNAVAILABLE`. Completion: Spring's report is authoritative; `COMMITTED` plus a later failure is uncertain; `ROLLED_BACK` was first recorded here as a proven rollback that is **not** retried **(superseded: see "Phase 4 review corrections" below; it is retried)**; `UNKNOWN` or no report is uncertain whatever its content; a normal completion gets one primary-key read outside any transaction (a missing row is a known rollback, a failed read is uncertain).

### Evidence

Real PostgreSQL, the real orchestration, deterministic coordination only (latches, hook points, `pg_blocking_pids`, `FOR UPDATE SKIP LOCKED` lock probes, a fixed deadlock victim by PostgreSQL itself; no sleeps). Tests run in the final complete run: `ManualVisitTermsTests` 10, `BusinessAppointmentAdministrationServiceTests` 50, `ManualAppointmentCreationIntegrationTests` 30, `ManualAppointmentConcurrencyIntegrationTests` 24, `ManualAppointmentScheduleRaceIntegrationTests` 41, `ManualAppointmentCommitFailureIntegrationTests` 10, `BusinessAppointmentCreateApiIntegrationTests` 20, `BusinessScheduleContextLockTranslationTests` 12, `OwnerMembershipLockTranslationTests` 9, `CreateAppointmentRequestParserTests` 11, `BusinessAppointmentExceptionHandlerTests` 2; the three boundary classes `BookingModuleBoundaryTests` 15, `CalendarModuleBoundaryTests` 7, and `ScheduleRevisionModuleBoundaryTests` 7 changed deliberately.

**Real versus injected.** Real: every lock wait, every `40001`, `23P01`, and deadlock, the retry in distinct PostgreSQL transactions with fresh snapshots (asserted by transaction identifier and snapshot), the real after-commit failure (the visit exists), the real pre-commit rollback (nothing exists, not retried), a swallowed failed statement (a fixed internal failure), a silent rollback found only by the verification read, and a terminated backend before commit (uncertain, nothing stored). Injected and labelled so in `BusinessAppointmentAdministrationServiceTests`: the completion statuses `COMMITTED`, `ROLLED_BACK`, `UNKNOWN`, and no report, the conflict types per module, and a conflict-shaped SQLState inside an exception (proving the runner never reads one). Not covered, by design: the Phase 5 and 6 orchestration races. The neighbouring-Appointment scenarios use only the Phase 2 persistence primitives (`AppointmentStore.cancel` and `reschedule`, and fixture inserts); a deterministic `40P01` through the exclusion index is not forced (the simultaneous-insert test accepts whichever PostgreSQL resolves and asserts one winner and a loser that retried in new transactions, two or three attempts); a Customer cannot become unavailable (ADR-0019 gives Customers no lifecycle), so only the missing and foreign cases and the non-blocking contact update are tested.

**Concurrency scenarios covered.** Two owners and the same slot; simultaneous exclusion-index inserts; manual versus guest booking in both orders; Service deactivation after the snapshot, in flight, and waiting for the creation; StaffMember deactivation and assignment removal in the same three shapes; the thirteen schedule paths (weekly replacement, and create, replace, and delete of closure, time off, working-day override, and additional periods) in three commit orders, with a guard-bypass control run showing the interleaving is the stale-availability race the guard stops; Business suspension in flight and after the snapshot; Membership deactivation between the first two locks; a Service term change after the snapshot becoming `REVIEW_CHANGED` on the retry; a concurrent Customer contact update that does not wait; a neighbour cancelled or moved after the snapshot; an overlap on every attempt; a concurrency failure on every attempt; many creations of one time; an unrelated Business unaffected.

**Statement count.** A successful creation issues 16 statements through the recording `DataSource` (the Business lock, the Membership lock, the Customer read, the StaffMember lock, the revision lock, the Service lock, four availability reads, the Appointment and line inserts, the read-your-write, and the post-commit verification read), identical for one and for five Services (`BusinessAppointmentCreateApiIntegrationTests`).

**Mutation controls** (each applied, the named suites run, and the file restored byte for byte; the SHA-256 of every file before and after is equal): missing owner check, missing `DRAFT` check, missing `SUSPENDED` check, missing Customer tenant predicate, missing StaffMember lock, missing schedule-revision guard, missing Service lock, revision lock before the StaffMember lock, the reviewed name, the duration, and the price ignored one at a time, a client total accepted by the parser, the public two-hour notice, the source changed from `MANUAL`, attempt fields accepted on `MANUAL`, a fourth attempt, a retry after `COMMITTED`, the entry guard removed, `READ_COMMITTED`, the raw Business and Membership persistence exceptions leaked, each of the two conflict translations turned into a plain failure, the internal note logged, and the internal note exposed to a guest replay. **All 25 mutants were killed.** One survived on the first run (the client-total mutant) because three request tests put their extra key inside the Service element instead of the root; the tests were corrected with a root-key helper and the mutant is killed.

**Verification (executed).** Focused suites while developing; the final complete `./mvnw --batch-mode verify`: **3953 tests, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS`** (3731 after Phase 3). A first complete run reported two failures, both corrected before the final run: `BusinessCalendarSecurityApiIntegrationTests.onlyGetIsRoutedAndEveryOtherVerbIsRefusedWithoutChangingAnything` correctly noticed that `POST /api/business/appointments` is now routed (it now asserts `400` with an empty body for that one path and keeps every other verb refused), and `PublicBookingCreationApiIntegrationTests.aReplayReturnsTheOriginalSnapshotsAfterTheServiceAndStaffMemberChange` failed intermittently because it asserted that a response does not contain the bare digits `99` while the response holds a random Service name (a UUID) that can contain them; this pre-existing test-data collision (not caused by this phase) was diagnosed rather than rerun, and the assertion now names the changed price `99.00` and the changed duration. `V1` to `V15` are byte-identical to `HEAD` (SHA-256 compared file by file); `git diff --check` is clean; no changed Java file has a compressed empty body, a wildcard import, or trailing whitespace; the frontend was not touched and no frontend suite was run. Local result only; the exact-HEAD CI of the later commit is a separate report.

### Deviations, defects, and limitations

1. The Phase 4 scope in the phase plan listed the options route; it is deferred (Q1).
2. The plan said the lines are stored in "(canonical name, identifier)" order; the committed rule is `normalized_name ASC, id ASC` and is what is used.
3. The older Business and Membership lock methods were left untouched for their other callers; two additive methods were added to two published interfaces, so seven test wrappers of those interfaces gained a delegating method, and `BookingModuleBoundaryTests`, `CalendarModuleBoundaryTests`, `ScheduleRevisionModuleBoundaryTests`, and one calendar security test changed deliberately (none weakened).
4. The entry guard reuses `BookingOrchestrationFailure`, whose message said "Guest booking"; corrected below to a neutral message; it is never shown to a client (it maps to `INTERNAL_ERROR`).
5. A request of another media type was an unhandled `500`; this route now answers `415 UNSUPPORTED_MEDIA_TYPE` (the older private routes were not changed).
6. ~~The Business runner never retries a commit-phase failure.~~ Superseded by the Phase 4 review corrections below: a proven commit-phase rollback is retried.
7. A start must be on the 15-minute grid and not before the clock instant: a walk-in at 10:07 is offered from 10:15, and a visit that already happened cannot be recorded.
8. The exclusion-index deadlock can add about one second of latency; a burst can end `APPOINTMENT_TEMPORARILY_UNAVAILABLE`. No idempotency exists, so a lost response can show a misleading `APPOINTMENT_SLOT_UNAVAILABLE` and the owner must check the calendar.
9. The `503` responses were first proven only by the exception-handler mapping test; the review corrections below add deterministic route-level evidence.

### Stopping point

Phase 4 stops here for review. Phase 5 needs its own read-only plan and explicit approval.

### Phase 4 review corrections (2026-10-15; implemented and verified locally; nothing staged or committed)

Narrow corrections applied after the product owner's review; the sections above remain the dated record of the first review. **1. Proven commit-phase rollback is retried.** The first review's rule (a `ROLLED_BACK` completion after a `Created` body is temporarily unavailable and not retried) contradicted the approved bounded retry and is replaced: completion `COMMITTED` followed by a failure is `APPOINTMENT_OUTCOME_UNCERTAIN` and never retried; `ROLLED_BACK` is a proven rollback, so the complete operation is retried in a fresh `REPEATABLE_READ` transaction; `UNKNOWN` or no report is uncertain and never retried; three proven rollbacks end `APPOINTMENT_TEMPORARILY_UNAVAILABLE` and a fourth attempt never occurs. A normal completion whose post-commit verification row is absent remains a known rollback without automatic retry, a failed verification read remains uncertain, overlap exhaustion remains slot unavailable, and every sanitized pre-commit conflict is retried as before. No cause, SQLState, driver, or Spring `DataAccessException` is read (the boundary tests still pin this, and the unit tests feed conflict-shaped and plain failures alike and get the same result). The reasons are recorded in the correction note of ADR-0029. **2. Neutral guard wording.** `BookingOrchestrationFailure` now says "Booking orchestration requires no active caller transaction"; it stays internal, sanitized, and cause-free; the Business and guest runner tests assert the exact message, and the Business problem and the public guest problem are asserted to stay the generic `INTERNAL_ERROR` without it. **3. Route-level `503` evidence.** `BusinessAppointmentCreateUnavailableApiIntegrationTests` drives the real `POST /api/business/appointments` (security chain, CSRF, parser, controller, advice) with the administration capability replaced by a Mockito double that raises exactly the published outcome, and asserts status `503`, the exact code and Bulgarian detail (`APPOINTMENT_TEMPORARILY_UNAVAILABLE` "Часът не беше запазен. Опитайте отново след малко."; `APPOINTMENT_OUTCOME_UNCERTAIN` "Не е ясно дали часът е запазен. Проверете графика, преди да опитате отново."), the title, the fixed `instance` `/api/business/appointments`, the exact key set (`title, status, detail, instance, code`), `application/problem+json`, `Cache-Control: no-store`, no `Location`, no `Set-Cookie`, and that the body contains no Customer name, internal note, Service name, StaffMember name, identifier of the Customer, StaffMember, Service, Business, or user, request date, SQL text, SQLState, constraint name, class name, or cause. Infrastructure failures are deliberately not provoked through HTTP.

**Real versus injected (updated).** Real, against PostgreSQL in `ManualAppointmentCommitFailureIntegrationTests`: a pre-commit callback failure makes Spring roll back and report `ROLLED_BACK`; the first attempt's rollback leaves no Appointment and no line, the retry runs in a distinct PostgreSQL transaction with a fresh snapshot (asserted by transaction identifier and snapshot, strictly increasing) and creates exactly one visit and one line; a rollback on every one of three attempts ends temporarily unavailable with three transactions, no fourth, and no data; the real after-commit failure, swallowed failed statement, silent rollback, and terminated backend are unchanged. Injected and labelled in `BusinessAppointmentAdministrationServiceTests`: the completion statuses `COMMITTED`, `ROLLED_BACK`, `UNKNOWN`, and no report (including `ROLLED_BACK` then `ROLLED_BACK` then `ROLLED_BACK`, `ROLLED_BACK` after an overlap, and `ROLLED_BACK` then `COMMITTED`). The `503` HTTP outcomes use a test double of the published capability, so they prove the route, the advice, and the privacy of the problem, not the infrastructure.

**Mutation evidence affected by the correction** (applied, the named suites run, the file restored byte for byte; all killed): retry after `ROLLED_BACK` disabled (10 failures), retry after `COMMITTED` (7), retry after `UNKNOWN` or no report (2), a fourth attempt allowed (13), the guard message exposed by the Business problem (2), the guard message reverted to the old wording (4), the `APPOINTMENT_TEMPORARILY_UNAVAILABLE` code changed (1), and the `APPOINTMENT_OUTCOME_UNCERTAIN` status changed (2). The earlier 25 mutants of the first review still stand except where the retry-after-`COMMITTED` and fourth-attempt mutants were re-run against the corrected runner.

**Verification (executed on the corrected tree).** Focused: `BusinessAppointmentAdministrationServiceTests` 57, `ManualAppointmentCommitFailureIntegrationTests` 11, `BusinessAppointmentCreateApiIntegrationTests` 20, `BusinessAppointmentCreateUnavailableApiIntegrationTests` 3, `BusinessAppointmentExceptionHandlerTests` 3, `PublicBookingOrchestrationGuardProblemTests` 1, `GuestBookingServiceTests` 95, `GuestBookingIntegrationTests` 23, and the three boundary classes 15, 7, and 7. One complete `./mvnw --batch-mode verify` on the corrected tree: **3966 tests, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS`** (3953 in the first review run). `git diff --check` is clean, no changed Java file has a compressed empty body, a wildcard import, or trailing whitespace, and `V1` to `V15` are byte-identical to `HEAD`. The working tree now has 35 modified and 23 new files. Local result only.

### Stopping point

Phase 4 stops here for review. Phase 5 needs its own read-only plan and explicit approval.

## Phase 5 record (2026-10-16; implemented, verified locally; status update 2026-10-16: committed as `10ce488`, CI of that commit succeeded)

Base `HEAD` `260e81df4e7b609707f609df64025ea4cc9011dc` (Phase 4 with its review corrections, clean, equal to `origin/main`). **CI observed at the start (advisory, read-only):** run 38859449131 for that commit was `in_progress`; it did not delay or block the work, and no GitHub state was touched. Scope: issue #19, Strict. No migration (`V1` to `V15` byte-identical to `HEAD`), no frontend, no options route, no cancel or note edit, no drag-and-drop, no Phase 6.

### Decisions made in this phase (within ADR-0028 and ADR-0029, nothing in them changed)

| # | Decision |
|---|---|
| P1 | Route finalized: `POST /api/business/appointments/{appointmentId}/reschedule`; one atomic request for a new start, a new StaffMember, or both. |
| P2 | A request that changes nothing (or names neither key) is `400 VALIDATION_ERROR`, judged after the version and the state, and never advances the version. Naming the current StaffMember is a time-only move. |
| P3 | One shared Business runner serves create and reschedule (the Phase 4 runner was generalized to take an attempt body; the create body and every Phase 4 test are unchanged apart from a constructor argument and the rejection count). |
| P4 | The time-only lock is the new sanitized `StaffBookingAccess.lockActiveForBooking`, not `StaffMemberReferenceAccess.lockReference` (a cause-carrying exception the runner may not read). Recorded as a deviation in the ADR-0029 Phase 5 note. |
| P5 | The decision "start before the clock instant" is taken after the eligibility locks, so the precedence is version, state, no change, StaffMember, start before now, offered slot. |
| P6 | The response is the complete stored Appointment (create keys plus `updatedAt`), without Customer name, phone, email, or Customer note. |

### Final contract

`POST /api/business/appointments/{appointmentId}/reschedule`, JSON, CSRF required, owner only, `ACTIVE` Business only, canonical UUID in the path. Request keys, exactly: `expectedVersion` (required integer) and optionally `start` (ISO-8601 with an explicit offset) and `staffMemberId`; an absent key keeps the stored value; an explicit `null`, an unknown key (Customer, Service, duration, price, Business), a non-canonical identifier, a non-integral number, or a start without an offset is `400 VALIDATION_ERROR`. Success: `200`, `Cache-Control: no-store`, no `Location`, keys `id, reference, status, source, start, end, timezone, staffMemberId, staffDisplayName, customerId, services[position,name,durationMinutes,price], durationMinutes, price, internalNote, version, createdAt, updatedAt` (local offsets, exact two-decimal prices). Problems (fixed `instance` `/api/business/appointments`, keys `title, status, detail, instance, code`, `application/problem+json`, `no-store`):

| Status | Code | When | Wording (Proposed) |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | shape or domain bound (negative version, neither key, no change, instant bounds) | Проверете въведените данни. |
| 401 / 403 | `AUTH_REQUIRED` / `ACTIVE_BUSINESS_REQUIRED` / `ACCESS_DENIED` | as for every owner route | as before |
| 404 | `APPOINTMENT_NOT_FOUND` | missing or foreign Appointment (byte-identical) | Часът не е намерен. |
| 409 | `BUSINESS_NOT_ACTIVE` | `DRAFT`, before any domain validation | Бизнесът не е активен. Часовете могат да се променят само в активен бизнес. |
| 409 | `BUSINESS_SUSPENDED` | `SUSPENDED`, before any domain validation | Спрян бизнес може само да преглежда данните си. |
| 409 | `APPOINTMENT_CONCURRENT_UPDATE` | the version differs, directly or after a lost row race | Часът е променен междувременно. Презаредете графика и опитайте отново. |
| 409 | `APPOINTMENT_STATE_CONFLICT` | cancelled, or started (start at or before the clock instant) | Часът не може да бъде променен, защото е отменен или вече е започнал. |
| 409 | `APPOINTMENT_STAFF_UNAVAILABLE` | time-only: current StaffMember inactive or missing; reassign: target missing, foreign, inactive, or not assigned to every stored Service (byte-identical) | Членът на екипа не е наличен за тази промяна. |
| 409 | `APPOINTMENT_SLOT_UNAVAILABLE` | start before now, not offered, taken, or lost the overlap race | Избраният час вече не е свободен. |
| 503 | `APPOINTMENT_TEMPORARILY_UNAVAILABLE` | proven rollback after three attempts | Промяната не беше направена. Часът е непроменен. Опитайте отново след малко. |
| 503 | `APPOINTMENT_OUTCOME_UNCERTAIN` | the change cannot be confirmed | Не е ясно дали промяната е направена. Презаредете графика и проверете часа, преди да опитате отново. |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | another media type | Заявката не може да бъде изпълнена. |
| 500 | `INTERNAL_ERROR` | anything else | Възникна неочаквана грешка. |

### Transaction, locks, retry, verification

Statement order (each hook point probes the held rows with `FOR UPDATE SKIP LOCKED`): Business schedule context `FOR SHARE`, owner Membership `FOR SHARE`, plain Appointment read, one clock read, the pre-lock refusals, the target StaffMember `FOR SHARE` (the current one for a time-only move, the target only for a reassignment), the schedule revision `FOR SHARE`, Business-side availability for the stored duration ignoring only this Appointment, the conditional `UPDATE`, a read-your-write. The Appointment row is the last lock; no Service and no Customer row is ever locked. A success issues 16 statements for one or five Services. Three attempts at most, each a new `REPEATABLE_READ` transaction; only a proven `ROLLED_BACK` completion is retried; `COMMITTED`, `UNKNOWN`, and no report are uncertain and never retried; no cause, SQLState, or driver exception is read. After a normal completion one read outside any transaction decides: the written version with the requested start and StaffMember is success; the expected version is a silent rollback (`APPOINTMENT_TEMPORARILY_UNAVAILABLE`); anything else is `APPOINTMENT_OUTCOME_UNCERTAIN`.

### Evidence (tests added in this phase)

Real PostgreSQL and the real orchestration unless stated: `AppointmentRescheduleIntegrationTests` 43 (the three operations column by column, no-change, stale version, started and cancelled, eligibility after creation, lifecycle, roles, tenant isolation, clock reads, instant rules, overlap, adjacency, ignore-self, guest and manual regressions, the daylight-saving gap and both repeated-hour occurrences derived from `ZoneRules`), `AppointmentRescheduleConcurrencyIntegrationTests` 31, `AppointmentRescheduleScheduleRaceIntegrationTests` 41 (thirteen paths in three commit orders plus two guard-bypass controls), `AppointmentRescheduleCommitFailureIntegrationTests` 12, `BusinessAppointmentRescheduleApiIntegrationTests` 20, `BusinessAppointmentRescheduleUnavailableApiIntegrationTests` 3 (the `503` route with a test double of the capability, **injected**), `StaffActiveLockIntegrationTests` 7. Unit and structural: `BusinessAppointmentRescheduleServiceTests` 50 (scripted body and fake transaction manager: every completion status is **injected** and labelled), `AppointmentRescheduleRefusalTests` 6, `BusinessAppointmentRescheduleBoundaryTests` 6 (lock order as call order, no Service or Customer contract, one write method, one clock read, sole callers, exactly three attempts). Changed deliberately, none weakened: `BusinessAppointmentAdministrationServiceTests` (60: the constructor and three more rejection kinds), `BookingModuleBoundaryTests`, `CalendarModuleBoundaryTests` (two POST routes), `ScheduleRevisionModuleBoundaryTests` (third guard consumer), `ManualAppointmentConcurrencyIntegrationTests` (its lock-order test lists its three create hook points explicitly because a new shared hook point exists), `BookingHookConfiguration` and `ManualBookingHooks` (a point after the Business-side availability and the new lock wrapper), and the shared transaction fake extracted to `FakeTransactionManager`. **Real:** every lock wait, `40001`, `23P01`, deadlock resolution, retry in a distinct transaction (asserted by transaction identifier and snapshot), after-commit failure, pre-commit rollback, swallowed failed statement, silent rollback, and terminated backend. **Not covered:** the orchestrated cancel and note races (Phase 6; neighbours are cancelled or edited through the Phase 2 store primitives); a deterministic `40P01` through the exclusion index is not forced (the simultaneous tests accept whichever PostgreSQL resolves and assert one winner and a loser retried in new transactions).

### Mutation and control checks

Each mutant was applied to the working tree, the focused reschedule, runner, domain, store, boundary, and lock suites (311 tests) were run, and the file was restored (the SHA-256 of every mutated source equals the baseline afterwards). **Killed (26 of 27):** availability without ignoring self (2 failures); all busy intervals ignored (the move-versus-create race); missing target eligibility (3 classes); missing active-current-StaffMember check (`StaffActiveLockIntegrationTests`; the end-to-end result is also guarded by the availability entry, so only the lock test sees it); missing expected-version predicate in the `UPDATE` (the store and inventory shape tests; under repeatable read the stale-version test is also decided by the domain's own version check); stale-version refusal removed from the domain (16 failures); wrong isolation `READ COMMITTED` (138 failures); reused retry transaction (143); retry after `COMMITTED` (9); retry after `UNKNOWN` or no report (3); retry after `ROLLED_BACK` disabled (16); a fourth attempt (19); partial persistence, StaffMember not written (14) and time not written (84); revision lock before the StaffMember lock (4); owner check removed (4); `DRAFT` allowed (2); started-at-now movable (5); cancelled not refused (7); naming the current StaffMember treated as reassignment (1); silent rollback reported as success (2); verification read skipped (6); start-before-now not refused by the domain (2); Business policy with the public two-hour notice (3). **M07, update performed before final availability validation (investigated).** A first broad run did not finish within 600 seconds. A thread dump and `pg_stat_activity` taken during the stall showed: PostgreSQL backend A `idle in transaction` (an attempt that had already executed the mutated update and was parked in its test hook), backend B `UPDATE appointment` waiting on A's transaction id, and backend C `TRUNCATE business CASCADE` (the next test's `@Sql` cleanup) waiting on A; the attempt thread `booking-a` was parked in an unbounded `CompletableFuture.join()` inside the hook of `aNeighbourMovedIntoTheIntervalAfterAvailability...`, which moves a neighbour into the interval that the mutated attempt had already updated. So the hang is a deterministic consequence of the mutation meeting a test-infrastructure defect: a Java `join()` waiting for a helper thread whose SQL waits for a lock of the very transaction that is joining, invisible to PostgreSQL's deadlock detector, which left the transaction open and blocked the next test's cleanup. It was not a leaked resource of the runner and not an application deadlock (the production code was never involved in the wait). The defect is fixed in the new tests: `RescheduleAppointmentTest.async` returns a future whose `join()` is bounded by the shared 20-second test timeout and fails with an assertion; no sleep was added and no timeout raised. After the fix M07 ends in finite time with 9 failures (`aMoveRacingACreationIntoTheSameIntervalLeavesOneWinnerWhicheverCommitsFirst` among them, and `BusinessAppointmentRescheduleBoundaryTests.theLockOrderIsTheOrderOfTheCallsInTheBody`). The same unbounded `join()` pattern in the Phase 4 manual-create tests is removed in the correction recorded below.

**M16, schedule revision guard not taken (investigated).** With the guard missing, the 41-test schedule-race class ends after 565 seconds with 28 failures, all bounded assertion failures: 13 times "the move never reached the guard" and 13 times "no PostgreSQL backend was observed blocked" (20-second waits each), plus two more. It is killed deterministically by assertions, but slowly, because the failures are 20-second waits; the broad 311-test run hit the harness limit of 600 seconds. `BusinessAppointmentRescheduleBoundaryTests` kills it in seconds.

**Survived (1):** the slot's StaffMember not checked (`staffMemberIds().contains(targetId)`): an equivalent mutant, because the Business-side availability is calculated for exactly one StaffMember so the slot can only contain the target; the check is defense in depth and is left in place.

### Verification (executed)

Focused suites while developing. **First complete run (earlier tree):** 4188 tests, five failures, all in `PublicBookingBodyLimitContainerIntegrationTests` (`404` over real HTTP connections). **Diagnosis (evidence, not assumption):** the log shows the application's Tomcat started on ephemeral port 49590; a later `lsof` showed the IDE process (IntelliJ IDEA, pid 2466) listening on `127.0.0.1:49590`. On this host a wildcard listener can bind a port that a loopback-specific listener already holds (reproduced with two sockets: the wildcard bind succeeded, and a client connecting to `127.0.0.1` was accepted by the loopback-only listener, while binding `127.0.0.1:49590` fails with `Address already in use`), so the test client, which connected to `localhost`, reached the IDE service, which answered `404` with an empty body, and never reached the application. The failure was therefore a port collision with an unrelated local process and not a mutation leftover. No leftover JVM, surefire fork, or Testcontainers container existed afterwards (`ps`, `lsof`, `docker ps`: only the developer's own long-running containers). **Test-isolation defect, fixed (in scope: tests only):** the class bound the server to the wildcard address and connected to `localhost`; it now sets `server.address=127.0.0.1` and connects to `127.0.0.1`, so the kernel chooses a port free on the loopback address and a collision is impossible. The class passes alone after the fix. The other two classes with real servers do not exist (`RANDOM_PORT` is used only by this class).

**Final complete verification on the final tree** (after the last production and test change; no stray surefire, mutation, or `mvnw` process and only the developer's own two long-running containers present, every mutated source byte-identical to its pre-mutation SHA-256): `./mvnw --batch-mode clean verify` (repeated after the review corrections below on the final tree): **4190 tests, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS`** (3966 after Phase 4; 224 added). The runs before the review corrections had 4188 tests, 0 failures. `git diff --check` is clean; a scan of every changed and new file found no compressed empty body, wildcard import, tab, trailing whitespace, or missing final newline; `V1` to `V15` are byte-identical to `HEAD`; the frontend was not touched and no frontend suite was run. Local result only; the exact-HEAD CI result is a separate report.

### Deviations, limitations, and residual risks

1. The time-only lock deviates from the ADR-0029 step 4 text (P4); marked in the ADR.
2. **Corrected after review:** the reschedule route no longer reuses create wording. `RescheduleFailure` (internal, `calendar.web`) carries a route failure to `BusinessAppointmentExceptionHandler`, which maps the same codes and statuses to operation-appropriate details (`BUSINESS_NOT_ACTIVE`, `APPOINTMENT_STAFF_UNAVAILABLE`, `APPOINTMENT_TEMPORARILY_UNAVAILABLE`, `APPOINTMENT_OUTCOME_UNCERTAIN` reworded; the rest were already neutral or reschedule-specific). The create route's wording and tests are unchanged. The Phase 11 review may still give UX feedback.
3. A change that another transaction commits between the move's commit and the verification read makes a successful move report `APPOINTMENT_OUTCOME_UNCERTAIN`; the interface reloads. Never reported as success without confirmation.
4. A clock before the Appointment's creation fails the attempt as `INTERNAL_ERROR` (the domain refuses it).
5. The exclusion-index deadlock latency and the possible `APPOINTMENT_TEMPORARILY_UNAVAILABLE` under a burst remain as documented.
6. The options route is still deferred (Phase 4 decision Q1); the form phases need it.
7. M16 is killed by bounded 20-second assertion failures that make its run slow (see above), and one equivalent mutant survived.

### Phase 5 review corrections (2026-10-16; implemented and verified locally; nothing staged or committed)

**1. Reschedule wording is final for the backend and operation-appropriate.** The first Phase 5 delivery reused the create route's details for the shared results, which said or implied that an Appointment was being created or saved. `RescheduleFailure` (internal to `calendar.web`, never serialized) now carries a failure of the reschedule route to `BusinessAppointmentExceptionHandler`, which maps the same codes and statuses to reschedule details; the create route, its wording, and its tests are unchanged. No reschedule detail contains "създа" or "запаз" (asserted for every reachable result). Every problem the route can return, with the final Bulgarian detail:

| Status | Code | Detail |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Проверете въведените данни. |
| 403 | `ACCESS_DENIED` | Нямате достъп до тази операция. |
| 404 | `APPOINTMENT_NOT_FOUND` | Часът не е намерен. |
| 409 | `BUSINESS_NOT_ACTIVE` | Бизнесът не е активен. Часовете могат да се променят само в активен бизнес. |
| 409 | `BUSINESS_SUSPENDED` | Спрян бизнес може само да преглежда данните си. |
| 409 | `APPOINTMENT_CONCURRENT_UPDATE` | Часът е променен междувременно. Презаредете графика и опитайте отново. |
| 409 | `APPOINTMENT_STATE_CONFLICT` | Часът не може да бъде променен, защото е отменен или вече е започнал. |
| 409 | `APPOINTMENT_STAFF_UNAVAILABLE` | Членът на екипа не е наличен за тази промяна. |
| 409 | `APPOINTMENT_SLOT_UNAVAILABLE` | Избраният час вече не е свободен. |
| 503 | `APPOINTMENT_TEMPORARILY_UNAVAILABLE` | Промяната не беше направена. Часът е непроменен. Опитайте отново след малко. |
| 503 | `APPOINTMENT_OUTCOME_UNCERTAIN` | Не е ясно дали промяната е направена. Презаредете графика и проверете часа, преди да опитате отново. |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Заявката не може да бъде изпълнена. |
| 500 | `INTERNAL_ERROR` | Възникна неочаквана грешка. |
| 401 / 403 | `AUTH_REQUIRED` / `ACTIVE_BUSINESS_REQUIRED` | as for every owner route (security chain and calendar problems, unchanged) |

The create-only results (`CUSTOMER_NOT_FOUND`, `APPOINTMENT_SERVICE_UNAVAILABLE`, `APPOINTMENT_REVIEW_CHANGED`) cannot occur on this route and are mapped to the generic `INTERNAL_ERROR` should they ever be raised. The contract table above is superseded in its "Wording" column by this table; codes and statuses are unchanged. Evidence: `BusinessAppointmentRescheduleUnavailableApiIntegrationTests.everyResultTheRouteCanReturnHasItsExactWordingAndNoneSpeaksOfCreatingOrSavingAnAppointment` (every result through the real route with a test double of the capability, **injected**, including the exact detail, code, status, key set, fixed instance, and the absence of private values), the reschedule HTTP tests against real PostgreSQL, and `BusinessAppointmentExceptionHandlerTests` (the reworded results differ from the create details and the others are identical).

**2. Unbounded helper-thread joins in the Phase 4 manual-create tests.** The bounded future of the M07 fix moved from `RescheduleAppointmentTest` to `ManualAppointmentTest`, so every `async(...).join()` of the manual-create classes is now bounded by the shared 20-second test timeout and fails with a message that names the likely cause; no sleep, no raised timeout, no changed assertion, no production change. Changed (through the base class): the six joins in `ManualAppointmentConcurrencyIntegrationTests` (the lock probe, the Membership deactivation hook, the neighbour move and the blocker insert and cancel hooks of the exhaustion tests, and the Service price change hook) and every join in the Phase 5 classes; the two joins of `StaffActiveLockIntegrationTests` now use the bounded `completed`. `ManualAppointmentCreationIntegrationTests`, `ManualAppointmentScheduleRaceIntegrationTests`, and `ManualAppointmentCommitFailureIntegrationTests` contain no join. **Intentionally retained, outside the scope of the manual-create tests:** the `join()` calls in the guest-booking and older suites (`GuestBookingIntegrationTests`, `GuestBookingConcurrencyIntegrationTests`, `GuestBookingScheduleRaceIntegrationTests`, `GuestBookingVisitIntegrationTests`, `BookingContractsIntegrationTests`, `AppointmentStoreIntegrationTests`, `IdentityLifecycleIntegrationTests`, `BusinessStoreIntegrationTests`, `BusinessAdministrationServiceIntegrationTests`, `PublicProfileTransactionIntegrationTests`): they belong to other issues and were not touched by this work; they were not audited line by line for the lock-cycle pattern and are recorded as residual test debt. Stability: the 240 tests of `ManualAppointment*IntegrationTests`, `AppointmentReschedule*IntegrationTests`, and `StaffActiveLockIntegrationTests` ran four times in a row with 0 failures each. M07 re-run against the concurrency class ends in finite time with 8 failures.

### Stopping point

Phase 5 stops here for review. Phase 6 needs its own read-only plan and explicit approval.

## Phase 6 record (2026-10-16; implemented, verified locally; status update 2026-10-16: committed as `82edadd`)

Base `HEAD` `10ce4889f1b524c6799680f4a738080d32e7f1d4` (Phase 5, clean, equal to `origin/main`). **CI observed at the start (advisory, read-only):** run 38900976388 for that commit was `in_progress`; it did not delay or block the work, and it had completed successfully when the phase ended. No GitHub state was touched. Scope: issue #21, Strict. No migration (`V1` to `V15` byte-identical to `HEAD`), no frontend, no options route, no reason, restore, deletion, history row, notification, refund, or Customer-facing cancellation, no Phase 7.

### Decision made before implementation (approved by the product owner, 2026-10-16)

Read-only inspection found one ambiguity: ADR-0029 section 9 said that cancel and note edit "have no separate classification" (an unexpected failure is the generic safe error), while the Phase 4 and 5 runner classifies by completion status and verifies after completion, and the phase brief required the completion statuses, the verification read, and the `503` outcomes. The smallest decision package offered three options; the product owner approved **Option A: one `READ COMMITTED` attempt, completion-status-only classification, a post-completion verification read, and no retry (not even after a proven rollback)**, together with the default routes, bodies, and wording below. It is recorded as an appended note in ADR-0029 and the superseded sentence of section 9 is marked in place. The reason for no retry: these are short `READ COMMITTED` optimistic-version mutations and an automatic second attempt cannot safely reinterpret the caller's expected version or intent.

| # | Decision |
|---|---|
| P1 | Routes: `POST /api/business/appointments/{appointmentId}/cancel` with exactly `{"expectedVersion": n}`; `PUT /api/business/appointments/{appointmentId}/internal-note` with exactly `{"expectedVersion": n, "internalNote": "…"}`. |
| P2 | `internalNote` is a required JSON string; a blank string clears it through the shared `NoteText` policy; `null`, a missing key, and an unknown key are `400`. |
| P3 | Note editing is allowed in every status (including `CANCELLED`, started, and past). |
| P4 | An unchanged normalized note is a `200` with the current row, issues no `UPDATE`, advances neither `version` nor `updated_at`, and is judged only after existence and the version check (a stale version is still `APPOINTMENT_CONCURRENT_UPDATE`). |
| P5 | Both responses are the complete private representation (the keys of the reschedule response), read after the transaction completed; no `cancelled_by`, no `cancelledAt`, no Customer name, phone, email, or Customer note. |
| P6 | The precedence is authorization, lifecycle, the domain bounds of the command, existence, version, state, then (the note only) the note rule. |
| P7 | A separate service and published contract (`AppointmentLifecycleAdministration`, `BusinessAppointmentLifecycleService`, `BusinessAppointmentLifecycleProcedure`) instead of a third operation on the repeatable-read runner: the `READ COMMITTED` single-attempt rules and the three-attempt runner stay structurally separate, and no Phase 4 or 5 test changed. |

### Final contract

Both routes: owner only, `ACTIVE` Business only, CSRF required, `application/json` only, `no-store`, the fixed problem `instance` `/api/business/appointments`, problem keys `title, status, detail, instance, code`, canonical identifiers. Success is `200`, no `Location`, no `Set-Cookie`, keys `id, reference, status, source, start, end, timezone, staffMemberId, staffDisplayName, customerId, services[position,name,durationMinutes,price], durationMinutes, price, internalNote, version, createdAt, updatedAt` (local offsets, exact two-decimal prices; `internalNote` is an explicit `null` when cleared). The complete status, code, and detail table per route is in [`security.md`](../security.md) and reproduced here:

| Status | Code | Cancellation detail | Internal-note detail |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | Проверете въведените данни. | Проверете въведените данни. |
| 401 | `AUTH_REQUIRED` | fixed body of the security chain | the same |
| 403 | `ACTIVE_BUSINESS_REQUIRED` | as for every owner route | the same |
| 403 | `ACCESS_DENIED` | Нямате достъп до тази операция. | Нямате достъп до тази операция. |
| 404 | `APPOINTMENT_NOT_FOUND` | Часът не е намерен. | Часът не е намерен. |
| 409 | `BUSINESS_NOT_ACTIVE` | Бизнесът не е активен. Часовете могат да се отменят само в активен бизнес. | Бизнесът не е активен. Бележките могат да се променят само в активен бизнес. |
| 409 | `BUSINESS_SUSPENDED` | Спрян бизнес може само да преглежда данните си. | Спрян бизнес може само да преглежда данните си. |
| 409 | `APPOINTMENT_CONCURRENT_UPDATE` | Часът е променен междувременно. Презаредете графика и опитайте отново. | Часът е променен междувременно. Презаредете графика и опитайте отново. |
| 409 | `APPOINTMENT_STATE_CONFLICT` | Часът не може да бъде отменен, защото вече е отменен или е започнал. | unreachable: `INTERNAL_ERROR` |
| 503 | `APPOINTMENT_TEMPORARILY_UNAVAILABLE` | Часът не беше отменен. Той е непроменен. Опитайте отново след малко. | Бележката не беше променена. Опитайте отново след малко. |
| 503 | `APPOINTMENT_OUTCOME_UNCERTAIN` | Не е ясно дали часът е отменен. Презаредете графика и проверете часа, преди да опитате отново. | Не е ясно дали бележката е променена. Презаредете графика и проверете часа, преди да опитате отново. |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Заявката не може да бъде изпълнена. | Заявката не може да бъде изпълнена. |
| 500 | `INTERNAL_ERROR` | Възникна неочаквана грешка. | Възникна неочаквана грешка. |

The create and reschedule only results map to the generic `INTERNAL_ERROR` on these routes. The wording stays open to the Phase 11 human review.

### Cancellation and note behavior

A cancellation is `CONFIRMED → CANCELLED` of an Appointment whose `start_at` is after the single clock instant of the attempt (an Appointment starting exactly at the instant has started): it writes `cancelled_at` and `updated_at` (that instant at microsecond precision), `cancelled_by = 'BUSINESS'`, and `version` plus one, and changes no other column (compared column by column and by tuple version); the Service lines, the Customer, the StaffMember, the period, the prices, and the snapshots are unchanged, and the time is released through the existing exclusion predicate. A repeated cancellation is `APPOINTMENT_STATE_CONFLICT` (or `APPOINTMENT_CONCURRENT_UPDATE` with the old version), never a success. The cancel predicate (`status = 'CONFIRMED' AND start_at > :now AND version = :expectedVersion`, scoped to the Business) is pinned by `AvailabilityMutationInventoryTests` and is the one a Customer cancellation (#35) will use with a different actor. A note edit writes only `internal_note`, `version` plus one, and `updated_at` and matches by Business, identifier, and expected version; the normalization, the 500 code point limit, and the character rules are the shared `NoteText` policy and nothing is ever truncated.

### Transaction, locks, completion, retry

One service-owned `REQUIRED`, `READ COMMITTED`, read-write transaction per call; the entry guard rejects an active caller transaction or synchronization with the sanitized `BookingOrchestrationFailure` before any clock read, statement, lock, or domain call. Lock order (each hook point probed with `FOR UPDATE SKIP LOCKED`): the Business schedule context `FOR SHARE`, the owner Membership `FOR SHARE`, then the plain Appointment read, the one clock read, and the conditional `UPDATE` last; the unchanged note never locks the Appointment; no StaffMember, revision, Service, or Customer row is touched. The exact completion table (also in the ADR-0029 Phase 6 note):

| Situation | Completion | Result | Retried |
|---|---|---|---|
| deterministic rejection | rollback by the service | the typed rejection | never |
| body threw before returning; a published sanitized conflict (Business schedule context, owner Membership, Appointment persistence) | rolled back by Spring | `APPOINTMENT_CONCURRENT_UPDATE` | never |
| body threw before returning; anything else | rolled back by Spring | `INTERNAL_ERROR` | never |
| write attempted, body returned, later failure | `COMMITTED` | `APPOINTMENT_OUTCOME_UNCERTAIN` | never |
| the same | `ROLLED_BACK` | `APPOINTMENT_TEMPORARILY_UNAVAILABLE` | never |
| the same | `UNKNOWN` or no report | `APPOINTMENT_OUTCOME_UNCERTAIN` | never |
| unchanged-note no-op, later failure | any | `INTERNAL_ERROR` (nothing written) | never |
| write, normal completion | one primary-key read outside every transaction: written version with the requested facts | success (the verified row) | n/a |
| the same | row still at the expected version | `APPOINTMENT_TEMPORARILY_UNAVAILABLE` | never |
| the same | any other row state, no row, or an unreadable row (including a change between the commit and the read) | `APPOINTMENT_OUTCOME_UNCERTAIN` | never |
| no-op, normal completion | the same read: same version and same note | success; anything else is `APPOINTMENT_OUTCOME_UNCERTAIN` | n/a |

No cause, SQLState, driver exception, or `DataAccessException` chain is inspected (pinned by a boundary test and by the mutation M11/M12 results). At `READ COMMITTED` a conditional `UPDATE` that waits for a competing writer re-checks its predicate and matches nothing instead of raising `40001`; the zero-row classification read then yields the typed stale-version or state result in the same transaction.

### Evidence (tests added in this phase; counts from the final complete run)

Real PostgreSQL and the real orchestration unless stated. `AppointmentCancellationIntegrationTests` 21 (the exact facts, immutability, multi-Service lines, the single clock read, the exact start boundary, state and version results, tenant isolation, roles, lifecycle, entry guard, isolation, freed time, guest replay), `AppointmentInternalNoteIntegrationTests` 30 (create, replace, clear, canonical whitespace and Unicode, the 500 code point limit without truncation, control characters and surrogates, every status, the no-op that issues no `UPDATE`, stale before invalid, roles, lifecycle, guard, isolation, privacy), `AppointmentLifecycleConcurrencyIntegrationTests` 19 (lock order for cancel, note, and no-op, different Appointments, cancel versus cancel in two shapes and at a barrier, cancel versus move and reassignment in both orders, cancel versus note in both orders, note versus note and move in both orders, cancellation versus creation and guest booking), `AppointmentLifecycleCommitFailureIntegrationTests` 17 (five misleading after-commit failures for both operations, a change between commit and verification, a pre-commit rollback, a failure and a swallowed failed statement inside the body, a silent rollback, a terminated backend, the verified no-op versus a silent rollback, a no-op changed before verification, a committed cancellation never reported as a rollback), `BusinessAppointmentLifecycleApiIntegrationTests` 24 (both routes: exact bodies and headers, CSRF, roles, lifecycle precedence, every shape violation, media type and verbs, byte-identical not-found, the note absent from the calendar, agenda, and history payloads, no log or problem leak, statement counts: 10 for a cancellation and for a note edit whatever the number of Services, 6 for the no-op, one `UPDATE` and none for the no-op). **Injected:** `BusinessAppointmentLifecycleServiceTests` 64 (scripted body, fake transaction manager, every completion status). **Test double:** `BusinessAppointmentLifecycleUnavailableApiIntegrationTests` 4 (every result of both routes through the real route with the capability replaced, **injected**). Unit and structural: `LifecycleRequestParserTests` 41, `BusinessAppointmentLifecycleBoundaryTests` 11 (lock order as call order, collaborator set, one clock read per body, the only callers of the two updates, no retry construct, no cause or SQLState read, READ COMMITTED), plus the changed `CalendarModuleBoundaryTests` (the exact route list: three `POST` and one `PUT`) and `BookingModuleBoundaryTests` (the published root types) and the test wiring (`MutableTestClock.onRead`, `ManualBookingHooks.Point.AFTER_CLOCK_READ`, `BookingHookConfiguration`). That is 231 new tests; none was weakened. **Not covered / limits:** a deterministic `40P01` is not forced (this operation holds only locks of the levels L1, L2, and L7 in order, which is an ordering argument and not a proof that no deadlock can occur); the `503` HTTP results come from a test double of the capability by design; the unbounded `join()` calls in the guest-booking and older suites remain recorded test debt (not audited line by line, not part of this phase).

### Mutation and control checks

Each mutant was applied to the working tree, the focused suite of 217 tests was run, and every mutated source was restored from its in-memory baseline (the SHA-256 of every mutated source equals the baseline afterwards, and `git diff` of the unrelated mutated files is empty). **All 14 were killed:** M01 cancellation allowed after start (domain check and store predicate removed; 7 failures); M02 cancelled Appointment accepted again (7); M03 expected-version predicate missing from the cancel and note `UPDATE` (8); M04 cancellation facts written partially, `updated_at` not written (11 failures, 2 errors; a first attempt at M04 changed the reschedule statement by mistake because its text is similar, was recognised as a surviving mutant of the wrong statement, and was repeated against the cancel statement); M05 immutable data changed during cancellation, the StaffMember name snapshot (2); M06 the note edit changing status and cancellation facts (4); M07 note persisted without the shared normalization (9 failures, 1 error); M08 the note exposed through the guest replay (2); M09 concurrent mutations both reported successful, a missed `UPDATE` reported as the write (7); M10 success reported without honouring the post-completion verification (11); M11 retry after `COMMITTED` or `UNKNOWN` (15); M12 a retry beyond the approved maximum of zero, after a proven rollback (7); M13 wrong isolation, `REPEATABLE READ` (5); M14 wrong lock order, Membership before the Business (4). No test hung.

### Verification (executed)

Focused suites while developing. **Final complete verification on the final tree:** `./mvnw --batch-mode clean verify`: **4421 tests, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS`** (4190 after Phase 5; 231 added), 03:27 minutes. `git diff --check` is clean; a scan of every changed and new Java file found no tab, trailing whitespace, wildcard import, compressed empty body, or missing final newline; `V1` to `V15` are byte-identical to `HEAD`; the frontend was not touched and no frontend suite was run. Local result only; the exact-HEAD CI result of the commit that will contain this phase is a separate report.

### Deviations, limitations, and residual risks

1. **Section 9 of ADR-0029 is superseded in one sentence for cancel and note** (approved Option A); the original sentence is kept and marked.
2. **A separate service, not a third operation of the Business runner (P7).** The two runners now duplicate the completion-recorder idea (a small private class each); a shared extraction needs its own approval and evidence.
3. A change that another transaction commits between this call's commit and the verification read makes a successful operation report `APPOINTMENT_OUTCOME_UNCERTAIN`; the interface reloads. A success is never reported without confirmation.
4. A clock before the Appointment's creation fails the call as `INTERNAL_ERROR` (the domain refuses it), as in Phase 5.
5. The note is private Business data and travels in the authenticated detail and the two mutation responses; it is deliberately absent from the lists, the history, the guest replay, problems, and logs, and a log or URL never contains it (asserted).
6. The Bulgarian wording stays open to the Phase 11 review. `cancelled_by` is not part of the mutation responses; the detail of Phase 3 remains the only place that shows it.
7. No rate limit exists on these owner routes, as for the other owner mutations.

### Stopping point

Phase 6 stops here for review. Phase 7 (the read-only frontend) needs its own plan and explicit approval.

## Phase 7 record (2026-10-16; implemented and verified locally; awaiting the product owner's review; nothing staged or committed)

Base `HEAD` `82edadd36e7adb64863428094cfa8ca3d23b450f` (Phase 6, clean, equal to `origin/main`). **CI observed at the start (advisory, read-only):** the run for that commit was `in_progress`; it did not affect the work. Scope: issue #21, Standard, frontend only. The backend, the migrations (`V1` to `V15`, byte-identical to `HEAD`) and every API contract are untouched; no contract defect was found. No create, reschedule, reassignment, cancellation or note-edit control exists; Phase 8 is not started.

### Routes and navigation

| Route (hash) | Screen |
|---|---|
| `#/business/calendar?view=day\|week\|agenda[&date=yyyy-MM-dd][&staff=<id>][&page=&size=]` | The calendar; `page` and `size` only for the agenda; invalid values normalize to the defaults |
| `#/business/appointments/{appointmentId}?customer=<id>` or the calendar query | The read-only Appointment detail; the query records where Back returns (a Customer, or the exact calendar state) |
| `#/business/customers/{customerId}` | unchanged route; the Customer detail gains the history section |

`График` is the first link of the Business group of the sidebar (active on the calendar and the detail routes); `Промени в графика` stays the second tab of `Работно време`, unrenamed. Only opaque identifiers, the view, a Business-local date, and the agenda page and size travel in the URL; no Customer value, filter text, note, or Appointment detail ever does (asserted for the URL, `history.state`, the title, storage, and the console). **Correction of the Phase 7 brief (approved by the product owner):** the brief's sentence "do not put … filters … in query strings" conflicted with ADR-0028 and UI guide §15.3, which put the view, the date, the opaque StaffMember identifier, the page, and the size in the route. The approved decision is to follow ADR-0028 and the guide; the hash may carry exactly those safe navigation values and never Customer data, contact data, internal notes, or Appointment contents. This is a correction of the brief, not a product deviation.

### Views

- **Day:** one column per StaffMember (as the API returns them) beside an hour axis, positioned by the elapsed time inside the day's own start and end instants (so a 23- or 25-hour day is exact). Working time is the plain surface, non-working time is hatched, and each column states its working time and any full-day closure in text. Overlapping entries (a cancelled and a confirmed entry of one StaffMember) take separate lanes; entries of different StaffMembers sit in their own columns and are never merged. Below 900 px the same data is a chronological list per StaffMember (deliberate responsive substitution; `matchMedia`, wide when unavailable).
- **Week:** Monday to Sunday sections of entry cards, with closure and time-off markers (cards, not a positional grid: a cross-day time axis would disagree on a daylight-saving day).
- **Agenda:** the calendar month containing the date (at most 31 dates), server-paginated with the shared `ListPagination` (10, 25, 50), page and size in the route, empty-page recovery by replacing history, a fixed chronological order (the recorded §15.1 exception).
- **Period navigation:** `Предишен`, `Днес`, `Следващ` (Back before Next), a native date field, and a native StaffMember select with an all-staff state; a StaffMember that disappears (`404 STAFF_MEMBER_NOT_FOUND`) clears the filter by replacing history and says so once. The StaffMember options come from the existing Staff catalog.
- **Entry:** start and end in the Business timezone (a repeated local time carries its offset: `03:30 (UTC+03:00)`), written status (`Потвърден` or `Отменен`; a cancelled entry is also dashed and struck through), Customer display name, ordered Services, StaffMember, and the server's total duration. Totals are never computed from client data.
- **Detail:** status, date and times, StaffMember, Services in stored order with their snapshots, the server's total duration and price, source, reference, timezone, Customer (name linking to the Customer, phone, email), both notes, and the cancellation time only when the contract supplies it. `cancelledBy`, `version`, and the audit timestamps are not shown. A missing, foreign, or malformed identifier shows one safe not-found state with focus and the way back.
- **Customer history:** a section of the Customer detail; the server page exactly as returned (newest first), `Предстоящ` or `Минал` from the entry's own instant (confirmed entries only), `Отменен` as written status; minimum facts only; the page and size (10, 25, 50) are component state, not route state (recorded decision, to avoid changing the Customer route).

### Timezone and bootstrap

All date-only values are handled as numeric parts in UTC; instants are formatted in the timezone the response returns. The only use of the browser timezone is the provisional "today" of a calendar opened without a date: the first answer names the Business timezone, the route is then replaced with the Business "today", and the request is repeated only when the day differs.

### Lifecycle

`ACTIVE`, `DRAFT`, and `SUSPENDED` all read. The shell shows one statement per screen: for `SUSPENDED` the calendar wording (`…Можете да преглеждате графика и часовете, но не можете да ги променяте.`), for `DRAFT` on the calendar screens only `Бизнесът още не е активен. …`, none for `ACTIVE`. Customer screens keep their own `SUSPENDED` wording and edit exception. A lost Business context uses the shared recovery; Manager, Staff, and platform-only sessions never see the destination or the routes.

### Evidence

New suites: `navigation.calendar.test.ts` 6, `dates.test.ts` 6 (calendar), `presentation.test.ts` 8, `BusinessCalendar.test.tsx` 34, `AppointmentDetail.test.tsx` 11, `CustomerAppointmentHistory.test.tsx` 8, `calendarLayout.test.ts` 4, `App.calendar.test.tsx` 17, and three calendar rows in `App.contextRecovery.test.tsx`. Existing suites adapted deliberately (never weakened): the navigation link lists gained `График` (`App.test`, `App.businessSelection.test`, `BusinessOwnerShell.test`, `PlatformAdminShell.test`), and the Customer detail and application tests mock the new history request. **Mutation and control checks (all killed; each mutated file restored and byte-compared):** the browser timezone shifting a date (M1), a late response replacing newer data (M2), a range longer than 31 dates (M3), the cancelled status missing from the text (M4a) or from the structure (M4b), private text entering the URL (M5), a mutation control on the detail (M6) and on the calendar (M8), `version` shown (M7), and a refetch on every render (M9).

### Rendered developer inspection (not the Phase 11 approval)

A throwaway Node mock of the read APIs with synthetic data only (no database, nothing in the repository; the built bundle served from the scratchpad) was inspected in the built-in browser at 1280, 820, 375, and 320 px: the populated day with overlapping entries, a cancelled entry, a three-Service visit, and long Customer, Service, and StaffMember names; the week; the paged agenda; the repeated-hour and spring-gap days; the Appointment detail and the Customer history; the `DRAFT` and `SUSPENDED` Businesses; keyboard focus (a real Tab shows the 3 px focus ring); and horizontal overflow (`scrollWidth` equals `clientWidth` on every screen at 1280, 375, and 320). The inspection found and fixed three defects: the hour axis drifted from the columns when a name wrapped (now a subgrid), short blocks clipped their status badge (a quiet confirmed badge, a legend, a compact block), and the axis label of a repeated hour was truncated. Limits: no 200% zoom run, no real backend, no screen reader; formal review is Phase 11.

### Verification (executed)

Focused suites while developing; then one complete `npm test` (84 files, 1938 tests passed), `npm run lint` (clean), `npm run build` (succeeded), and `git diff --check` (clean). Backend tests were not run because no backend file changed. Local result only; the exact-HEAD CI result is a separate report.

### Deviations, limitations, and residual risks

1. None for the route state: the StaffMember filter in the route is the approved reading of ADR-0028 (see the correction of the brief above).
2. The week is a card layout, not a positional grid; the day grid is positional.
3. The agenda covers the calendar month of the date, not a free range.
4. A grid block clips its text when its range is short; the same facts are in the entry link, the week, and the agenda.
5. The Customer history page and size are not in the route.
6. The StaffMember select needs the Staff catalog; if it fails, only "all members" and the current selection are offered.
7. Bulgarian wording stays Proposed until Phase 11.

### Stopping point

Phase 7 stops here for review. Phase 8 (the manual-create frontend) needs its own approval. **The read-only calendar is available for optional browser inspection; this is a notification, not a gate: formal human visual approval remains Phase 11.**

## Phase 8 prerequisite record (2026-10-16; implemented and verified locally; awaiting the product owner's review; nothing staged or committed)

Base `HEAD` `6ea345f87ab876997f51dbc4c59d6d659214dd2c` (Phase 7, clean, equal to `origin/main`). **CI observed at the start (advisory, read-only):** the run for that commit was `in_progress`; it did not affect the work. Scope: issue #21, Strict, backend only. This is a **discovered Phase 8 prerequisite, not completed frontend work**: no frontend file changed, the manual-create form is not started, no migration (`V1` to `V15` byte-identical to `HEAD`), no change to any existing route, contract, or production behaviour, and no GitHub change.

### Why the route was missing

Phase 4 decision Q1 deferred `GET /api/business/appointment-options` because it needed a plain-read Catalog duration contract, and Phases 5 and 6 left it deferred; the plan said it would be introduced "as its own Strict backend step before the Phase 8 form". When Phase 8 was started the repository confirmed that no Business-side read of the eligible StaffMembers or of the offered starts existed (the only availability reads are the unauthenticated public routes, whose two-hour notice and 30-date horizon are the wrong policy and which are public by design), so the frontend could not obtain authoritative starts. The plain-read Catalog contract turned out to exist already (`ServiceAvailabilityAccess.findBookableServices`, one statement, added for the public availability), so no Catalog contract was added.

### Contract decision

Two routes under the authenticated namespace, mirroring the public pair (`booking-options` and `availability`), were chosen over a single combined route: the StaffMember choice must precede the date, the first read is cheap and stable while the second is date-specific, and a combined route would need an optional StaffMember and date and answer two different questions. They are named `appointment-options` as the record already did. The Service facts of the reviewed terms (name, duration, price) are **not** repeated: the form already obtains them, exactly and per Service, from the Service administration API, and the creation compares them with the locked rows anyway (`APPOINTMENT_REVIEW_CHANGED`); the routes return only the authoritative summed duration, which is the one the availability and the creation use. The public routes are not reused. The Phase 9 reschedule form will extend the availability route (the stored duration and the ignored Appointment); nothing speculative is added now.

### Finalised HTTP contracts

Both routes are `GET`, owner-only, derive the Business and the user only from the authenticated context (a `businessId` parameter is ignored), need no CSRF token, and answer `Cache-Control: no-store`. The selection is the repeated query parameter `serviceId`, one canonical 8-4-4-4-12 UUID per occurrence, one to five, no duplicate, order irrelevant (a comma list stays one malformed value); `staffMemberId` is one canonical UUID; `date` is a canonical `YYYY-MM-DD` Business-local date from 2000-01-01 to 2100-12-31. Unknown parameters are ignored; a repeated `staffMemberId` or `date` is invalid. No Customer, contact, note, price, or Appointment datum is in a request or a response.

| Route | Success keys (in order) |
|---|---|
| `GET /api/business/appointment-options?serviceId=…` | `timezone, durationMinutes, staffMembers[id, displayName]` — the active StaffMembers assigned to **every** selected Service (the intersection), ordered by canonical display name then identifier; empty when nobody supports the whole selection (a `200`, distinct from eligible StaffMembers without free time) |
| `GET /api/business/appointment-options/availability?serviceId=…&staffMemberId=…&date=…` | `timezone, date, durationMinutes, slots[start, end]` — the starts of that one StaffMember on that date, ordered by instant; every timestamp is ISO-8601 with the Business-local offset, so both occurrences of a repeated wall-clock hour are distinct |

The policy is the Business-side policy of ADR-0029 section 1 through the existing `AvailabilityQuery.calculateForBusiness`: **zero minimum notice** (the earliest start is the clock instant, inclusive, on the 15-minute grid), the summed **current** Service duration, the Business timezone and its daylight-saving rules, the schedule and its exceptions, every blocking Appointment, no ignored Appointment, and exactly the explicit StaffMember (never an automatic assignment). The clock is read once and truncated to microseconds, as the creation does, so the offered starts are exactly the starts the creation accepts (proved against the real creation).

| Status | Code | Detail |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Проверете въведените данни. (every malformed or out-of-bound request, and a current duration sum over 480 minutes; nothing is echoed) |
| 401 | `AUTH_REQUIRED` | fixed body of the security chain |
| 403 | `ACTIVE_BUSINESS_REQUIRED` | as for every owner route |
| 403 | `ACCESS_DENIED` | Нямате достъп до тази операция. (Manager and Staff) |
| 409 | `BUSINESS_NOT_ACTIVE` | Бизнесът не е активен. Часове могат да се създават само в активен бизнес. (`DRAFT`) |
| 409 | `BUSINESS_SUSPENDED` | Спрян бизнес може само да преглежда данните си. |
| 409 | `APPOINTMENT_SERVICE_UNAVAILABLE` | Някоя от избраните услуги вече не е налична. (a missing, foreign, and inactive Service, byte-identical) |
| 409 | `APPOINTMENT_STAFF_UNAVAILABLE` | Избраният член на екипа не е наличен за тези услуги. (availability only; a missing, foreign, inactive, and unassigned StaffMember, byte-identical) |
| 500 | `INTERNAL_ERROR` | Възникна неочаквана грешка. |

The fixed problem `instance` is `/api/business/appointment-options` for both routes; the statuses, codes, and wording are the creation's, so the form shows one text whether a problem appears while choosing or while saving.

### Lifecycle decision

The choices follow the availability of the mutation they prepare (ADR-0028 section 2: only `ACTIVE` mutates): a `DRAFT` Business gets `409 BUSINESS_NOT_ACTIVE` and a `SUSPENDED` one `409 BUSINESS_SUSPENDED`, the creation's own results, before any Service or StaffMember is read. The engine itself already refuses a non-`ACTIVE` Business. The frontend therefore shows no create entry for them and, if reached, explains with the shared wording.

### Module and decision order

`booking` publishes `AppointmentCreationOptions`, `AppointmentCreationOptionsRecords`, and `AppointmentCreationOptionsException` (three new root types; `BookingModuleBoundaryTests` and `CalendarModuleBoundaryTests` changed deliberately, none weakened) and implements them in `AppointmentCreationOptionsService`, which uses two existing narrow contracts (`ServiceAvailabilityAccess` and `PublicStaffAccess`, both plain reads) and `AvailabilityQuery`. The adapter adds `BusinessAppointmentOptionsController`, its HTTP records, and its exception handler to the `calendar` module, which still depends only on published root types of `booking` and `identity`. Decision order, as in the creation: authorization, lifecycle, domain bounds, Service availability, StaffMember eligibility. Shape errors (an unparsable identifier or date, a bad count) are raised in the adapter before authorization, exactly like the calendar reads.

### Transactions and statement counts

Each operation is one read-only `REPEATABLE READ` transaction whose first statement (the Business context) fixes the snapshot; it takes no lock, writes nothing, and reads the clock once. Statements, asserted through the published contract and identical for 1 and for 5 Services, 1 and 13 StaffMembers, and 0 and 72 Appointments: **options 4** (Business context, owner Membership, Service durations, StaffMember intersection); **availability 8** (the same four, then the engine's Business context, the StaffMember's weekly periods, the schedule exceptions, and the busy Appointments). The same count holds for a busy day, a day with no slots, and a past date.

### Evidence

Real PostgreSQL unless stated. `BusinessAppointmentOptionsApiIntegrationTests` 31 (exact bodies, headers, and keys; one and five Services; order independence; intersection, empty intersection, inactive and unassigned StaffMembers; the 480-minute boundary; missing, foreign, and inactive Services and StaffMembers byte-identical; zero notice and the grid; adjacency, overlap, a cancelled Appointment, another StaffMember's Appointment, the schedule and a day off; a repeated hour and a spring gap; `DRAFT`, `ACTIVE`, `SUSPENDED`; anonymous, no selection, Manager, Staff, inactive, foreign, and platform-only sessions; a client-supplied `businessId`; every malformed request; the supported date edges; CSRF-free reads and refused verbs; privacy of bodies, problems, and logs), `AppointmentCreationOptionsTransactionIntegrationTests` 6 (statement counts and tables, one read-only `REPEATABLE READ` transaction, no write or lock, and two snapshot proofs that commit a change on another thread between two statements, joined before the read continues, no sleep), `AppointmentCreationOptionsAgreeWithCreationIntegrationTests` 3 (the real creation accepts every offered start and rejects an unoffered one, the stored duration equals the reported one), and `AppointmentCreationOptionsServiceTests` 14 (**injected** collaborators and a counting clock, labelled so: the decision order, the exact engine call, the single truncated clock read, and the sanitized failure). **Mutation controls** (each applied, the named suites run, the file restored and byte-compared; **all 6 killed**): union instead of intersection, the public two-hour notice instead of zero notice, the longest instead of the summed duration, an unassigned StaffMember accepted, a foreign StaffMember accepted (check skipped and the engine's refusal swallowed), and ignored busy intervals.

### Verification (executed)

Focused suites while developing; one complete `./mvnw --batch-mode clean verify`: **4475 tests, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS`** (4421 after Phase 6; 54 added), 03:32 minutes. `V1` to `V15` are byte-identical to `HEAD`; `git diff --check` is clean. The frontend was not touched and no frontend suite was run. Local result only; the exact-HEAD CI result is a separate report.

### Deviations, limitations, and residual risks

1. The options carry no Service facts; the form takes them from the Service administration API (a separate read), and the creation remains the authority (`APPOINTMENT_REVIEW_CHANGED`).
2. A current duration sum over 480 minutes is a `400`, like the creation's domain bound, and not a distinct code.
3. The result is advisory and may be stale at once; the creation revalidates everything under its own locks.
4. `PublicStaffAccess` carries a "public" name but is a plain, identifier-and-name read; reusing it avoided a duplicate contract.
5. The availability covers one date per request; a month overview of free dates is not offered.
6. The reschedule form (Phase 9) needs the stored duration and the ignored Appointment; that extension is out of scope here.

### Stopping point

This prerequisite stops here for review and a separate commit. The Phase 8 frontend must not start until it is committed.

## Phase 8 record (2026-10-16; implemented and verified locally; awaiting the product owner's review; nothing staged or committed)

Base `HEAD` `2002a0187b92b955d06d0ed4f0c666543065e175` (the Phase 8 prerequisite, clean, equal to `origin/main`). **CI observed at the start (advisory, read-only):** the run for that commit was `in_progress`; it did not affect the work. Scope: issue #21, Standard, frontend only. No backend file, no migration (`V1` to `V15` byte-identical to `HEAD`), and no API contract changed; no missing contract or blocking contradiction was found. No reschedule, reassignment, cancellation, or internal-note editing of an existing Appointment exists; Phase 9 is not started.

### Route, entry, and lifecycle

| Route (hash) | Screen |
|---|---|
| `#/business/appointments/new` plus the calendar query (`view`, `date`, `staff`, `page`, `size`) | the manual-create flow; the query is only the calendar state that Cancel and the completion return to |

The entry is the `Нов час` button on the calendar, rendered **only** for the owner of an `ACTIVE` Business: for `DRAFT` and `SUSPENDED` no control exists at all, neither enabled nor disabled. The route is also guarded: a `DRAFT` or `SUSPENDED` Business, a Manager, a Staff, a platform-only session, and an anonymous visitor never reach the form (the first two are redirected to the calendar without any read, the others by the existing owner-route rules); a Business switch discards the form. The hidden control is not the only protection: every create, options, and availability refusal of the server (`BUSINESS_NOT_ACTIVE`, `BUSINESS_SUSPENDED`, `ACCESS_DENIED`, `ACTIVE_BUSINESS_REQUIRED`) is handled, blocks the confirmation, and points back to the calendar. A lost Business context uses the shared recovery. Nothing private is in the route: no Customer, contact, note, Service, price, or attempt state.

### Flow and headings

Six steps, one component in memory only (`business/appointments/`): **Клиент**, **Избор на услуги**, **Избор на служител**, **Дата и час**, **Вътрешна бележка**, **Преглед и потвърждение**. The durable documents define only the public headings («Избор на услуги», «Избор на служител», «Дата и час», «Преглед и потвърждение»), which the owner flow reuses; **Клиент** and **Вътрешна бележка** are new and, like every Bulgarian text of the flow, Proposed until the Phase 11 review. The button wording is `Напред`, `Назад`, `Създай час`, `Обратно към графика`.

- **Customer.** An existing Customer of the Business through the Customer search (the term travels only in the POST body of `…/customers/search`; a blank term lists the first ten; containing, case-insensitive semantics are the backend's; the standard debounce and abort rules). One contact line (phone, else email) disambiguates; more than ten matches say to narrow the search. **No Customer is created here**: `Добави клиент` is navigation away through the unsaved-changes guard (ADR-0028 section 5: a Customer is a deliberate record created through the Customer administration), and the flow does not return automatically.
- **Services.** All active Services in catalog (name) order from the Service administration API; native checkboxes in a labelled group; one to five, at most 480 minutes, enforced by disabling an unselected option with its reason in text while a selected one always stays removable; the selection panel lists the chosen Services with `Общо: 42.00 € — 75 мин.` (price first, integer cents).
- **StaffMember.** The options route for the canonical selection; native radios, none preselected, never assigned automatically; an empty intersection is its own message with `Промени услугите`; a failed read is a different message with a retry.
- **Date and time.** A native date (not before the Business "today"), the availability route for exactly (selection, StaffMember, date), the starts as radios; a repeated local time carries its offset (`03:30 (UTC+03:00)`), the spring gap offers nothing inside it; no timezone identifier and no explanatory sentence are shown; no start is a distinct message.
- **Note.** Optional, private; normalized (line breaks to LF, edge white space removed, blank omitted from the request, never truncated); too long or invalid characters are an inline error without a number.
- **Review.** Customer, ordered Services with the total, StaffMember, date and time, note; each section has its own `Промени`; the text says that the Customer and the Services of a created Appointment cannot be changed. Back is on the left, the forward action (`Напред`, `Създай час`) on the right.

### Invalidation and direct review edits

A changed Service set clears the StaffMember, the date, and the start; a changed StaffMember or date clears the start; the Customer and the note depend on nothing (the date is an input of the availability query, so a changed StaffMember keeps it). From the review each `Промени` returns to the review as soon as the later choices are still valid, otherwise to the first incomplete step. Every read is keyed by what it answers, aborted when superseded, and ignored if late; a change of the Customer or the note reloads nothing; the options are kept while an unrelated step is edited from the review.

### The request and the results

One `POST /api/business/appointments` per deliberate confirmation (a synchronous reference guard on top of the disabled button, and a permanent freeze after an unresolved outcome), exactly the Phase 4 keys: `customerId`, `staffMemberId`, `start` (the offered instant with its offset), `services[]` (`serviceId`, `reviewedServiceName`, `reviewedDurationMinutes`, `reviewedPriceCents`, sorted by canonical identifier, so the same set is the same bytes whatever the click order), and `internalNote` only when present. No client total, end, status, or idempotency value. The Service claims are the facts the review showed; if the authoritative duration of the options differs from them the terms are read again. A response that arrives after the form was left never navigates.

| Result | Frontend behavior |
|---|---|
| `201` | the form's state is dropped, history is **replaced** by the Appointment detail (so no resubmittable form stays, and Back returns to the originating calendar), `Часът е създаден.`, and the detail shows the data read from the server |
| `400 VALIDATION_ERROR` | generic message on the review |
| `401` | shared authentication recovery |
| `403`, `409 BUSINESS_NOT_ACTIVE`, `409 BUSINESS_SUSPENDED` | the confirmation is blocked, the message is shown, `Към графика` |
| `404 CUSTOMER_NOT_FOUND` | the Customer is dropped; back to the Customer step, everything else kept |
| `409 APPOINTMENT_SERVICE_UNAVAILABLE` | the catalog and options are read again; unavailable Services leave the selection; back to the Services |
| `409 APPOINTMENT_STAFF_UNAVAILABLE` | the options are read again; the StaffMember and start are dropped; back to the StaffMember |
| `409 APPOINTMENT_REVIEW_CHANGED` | the terms are read again and the new total is shown on the review; if the duration changed the start is dropped; the owner confirms again, never automatically |
| `409 APPOINTMENT_SLOT_UNAVAILABLE` | the starts are read again; only the start is dropped; back to Дата и час |
| `503 APPOINTMENT_TEMPORARILY_UNAVAILABLE` | "Часът не беше създаден. Опитайте отново след малко."; an explicit retry only |
| `503 APPOINTMENT_OUTCOME_UNCERTAIN`, a response that never arrived, an unreadable success response, and any other result that does not rule the creation out (`500`, an unknown or gateway failure) | **unresolved: the form freezes** (see below) |
| `500` and anything else | generic message, "Проверете графика…"; backend text is never shown |

**Known rollback versus unresolved outcome (correction before the commit).** A deterministic rejection and `503 APPOINTMENT_TEMPORARILY_UNAVAILABLE` are known rollbacks: the review stays editable and an explicit retry (`Създай час` again, or a repair step) is allowed. Every result that does not rule the creation out is **unresolved**, and because the manual create has no idempotency another POST before the calendar is checked could duplicate the visit, so the form freezes for good: the submitted review stays visible exactly as shown, `Създай час`, `Назад`, `Напред`, every `Промени`, and every input are removed (no way to edit and resubmit from the same unresolved form), the message claims neither that the Appointment exists nor that it does not (`Не е ясно дали часът е създаден. Проверете графика, преди да опитате отново, за да не създадете втори час.`), and nothing is sent again by a click, a repeated click, a stale handler, a rerender, or the passage of time. The only controls are `Обратно към графика` and the primary `Към графика`, both returning to the originating calendar state (view, date, StaffMember, page, size) without an ordinary unsaved-changes prompt; leaving elsewhere (the sidebar, browser Back) uses the guard with the warning `Неясен резултат` (`Не е ясно дали часът е създаден.` / `Проверете графика, преди да създавате часа отново.`); Forward or a Business switch only ever reaches a new, empty form. There is no `Започни отначало` in this phase (the durable documents do not require it). Nothing of the unresolved request is stored anywhere: it exists only in the memory of the mounted component.

`APPOINTMENT_CONCURRENT_UPDATE` is not a result of the create. A response that never arrived is treated as uncertain, not as "nothing was created", because the request may have been applied.

### Guard, accessibility, and privacy

The shared unsaved-changes guard covers the route-level Cancel, the sidebar, `Добави клиент`, the browser Back and Forward, and the Business switch (`Несъздаден час`, `Остани` first and focused, `Напусни` destructive). Focus moves to the step heading on every step change and to the first invalid control after a failed `Напред`; errors are inline and tied to their controls; groups are labelled fieldsets of native controls with 44 px rows. No private value reaches the URL, `history.state`, the title, local or session storage, cookies, or the console (asserted, together with a mutation that writes the note to storage).

### Evidence

**Automated** (Vitest and Testing Library; only the network helper mocked): `ManualCreate.test.tsx` 23 (the flow and headings, Customer search, the Services and cents arithmetic, the StaffMember and starts, DST), `ManualCreate.recovery.test.tsx` 44 (invalidation, direct review edits, stale and late responses, the note, the single POST and double click, every create result, validation and focus, five Services, privacy), `model.test.ts` 14, `App.manualCreate.test.tsx` 24 (the entry and lifecycle, route and return context, the guard, Back and Forward, success and history, Business switching, privacy), and additions to `BusinessCalendar.test.tsx` (2), `calendarLayout.test.ts` (3), and `App.contextRecovery.test.tsx` (1): **111 new tests**. **Mutation and control checks** (each applied, the focused suites run, the file restored and byte-compared; **all 15 killed**; two survived the first run, the floating-point total and the automatic retry after an uncertain result, because the tests used prices that a float adds exactly and a retry the reference guard happened to block, and were strengthened): independent POSTs per Service, an automatic StaffMember assignment, floating-point totals, click-order-dependent bytes, a stale StaffMember, date, and start kept after a Service change, an automatic retry after an uncertain result, a removed double-submit guard, the note written to storage, a create control shown for every lifecycle, a removed route guard, and the client terms trusted over the server duration; and, with the correction, an uncertain outcome that is not frozen, the create button available after an uncertain outcome, a stale callback that can still submit, and review edit controls available after an uncertain outcome.

**Developer-rendered** (a throwaway Node mock of the APIs with synthetic data in the scratchpad, never a database; not the Phase 11 approval): the whole flow at 1280, 820, 375, and 320 px and at 640 px (the 200% zoom of a 1280 px screen); one and five Services, long names, the sixth Service disabled, an empty intersection, a validation error, the uncertain outcome, a slot conflict with recovery, the dirty-leave dialog (`Остани` focused), success to the created Appointment, `DRAFT` and `SUSPENDED` (no control, redirect, one banner), a real keyboard-only pass (search, a radio with Space, `Напред` with Enter, heading focus), and `scrollWidth` equal to `clientWidth` at every step and width. Defects found and fixed: the empty-intersection step showed no error for `Напред`, the note label repeated the step heading, and an options or lifecycle refusal was shown twice.

The unresolved state was inspected again after the correction at desktop and mobile width (the review stays, only the two calendar actions exist, the primary action returns to the originating calendar state).

**Reserved:** the formal human visual review (Phase 11), a real 200% browser zoom, a screen reader, a real backend, and the browser E2E (Phase 12). The reviewed-terms, temporary-unavailable, `DRAFT`, and long-name states were inspected through the mock and tests, not against real data.

### Verification (executed)

Focused suites while developing; then one complete `npm test` (88 files, **2049 tests passed**), `npm run lint` (clean), `npm run build` (succeeded), and `git diff --check` (clean). Backend tests were not run because no backend file changed. Local result only; the exact-HEAD CI result is a separate report.

### Deviations, limitations, and residual risks

1. `Добави клиент` leaves the form and discards it (through the guard); the owner returns to the calendar and starts again.
2. The Customer search lists only the first ten matches (narrow the search) and shows a contact line.
3. The Service terms come from the Service administration API; the server remains the authority (`APPOINTMENT_REVIEW_CHANGED`).
4. A network failure, an unreadable success response, and any other result that does not rule the creation out are unresolved (the form freezes); only a known rollback allows an explicit retry.
5. After an unresolved outcome the owner must start a new creation from the calendar after checking it; the submitted data is not kept (no persistence by design, and no `Започни отначало`).
6. A changed StaffMember keeps the date (it is an input of the availability query).
7. Wording and layout stay Proposed until Phase 11.

### Stopping point

Phase 8 stops here for review. Phase 9 (the reschedule and reassignment frontend) needs its own approval. **The manual-create flow is available for optional browser inspection; this is a notification, not a gate: formal human visual approval remains Phase 11.**

## Phase 9 backend prerequisite record (2026-10-18; implemented and verified locally; awaiting the product owner's review; nothing staged or committed)

Base `HEAD` `72a754774250d28aeb7b5fbd407b8843f593b3f2` (Phase 8, clean, equal to `origin/main`). **CI observed (advisory, read-only):** the run for that commit (`39152665167`) was `in_progress` when the work started and finished as `failure` while it ran: the `backend` and `frontend` jobs succeeded and the `e2e` job failed in the step "Verify Business onboarding and lifecycle in the browser". It concerns the browser journeys of the existing committed code, is not related to this backend read-only addition, and was neither investigated further nor allowed to expand the work (the runs of the two previous commits had also ended as `failure`; their jobs were not inspected). Scope: issue #21, Strict, backend only. This is a **discovered Phase 9 prerequisite, not completed frontend work**: no frontend file changed, the reschedule form is not started, no migration (`V1` to `V15` byte-identical to `HEAD`, compared file by file), no change to the reschedule mutation, to the manual-create reads, or to the public availability, and no GitHub change.

### The missing read contract (confirmed before editing)

The Phase 8 report said the reschedule form needs (1) the Appointment's stored duration, (2) the Appointment ignored from its own busy intervals, (3) the authoritative eligible StaffMembers, and (4) the authoritative starts of one explicit StaffMember and date. The repository confirms all four are absent from every existing read: `GET /api/business/appointment-options[/availability]` computes the duration from the **current** Services of a client-supplied selection, passes **no** ignored Appointment, and checks the **current** assignments, so using it for a move would (a) offer a visit of another length, (b) make the Appointment conflict with itself, (c) refuse the current StaffMember when an assignment was removed later (which the mutation permits), and (d) make the browser re-derive eligibility it must not own. The private detail returns the stored duration and the Service lines but no eligibility and no starts. The engine already supports exactly what is needed (`AvailabilityQuery.calculateForBusiness` takes a duration and an ignored Appointment, and the Phase 5 mutation uses it that way); no engine, Catalog, or Workforce contract had to change. The only domain addition is a version-free state check, `AppointmentTransitions.rescheduleStateRefusal`, which delegates to the very check the mutation's `rescheduleRefusal` makes (a unit test proves they agree for every status and instant).

### Contract decision

Two **Appointment-scoped** routes, the names the task suggested (the existing conventions, `…/{appointmentId}/reschedule`, `/cancel`, and `/internal-note`, favour exactly this shape): the Appointment is a path identifier, so there is no way to supply a different duration, Service selection, or ignored Appointment; one route answers "who can the visit be given to" (cheap and stable), the other "which starts" (date-specific), as in the manual-create pair. Extending `appointment-options/availability` with optional duration or ignored-Appointment parameters was rejected because it would accept exactly the client claims that must not be trusted. The Service facts are not returned (the form already has them from the private detail); `version` is not returned (the mutation's `expectedVersion` comes from the detail the form already loaded, and a version in the options would invite using a different one).

### Finalised HTTP contracts

Both routes are `GET`, owner-only, derive the Business and the user only from the authenticated context, need no CSRF token, answer `Cache-Control: no-store`, set no cookie and no `Location`, and return no Customer contact, note, cancellation actor, price, version, or Service datum. The path identifier and `staffMemberId` are canonical 8-4-4-4-12 UUIDs (an upper-case one is the same identifier; a hyphenless, signed, or short form is invalid); `date` is a canonical `YYYY-MM-DD` Business-local date from 2000-01-01 to 2100-12-31; a missing or repeated `staffMemberId` or `date` is invalid; unknown parameters are ignored (the project's rule for the private calendar reads).

| Route | Success keys (in order) |
|---|---|
| `GET /api/business/appointments/{appointmentId}/reschedule-options` | `timezone, durationMinutes, currentStaffMember{id, displayName, available}, staffMembers[id, displayName]` — `durationMinutes` is the **stored** duration; `currentStaffMember` carries the stored snapshot name (as the detail shows it) and whether it is still active (all a time-only move needs); `staffMembers` are the **replacements**: the other active StaffMembers assigned to every stored Service line, ordered by canonical display name then identifier (empty when there is none; the current StaffMember is never listed) |
| `GET /api/business/appointments/{appointmentId}/reschedule-options/availability?staffMemberId=…&date=…` | `timezone, date, durationMinutes, slots[start, end]` — the starts of that one StaffMember on that date for the stored duration, ordered by instant; every timestamp is ISO-8601 with the Business-local offset, so both occurrences of a repeated wall-clock hour are distinct |

The policy is the Business-side policy of ADR-0029 section 1 through `AvailabilityQuery.calculateForBusiness`: **zero notice** (the earliest start is the clock instant, inclusive, truncated to microseconds like the mutation, on the 15-minute grid), the stored duration, the Business timezone and daylight-saving rules, the schedule and its exceptions, every blocking Appointment **except the route Appointment**, exactly the explicit StaffMember (never an automatic assignment). One refinement of what is offered: **for the current StaffMember the unchanged start is not offered**, because a request that changes nothing is refused by the mutation (`400`); it is offered to a replacement, because that is a reassignment. This keeps the stated property "every offered start is accepted by the real mutation".

| Status | Code | Detail |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Проверете въведените данни. (every malformed identifier, StaffMember, or date; nothing is echoed) |
| 401 | `AUTH_REQUIRED` | fixed body of the security chain |
| 403 | `ACTIVE_BUSINESS_REQUIRED` | as for every owner route (no selection, platform authority alone, an inactive, missing, or foreign Membership) |
| 403 | `ACCESS_DENIED` | Нямате достъп до тази операция. (Manager and Staff) |
| 404 | `APPOINTMENT_NOT_FOUND` | Часът не е намерен. (a missing and a foreign Appointment, byte-identical) |
| 409 | `BUSINESS_NOT_ACTIVE` | Бизнесът не е активен. Часовете могат да се променят само в активен бизнес. (`DRAFT`) |
| 409 | `BUSINESS_SUSPENDED` | Спрян бизнес може само да преглежда данните си. |
| 409 | `APPOINTMENT_STATE_CONFLICT` | Часът не може да бъде променен, защото е отменен или вече е започнал. (`CANCELLED`, or `start_at` not after the clock instant; no option is exposed) |
| 409 | `APPOINTMENT_STAFF_UNAVAILABLE` | Членът на екипа не е наличен за тази промяна. (availability only; a missing, foreign, inactive-current, inactive, and ineligible StaffMember, byte-identical) |
| 500 | `INTERNAL_ERROR` | Възникна неочаквана грешка. |

Every problem has exactly the keys `title, status, detail, instance, code`, the media type `application/problem+json`, `no-store`, and the fixed `instance` `/api/business/appointments` (the route family's base path), so no identifier is echoed. The wording is the reschedule route's, so the form shows one text whether a problem appears while choosing or while saving.

### Current versus replacement StaffMember rules (the mutation's, exactly)

| Case | Mutation (Phase 5) | Options and availability |
|---|---|---|
| keep the current StaffMember (time-only move) | `lockActiveForBooking`: active only; no Service and no assignment check | `currentStaffMember.available` = active; availability for it needs only an active StaffMember (the engine refuses an inactive one as `APPOINTMENT_STAFF_UNAVAILABLE`); a Service deactivated or an assignment removed later changes nothing |
| a replacement | `lockEligibleForBooking` over the stored Service identifiers: active and assigned to **every** stored line | listed only if active and assigned to every stored line (the same predicate, `PublicStaffAccess.findBookableStaffForServices`, ordered by name then identifier); availability for any other StaffMember is `APPOINTMENT_STAFF_UNAVAILABLE` |
| missing, foreign, inactive, or ineligible target | one byte-identical `APPOINTMENT_STAFF_UNAVAILABLE` | the same single result |

The exact distinction is implementable from the current contracts (the stored Service identifiers on the Appointment, `PublicStaffAccess`, and `StaffAvailabilityAccess.findActiveWithWeeklyPeriods`); no seam is missing.

### Lifecycle, state, and role matrix

Decision order, the mutation's: authorization, (shape and domain bounds), lifecycle, existence, Appointment state, StaffMember. Shape errors of the identifiers and the date are raised in the adapter before authorization, exactly like the other private reads; the date's range is checked by the service after authorization. `DRAFT` and `SUSPENDED` are refused before the Appointment is read; a foreign and a missing Appointment are one result; a `CANCELLED` and a started Appointment are the state conflict on both routes. Anonymous, no selection, platform-only, inactive, foreign-Membership, Manager, and Staff are the rejections of every owner route; a platform administrator who is also an owner is allowed as an owner.

### Transactions and statement counts

Each operation is one read-only `REPEATABLE READ` transaction whose first statement (the Business context) fixes the snapshot; it takes no lock, writes nothing, and reads the clock once (after the Appointment exists, like the mutation). Statements, asserted through the published contract and identical for 1 and for 5 Service lines, 1 and 13 StaffMembers, and 1 and 74 Appointments: **options 6** (Business context, owner Membership, Appointment, its lines, the replacement intersection, the current StaffMember's activity); **availability 8** for the current StaffMember (Business context, owner Membership, Appointment, lines, then the engine's Business context, weekly periods, schedule exceptions, and busy Appointments) and **9** for a replacement (plus the intersection). The same counts hold for a busy day, a day with no slots, and a past date.

### Evidence

Real PostgreSQL unless stated. **73 new tests.** `BusinessAppointmentRescheduleOptionsApiIntegrationTests` 32 (exact bodies, key sets, headers, no `Location` or `Set-Cookie`; the stored duration and Service identities after the Services were changed and deactivated; replacements as the intersection over several stored lines, inactive and unassigned StaffMembers excluded, ordered; a current StaffMember who lost the assignment still keepable but not a replacement; an inactive current StaffMember; byte-identical missing, foreign, inactive, and unassigned StaffMembers; the own interval ignored, the unchanged start not offered, a replacement offered it; a different overlapping Appointment blocking, adjacency free, a cancelled Appointment and another StaffMember's not blocking; zero notice on and off the grid; a day off; a repeated hour and a spring gap; `CANCELLED`, started, starting-exactly-now, and one microsecond later; byte-identical missing and foreign Appointments; `DRAFT`, `ACTIVE`, `SUSPENDED`; anonymous, no selection, Manager, Staff, inactive, foreign, and platform-only sessions; forged `businessId`, `durationMinutes`, `serviceId`, `ignoredAppointmentId`, and `appointmentId` parameters changing nothing; every malformed request; the date edges; CSRF-free reads and refused verbs without any write; privacy of bodies, problems, and logs including a foreign Business's values), `AppointmentRescheduleOptionsAgreeWithRescheduleIntegrationTests` 8 (**the agreement proof**: for the current StaffMember and for a replacement, every offered start is accepted by the real `AppointmentAdministration.reschedule` on a fresh Appointment and every other one of the 48 candidate instants per StaffMember (the 24 quarter-hour starts of 05:00 to 11:00 UTC and the same instants shifted by one minute) is refused, with the numbers of accepted and refused moves asserted above 15 each; a different overlapping Appointment blocks; adjacency is allowed; the Appointment never conflicts with itself; the stored 60-minute duration of a two-Service visit governs both sides after the Services became 15 minutes and inactive; the current StaffMember needs only to be active; a replacement is eligible exactly when the mutation locks it, for an unassigned, inactive, foreign, and missing target and for a multi-Service visit; `CANCELLED` and started Appointments are refused by both; `DRAFT`, `SUSPENDED`, Manager, foreign Membership, missing and foreign Appointment give the same typed result on both sides), `AppointmentRescheduleOptionsTransactionIntegrationTests` 8 (statement counts and tables, one read-only `REPEATABLE READ` transaction, no write or lock statement, three snapshot proofs that commit a change on another thread between two statements, joined before the read continues, and a proof with latches that a writer holding exclusive row locks on the Appointments, StaffMembers, and Business (verified held with `FOR UPDATE NOWAIT`) does not delay either read and leaves no `Lock` wait in `pg_stat_activity`; no sleep), `BusinessAppointmentRescheduleOptionsBoundaryTests` 5, `AppointmentRescheduleStateRefusalTests` 3, and `AppointmentRescheduleOptionsServiceTests` 17 (**injected** collaborators and a counting clock, labelled so: the decision order, the exact engine call with the stored duration, the single truncated clock read, the current-start exclusion, no Service contract, and the sanitized failure). The existing Phase 5 reschedule suites and the Phase 8 manual-create option suites ran **unchanged and green** (423 tests in the focused regression run, which also contained the new suites). `BookingModuleBoundaryTests` and `CalendarModuleBoundaryTests` changed only by the deliberate allowlist additions (three root types; the two new `GET` routes), none weakened.

**Mutation controls** (each applied to the production code, the five new suites run, the file restored and byte-compared by SHA-256 after the last one; **all 9 killed**, none a compile error): M1 not ignoring the current Appointment (9 tests fail); M2 ignoring every busy Appointment, in `BookingBusyIntervalSource` (6); M3 the current Service durations instead of the stored duration, by a temporary SQL sum over the current Services (6); M4 allowing an inactive current StaffMember to be reported keepable (4; the engine's own refusal of an inactive StaffMember in the availability read is covered by the unchanged availability suites); M5 allowing an ineligible replacement (6); M6 applying the public two-hour notice (3); M7 trusting a client-provided ignored Appointment, in the controller (1: the forged-parameter test; trusting a client-provided duration cannot be mutated without inventing a parameter, because the service interface has none, which `BusinessAppointmentRescheduleOptionsBoundaryTests` pins together with the exact engine call, and the forged `durationMinutes` is shown to change nothing); M8 returning options for a cancelled or started Appointment (4); M9 offering the unchanged start to the current StaffMember (5).

### Verification (executed)

Focused suites while developing. **Complete `./mvnw --batch-mode clean verify` on the final code: 4548 tests (4475 + 73), 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS`, 04:38 minutes.** An earlier complete run on the same code ended at 4548 tests with 4 errors, all in `BookingAvailabilityIntegrationTests`: the Spring context could not start because the Flyway connection to the Testcontainers PostgreSQL timed out (`SocketTimeoutException: Read timed out`) after 52 seconds; the class passes alone (4 tests) and the repeated complete run was green, so the failure was environmental and is reported, not hidden. `V1` to `V15` are byte-identical to `HEAD`; `git diff --check` is clean; a Java hygiene check of the 16 changed or new Java files (no tab, trailing whitespace, CR, wildcard or unused import, `System.out`, `printStackTrace`, `TODO`, overlong main line, and a final newline everywhere) found nothing. The frontend was not touched and no frontend suite was run. Local result only; the exact-HEAD CI result is a separate report.

### Changed-file inventory

Production: `booking/AppointmentRescheduleOptions.java`, `…Records.java`, `…Exception.java` (published root types), `booking/application/AppointmentRescheduleOptionsService.java`, `booking/domain/AppointmentTransitions.java` (one added method), `calendar/web/BusinessAppointmentRescheduleOptionsController.java`, `…ExceptionHandler.java`, `…HttpRecords.java`. Tests: the six new classes named above, plus the two deliberate allowlist additions in `BookingModuleBoundaryTests` and `CalendarModuleBoundaryTests`. Documentation: this record, the status and route rows of this file, `ADR-0028` and `ADR-0029` implementation notes, `architecture.md`, `security.md`, `testing-strategy.md`, `implementation-plan.md`, and `product-roadmap.md`.

### Deviations, limitations, and residual risks

1. The unchanged start is deliberately not offered to the current StaffMember (see above); a client that wants to keep the start simply does not change it.
2. Unknown query parameters are ignored, not rejected (the project's rule for the private calendar reads); the HTTP test proves a forged duration, Service, Business, or ignored-Appointment parameter changes nothing.
3. The fixed problem `instance` is the base path `/api/business/appointments`, shared with the other Appointment routes, per the convention of the module.
4. The current StaffMember's `displayName` is the stored snapshot (as the detail shows it), not the current name; replacements show the current names.
5. A clock earlier than the Appointment's creation instant makes the state check, like the mutation's, an internal inconsistency and an `INTERNAL_ERROR`; this cannot happen with a real clock and is not changed here.
6. The result is advisory and may be stale at once; the mutation revalidates everything under its own locks (a start offered here can still be `SLOT_UNAVAILABLE`, `STAFF_UNAVAILABLE`, or a state conflict when it is submitted).
7. One date per request; a month overview of free dates is not offered.
8. `StaffAvailabilityAccess.findActiveWithWeeklyPeriods` also reads the weekly periods; only the activity is used here (one bounded statement).

### Stopping point

This prerequisite stops here for review and a separate commit. The Phase 9 frontend (the reschedule and reassign form) must not start until it is committed.

## Phase 9 record (2026-10-18; implemented and verified locally; awaiting the product owner's review; nothing staged or committed)

Base `HEAD` `8c80b79503553831439791b271f7a65f222da70f` (the Phase 9 backend prerequisite, clean, equal to `origin/main`). **CI observed (advisory, read-only):** the run for that commit (`39155416863`) was `in_progress` when the phase started and finished as `failure`: the `backend` and `frontend` jobs succeeded and the `e2e` job failed in the step "Verify Business onboarding and lifecycle in the browser", the same step that failed for the previous commit; it concerns the browser journeys of committed code, was not investigated further, and did not block the phase. Scope: issue #21, Standard, **frontend only**. No backend file, no migration, and no contract changed; the two committed option reads and the Phase 5 mutation were consumed exactly as documented, and no missing contract or contradiction was found. Phases 10 to 12 are not started, no GitHub change, and no review environment is left running.

### Route and entry

- **Route:** `#/business/appointments/{appointmentId}/reschedule?<origin>`, kind `business-appointment-reschedule`. The query holds only the safe origin of the detail it was opened from (the calendar `view`, `date`, `staff`, `page`, and `size`, or `customer`); anything else in the query is dropped when the route is read, so no Customer, Service, note, price, Appointment fact, or version can travel in it.
- **Entry:** `Промени часа` beside `Обратно към графика` on the private Appointment detail. It exists only for the owner of an `ACTIVE` Business (session and the `businessStatus` of the detail) and only for a `CONFIRMED` Appointment whose start is after the clock; otherwise it is **absent**, not disabled. The server decides again on every request.
- **Direct route:** the same guards as every Business route (anonymous: sign-in; Manager, Staff, platform-only: the profile or Business selection; no selection: the Business selection). A `DRAFT` or `SUSPENDED` Business is redirected (replace, not push) to the read-only detail of the same Appointment before any read. A refusal that arrives from the server (`ACCESS_DENIED`, `ACTIVE_BUSINESS_REQUIRED`, `BUSINESS_NOT_ACTIVE`, `BUSINESS_SUSPENDED`, `APPOINTMENT_NOT_FOUND`) is one safe message with the way back, and a cancelled or started Appointment (judged from the authoritative detail and, if raced, from `APPOINTMENT_STATE_CONFLICT`) removes the mutation path and explains why.
- **Return:** `Обратно към часа` and `Отказ` return to the detail with the same origin; the detail returns to the exact calendar state (or Customer) it came from. A switch of Business replaces the route with the calendar and discards every Appointment-scoped value; a lost context uses the shared recovery.
- **Shell:** the section title is `Промяна на час`; the sidebar keeps `График` current; the lifecycle banner of the calendar screens applies.

### Form

Three steps in one component (`Reschedule.tsx`, state in memory only): **Избор на служител**, **Дата и час**, **Преглед и потвърждение**. The Appointment (`GET /api/business/appointments/{id}`) and `GET …/reschedule-options` are read first; every fact comes from the server.

- **Read-only context** (steps 1 and 2): the Customer, the Services in stored order with their stored price and duration and the stored total, the current StaffMember, the current date and time, and the status; no control can change them, and the text says that the Customer and Services cannot be changed and that the Business must cancel and recreate when they are wrong. The version is used only as `expectedVersion` and is never displayed; no technical timezone identifier is shown.
- **StaffMember:** native labelled radios in one fieldset. The current StaffMember is a separate first option, **preselected** while `currentStaffMember.available` is true (keeping it is the unchanged state, not an assignment; this is the one deliberate departure from section 23 of the UI guide, where nothing is preselected), and **absent** with an explanation when it is not; the replacements come only from the options response and never repeat the current one. An empty replacement list (a statement) is distinct from a failed read (a retryable error), and nobody available at all is one statement that cannot proceed. Nobody is ever assigned automatically.
- **Date and time:** the date starts as the original Business-local date (a date-only string, never converted through the browser timezone); `GET …/availability?staffMemberId=…&date=…` is the only availability request and carries no duration, Service, price, or ignored Appointment. The offered starts are shown with `formatTimeDistinct`, so both occurrences of a repeated hour keep their offsets and a spring gap is never synthesized. The server does not offer the unchanged start to the current StaffMember (the form relies on that and never recomputes it); a replacement is offered it. An empty day, a failed read, and no eligible StaffMember are three different states.
- **Invalidation:** a changed StaffMember or date clears the chosen start; reads are abortable per key and a late answer is ignored.
- **Review:** the unchanged Customer and Services (marked «без промяна») with the stored total, the original and the proposed StaffMember, date, and time side by side (`Сега:` and `След промяната:`, in text, not by colour). `Промени` returns directly to the review when the remaining choices are still valid. Confirmation is enabled only for an **effective** change (another StaffMember or another instant); a no-op is explained and never sent.
- **Action order:** `Отказ` or `Назад` on the left, `Напред` or `Промени часа` on the right in one row; `Обратно към часа` sits above.

### The mutation

One `POST /api/business/appointments/{id}/reschedule` per deliberate confirmation, with exactly the documented body: `expectedVersion` (the loaded Appointment's) always, `staffMemberId` only when it differs from the stored one, and `start` only when it differs from the stored instant. A synchronous ref guard blocks double clicks and stale callbacks; a response that arrives after the form was left does not navigate; nothing is retried automatically; the calendar and the detail are never moved optimistically. A success is accepted only for a readable answer for the same Appointment; the route is then **replaced** by the detail (the server re-reads it), one `Часът е променен.` is shown, and the originating calendar reads its own data when it is opened.

### Failure and recovery matrix (frontend-owned Bulgarian wording, never the backend text)

| Result | Behaviour |
|---|---|
| `VALIDATION_ERROR` | the review and the proposal stay; safe text; editable; an explicit retry is possible |
| `AUTH_REQUIRED` | the shared authentication recovery; no other message |
| `ACCESS_DENIED`, `ACTIVE_BUSINESS_REQUIRED`, `BUSINESS_NOT_ACTIVE`, `BUSINESS_SUSPENDED`, `APPOINTMENT_NOT_FOUND` | confirmation blocked; one message and the way back to the calendar (`Към графика` or `Към клиента`) |
| `APPOINTMENT_CONCURRENT_UPDATE` | the stale proposal, version, and options are discarded; the Appointment and the options are read again; the owner starts the review again; the next request carries the new version |
| `APPOINTMENT_STATE_CONFLICT` | the Appointment is read again; the mutation path is removed; the message says it is cancelled or has started; `Към часа` and the calendar remain |
| `APPOINTMENT_STAFF_UNAVAILABLE` | the options are read again; only the invalid StaffMember choice is cleared; the owner returns to the StaffMember step |
| `APPOINTMENT_SLOT_UNAVAILABLE` | the availability is read again; only the start is cleared; the StaffMember and date are kept |
| `APPOINTMENT_TEMPORARILY_UNAVAILABLE` | a known rollback: "nothing was changed"; the form stays editable; only an explicit retry |
| `APPOINTMENT_OUTCOME_UNCERTAIN`, an unexpected or unknown result, a lost response, an unreadable success, a success for another Appointment | **sticky freeze**: the submitted review stays visible and read-only; confirmation, edit, retry, and step controls are removed; the message claims neither success nor failure; only `Към часа` and the calendar or Customer button remain, and they leave without the ordinary prompt; leaving elsewhere shows `Неясен резултат`; exactly one POST was ever sent |

Guard and the **dirty baseline rule** (corrected before the commit): the form is dirty exactly when it retains an owner-selected value that differs from the authoritative original Appointment: a StaffMember other than the original (or any StaffMember when the original cannot be kept), a date other than the original, or a chosen start. It is never decided by whether the proposal is complete or submittable. A cleared value (no StaffMember, no date) is not a retained choice, so a form holding only the original values, or nothing selected, is clean. Consequences: after `APPOINTMENT_SLOT_UNAVAILABLE` only the start is cleared and a changed StaffMember or date keeps the ordinary `Непотвърдена промяна` warning; after a failed availability read the safe choices stay and keep the guard while they differ; after `APPOINTMENT_STAFF_UNAVAILABLE` the invalid StaffMember is cleared and the guard stays only if another choice (for example the date) still differs, so the form is clean when it truly returns to the original state; after `APPOINTMENT_CONCURRENT_UPDATE` the reloaded Appointment is the new clean baseline (the form is rebuilt from it, never compared with the obsolete version); a temporary refusal preserves the complete proposal and the guard; an unresolved outcome uses only the stronger `Неясен резултат`. A clean form leaves without a warning, a success clears the guard before navigating, and a changed proposal warns with `Непотвърдена промяна` (`Остани` focused first). Back and Forward are guarded like every route; a later visit to the route mounts a fresh form that reads everything again and restores nothing; a Business switch discards everything. Nothing unresolved is persisted anywhere.

### Evidence

**Automated** (Vitest and Testing Library; only the network helper mocked; deterministic, no sleeps; the clock is injected into the form and only `Date` is faked at application level): `Reschedule.test.tsx` 31 (the initial data and read-only context, the lost Appointment, a failed options read versus an empty list, cancelled and started Appointments, `DRAFT` and `SUSPENDED`, the current StaffMember and the inactive-current, empty-replacement, and nobody cases, long names, the exact availability request, the excluded current start and the replacement that is offered it, a repeated hour and a spring gap, three browser timezones, the clearing of the start, direct edits from the review, the three distinct empty/failed/no-StaffMember states, an aborted and a late availability read, the exact time-only, reassignment-only, and combined bodies, a no-op and an equal instant in another spelling, double click and stale callbacks, a response after leaving, action order and focus, privacy), `Reschedule.recovery.test.tsx` 27 (validation and temporary refusal with an explicit retry, an expired session, the five blocking refusals, the concurrent update with the new version, the state conflict, the StaffMember and slot results, five unresolved results and the stale-button and key attempts, the guard in every state), `Reschedule.guard.test.tsx` 12 (the dirty baseline after every recoverable failure: a slot conflict that keeps a changed StaffMember and date, the leave warning and `Остани` and `Напусни`, the form becoming clean only when nothing differs, a failed availability read, the StaffMember refusal with and without another surviving difference, a concurrent update as a new clean baseline, a known rollback, and the stronger warning alone after an uncertain outcome), `rescheduleModel.test.ts` 17, `navigation.reschedule.test.ts` 5, and `App.reschedule.test.tsx` 32 (the entry for ACTIVE, `DRAFT`, `SUSPENDED`, cancelled, started, exactly-now, Manager, Staff, platform-only, and anonymous sessions, the direct routes, the return state, Back and Forward, the replaced history after a success, a pending request that moves nothing, the unresolved outcome through the shell, Business switching and a lost context, and privacy of the URL, history state, title, storage, cookies, and console): **124 new tests**. **Mutation controls** (each applied, the five focused suites run, the files restored and byte-compared after the last run; **all 18 killed**): a no-op submit allowed; a client duration sent; a stale start kept after a StaffMember change and after a date change; a late availability answer accepted; an automatic retry after an uncertain result (the first, unbounded formulation looped until the runner timed out, so it was reformulated as exactly one automatic retry and killed by two tests); the mutation re-enabled after an uncertain result; `expectedVersion` omitted; the Appointment moved optimistically before the answer; a response after leaving that navigates; the current StaffMember duplicated among the replacements; an unavailable current StaffMember kept; the date taken from the browser timezone; and the unchanged start sent; and, with the dirty-state correction, a dirty state that depends only on a chosen start (17 tests fail), one that depends only on submit readiness (13), a stale baseline kept (2), and no reload of the Appointment after a concurrent update (1). One formulation of the last (keeping the form key) survived because the loading view already unmounts the form; it was recognised as an equivalent mutant and replaced.

**Developer-rendered** (a throwaway Node mock of the APIs with synthetic data in the scratchpad, never a database or the real backend; not the Phase 11 approval): the flow at 1280, 820, 640 (the 200% zoom of 1280), 375, and 320 px. Sixteen states were driven through the real controls at every width: the default flow, an inactive current StaffMember, an empty replacement list, nobody available, very long Customer, Service, and StaffMember names, no slots, a failed availability read, a repeated DST hour (five starts with distinct offsets), a validation error and its explicit retry, a known rollback, a slot conflict and its recovery (start cleared, date kept), a StaffMember conflict, a concurrent update, the uncertain frozen state, and a lost response. `scrollWidth` equals `clientWidth` and no element exceeds the viewport in any state at any width; the step heading or the notice holds focus after every change. The unsaved dialog was inspected at 375 px (`Остани` focused, safe action first). After the dirty-state correction the recovery case was repeated at 1280 and 375 px: a replacement StaffMember and another date, a slot conflict, the start cleared with the StaffMember and date still shown, the `Непотвърдена промяна` warning on leaving, `Остани` keeping the proposal, only-the-date-differs still warning, and the original StaffMember and date again leaving without a warning; no overflow. A real keyboard-only pass at 1280 px (Tab to the radio group, an arrow key to choose a replacement, Enter on `Напред`, Tab to the date and the first start, arrow keys to choose, Enter on the review, Enter on `Промени часа`) completed the whole change with the exact body and returned to the re-read detail and then to the calendar showing the new time. Defects found and fixed: the review repeated the whole context panel above itself (now only on the earlier steps), and the "nobody available" state said both "choose another" and "nobody is available" (now one statement).

**Reserved:** the formal human visual review (Phase 11), a real 200% browser zoom, a screen reader, a real backend, and the browser E2E (Phase 12). Wording and layout are Proposed until Phase 11.

### Verification (executed)

`npx tsc -b`, `npm run lint`, `npm run build`, and the complete `npx vitest run`: **94 files and 2173 tests, 0 failures** (2049 before; 124 added), and `git diff --check` clean. No backend file changed, so no backend suite was run. Local result only; the exact-HEAD CI result is a separate report.

### Deviations, limitations, and residual risks

1. The current StaffMember is preselected whenever it is still available. This is the intended reschedule default, not a deviation and not a product decision: the manual create (UI guide section 23) preselects nobody because a new Appointment has no current StaffMember, and the reschedule review still requires an effective change. The UI guide now states both rules.
2. The unresolved freeze applies to an unknown result and to a 5xx without a documented code, as in Phase 8: a conservative choice, because none of them rules out a commit.
3. An abandoned in-flight request (the owner left while it was pending) cannot be cancelled and may commit; the owner finds the real state on the re-read detail.
4. *(Corrected before the commit.)* The earlier report said that after a slot conflict "nothing remains to warn about"; that was wrong for a proposal whose StaffMember or date differs from the original. The rule above applies, with tests.
5. The success message is shown by the shell feedback and is cleared by the next navigation like every other.
6. The detail shows the technical timezone identifier that Phase 7 already displayed; the new flow does not.
7. The browser E2E (Phase 12) is the first test against the real backend; the contracts were exercised against fakes that follow the committed backend tests.

### Stopping point

Phase 9 stops here for review and a separate commit. **The reschedule and reassignment flow is available for optional browser inspection; this is a notification, not a gate: formal human visual approval remains Phase 11.** Phase 10 (cancellation and the internal-note editor) needs its own approval.
