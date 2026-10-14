# SpotYourSlot — Business Calendar and Appointment Management (Issues #19 and #21)

Status (current, 2026-10-14): **Phase 1 (decisions and documentation) is complete and awaits review; nothing is implemented.** Issues #19 and #21 are one combined delivery and both are In Progress on the Project board. The product owner alone edits GitHub (issue texts, comments, states, Project status); this repository work never does. The decisions are recorded in [ADR-0028](../decisions/ADR-0028-administer-appointments-through-a-private-owner-only-api-with-immutable-visit-identity-and-business-cancellation.md) and [ADR-0029](../decisions/ADR-0029-calculate-business-availability-with-a-separate-policy-and-serialize-appointment-mutations-with-ordered-locks-optimistic-versions-and-bounded-retry.md); this record holds the plan, the acceptance mapping, the contract and migration plan, the phase plan, and the exact proposed GitHub amendment texts.

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
| `GET /api/business/appointment-options?...` | Authoritative starts for a create or a reschedule (advisory, reserves nothing) | 4 and 5 |
| `POST /api/business/appointments` | Create | 4 |
| `POST /api/business/appointments/{id}/reschedule` | Move and/or reassign | 5 |
| `POST /api/business/appointments/{id}/cancel` | Cancel | 6 |
| `PUT /api/business/appointments/{id}/internal-note` | Edit the internal note | 6 |

## Contract and module changes (planned)

- `scheduling`: the engine request takes the minimum notice and the date window; `AvailabilityQuery` gains a Business-side entry (Business, occupied duration, StaffMember, window, injected `now`, optional ignored Appointment); `BusyIntervalSource` gains a second abstract method; a published working-time overview per StaffMember and date. The public path passes the former constants and its results do not change.
- `workforce`: a published read of an active StaffMember's weekly periods by identifier, independent of Service assignments.
- `customer`: narrow published read contracts for display names and for the detail's contact data (same-Business, no lock); the module still does not depend on `booking`.
- `booking`: published `AppointmentAdministration` (create, reschedule, cancel, note) and `AppointmentCalendar` (reads); a separate non-transactional Business runner; narrow use of the published `identity` types; the new `UPDATE` statements.
- A new HTTP adapter module (working name `calendar`) that depends only on the published contracts of `booking`.
- Pinned tests that change with these edges (none is weakened): `BookingModuleBoundaryTests`, `CustomerModuleBoundaryTests`, `ScheduleRevisionModuleBoundaryTests`, `AvailabilityMutationInventoryTests`, `BusyIntervalSourceFailFastTests`.

## Migrations (planned, not created)

**`V14__add_appointment_administration.sql` (Phase 2).** The SQL, the invariants, and the test adaptations are in [ADR-0028 section 8](../decisions/ADR-0028-administer-appointments-through-a-private-owner-only-api-with-immutable-visit-identity-and-business-cancellation.md). It adds `cancelled_at`, `cancelled_by`, and `internal_note`, backfills `UNKNOWN` for rows already `CANCELLED`, adds the strict consistency checks, and ends with a verification block that fails the migration on any inconsistent row. Required evidence: a V13-to-V14 upgrade test over `CONFIRMED` and `CANCELLED` rows (the old cancelled rows become `UNKNOWN` with a null time, every other column identical), each allowed and each forbidden attribution combination, a migration that fails and leaves nothing on a corrupt fixture, the V1 to V13 hashes unchanged, and a fresh install equal to an upgraded one.

**`V15` (Phase 3).** Indexes ship only with the read query that uses them and only after `EXPLAIN ANALYZE` with representative synthetic data (about 10,000 and 50,000 Appointments over several Businesses, as the Customer search measurement did):

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
| 4 | Manual multi-Service create and Business conflict mapping | #19 |
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

