# ADR-0028: Administer Appointments through a private owner-only API with immutable visit identity and Business-side cancellation

## Metadata

- **Status:** Accepted (the product decisions were approved by the product owner on 2026-10-14; this record has not yet had an independent review; nothing in it is implemented)
- **Decision date:** 2026-10-14
- **Recorded date:** 2026-10-14
- **Related issues:** #19 and #21 (one combined delivery; #19 closes first, #21 immediately afterwards). Customer self-service cancellation is #35 and is not decided here.
- **Supersedes:** None
- **Amends in part:** [ADR-0022](ADR-0022-model-appointments-with-snapshots-two-statuses-and-a-database-overlap-exclusion.md) (cancellation metadata and the private note were deferred there and are decided here), [ADR-0027](ADR-0027-book-several-services-as-one-atomic-visit-with-service-lines-a-versioned-set-fingerprint-and-an-explicit-review-consistency-check.md) (its revisit condition "Business-created multi-Service appointments" is decided here). Each keeps its text and carries an amendment note.
- **Companion:** [ADR-0029](ADR-0029-calculate-business-availability-with-a-separate-policy-and-serialize-appointment-mutations-with-ordered-locks-optimistic-versions-and-bounded-retry.md) decides the Business-side availability policy, transactions, lock order, and conflict taxonomy.
- **Superseded by:** None

## Context and problem

Issue #18 delivered the Appointment core and guest booking: the `appointment` table with its database overlap exclusion (`V11`), visit lines (`V13`), the transactional idempotent guest booking, and its safe public conflict recovery. It deliberately delivered no way for a Business to see or change what it receives. Issue #19 therefore stays open (manual creation, moving, StaffMember change, Business-side conflict feedback, and Business-user races are unproven), and Issue #21 (the calendar and Appointment management) is the consumer that must provide them. Production public booking must not go live before that consumer exists (ADR-0026).

The two issue texts predate the multi-Service visit, the zero-buffer policy, and the owner-only authorization model that the repository actually has. They also leave open who may act, what may change after creation, what cancellation records, and how a Business-side change differs from a public booking. These product questions need one authoritative answer before any schema or code.

## Constraints

- Tenant isolation, authorization derived from the authenticated Membership, and strict safe errors (`security.md`, ADR-0003, ADR-0021).
- Every private API in the repository is `BUSINESS_OWNER` only; no `MANAGER` or `STAFF` application access exists and the schema has no Membership-to-StaffMember link (`product-spec.md`).
- The `appointment` row is one visit: immutable per-Service lines, visit totals, one overlap range, one status, one version (ADR-0022, ADR-0027). The lines are immutable by database trigger.
- Normal operations never physically delete Appointments; historical facts must not be silently rewritten.
- A committed migration is never edited (ADR-0004). Existing tests that write `CANCELLED` by raw SQL must adapt to a strict production invariant; production integrity is not weakened for tests.
- Zero buffers, a 15-minute grid, and PostgreSQL as the final overlap arbiter (ADR-0013, ADR-0022).
- Frontend changes follow `docs/ui-design-guidelines.md`; a new dependency needs explicit approval (`AGENTS.md`).

## Options considered

### Who may use the calendar

1. **Owner, Manager, and Staff now.** Matches the issue text, but needs a new authorization design, a Membership-to-StaffMember link, and an invitation flow; the issue itself excludes staff login creation. Rejected as a hidden second capability.
2. **`BUSINESS_OWNER` only. Selected.** Consistent with every existing private API; Manager and Staff access is a separate future capability (roadmap "Expanded Business roles").

### What may change after creation

1. **Edit the Services, Customer, duration, and price in place.** Convenient, but it rewrites the agreed facts that ADR-0022 snapshots and the V13 trigger protects, and a Service change inside one visit needs new lines, so it would require relaxing a database guarantee.
2. **Change only the schedulable and administrative fields; cancel and recreate to change identity. Selected.** The visit identity stays immutable; the cost is two explicit user operations for a wrong Customer or Service.

### Cancellation attribution

1. **Status only.** Smallest, but the same legacy-row problem would return when Customer cancellation (#35) needs attribution, and nothing could distinguish who cancelled.
2. **`cancelled_at` and `cancelled_by`, with an explicit `UNKNOWN` for rows cancelled before the columns existed. Selected.** Truthful for old rows and forward-compatible.
3. **A free-text reason or an event table.** Rejected for the MVP (personal-data risk, scope).

### A "changed" indicator

1. **Derive it from `version > 0`.** Rejected: `version` also moves on a note edit or a cancellation, so it would state something untrue.
2. **A dedicated `schedule_changed_at` column.** Truthful, but it is a new fact to maintain for a badge nobody has asked to rely on. Not added.
3. **Show no such state until real history exists. Selected.**

### Drag-and-drop

Moved out of Issue #21 into a separate future issue. It needs a dependency decision (no calendar or drag library exists), keyboard and touch alternatives, and its own visual review; the form-based operation is the canonical accessible one and is required either way.

## Decision

### 1. Authorization

The calendar and every Appointment operation are `BUSINESS_OWNER` only, resolved from the server-managed context, never from a client Business identifier.

| Caller | Result |
|---|---|
| Unauthenticated | `401 AUTH_REQUIRED` |
| No selected Business, platform administrator alone, or an inactive, missing, or foreign Membership | `403 ACTIVE_BUSINESS_REQUIRED` |
| An active `MANAGER` or `STAFF` Membership of the selected Business | `403 ACCESS_DENIED` |
| Customer sessions | none exist |

A StaffMember record, however it is displayed, grants no authentication or access. Manager and Staff calendar access (including the optional one-to-one Membership link) is a separate future capability. A missing and a foreign Appointment, Customer, StaffMember, or Service identifier are the same `404`.

### 2. Business lifecycle

| Status | Calendar and history reads | Create, reschedule, reassign, cancel, internal-note edit |
|---|---|---|
| `ACTIVE` | allowed | allowed |
| `DRAFT` | allowed (a read-only preview) | rejected with the new `409 BUSINESS_NOT_ACTIVE` |
| `SUSPENDED` | allowed | rejected with the existing `409 BUSINESS_SUSPENDED` |

This deliberately differs from the Service, StaffMember, schedule, and Customer APIs, which allow `DRAFT` mutations: an Appointment is a commitment to a Customer and a `DRAFT` Business receives no bookings. Public booking remains unavailable for `DRAFT` and `SUSPENDED` (ADR-0026). The calendar states plainly when it is read-only. The Bulgarian wording of `BUSINESS_NOT_ACTIVE` is Proposed until the rendered human review.

### 3. Operations and fields

| Field | Create | Reschedule (new start) | Reassign (new StaffMember) | Cancel | Internal note |
|---|---|---|---|---|---|
| Customer id | chosen once | immutable | immutable | immutable | immutable |
| Services, lines, order, duration, price, line and name snapshots | server-derived once | immutable | immutable | immutable | immutable |
| `source` | `MANUAL` | immutable | immutable | immutable | immutable |
| `public_reference`, guest-idempotency columns, `customer_note` | reference generated; idempotency columns null; `customer_note` null | immutable | immutable | immutable | immutable |
| `start_at`, `end_at`, `occupied_until` | chosen start; end and occupied end derive from the stored duration | start changes; end and occupied end derive | may change together with the start | immutable | immutable |
| `staff_member_id` and `staff_display_name` snapshot | chosen StaffMember | immutable | changes; the snapshot is refreshed from the locked target | immutable | immutable |
| `status` | `CONFIRMED` | unchanged | unchanged | `CONFIRMED → CANCELLED` | unchanged |
| `internal_note` | optional | unchanged | unchanged | unchanged | edited |
| `version`, `updated_at` | `0`, creation | +1, now | +1, now | +1, now | +1, now |

Reschedule and reassign are one server operation with one `expectedVersion`; either, or both, of the start and the StaffMember may change, and at least one must. There is no restore of a cancelled Appointment, and no operation changes the Customer, the Services, the duration, the price, or a snapshot. Customer contact corrections use the Customer capability and never change the Appointment's Customer id.

A wrong Customer or Service is corrected by **cancelling the Appointment and creating a separate new one**. These are two distinct operations, not an atomic edit; the interface must say so before the cancellation is confirmed and must not offer a combined action.

### 4. Manual creation is an atomic visit of one to five Services

The Business-created Appointment is the same visit as an online booking (ADR-0027): one to five distinct Services, one StaffMember performing all of them consecutively, one continuous interval covering the stored total duration, lines in the public-profile order, headline equal to line 1. The StaffMember is chosen explicitly by the owner and must support every selected Service; there is no automatic assignment for Business-created visits. The server alone calculates and stores the lines, snapshots, duration, and price from the locked Service rows. The request carries the reviewed duration and price of each Service as claims, compared with the locked rows exactly as in ADR-0027 section 6, answered `409 APPOINTMENT_REVIEW_CHANGED`, and never persisted. The final overlap guarantee covers the whole visit through the existing exclusion constraint.

### 5. The Customer of a manual Appointment

The owner selects an existing same-Business Customer or first creates one through the Customer administration capability (ADR-0021). Appointment creation receives an explicit Customer identifier, verifies it through the published `CustomerReferenceAccess`, and **never calls `CustomerIdentification.findOrCreate`** (no implicit matching, no hidden Customer creation). A Customer created before an Appointment attempt is a deliberate record and stays if the Appointment fails.

### 6. Eligibility after creation (exact rules and their trade-off)

| Operation | Services | StaffMember | Assignment | Always |
|---|---|---|---|---|
| Create | every selected Service must be active | the target must be active | the target must be assigned to every selected Service | working schedule, exceptions, conflict protection |
| Time-only move (same StaffMember) | not checked: a Service deactivated later does not block it | the current StaffMember must still exist and be active (approved: a later-deactivated Service or a removed assignment does not block a time-only move, an inactive StaffMember does) | not checked: an assignment removed later does not block it | working schedule, exceptions, conflict protection |
| Reassign to a different StaffMember | not checked for activity | the target must be active | the target must be currently assigned to every stored Service | working schedule, exceptions, conflict protection |
| Cancel, internal-note edit | not checked | not checked | not checked | ownership, lifecycle, version |

No edit recalculates a price, a duration, or a Service snapshot.

**Operational and historical trade-off.** The Business may still need to honour a visit whose Service was later deactivated or whose assignment was later removed, and forcing a cancellation would lose a real commitment (ADR-0022 already preserves such Appointments). The price is that the calendar can hold a visit that current configuration could not create today, and that a moved visit keeps its agreed stored duration even if the Service now takes longer or shorter. A time-only move does not widen what the StaffMember is claimed to perform, so it needs no assignment; a reassignment does assert a new StaffMember-Service capability, so it is stricter. Nothing in these rules lets an edit launder a deactivated Service into a newly created visit.

### 7. Started and past Appointments

An Appointment with `start_at <= now` (one clock instant per attempt) cannot be rescheduled, reassigned, or cancelled (`409 APPOINTMENT_STATE_CONFLICT`). It is read-only except for the internal note. `COMPLETED` and `NO_SHOW` are **not** introduced by this decision.

### 8. Cancellation and `V14`

Business cancellation is `CONFIRMED → CANCELLED` only, allowed only before `start_at`, versioned (`expectedVersion`), never a hard delete, and without a free-text reason. It records `cancelled_at` (the transaction's clock instant) and `cancelled_by = 'BUSINESS'`, and releases the time through the existing `WHERE status = 'CONFIRMED'` predicate of the exclusion constraint.

The forward migration `V14` (designed in Phase 1 and implemented in Phase 2; V1 to V13 stay byte-identical):

```sql
ALTER TABLE appointment
    ADD COLUMN cancelled_at timestamptz,
    ADD COLUMN cancelled_by varchar(16),
    ADD COLUMN internal_note varchar(500);

-- Rows cancelled before this migration have no recorded actor or time.
UPDATE appointment SET cancelled_by = 'UNKNOWN' WHERE status = 'CANCELLED';

ALTER TABLE appointment
    ADD CONSTRAINT appointment_cancellation_consistent CHECK (
           (status = 'CONFIRMED' AND cancelled_at IS NULL AND cancelled_by IS NULL)
        OR (status = 'CANCELLED' AND cancelled_by IS NOT DISTINCT FROM 'BUSINESS' AND cancelled_at IS NOT NULL)
        OR (status = 'CANCELLED' AND cancelled_by IS NOT DISTINCT FROM 'UNKNOWN'  AND cancelled_at IS NULL)),
    ADD CONSTRAINT appointment_cancelled_at_valid CHECK (
        cancelled_at IS NULL
        OR (pg_catalog.isfinite(cancelled_at) AND cancelled_at >= created_at)),
    ADD CONSTRAINT appointment_internal_note_plain_text CHECK (
        /* the exact customer_note rule of V11 */);
-- followed by a V13-style verification block that fails the migration on any inconsistent row
```

| Row | Invariant |
|---|---|
| `CONFIRMED` | both attribution columns null |
| Newly cancelled by the Business | `cancelled_by = 'BUSINESS'`, non-null `cancelled_at` equal to the attempt's clock instant, `version + 1`, `updated_at = cancelled_at` |
| Cancelled before `V14` | `cancelled_by = 'UNKNOWN'`, `cancelled_at` null: the time is not known (tests that wrote `status` by raw SQL never touched `updated_at`, so deriving the time from it would be untrue) |
| Future Customer cancellation (#35) | a forward migration widens the allowed actors with `CUSTOMER` (non-null time) and adds whatever late-cancellation fact #35 decides |

`UNKNOWN` is never written by application code. The constraint stays strict for every row. Every test or fixture that writes `status = 'CANCELLED'` by raw SQL (at least the `UPDATE appointment SET status = 'CANCELLED'` statements in the guest-booking, replay, visit, availability, and schema tests, and the `CANCELLED` rows built from `AppointmentFixtures.row`) must set the attribution columns through a fixture helper, and `AppointmentSchemaIntegrationTests.theConstraintAlsoGuardsUpdatesThatMakeARowBlockingAgain` must also clear them, otherwise the new CHECK would fire before the exclusion constraint and the test would assert the wrong constraint. The exact-column, exact-constraint, and migration-list assertions of the schema tests change with the migration; none is weakened. `V14` adds no index; indexes ship in `V15` (section 12).

### 9. The internal note

`internal_note` is nullable, at most 500 Unicode code points, with the same plain-text policy as `customer_note` (trimmed, no control characters except tab and line feed, line breaks normalised, blank equals null). It is private Business data: never public, never in a URL, browser storage, log, or error, and never sent to a Customer. It is editable by the owner, only while the Business is `ACTIVE`, on a future, past, `CONFIRMED`, or `CANCELLED` Appointment, and is versioned like every other change. The guest-written `customer_note` is immutable.

### 10. Version and history

`version` is concurrency metadata only. It never means "rescheduled" or "changed". No `schedule_changed_at` column is added and no generic "Променен" badge is shown. The calendar may truthfully display: `CONFIRMED` or `CANCELLED`; the source (`ONLINE` or `MANUAL`); upcoming or past derived from the Business-timezone clock; and the created and updated timestamps, with `updated_at` never presented as a scheduling event. Full audit and event history (the planned `appointment_event` and `audit_event` tables) remains deferred.

### 11. Customer appointment history (included in #21)

Issue #20 deferred Customer appointment history to #21. It is a **Booking-owned**, same-Business, server-paginated read exposed to the owner, with a fixed newest-first order (`start_at DESC, id DESC`) and the standard sizes 10, 25, and 50. A history row carries only the minimum facts: identifier (to open the detail through the Appointment authorization boundary), start and end instants, status, source, the Service names of the lines, and the StaffMember display-name snapshot. It carries no note and no contact data. The `customer` module gains no dependency on `booking`; the Business-owner interface calls the Booking-owned route from the Customer detail screen. An unknown or foreign Customer is the same `404`.

### 12. Calendar reads

- A range request covers **at most 31 Business-local dates** (inclusive), with bounds `from.atStartOfDay(zone)` to `to.plusDays(1).atStartOfDay(zone)` using zone rules, so a daylight-saving day is 23 or 25 hours. Dates are bounded to 2000-01-01 through 2100-12-31 (the schedule-exception technical bound); navigation inside that range is unrestricted and never caps booking.
- An Appointment is included when its occupied range overlaps the request range; the order is `(start_at, id)`.
- The agenda is a data list: server pagination with the sizes 10, 25, and 50, the page and size in the URL, and a **fixed chronological order that is not user-sortable**. This is an approved exception to UI guide section 15.1 (the order is the meaning of an agenda). The day and week grids are layouts, not data-list tables; the sorting checklist does not apply to them and this is recorded in the guide.
- Customer names, search terms, and any private value never appear in a URL. Calendar URL state is limited to safe navigation: the view, the date or week, and a StaffMember filter by identifier.
- Appointments, lines, and Customer display names are loaded without a query per Appointment or per StaffMember (one batch each).
- Indexes are Business-scoped and shipped in `V15` only with the query that uses them and only after `EXPLAIN ANALYZE` on representative synthetic data. The candidates are `(business_id, start_at, id)` (range and agenda), `(business_id, staff_member_id, start_at, id)` (a StaffMember filter that must also see cancelled rows, which the partial exclusion index cannot serve), and `(business_id, customer_id, start_at DESC, id DESC)` (Customer history). A candidate that the measurement does not justify, or that a constraint index already provides, is not created. The exclusion index already serves the busy-window query.

### 13. Module boundaries

`booking` owns the Appointment lifecycle, the calendar and history reads, and the Business-side orchestration. It gains a narrow dependency on the already published `identity` types for server-derived context (`AuthenticatedBusinessContext`, `SelectedBusinessOwnerAccess`, `SelectedBusinessRequired`) and on narrow new published read contracts of `customer` (display names and the detail's contact data, same-Business, no lock) and `scheduling` (working time per StaffMember and date). It must not use `identity` internals, and the `customer` module still does not depend on `booking`. A new HTTP adapter module (working name `calendar`, mirroring `publicbooking`) hosts the private routes and depends only on the published contracts of `booking`. `BookingModuleBoundaryTests`, `CustomerModuleBoundaryTests`, and `ScheduleRevisionModuleBoundaryTests` pin exact dependency sets and change with this decision; none is weakened. The working-name `calendar` is finalised in the phase that creates it.

### 14. Navigation and wording

- The new operational destination is `График`, added to the Business-scoped group of the shared navigation.
- The full UI audit (2026-10-14) found that `Промени в графика` is **not** a sidebar label. It is the second tab of the `Работно време` destination (`ScheduleTabs`, beside `Седмични графици`), and the literal phrase appears as the tab label (`ScheduleTabs.tsx`) and the list caption (`ScheduleExceptionList.tsx`), in six references of one E2E spec, six references of two Vitest files, and eight mentions in six documents (the README, the product specification, the implementation plan, the testing strategy, the UI guide, and task 05a). Inflected forms appear in two page headings (`Нова промяна в графика`, `Промяна в графика`), a pagination label, the error sentences, and the `Обратно към графика` back links. The two labels occur at different navigation levels: `График` is a sidebar destination and `Промени в графика` is a tab inside `Работно време`. The existing tab **remains** `Промени в графика`; it is renamed (the tab, its caption, the back links, the headings, and the sentences that name the tab, together with every document and test occurrence) only if the rendered human review in Phase 11 proves the two labels confusing. No rename is planned or pre-approved, and the current documents describe the current interface.
- The word «график» is also used for the weekly schedule (`Седмични графици`, `Редактирай графика`, `Изчисти графика`, `Копирай графика`). Those are unchanged; the human review decides whether `График` beside them needs any wording change.
- Bulgarian error and confirmation wording stays Proposed until the rendered human review.

### 15. Drag-and-drop and what is deferred

Drag-and-drop rescheduling is removed from Issue #21 and belongs to a separate future issue that the product owner will create. Form-based creation and rescheduling are the canonical, accessible MVP. Also deferred and not decided here: Manager and Staff access, Customer self-service cancellation (#35) and rescheduling (#36), Customer-specific durations (#37), notifications (#22), `COMPLETED`/`NO_SHOW`, event or audit history, buffers (zero, unmodelled), and an operation-id idempotency design for manual creation (ADR-0029).

## Rationale

Owner-only access reuses the one authorization model that exists and avoids inventing a Membership link. Keeping the visit identity immutable preserves the agreed facts, keeps V13's immutable-lines guarantee, and makes the calendar's history honest; the cost of cancel-and-recreate is a clear, explicit user action rather than a hidden rewrite. A strict cancellation invariant with an explicit `UNKNOWN` is the only way to keep production integrity strict and truthful for rows that predate the columns. Deriving "changed" from `version` would invent history. Including Customer history here finishes the deferral that #20 recorded, without making `customer` depend on `booking`.

## Tradeoffs and disadvantages

- Correcting a wrong Customer or Service takes two operations, and the first (cancel) is irreversible.
- `DRAFT` rejects mutations although other private APIs allow them: a deliberate asymmetry that the documents must state.
- A visit whose Service or assignment changed can persist in the calendar and be moved.
- The calendar cannot say "this visit was moved"; owners must rely on their own notes.
- Started Appointments cannot be cancelled until `COMPLETED`/`NO_SHOW` exist.
- Existing raw-SQL tests and fixtures must change with `V14`.

## Risks and mitigations

- **Time-only move of a visit whose StaffMember was deactivated.** The product owner approved the rule: a time-only move requires the current StaffMember to remain active, because a deactivated member has no schedule to move within; reassignment is offered instead. A Service deactivated later or an assignment removed later does not block a time-only move.
- **Cancel-and-recreate misunderstood as atomic.** The cancel dialog must say that creating the replacement is a separate action and that the cancellation cannot be undone; checked in the rendered review.
- **Raw-SQL fixtures hiding the invariant.** A fixture helper is the only way to build a cancelled row, and the schema tests prove the CHECK for each allowed and each forbidden combination.
- **Cross-module contract growth.** Each new contract is narrow, tenant-scoped, and pinned by a boundary test.
- **Customer or note data leaking through lists.** The list contracts carry the minimum facts; notes appear only in the detail; sentinel tests cover responses, errors, logs, URLs, and storage.

## Consequences

Phase 1 (documentation) creates this ADR, ADR-0029, task 09a, and the amendment notes. Implementation follows the twelve phases of [task 09a](../tasks/09a-business-calendar-and-appointment-management.md), each with its own approval. Phase 11 (the rendered human review) is an explicit approval gate: Phase 12 does not start until the product owner's visual approval is recorded, and the notifications after Phases 7 and 10 are not approvals. Route names, problem codes, and Bulgarian wording are working names finalised and tested in the implementing HTTP or interface phase. The first code phase writes `V14` and the domain and persistence transitions; the booking, replay, availability, and public contracts are unchanged. Issue #19 and Issue #21 stay open and In Progress together; the owner alone edits GitHub, using the amendment texts stored in task 09a.

## Evidence

Direct evidence: issues #18 to #21; `docs/product-spec.md`, `docs/security.md`, `docs/data-model.md`, `docs/ui-design-guidelines.md`; ADR-0003, ADR-0021, ADR-0022, ADR-0026, ADR-0027; `V11`, `V13`; `AppointmentStore` (no update or cancellation exists); `AppointmentSchemaIntegrationTests` (the exact column, constraint, and index assertions, and the raw-SQL cancellation tests); `CustomerModuleBoundaryTests` and `BookingModuleBoundaryTests` (the pinned dependency sets); `ScheduleTabs.tsx`, `ShellNavigation.tsx`, `BusinessOwnerShell.tsx` (the navigation audit). Inference: that the `CHECK` fires before the exclusion constraint on a raw `UPDATE ... SET status = 'CONFIRMED'` (to be confirmed by the Phase 2 test), and that the three candidate indexes can be justified or rejected by measurement.

## Conditions for revisiting

Revisit for Manager or Staff access, Customer cancellation (#35), `COMPLETED`/`NO_SHOW`, an event or audit history, a "changed" indicator, a cancellation reason, buffers, restoring a cancelled Appointment, editing the Services or Customer of a visit, an operation-id idempotency design, or drag-and-drop rescheduling.

## Amendment note (issue #19 Phase 2, 2026-10-14)

The decision above is preserved, with one correction found while implementing `V14`. The section 8 constraint was first written with plain comparisons (`cancelled_by = 'BUSINESS'`). A `CHECK` accepts a row whose expression is `NULL`, and for `status = 'CANCELLED'` with a null actor and a null time the third branch evaluates to `NULL` and the others to false, so that row would have passed. The constraint above now uses `IS NOT DISTINCT FROM`, which is two-valued, and the implemented `V14` does too; the implemented schema test evaluates the original expression and shows it yields `NULL`, and a matrix test shows every forbidden combination (including the missing actor) is refused. The internal note is independent of the status: no constraint ties it to `CONFIRMED` or `CANCELLED`, and the verification block does not judge it. Two consequences are recorded: PostgreSQL evaluates check constraints in name order, so a status outside the domain is now reported by `appointment_cancellation_consistent` before `appointment_status_valid`; and the backfill `UPDATE` queues the deferred whole-visit trigger of `V13`, which `V14` judges with `SET CONSTRAINTS ALL IMMEDIATE` before its `ALTER TABLE`. Adding the nullable columns is a metadata-only change; the backfill physically writes new tuple versions of the pre-existing `CANCELLED` rows (and of nothing else), so a stable table file (`relfilenode`) proves there was no full table replacement but not that no tuple was rewritten. See the Phase 2 record in [task 09a](../tasks/09a-business-calendar-and-appointment-management.md).

## Implementation note (issue #21 Phase 3, 2026-10-14)

The decision above is preserved. Phase 3 implemented the read side of sections 11 and 12 and recorded these refinements. **Contract:** the four owner-only `GET` routes are served by the new `calendar` adapter module over the published `booking.AppointmentCalendar` contract (calendar, agenda, Appointment detail, Customer history). `businessStatus` (`DRAFT`, `ACTIVE`, or `SUSPENDED`) is part of the calendar, agenda, and detail responses; all three lifecycle states are readable. The cancellation attribution (`cancelledAt`, `cancelledBy`, with a null time for a legacy `UNKNOWN`) appears only in the authorized detail; the internal note, the guest note, the public reference, the price, the version, and the Customer contact appear only in the detail; the Customer history carries no Customer name, note, reference, price, or attribution. Timestamps are ISO-8601 with the Business-local offset; prices keep their exact two decimals. **Inactive StaffMembers (refines section 12):** the unfiltered calendar lists every active StaffMember and an inactive one only when it has at least one visible Appointment in the requested range; an explicit `staffMemberId` may name an inactive same-Business StaffMember; a missing or foreign one is the same `404 STAFF_MEMBER_NOT_FOUND`. **Working time:** the calendar returns, per StaffMember, the net working intervals as instants (partial exceptions are subtracted) and a closure marker only for a date covered by a full-day Business closure (which wins) or a full-day StaffMember time off; an inactive StaffMember has neither. These are rendering facts, not bookability, and no minimum notice, horizon, or grid applies. **Problems:** each route family has a fixed, non-echoing `instance` (`/api/business/calendar`, `/api/business/appointments`, `/api/business/customers`). **Indexes (section 12):** `V15` ships `appointment_business_start_idx` and `appointment_business_customer_start_idx` on the evidence recorded in task 09a; the StaffMember-filter candidate was measured and rejected. The Customer history route lives under the Customer path but is served by the calendar adapter, so the `customer` module still does not depend on `booking`.