- **Scope:** the create operation of `AppointmentAdministration`, the non-transactional Business runner and attempt procedure (a separate class; the guest runner is not touched), the options route, `POST /api/business/appointments`, the full error taxonomy for create, and the HTTP mapping. **Exclusions:** reschedule, cancel, note edit, idempotency columns, and the frontend.
- **Verification:** unit tests of the runner (entry guard before any work, one new transaction per attempt, retry classification, commit-phase classification with injected statuses labelled as such); PostgreSQL integration tests of the success path (lines in profile order, summed totals, snapshots from locked rows), the eligibility matrix, the reviewed-terms check with zero writes, a foreign or missing Customer, the `DRAFT` and `SUSPENDED` rejections, the role matrix, isolation, the instant rules and daylight-saving cases, and privacy sentinels. **Full:** `./mvnw --batch-mode verify`.
- **Concurrency evidence:** create versus create, create versus guest booking, the loser leaves no row and no Customer change, lock order observed with `FOR UPDATE SKIP LOCKED` probes at the hook points, a lock-changed-after-snapshot `40001` retry in a distinct transaction, the controlled exclusion-index deadlock retry.
- **Gate:** Strict. **Commit:** `feat: create Appointments for the Business owner with conflict protection (#19)`.

### Phase 5 — reschedule and reassign (#19)

- **Scope:** the reschedule operation, its options route, `POST …/reschedule`. **Exclusions:** cancel, note, frontend.
- **Verification:** time-only move with a Service deactivated later, with an assignment removed later, with the current StaffMember inactive (rejected); reassignment to an unassigned or inactive target (rejected) and to an assigned one (accepted, snapshot refreshed); the stored duration is used even if the Service duration changed; price, duration, lines, Customer, and source unchanged; started and cancelled Appointments rejected; a no-change request rejected; a failed move leaves the row byte-identical.
- **Concurrency evidence:** every scenario of the ADR-0029 section 7 table for these operations, including both reassign orders, two moves of one Appointment, move versus create, move versus guest booking, move versus cancel, and schedule, StaffMember, Service, and Business changes, all with latches and lock-wait evidence; a stale `expectedVersion` versus a state conflict versus a lost slot asserted as three distinct results.
- **Gate:** Strict. **Commit:** `feat: reschedule and reassign Appointments with conflict protection (#19)`.

### Phase 6 — cancellation and internal-note edit (#21)

- **Scope:** `POST …/cancel`, `PUT …/internal-note`, the attribution, the started-Appointment rule. **Exclusions:** reasons, restore, frontend.
- **Verification:** cancellation attribution and the `V14` invariants through the real service; before-start only; repeat and stale attempts; a cancelled Appointment stops blocking and the time is offered again; the note on future, past, confirmed, and cancelled Appointments, only while `ACTIVE`; privacy of the note in responses, logs, and errors.
- **Concurrency evidence:** cancel versus cancel, cancel versus move, note versus move, with latches; the cancel predicate is shown to be the same one a Customer cancellation (#35) will use.
- **Gate:** Strict. **Commit:** `feat: cancel Appointments and edit the internal note (#21)`.

### Phase 7 — read-only frontend (#21)

- **Scope:** the `График` destination; routes with safe URL state (view, date or week, StaffMember identifier, page, size); the day and week grids built with plain React and CSS (no dependency); the agenda with the §15.1 exceptions; the read-only detail; the Customer history section on the Customer detail; lifecycle notices; stale-response and Business-switch handling. **Exclusions:** every form, mutation, and drag-and-drop.
- **Verification:** component tests with deferred promises for stale and out-of-order responses, a fake clock and a fixed timezone, a daylight-saving day, long names, route parsing and normalization, the Business switch, role and lifecycle states, and no write to browser storage; `npm run lint`, `npm run build`, the full `npm test`. The existing `Промени в графика` tab and its occurrences are not changed in this phase.
- **Concurrency evidence:** the frontend ignores a superseded request, a late response of a previous Business, and an obsolete range.
- **Gate:** Standard; the report notifies that the read-only calendar is visually testable and gives the manual steps; this notification is not the visual approval, which is Phase 11. **Commit:** `feat: add the read-only Business calendar and Customer history (#21)`.

### Phase 8 — manual-create frontend (#21)

- **Scope:** the create form (Customer selection or creation through the Customer capability, one to five Services, StaffMember, the authoritative starts, an optional internal note, a review, the single-flight guard, the unsaved-changes guard), the conflict and uncertain-outcome recovery of ADR-0029 section 10. **Verification:** validation and focus, action order, duplicate-submit protection, no automatic repeat after an uncertain outcome, reload of the authoritative calendar after any failure, the repeated-hour disambiguation, wrapped errors. **Gate:** Standard. **Commit:** `feat: add the manual Appointment form (#21)`.

### Phase 9 — reschedule and reassign frontend (#21)

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
