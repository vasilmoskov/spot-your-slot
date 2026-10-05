# Task 04d — Business-owner configuration interface

## Reference

Implements [issue #14](https://github.com/vasilmoskov/spot-your-slot/issues/14),
sub-issue of parent [#10](https://github.com/vasilmoskov/spot-your-slot/issues/10).

Depends on, and does not change the contracts of:

* [#11](https://github.com/vasilmoskov/spot-your-slot/issues/11) — Business
  services backend (completed).
* [#12](https://github.com/vasilmoskov/spot-your-slot/issues/12) — Staff
  management and service assignments backend (completed).
* [#13](https://github.com/vasilmoskov/spot-your-slot/issues/13) — Recurring
  staff working schedules backend (completed).

## Scope

Provide a responsive Bulgarian interface through which an authorized
`BUSINESS_OWNER` manages Services, StaffMembers, Service assignments, and
recurring weekly working schedules of the active Business, consuming the
completed backend APIs without changing their approved contracts. Full scope,
UI/UX requirements, feedback/confirmation behavior, and acceptance criteria
are as defined in issue #14 and are not repeated here.

## Explicit exclusions

* Backend Service, StaffMember, assignment, or schedule contract changes.
* Availability and slot calculation, appointments and booking.
* Public Business pages, Customers, staff login accounts and invitations,
  Membership management.
* Exceptional dates, holidays, leave, temporary overrides, a separate Break
  entity, rooms/chairs/equipment/resources, service images and public staff
  profiles.
* Production deployment and broad application redesign.

## Implementation phases

1. **Business-owner shell + Services management** (completed). New
   `BusinessOwnerShell`, active-Business identification via the existing
   session flow, navigation for Услуги/Екип/Работно време with only Услуги
   fully implemented, complete Services list/create/detail/edit/cancel/
   deactivate/reactivate UI, optional starter presets, exact EUR handling,
   `SUSPENDED` read-only behavior, safe feedback, focused tests.
2. **Phase 1.1 — Shared table sorting, pagination, and interaction-guideline
   synchronization** (completed). Product-wide server-side sorting and
   pagination standard applied to the Services and Platform-admin Businesses
   tables; see "Phase 1.1 — table sorting and pagination" below for the
   concrete decisions and evidence.
3. **Phase 2 — Staff management + service assignments** (completed).
   StaffMember list/create/detail/edit/deactivate/reactivate UI and
   Service-assignment management, following the same shape as Phase 1; see
   "Phase 2 — Staff management + service assignments" below for the concrete
   decisions and evidence.
4. **Phase 3 — Recurring working-schedule management** (completed). Per-staff
   weekly editor, split working days, weekday/full-schedule clearing with
   confirmation, timezone display, backend overlap/concurrency error
   rendering; see "Phase 3 — Recurring working-schedule management" below for
   the concrete decisions and evidence.
5. **Phase 4 — Cross-cutting hardening, validation UX, and documentation**
   (completed, pending the human visual checkpoint below). Field-level local
   validation for every Issue #14 form, shared unsaved-changes and
   destructive-confirmation hardening, stale-response audit, documentation and
   durable UI rules; see "Phase 4 — cross-cutting hardening" below.

## Phase 1 acceptance criteria

* An authorized `BUSINESS_OWNER` can reach a Business-owner shell that is
  visibly separate from the `PLATFORM_ADMIN` interface and exposes no
  platform-admin-only controls.
* The active Business is clearly identified, including for an owner with only
  one Business; existing multi-Business selection behavior is unchanged
  (current behavior, issue #20 correction: selection lives on the `Бизнеси` page and the Business-scoped links
  appear under the selected Business's name; the Profile has no selector).
* Navigation exposes Услуги, Екип, and Работно време; only Услуги is fully
  implemented in this phase.
* An owner can view, create, view details of, edit, cancel edits to,
  deactivate, and reactivate Services, with optional editable starter
  presets.
* EUR prices are handled without floating-point loss from form input through
  the API request.
* `DRAFT`/`ACTIVE` Businesses allow Service configuration; `SUSPENDED`
  Businesses remain visible but read-only, with backend authorization
  remaining authoritative.
* Validation, authorization, and optimistic-concurrency conflicts are
  surfaced safely, with a reload path for concurrent updates.
* Stale asynchronous responses cannot overwrite state after a Business or
  route context change.
* Focused Vitest/Testing Library coverage exists for the shell and Services
  feature; no backend, migration, or dependency changes are introduced.

## Phase 1.1 — table sorting and pagination

Applied the product-wide paginated-table standard in
`docs/ui-design-guidelines.md` (section 15) to the two existing tables:
Business-owner Services and Platform-admin Businesses.

**Defaults and sortable fields**

* Services: `page=0&size=10&sort=name&direction=asc`. Sortable columns: Име
  (`name`), Продължителност (`duration`), Цена (`price`), Статус (`status`).
  Tie-breakers: case-insensitive name then id for `name`/`duration`/`price`/
  `status`; `status` ascending is active-first (`active DESC` in SQL).
* Platform Businesses: `page=0&size=10&sort=displayName&direction=asc`
  (previously `page=0&size=50` with fixed `created_at DESC` ordering).
  Sortable columns: Име (`displayName`), Идентификатор в уеб адреса (`slug`),
  Дейност (`businessType`), Статус (`status`). `status` uses the documented
  semantic lifecycle order DRAFT → ACTIVE → SUSPENDED ascending (a `CASE`
  rank expression, not enum/alphabetical order).
* Page-size options are exactly 10/25/50 for both tables; the backend
  enforces an exact allowlist (`{10, 25, 50}`), rejecting every other value —
  including values inside the old 1–50 range such as 1, 7, 20, and 37, not
  only values above 50 — with the existing `VALIDATION_ERROR` response. The
  page-size selector lives in the pagination region beside Previous/Next
  (not duplicated above the table).

**URL state and history push vs. replace**

Both routes carry their list state as canonical query parameters on the
existing hash router (`#/business/services?page=...` and
`#/platform/businesses?page=...`). Refresh and Back/Forward restore the
exact configuration; missing or invalid values normalize to the documented
defaults. Switching the active Business resets the Services route's page to
0 while preserving size/sort/direction.

Every explicit user interaction (column sort, responsive sort control, page
size, Previous/Next) pushes a new history entry so Back can undo it.
Automatic canonicalization/recovery — URL normalization on load, the
Business-switch page reset, and out-of-range-page recovery — replaces the
current history entry instead, via an explicit `mode?: 'push' | 'replace'`
parameter on the list-state change callback (`ListNavigationMode` in
`navigation.ts`). This avoids a Back-navigation trap where recovering from
an obsolete page would otherwise push a new entry, letting Back return to
the same obsolete page and re-trigger recovery in a loop.

**Sortable header presentation**

Every sortable desktop header always shows both direction arrows (`▲▼`);
the active column is distinguished by a subtle background/text treatment
plus emphasizing only the arrow matching the current direction, never by
color alone. `aria-sort` remains `ascending`/`descending`/`none` as before.

**Service hard deletion — explicit exclusion**

Services continue to use only deactivate/reactivate lifecycle behavior.
Hard deletion is out of scope for this phase and requires a later
dependency/history analysis covering staff assignments, availability,
appointments, and reporting before it can be considered. A trash icon or
equivalent destructive-delete affordance must not be used to represent
deactivation.

**Test evidence**

* Backend (real PostgreSQL): `ServiceStoreIntegrationTests`,
  `BusinessStoreIntegrationTests`, `ServiceAdministrationServiceIntegrationTests`,
  `BusinessAdministrationServiceIntegrationTests`,
  `BusinessServiceApiIntegrationTests`, `PlatformBusinessApiIntegrationTests`,
  `ServiceInputValidatorTests`, `BusinessInputValidatorTests` — default size,
  the exact `{10, 25, 50}` page-size allowlist (10/25/50 accepted; 1, 7, 20,
  37, 49, 51 rejected), invalid page/sort/direction, every sort field and
  both directions, deterministic tie-breaking, semantic status ordering,
  tenant isolation. Full `./mvnw --batch-mode verify`: 965 tests, 0 failures.
* Frontend (Vitest/Testing Library): `navigation.test.ts`,
  `ServiceList.test.tsx`, `BusinessList.test.tsx`, `App.test.tsx` — default
  page/size, exact 10/25/50 size options, page reset on sort/size change,
  bidirectional sort-indicator presentation and active-column/direction
  hooks, `aria-sort` correctness through toggling, a single page-size
  selector placed inside the pagination region, URL read/write and
  normalization, Back/Forward restoration, Business-context page reset,
  empty-page recovery, and explicit `history.pushState`/`replaceState`
  assertions proving canonicalization/recovery replaces history while
  explicit sort/size/page interactions push. Full `npm run test`: 240 tests
  passed. `npm run lint` and `npm run build` both clean.

**Remaining Issue #14 phases**

Staff management (Phase 2 in this document) and recurring working-schedule
management (Phase 3) are unaffected and not started by this phase.

## Phase 2a backend contract extension — Staff sort/pagination (Phase 1.1 parity)

Read-only inspection at the start of Phase 2 (Staff management frontend)
found that the committed `StaffMember` backend contract had not been
migrated to the Phase 1.1 table standard: `BusinessStaffMemberController`
accepted only `page`/`size` (no `sort`/`direction`), `StaffMemberAdministration`/
`StaffMemberAdministrationService`/`StaffMemberStore` had no sort parameter
or `ORDER BY` selection, and `StaffMemberInputValidator` still used
`DEFAULT_PAGE_SIZE = 50` with a `1..100` range instead of the documented
default-10 / exact-`{10, 25, 50}`-allowlist standard. This was confirmed
with the user as an unintentional gap (not an approved exemption) and fixed
as a separate Strict-risk backend-only phase, mirroring the already-completed
Services contract exactly, before any Staff frontend UI work began.

**Changes**

* `StaffMemberRecords` gained a `StaffMemberSortField` enum (`NAME`,
  `STATUS`) — the two sortable fields `StaffMemberDetails` actually exposes
  (`displayName`, `active`); there is no Staff analogue of Services'
  `duration`/`price` columns.
* `StaffMemberApplicationException.InputField` gained `SORT` and
  `DIRECTION`, reported through the existing `InvalidInput` /
  `VALIDATION_ERROR` contract.
* `StaffMemberAdministration.list` / `StaffMemberAdministrationService.list`
  / `BusinessStaffMemberController.list` gained `sort`/`direction` query
  parameters, validated and passed through exactly like the Services
  contract (`sort=name|status`, `direction=asc|desc`, default
  `name`/`asc`).
* `StaffMemberStore.list` orders in the database via a `normalized_display_name`/
  `id` tie-breaker for `NAME`, and `active`/`normalized_display_name`/`id`
  for `STATUS`; `status` ascending is active-first (`active DESC` in SQL),
  matching the Services `status` semantic exactly. The existing
  `staff_member_business_normalized_display_name_id_idx` index (from V6)
  already covers the `NAME` sort; no new index or migration was needed,
  because the Services `STATUS` sort has no dedicated `active` index either
  — the same tradeoff was mirrored rather than introduced.
* `StaffMemberInputValidator.DEFAULT_PAGE_SIZE` changed from `50` to `10`;
  page size now enforces the exact `{10, 25, 50}` allowlist (rejecting
  every other value, including values inside the old `1..100` range such as
  1, 7, 20, 37, 49) via the same `ALLOWED_PAGE_SIZES` pattern as
  `ServiceInputValidator`.
* No Flyway migration was added or edited; no frontend file was touched.

**Test evidence**

Backend (real PostgreSQL via Testcontainers): `StaffMemberStoreIntegrationTests`,
`StaffMemberAdministrationServiceIntegrationTests`,
`StaffMemberAdministrationServiceTests`, `StaffMemberInputValidatorTests`,
`BusinessStaffMemberControllerTests`, `BusinessStaffMemberApiIntegrationTests` —
default size, the exact `{10, 25, 50}` page-size allowlist (10/25/50
accepted; 1, 7, 20, 37, 49, 51 rejected), invalid page/sort/direction, both
sort fields in both directions, deterministic tie-breaking, semantic status
ordering, tenant isolation preserved. Full `./mvnw --batch-mode verify`:
982 tests, 0 failures.

This unblocks Phase 2 (Staff management frontend), which can now reuse the
same `ListNavigationMode`/sortable-table frontend pattern already built for
Services in Phase 1.1 without a second backend round-trip.

## Phase 2 — Staff management + service assignments

Implemented the Bulgarian Business-owner UI for StaffMember list/create/
detail/edit/deactivate/reactivate and Service-assignment management,
mirroring the Phase 1/1.1 Services feature shape (`api.ts`/`errors.ts`/
`presentation.ts`/list/detail/form) against the Staff backend contract
extended in the phase documented above.

**New feature module** — `frontend/src/business/staff/`: `api.ts` (typed
client for `/api/business/staff-members` list/get/create/update/deactivate/
reactivate and `/service-assignments` get/replace), `errors.ts` (safe error
codes: `VALIDATION_ERROR`, `ACCESS_DENIED`, `ACTIVE_BUSINESS_REQUIRED`,
`STAFF_MEMBER_NOT_FOUND`, `SERVICE_NOT_FOUND`,
`STAFF_MEMBER_INVALID_LIFECYCLE`, `STAFF_MEMBER_CONCURRENT_UPDATE`,
`SERVICE_INACTIVE`, `BUSINESS_SUSPENDED`), `presentation.ts` (status
labels), `StaffList.tsx`, `StaffForm.tsx`, `StaffCreate.tsx`,
`StaffDetail.tsx`, and `StaffServiceAssignments.tsx`.

**Routes** — `#/business/staff` (list, canonical `page/size/sort/direction`
query, reusing `ListQueryState`/`ListNavigationMode`/`STAFF_SORT_FIELDS`
exactly like Services), `#/business/staff/new`, and
`#/business/staff/{staffMemberId}`, added to `navigation.ts` and wired into
`App.tsx`/`BusinessOwnerShell.tsx` alongside the existing Services routes.
The Business-switch page-reset layout effect in `App.tsx` was generalized
from Services-only to cover both `business-services` and `business-staff`
routes.

**List** — Replaces the previous "Екип" placeholder. Sortable columns Име
(`name`) and Статус (`status`, active-first ascending, matching the Services
`status` semantic); default `page=0&size=10&sort=name&direction=asc`; page
sizes exactly 10/25/50 with the selector in the pagination region; both
direction arrows always shown; `aria-sort` correct; stale-request
protection and out-of-range-page recovery via `replace` navigation, all
reusing the shared `ListSortControls`. Each row shows name, status, and
contact info (email or phone, whichever is present); a `section-introduction`
notice states a StaffMember is a business resource used for bookings, not
automatically a SpotYourSlot login account.

**Create/edit** — Bulgarian form against the exact current backend fields
(`displayName`, `contactEmail`, `contactPhone`); empty create form; no
starter presets; clearing an optional contact field sends it as omitted
(equivalent to explicit `null` under Jackson's default record binding, as
confirmed against `BusinessStaffMemberApiIntegrationTests`, which asserts
`null` clears `contactEmail` on update). Shared `UnsavedChangesGuard` for
Cancel, sidebar/internal navigation, browser Back/Forward, Business
switching, and logout; a successful create/save clears the guard before
navigating so no false "unsaved changes" prompt appears.

**Lifecycle** — `DRAFT`/`ACTIVE` Businesses allow create/edit/deactivate/
reactivate/assignment mutation; `SUSPENDED` Businesses are read-only (backend
remains authoritative; the frontend only hides/disables controls).
Deactivate requires confirmation showing the StaffMember's name; reactivate
does not. Optimistic-concurrency conflicts (`STAFF_MEMBER_CONCURRENT_UPDATE`)
surface a safe message with a reload action, matching the Services pattern
exactly. No hard deletion.

**Service assignments** — `StaffServiceAssignments.tsx` has an explicit
read-only view mode (the persisted assignments, active or inactive, as a
plain list) and an explicit edit mode entered via a "Редактирай услугите"
action. Entering edit mode loads the current assignments
(`GET .../service-assignments`) and the *entire* Service catalog — the first
page of `ALLOWED_PAGE_SIZES`-maximum size, then every remaining page
computed from `totalElements`, all sharing one `AbortSignal` — and renders
one checkbox per Service; a partial multi-page result is never rendered as
complete. Any assigned Service missing from the loaded catalog (e.g. an
inactive Service excluded defensively) is merged in without duplication. A
Service already assigned (active or inactive) can always be unchecked
(removed); an unassigned inactive Service is disabled and cannot be newly
added, matching `StaffMemberAdministrationService.replaceServiceAssignments`,
which only requires newly-added service ids to be active — a
previously-assigned inactive service may remain in the desired set without
error. Duplicate assignment is impossible by construction (checkbox
selection is a `Set`, not a free-text list). Per ADR-0011,
`StaffMemberAssignments.version` is the same aggregate version as the
StaffMember profile; `StaffDetail` threads its current `version` down as
`expectedVersion` and the assignments editor reports the authoritative
version back up after every successful load or save via a memoized
callback, so a profile edit and an assignment save started from the same
detail screen never race on a stale version. Saving renders the
authoritative backend response, returns to view mode, and clears the guard;
a concurrent-update conflict shows the shared reload action. The panel uses
the shared `UnsavedChangesGuard` (`useGuardedFormState`): a clean Cancel
exits immediately (no-op, nothing changed) back to view mode, a dirty Cancel
confirms via the shared dialog and reverts to the persisted selection before
returning to view mode.

**Mutually exclusive editors** — The Staff profile editor and the
Service-assignment editor are mutually exclusive: `StaffDetail` holds a
single `editingSection` (`'none' | 'profile' | 'assignments'`), and every
transition into an editor goes through the shared `UnsavedChangesGuard`
(`guard.guard(...)`), not a direct state change. If the other editor is
dirty, the shared confirmation dialog appears; rejecting preserves the
dirty editor untouched, confirming discards it and opens the requested one.
Because at most one editor is ever open (and therefore dirty) at a time,
the existing single-registration `UnsavedChangesGuard` contract did not need
to change — the fix is entirely in `StaffDetail`'s mutual-exclusion
coordination, not in the shared guard, which keeps the Services feature
(also built on the same shared guard) unaffected.

**Stale-response and Business-context safety** — `StaffList`/`StaffDetail`
are keyed by `businessKey`/`staffMemberId` exactly like their Services
counterparts, so a Business switch remounts them; in-flight requests are
aborted via `AbortController` and a superseded response is ignored via a
request-sequence guard, verified by an App-level test that resolves a
stale Business-A staff list fetch only after Business B's response has
already rendered.

**Test evidence** — Frontend (Vitest/Testing Library):
`business/staff/api.test.ts`, `errors.test.ts`, `presentation.test.ts`,
`StaffList.test.tsx`, `StaffCreate.test.tsx`, `StaffDetail.test.tsx`
(including the embedded `StaffServiceAssignments` behavior: display,
add/remove, duplicate prevention, concurrent-update reload, dirty guard,
guard-cleared-after-save, SUSPENDED read-only), plus updated
`navigation.test.ts` (new `business-staff-new`/`business-staff-detail`
routes and canonicalized `business-staff` list query), `BusinessOwnerShell.test.tsx`,
and `App.tsx` coverage (canonical route/Back-Forward with sort/size, invalid
query normalization, Business-switch stale-response and page-reset,
guarded navigation away from a dirty Service edit into Staff). Full
`npm run test`: 294 tests passed across 26 files. `npm run lint` and
`npm run build` (`tsc -b && vite build`) both clean. `git diff --check`
clean. No backend, migration, or dependency file was modified in this
phase; the 14 backend files from the prior Phase 1.1-parity phase remain
uncommitted and untouched, exactly as received.

**Deviations / exclusions** — None remaining for Service-assignment
selection: an earlier draft of this phase fetched only the first Service
page (up to `ALLOWED_PAGE_SIZES` maximum), which was corrected in the
combined Issue #14 Phase 2 review pass documented below — every Service page
is now loaded before the editable list renders. Recurring working-schedule
UI (Phase 3) was not started.

### Recorded table-standard exception — `StaffServiceAssignments`

`StaffServiceAssignments` is a bounded selection/editor table. Its purpose is
choosing assignments from the complete Service catalog (every Service page is
loaded before it renders), not browsing a pageable data list. Sorting and
pagination are therefore intentionally not required for this component; it keeps
its fixed name ordering. This is a deliberate, recorded exception to the table
standard in `docs/ui-design-guidelines.md` section 15.1 and must not be read as
applying to ordinary data-list tables such as Services, Staff, Platform Businesses
or Schedule Changes.

## Combined Issue #14 Phase 2 correction pass

A follow-up review of this phase's uncommitted work, before it was
committed, found and fixed four issues:

1. **Service-assignment pagination gap** — the assignment editor only
   fetched the first 50 Services (see the superseded "Deviations" note
   above); this silently hid Services beyond the first page from assignment.
   Fixed by loading every Service page (§ "Service assignments" above) with
   tests proving 51+ Services load across pages, an active page-2 Service is
   assignable, an assigned inactive Service absent from the first page
   remains visible and removable, and a stale/aborted later-page response
   cannot render.
2. **Shared dirty-guard clobbering** — the Staff profile form and the
   Service-assignment editor could previously both be open (and dirty) at
   once, and the single-registration `UnsavedChangesGuard` could forget one
   of them. Fixed by making the two editors mutually exclusive (§ "Mutually
   exclusive editors" above) rather than redesigning the shared guard, with
   regression tests for both discard directions, reject-preserves-dirty,
   confirm-opens-the-requested-editor, guard-cleared-after-save, and no two
   dirty editors coexisting.
3. **Missing full-stack public page-size coverage** — added
   `BusinessStaffMemberApiIntegrationTests` cases proving sizes 10, 25, and
   50 are accepted end-to-end (real PostgreSQL), alongside the existing
   rejections for unsupported sizes (1, 7, 20, 51).
4. **Phase-numbering error in this document** — this document's own Staff
   sections previously called Staff management + Service assignments "Phase
   3" and recurring working schedules "Phase 4", which did not match the
   approved Issue #14 breakdown (Phase 1 shell+Services, Phase 1.1 shared
   table sorting/pagination, Phase 2 Staff management + Service assignments,
   Phase 3 recurring working schedules, Phase 4 cross-cutting
   hardening/visual review/documentation). Corrected throughout this
   document; the Staff backend sort/pagination extension is described as a
   "Phase 2a backend contract extension" (a Phase 2 prerequisite), not
   Phase 3.

## Final combined correction pass — canonical telephone, four-column sorting, read-only assignment indicator

A further Strict-risk correction pass on the still-uncommitted Issue #14
Phase 2 work, after the visual/UX correction pass above it (header lifecycle
badge removal, action-oriented button labels, the two explanatory-paragraph
removals, split Телефон/Имейл columns, the compact `Услуга`/`Назначена`
assignment editor), fixed the following:

**Canonical international telephone contract** — `StaffMember.contactPhone`
is now stored and returned in compact E.164-compatible form (for example
`+359895555777`), with Bulgaria as the product's default region.
`StaffMemberTextCanonicalizer.canonicalContactPhone` strips the approved
visual separators (ordinary whitespace, `(`, `)`, `-`, `.`) from anywhere in
the value, then interprets the remaining prefix: `00` becomes `+`; a lone
leading `0` becomes `+359`; a leading `+` keeps its supplied country code;
any other non-empty value is left without a leading `+` on purpose, so it is
always rejected downstream rather than silently assumed to be Bulgarian.
`StaffMemberInputValidator` is the single validation point that enforces the
final shape — `^\+[1-9][0-9]{7,14}$`, i.e. 8–15 total digits after `+`
(`CONTACT_PHONE_MIN_DIGITS`/`CONTACT_PHONE_MAX_DIGITS`), a reasonable
minimum chosen to reject an obviously incomplete number while still
accepting the shortest real international numbers. Migration
`V8__canonicalize_staff_member_contact_phone.sql` normalizes the four
existing local rows (all safely convertible — two already `+359`-prefixed
with separators, two Bulgarian `0`-prefixed local numbers) before replacing
the `staff_member_contact_phone_canonical` CHECK constraint with the
stricter compact-form pattern; no legacy value was ambiguous, so nothing
needed to be reported as a conflict. The frontend sends ordinary user input
unchanged and renders the authoritative canonical value the backend returns;
`business/staff/presentation.ts` adds `formatStaffPhone`, the single shared
presentation helper, which groups a canonical `+359` number with nine
national digits into `+359 895 555 777` and shows every other international
number (a different explicit country code, or a Bulgarian number without
exactly nine national digits) as its compact canonical value, rather than
guessing per-country grouping without a phone-number library.

**Four-column Staff sorting** — `StaffMemberSortField` gained `PHONE` and
`EMAIL` alongside the existing `NAME`/`STATUS`; `StaffMemberInputValidator`
allowlists `sort=phone|email`; `StaffMemberStore.list`'s closed
`orderClause` switch adds trusted `contact_phone`/`contact_email` fragments,
each `NULLS LAST` in both directions with the existing
`normalized_display_name ASC, id ASC` tie-breakers, so ordering stays fully
deterministic and a missing phone/email always sorts last. The default
(`sort=name`, `direction=asc`) and the `{10, 25, 50}` page-size allowlist
are unchanged. `navigation.ts`'s `STAFF_SORT_FIELDS` gained `'phone'`/
`'email'`, which `StaffList.tsx` picks up automatically for both the
`SortableColumnHeader` desktop columns and the `ResponsiveSortSelect`
mobile control — no separate per-field wiring was needed since both already
render generically from the allowlist.

**Read-only assignment indicator** — the read-only `Назначени услуги` table
(shown outside edit mode) now renders `Услуга`/`Назначена` columns matching
the editable table's headers, with a non-interactive `✓`/`✕` plus visually
hidden `Да`/`Не` text (not a disabled checkbox) in the second column, for
every active Service and any assigned inactive Service; no status badge. The
editable assignment table, its compact interactive checkboxes, active/
unassigned filtering, full-catalog pagination, and stale-response
protection are unchanged.

**Final removed explanatory copy** — the last remaining
`StaffForm.tsx` sentence ("Член на екипа е ресурс на бизнеса, а не
автоматично профил за вход в SpotYourSlot.") was removed with no
replacement copy, for both Staff creation and editing (shared form); a
repository-wide search confirmed no equivalent StaffMember/resource/
login-account explanatory text remains anywhere in the Business-owner
frontend.

**Migration decision** — exactly one new forward-only migration,
`V8__canonicalize_staff_member_contact_phone.sql`, was added; V1–V7 were not
touched. No new third-party phone-number dependency was introduced or
considered necessary.

**Test evidence** — Backend (real PostgreSQL via Testcontainers):
`StaffMemberTextCanonicalizerTests` (Bulgarian-`0`, international-`00`,
explicit `+`-prefixed, ambiguous-prefix, and approved-separator-stripping
cases), `StaffMemberInputValidatorTests` (canonicalized valid phones,
rejected ambiguous/malformed/short/long phones, digit-boundary tests,
`phone`/`email` accepted by `validateSort`), `StaffSchemaIntegrationTests`
(compact canonical form accepted, non-compact/legacy/ambiguous forms
rejected by the new CHECK constraint), `StaffMemberStoreIntegrationTests`
(phone/email sort ascending/descending, nulls last both directions,
name/id tie-breakers), `StaffMemberAdministrationServiceIntegrationTests`
(phone/email sort through the application layer), `BusinessStaffMemberApiIntegrationTests`
(full-stack phone/email sort with nulls last and tenant isolation, canonical
phone in create/get responses), `BusinessSchemaIntegrationTests` /
`WorkingScheduleSchemaIntegrationTests` (migration count updated to eight).
Full `./mvnw --batch-mode verify`: 993 tests, 0 failures. Frontend
(Vitest/Testing Library): `presentation.test.ts` (phone formatting),
`StaffList.test.tsx` (four sortable headers, phone/email click-to-sort and
direction toggle, responsive selector offering all four fields),
`StaffDetail.test.tsx` (read-only `✓`/`Да` assignment indicator, no
checkbox/status badge in read-only mode, canonical phone round-trip through
edit), `StaffCreate.test.tsx` (explanatory-copy absence),
`navigation.test.ts` (`phone`/`email` accepted by the Staff route
allowlist). Full `npm run test`: 315 tests passed across 26 files. `npm run
lint` and `npm run build` both clean. `git diff --check` clean.

## Phase 3 — Recurring working-schedule management

Implemented the Bulgarian Business-owner UI for viewing and editing each
StaffMember's recurring weekly working schedule, consuming the completed
Phase-#13 backend (`GET`/`PUT /api/business/staff-members/{staffMemberId}/working-schedule`)
without changing its contract. `navigation.ts`'s `business-schedule` route,
`BusinessOwnerShell`'s "Работно време" sidebar link, and its page title were
already wired ahead of this phase; only the API/component layer was missing.

**New feature module** — `frontend/src/business/schedule/`: `api.ts` (typed
`getWorkingSchedule`/`replaceWorkingSchedule` client), `errors.ts` (safe error
codes: `VALIDATION_ERROR`, `ACCESS_DENIED`, `ACTIVE_BUSINESS_REQUIRED`,
`STAFF_MEMBER_NOT_FOUND`, `STAFF_MEMBER_INACTIVE`,
`WORKING_SCHEDULE_CONCURRENT_UPDATE`, `BUSINESS_SUSPENDED` — the concurrent-
update code is schedule-specific and distinct from Staff's own
`STAFF_MEMBER_CONCURRENT_UPDATE`), `presentation.ts` (the seven Bulgarian
weekday labels in the required order, chronological sorting/grouping, and a
pure `validateDraftPeriods` function mirroring the backend's own overlap/
adjacency/range rules for immediate client-side feedback),
`StaffWorkingSchedule.tsx` (StaffMember selector), and
`WorkingScheduleEditor.tsx` (the per-StaffMember weekly view/editor). Wired
into `App.tsx`'s `route.kind === 'business-schedule'` branch (previously
`ComingSoon`), passing `key={businessKey}` like every other Business-owner
section.

**StaffMember selection** — The route carries no StaffMember id (a single
combined page, not a second router or per-staff URL). `StaffWorkingSchedule`
loads every page of the StaffMember catalog (active and inactive, the same
full-pagination pattern already used for the Staff Service-assignment
editor) into a `<select>`, defaulting to the first entry and showing the
selected StaffMember's status badge. Switching the selection goes through
the shared `UnsavedChangesGuardProvider` (`guard.guard(...)`), so a dirty
schedule draft blocks the switch exactly like sidebar navigation, logout,
Business switching, and browser Back/Forward do automatically for any
component registered via `useGuardedFormState`. The whole page remounts
(`key={businessKey}` in `App.tsx`) on a Business switch, so a Business-A
selection is never silently retained into Business B, and the per-StaffMember
`WorkingScheduleEditor` is itself keyed by the selected StaffMember id, so
switching StaffMembers aborts any in-flight schedule request via the
existing `AbortController`/remount pattern rather than needing a separate
request-sequence guard.

**View and edit** — All seven weekdays always render, Понеделник through
Неделя, each showing every configured period in chronological order (or "Няма
работни часове" when empty), split working days included. The authoritative
Business timezone from the response is displayed verbatim; the frontend never
infers or hardcodes it. Editing uses native `<input type="time">` controls
(always canonical `HH:mm`, no seconds, no Date/timezone conversion of any
kind — pure wall-clock strings from load to save) with per-weekday add/
remove/clear controls and a whole-schedule clear control. Saving always sends
the complete resulting period set with the current `expectedVersion` (the
backend's replace-only contract), never a partial update; an empty period
list is a valid save. `useGuardedFormState` compares the live draft against
the last-loaded schedule (not a touched flag); a successful save clears the
guard before returning to view mode, a failed save preserves the draft and
keeps the guard active, and Cancel goes through the shared guard exactly like
the Staff profile/assignment editors, restoring the persisted schedule when
a dirty discard is confirmed. Only one editor (the schedule itself) is ever
open per page, so the existing single-registration guard contract needed no
change.

**Confirmation dialogs** — Clearing a weekday with periods, and clearing the
whole schedule, both require an inline `role="alertdialog"` confirmation
(mentioning the Bulgarian weekday name for the per-day case; the exact
copy specified for the whole-schedule case: heading "Изчистване на работния
график", body "Всички работни периоди за седмицата ще бъдат премахнати.
Сигурни ли сте, че искате да продължите?", safe button "Запази графика",
destructive button "Изчисти графика"). Removing a single period, or clearing
an already-empty day/schedule (the button is disabled in that state), never
prompts. The safe button always receives initial focus and the destructive
button is never auto-focused — the same safe-focus rule the shared
`UnsavedChangesGuard` dialog uses. (The Service/Staff deactivate confirmations,
which originally focused the destructive action, were aligned to this rule in
Phase 4.)

**Validation** — `validateDraftPeriods` (pure, unit-tested in
`presentation.test.ts`) enforces: both times required, canonical `HH:mm`
format, start strictly before end (a reversed or equal range is rejected),
no two periods on the same weekday overlapping, and a maximum of 100 periods
for the week (the backend's own `MAX_PERIODS`). Adjacent periods (one
period's `endTime` equal to another's `startTime`) are explicitly accepted,
matching the backend's half-open-interval semantics. A duplicate period is
caught by the same overlap check, since an identical range always overlaps
itself. Errors render next to the affected period (`aria-invalid`,
`aria-describedby`, and an inline Bulgarian message), not only as a
page-level notice; a failed submit attempt moves focus to the first invalid
period's start-time input, or to an error summary when the only failure is
the period-count limit. The backend remains authoritative — this only
improves UX by surfacing the same failures immediately; a genuine backend
`VALIDATION_ERROR` (which cannot distinguish the failing field, per the
existing generic contract) still renders as a safe page-level message via
`safeScheduleError`.

**Lifecycle and error handling** — `GET` succeeds for inactive StaffMembers
and `SUSPENDED` Businesses (confirmed by reading
`StaffWorkingScheduleAdministrationService`: `authorizeRead` performs no
lifecycle check at all); `PUT` is blocked for both, matching the backend's
`authorizeMutation`. The frontend mirrors this exactly: `canEdit = !readOnly
&& staffMemberActive` hides the edit control and shows the corresponding
Bulgarian explanation, while the schedule itself always remains visible and
readable. Every other backend failure case (unauthenticated,
`ACTIVE_BUSINESS_REQUIRED`, `ACCESS_DENIED`, `STAFF_MEMBER_NOT_FOUND`, a
stale `expectedVersion`, an unexpected failure) is handled with the same
safe-error/feedback pattern already established for Services and Staff. A
stale-version conflict (`WORKING_SCHEDULE_CONCURRENT_UPDATE`) shows a sticky
message with an explicit "Зареди актуалните данни" reload action and never
silently overwrites the user's draft.

**Test evidence** — Frontend (Vitest/Testing Library):
`business/schedule/api.test.ts`, `errors.test.ts`, `presentation.test.ts`
(all seven weekdays and labels in order, formatting, grouping, and every
validation rule: empty list, single/multiple/adjacent/overlapping/duplicate/
reversed/malformed periods, the 100-period boundary, Bulgarian messages),
`WorkingScheduleEditor.test.tsx` (empty and split-day display, authoritative
timezone, SUSPENDED/inactive read-only notices and control hiding, add/edit/
remove period, inline overlap errors blocking save, adjacent-period save,
weekday/whole-schedule clear confirmation including disabled-when-empty and
safe-button focus, single-period removal without confirmation, Cancel with
and without changes, the shared guard blocking an external guarded action
and clearing after a successful save, a failed save preserving the draft,
stale-version conflict with reload, and the 401 redirect),
`StaffWorkingSchedule.test.tsx` (default selection and status display,
full-catalog pagination, empty-team state, StaffMember switching and
schedule reload, a stale schedule response ignored after switching, the
guard blocking a StaffMember switch while dirty, and the 401 redirect), plus
an `App.tsx` test confirming the route renders the real feature instead of
the previous `ComingSoon` placeholder (the now-obsolete
`#/business/schedule` entry in `App.test.tsx`'s placeholder-sections table
was removed, since the route is no longer a placeholder). Full `npm run
test`: 367 tests passed across 31 files. `npm run lint`, `npm run build`
(`tsc -b && vite build`), and `git diff --check` all clean. No backend,
migration, dependency, or unrelated file was modified; `git status` shows
only frontend changes for this phase.

**Deviations / exclusions** — None. Phase 4 (cross-cutting hardening, visual
review, documentation) was not started per the approved scope; this document
records the mandatory field-level-validation hardening item below as
required future scope rather than broadening Phase 3 into it.

### Phase 3 UI/UX redesign — compact weekly grid

The initial Phase 3 implementation above (one large vertical card per
weekday, with permanent time inputs and a `Премахни`/`Изчисти деня`/`Добави
период` button trio always visible inside every weekday) was visually
rejected as too long, button-heavy, and form-like. This pass replaced the
interaction model while preserving every Phase 3 behavioral guarantee
(complete-set atomic `PUT`, optimistic `expectedVersion`, the shared
`UnsavedChangesGuard`, failed-save draft preservation, successful-save guard
cleanup, stale-version reload, and all inactive/`SUSPENDED` read-only
authorization behavior). No backend, migration, dependency, or API-contract
change was involved.

**Weekly grid, not seven independent cards** — `WorkingScheduleEditor.tsx`
renders one `.schedule-grid` container holding all seven weekday `<section
className="schedule-day">` elements in the same Monday-to-Sunday DOM order
as before. The container is `display: flex; flex-direction: column` by
default (mobile-first stacked cards) and becomes `display: grid;
grid-template-columns: repeat(7, minmax(0, 1fr))` at the existing shared
`48rem` breakpoint (the same breakpoint already used elsewhere in the
product for the mobile/tablet transition), so the desktop weekly table and
the mobile stacked cards are the *same DOM*, not two parallel
implementations — only the container's CSS `display` mode changes. This is
also why the frontend test suite verifies the responsive transformation
through semantic structure (one `.schedule-grid` container, seven
`section.schedule-day` children, one heading per weekday) rather than through
rendered-pixel snapshots, which jsdom cannot evaluate media queries against
anyway.

**Compact chips instead of permanent inputs** — Read-only and edit mode both
render each period as a small pill (`.schedule-period-chip`, e.g.
`09:00–12:00`) instead of a bulleted list or a permanent pair of `<input
type="time">` elements. An empty weekday shows the single word `Почивен ден`
instead of a full empty-state card. No `<input type="time">` exists anywhere
in read-only mode. In edit mode, each chip becomes a small button pair: the
period text (click to edit) plus a compact `×` remove button — removal stays
immediate with no confirmation, matching the previously accepted behavior.
Each weekday has one compact `+ Добави` action; there are no other
permanently visible per-weekday buttons.

**Focused add/edit dialog** — Clicking `+ Добави` or an existing period chip
opens one modal dialog (`role="dialog"`, `aria-modal`) titled `Добавяне на
период за {weekday}` or `Редактиране на период за {weekday}`, with a start-
and end-time field, inline Bulgarian validation next to the fields
(`aria-invalid`/`aria-describedby`), and primary/secondary actions
(`Добави`/`Запази` and `Отказ`). Validation reuses the existing
`validateDraftPeriods` function unchanged (required fields, canonical
`HH:mm`, start-before-end, no overlap, no duplicates — duplicates are
naturally caught as identical-range overlaps, adjacent periods accepted, the
existing 100-period cap): the dialog constructs a candidate full draft with
the new/edited period applied and re-validates the whole schedule, so the
same overlap/duplicate/limit logic that governs the final save also governs
every single add/edit, with no separate implementation to keep in sync. On
failure the dialog stays open, shows the concrete Bulgarian message, and
preserves the entered values; a valid period is never left unsaved and an
invalid one is never committed to the draft. The start-time field receives
initial focus on open (via an effect keyed on dialog-open state only, not on
the dialog's own contents, so typing never steals focus back). Escape closes
the dialog only when it is clean (start/end unchanged from how it opened);
once the user has typed anything, Escape is a no-op and only the explicit
`Отказ` button can discard the in-progress entry, per the task's explicit
requirement that Escape must never silently discard dirty dialog input.
Closing (by any path) restores focus to whichever control opened the
dialog.

**Overflow menu, not permanent per-day buttons** — The `Изчисти деня`
button no longer renders permanently inside every weekday. A weekday that
currently has at least one period shows a compact `⋯` ("Още действия за
{weekday}") trigger; clicking it opens a small `role="menu"` with two
`role="menuitem"` actions: `Изчисти деня` (unchanged confirmation dialog and
copy, now presented as a page-level modal rather than inline inside the
narrow grid cell) and the new `Копирай към…`. An empty weekday shows no `⋯`
trigger at all, since it has no non-empty-day actions to offer.

**Copy-to-weekdays** — `Копирай към…` opens a dialog (`role="dialog"`)
titled `Копиране на график от {source weekday}` with one checkbox per *other*
weekday (the source weekday is never offered as its own target — enforced by
filtering it out of the target list, not merely disabling it). A target
weekday that already has draft periods discloses this inline next to its
checkbox (`ще замени N период(а)`), and the primary button's label switches
from `Копирай` to `Копирай и замени` whenever at least one selected target
currently has periods, so the replacement is disclosed before the user
commits, not hidden behind a generic confirm. Copying operates purely on the
local `draft` state — the target weekdays' existing periods are removed and
replaced with clones of the source weekday's periods (new client-side ids,
reassigned weekday, same start/end times); nothing is sent to the backend
until the page-level `Запази промените`. Because a source weekday's own
periods never overlap each other (it is itself a valid saved/draft day) and
a target's prior periods are fully cleared before the copies are inserted,
copied periods cannot introduce a new overlap; the existing whole-schedule
`validateDraftPeriods` result (surfaced as the page-level "too many periods"
notice) still governs the 100-period cap after a copy that pushes the total
over the limit, since copying is the one path that adds periods without
going through the per-dialog check. Copying immediately marks the form
dirty through the same `draft`-vs-`schedule.periods` comparison already used
everywhere else, so it participates in the shared `UnsavedChangesGuard`
exactly like a manual edit. Cancel discards the pending target selection and
changes nothing.

**One page-level action group** — `Запази промените`, `Отказ`, and a
secondary-positioned destructive `Изчисти графика` (the existing
whole-schedule clear, with its existing confirmation copy and safe-button-
focus behavior, now presented as a page-level modal) appear exactly once, in
a `.schedule-page-actions` row below the grid, never repeated per weekday.

**Removed metadata** — The StaffMember status badge (`Активен`/`Неактивен`)
no longer renders in the schedule header; `StaffWorkingSchedule.tsx`'s
`<select>` still appends `(неактивен)` to an inactive StaffMember's option
text, which remains the only way the picker distinguishes lifecycle state.
The visible `Часова зона на бизнеса: {timezone}` paragraph was removed
entirely; the backend response's `timezone` field is unchanged and still
flows through the API/domain layer untouched — it is simply not rendered on
this screen anymore, and is neither inferred nor hardcoded anywhere in the
frontend. For an inactive StaffMember, the message was corrected from the
inaccurate "Неактивен член на екипа не може да получи работен график."
(which described a write restriction as if it blocked all access) to
"Работният график на неактивен член на екипа може само да бъде преглеждан."
For a `SUSPENDED` Business, the schedule-specific paragraph was removed
entirely — `BusinessOwnerShell`'s existing shared banner already states the
Business is suspended, so the editor now only hides its edit controls
(`canEdit = !readOnly && staffMemberActive`) without repeating that
explanation in a second, schedule-specific notice.

**Focus-restoration edge cases** — Two destructive confirmations can remove
the very control that invoked them: clearing a weekday removes its `⋯`
trigger (which only renders while the weekday is non-empty), and clearing
the whole schedule disables the invoking `Изчисти графика` button (disabled
once the draft is empty, and a disabled control cannot receive focus).
`confirmClearWeekday` therefore restores focus to that weekday's
always-present `+ Добави` control instead of the vanished trigger, and
`confirmClearAll` restores focus to the primary `Запази промените` button
instead of the now-disabled invoker.

**Test evidence** — `frontend/src/business/schedule/WorkingScheduleEditor.test.tsx`
was rewritten (29 tests) covering: the seven-weekday grid structure and
order, chip-based read-only display (`Почивен ден`, chronological chips, no
`<input type="time">` in read-only mode), absence of the status badge and
timezone text, the corrected inactive-StaffMember wording with no duplicate
`SUSPENDED` message, opening the Add dialog for the correct weekday with
initial focus, opening the Edit dialog pre-populated, immediate no-confirm
period removal, dialog validation (required fields, reversed range,
overlap, adjacent-accepted), Escape closing only when clean and restoring
focus, the day overflow menu appearing only for a non-empty weekday, clear-day
and clear-all confirmations (including safe-button focus and the
now-unmounted/disabled invoker focus-restoration fallbacks above), copy to
one and to multiple target weekdays, the inline replacement disclosure and
`Копирай и замени` label switch, copy cancel leaving the draft unchanged,
the source weekday never being offered as its own target, a copy
participating in the dirty-state guard and in the final save payload,
Cancel with and without changes, the shared guard blocking an external
guarded action and clearing after a successful save, a failed save
preserving the draft, the stale-version conflict with reload, and the 401
redirect. `StaffWorkingSchedule.test.tsx` was updated in place (no test
removed) to drop the retired status-badge/timezone-text assertions and
assert the corrected inactive wording and structural waits instead; its
StaffMember-switch, full-catalog pagination, stale-response, and
unsaved-changes-guard coverage is otherwise unchanged. `presentation.test.ts`,
`errors.test.ts`, and `api.test.ts` required no changes (the validation,
error-mapping, and HTTP-client logic they cover did not change). A repo-wide
CSS guardrail test (`frontend/src/ui/Button.test.tsx`, "restricts action
colors to shared semantic selectors") caught one styling mistake during this
pass — a bare `.schedule-day-menu button` element selector with color/
background rules, which the guardrail requires to be scoped under `.button`
or to avoid the word "button" in the selector entirely — fixed by renaming
it to the dedicated `.schedule-day-menu-item` class instead of relying on
the bare element selector. Full `npm run test`: 379 tests passed across 31
files. `npm run lint`, `npm run build` (`tsc -b && vite build`), and
`git diff --check` all clean. No backend, migration, dependency, or
unrelated file was modified.

**Manual visual verification** — Performed in the browser against the same
running local backend/frontend and Business-owner fixture from the initial
Phase 3 review (see below for the exact steps and screenshots taken).

**Deviations / limitations** — None from the approved redesign scope. The
day overflow menu is a minimal custom implementation (`role="menu"`/
`role="menuitem"` with initial-open-state focus and Escape-to-close) rather
than a full roving-tabindex ARIA menu widget with arrow-key navigation;
given the existing codebase has no shared menu component to extend and the
menu holds exactly two items, this was judged proportionate rather than
under-built, consistent with the same level of rigor the existing
`UnsavedChangesGuard` dialog uses (initial focus, Escape, focus restoration,
no full focus trap).

### Phase 3 final corrections — wording, spacing, ordering, copy shortcuts, dialog guard

A further correction pass on the accepted compact-weekly-grid redesign,
addressing five issues found in review before the stable checkpoint commit.
No backend, migration, dependency, or API-contract change; the weekly-grid
design and every previously completed behavior are preserved.

**Bulgarian sentence-form weekday wording** — `presentation.ts` now exports
`WEEKDAY_SENTENCE_LABELS` (lowercase: `понеделник`, `вторник`, `сряда`,
`четвъртък`, `петък`, `събота`, `неделя`) alongside the existing capitalized
`WEEKDAY_LABELS` (standalone column headings and copy-target checkbox
labels, which are list-item-style standalone labels, not sentence-embedded
text). Every schedule dialog sentence/phrase that embeds a weekday
mid-sentence now uses the sentence-form label: the Add/Edit-period dialog
heading (`Добавяне на часове за {weekday}` / `Редактиране на часове за
{weekday}` — note "часове" replaces the earlier "период" wording, per the
accepted correction, not only a casing change), the Copy-to-weekdays dialog
heading and description, the Clear-day confirmation heading and body, the
day overflow-menu's accessible name (`Още действия за {weekday}` /
`Действия за {weekday}`), and the period chip's edit/remove accessible
names (`Редактирай периода … за {weekday}` / `Премахни периода … за
{weekday}`). A shared label mapping is used throughout rather than scattered
inline `.toLowerCase()` calls or string concatenation, so the distinction is
enforced by which constant a call site imports, not by an easy-to-miss
per-call transformation.

**Dialog spacing** — `.schedule-dialog-panel` (the Add/Edit-period, Copy-to-
weekdays, Clear-day, and Clear-all-schedule modals) is now `display: grid;
gap: var(--space-4)`, and `.schedule-dialog-panel > *` is reset to
`margin: 0`. Both halves of this rule matter: `gap` establishes the token-
based spacing between the heading, fields, inline validation, and action
group, and the margin reset prevents a plain `<h4>`/`<p>`'s own
browser-default margin from silently stacking on top of that `gap` — CSS
Grid does not collapse item margins the way normal document flow collapses
adjacent block margins, so without the reset the validation error would
still end up closer to (or further from) the action buttons than the
declared `space-4` token, depending on the browser's UA stylesheet. Verified
in the browser with no error, a one-line error, a wrapping two-line error
(the overlap message), mobile width, and 200% zoom — see the visual
verification below. `docs/ui-design-guidelines.md` §5 now states the durable
general rule (distinct semantic blocks need explicit token-based spacing,
never accidental default margins; review the error-state layout
specifically) once, in the one place `AGENTS.md`/`CLAUDE.md` already point
agents to for UI guidance — not duplicated in either instruction file.

**Immediate chronological ordering** — `sortPeriodsForDisplay` (in
`presentation.ts`) is now generic over any `{ weekday, startTime, endTime }`
shape (previously typed only for the plain `WorkingPeriod` API shape), with
an explicit end-time tie-break added for full determinism, and is the single
function used for: the read-only view, the editable draft (grouped via
`groupPeriodsByWeekday(sortPeriodsForDisplay(draft))`, recomputed by the
existing `draftGroups` memo on every `draft` change), and the final atomic
PUT payload (`sortPeriodsForDisplay(draft).map(stripClientId)`). Because
every local draft mutation — add, edit, copy, remove, clear-then-add, or a
discarded/restored draft — funnels through the same `draft` state and the
same memoized grouping, the weekly grid re-sorts immediately after each one,
with no dependency on a backend round-trip; `clientId` values are untouched
by the sort (it only reorders array position). Test evidence: a new
"immediate chronological ordering" describe block in
`WorkingScheduleEditor.test.tsx` covering out-of-order adds, an edit that
moves a period earlier, a copy from a deliberately unsorted source array,
removal, clear-then-add, discard/restore, and the exact final PUT payload
order built from an edit sequence that scrambles insertion order across
weekdays.

**Copy-dialog shortcuts** — The accepted `Копирай към…` dialog gained three
compact shortcut buttons above the target checkboxes: `Понеделник–петък`
(the weekday range minus the source day), `Всички останали дни` (all six
possible targets), and `Изчисти избора` (clears the pending selection). Each
only adjusts `copyDialog.targets`; none performs the copy — the user must
still press `Копирай`/`Копирай и замени`, the existing inline replacement
disclosure is unaffected, copying remains local-draft-only until the page-
level `Запази промените`, and no second backend call was introduced. Styled
as small pill buttons (`.schedule-copy-shortcut`), visually contained inside
the dialog — the weekly grid itself gained no new controls.

**Add/Edit dialog joins the shared unsaved-changes guard** — Fixed the
confirmed correctness gap: the values a user types into the Add/Edit-period
dialog (`periodDialog.start`/`.end`) previously existed only in that local
dialog state, which the shared `UnsavedChangesGuard` registration never
saw — only the weekly `draft`-vs-`schedule.periods` comparison was
registered. A guarded navigation while a dialog was dirty but the weekly
draft was still clean (the common case: opening Add and typing a time before
ever submitting) would previously show no confirmation at all, silently
discarding the typed value. The registered dirty state is now
`weeklyDraftDirty || periodDialogDirty` (the latter already existed for the
Escape-key check and is now reused, not duplicated), and the shared guard's
discard callback resets both: it restores `draft` from the persisted
schedule and closes the dialog (`setPeriodDialog(null)`). This required no
second confirmation implementation — the existing single shared
`UnsavedChangesGuardProvider` dialog, already used for every other guarded
transition in the app, now simply reflects a dirty state that accounts for
the open dialog. The explicit local `Отказ` button inside the dialog still
discards immediately without its own nested confirmation (consistent with a
Cancel button's ordinary meaning — the click itself is the explicit,
non-accidental discard signal) and leaves no stale registration, since
`isDirty` recomputes to `false` once the dialog closes; only Escape is
gated (closes a clean dialog immediately, is a no-op on a dirty one),
because an accidental key press is exactly the case this guard exists to
protect against. Test evidence: a new "dirty Add/Edit dialog participates in
the shared guard" describe block in `WorkingScheduleEditor.test.tsx`
(untouched dialog is not guarded; either field becoming dirty is guarded
even with a clean weekly draft; an unchanged vs. changed Edit dialog;
`Продължи редактирането` preserves the dialog and its values; confirming
discard — with both the dialog and the weekly draft dirty at once — closes
the dialog, restores the persisted draft, and completes the pending guarded
action; explicit local `Отказ` leaves no stale registration; a successful
Add transfers the change into the weekly draft, which then itself carries
the guard; Escape's clean-only behavior), plus one new `App.test.tsx` test
proving the same combined dirty state blocks and correctly resolves a real
browser Back (`popstate`) navigation. `beforeunload` arming is not
duplicated with a schedule-specific test: it is already proven generically
in `UnsavedChangesGuard.test.tsx` against the same shared `isDirty` state
this fix now correctly feeds, for any registered dirty source.

**Test evidence (this pass)** — `WorkingScheduleEditor.test.tsx` grew from
29 to 48 tests; `presentation.test.ts` grew from 16 to 19 tests (sentence-
label mapping, the end-time tie-break, and clientId preservation through the
generic sort); `App.test.tsx` gained one test. Full `npm run test`: 402
tests passed across 31 files (up from 379). `npm run lint`, `npm run build`
(`tsc -b && vite build`), and `git diff --check` all clean. No backend,
migration, dependency, or unrelated file was modified.

**Deviations / limitations** — None from the approved correction scope.

## Product boundary — recurring weekly schedule, schedule exceptions, and the appointment calendar

Recorded per explicit product-boundary review during Phase 3, for durable
reference by future issues (not implemented in this correction; nothing
below is new scope for Phase 3):

* Phase 3 (`#/business/schedule`) manages the **recurring standard weekly
  schedule only** — a Monday-to-Sunday template of working periods that
  repeats every week until changed. It has no concept of a specific
  calendar date.
* **Date-specific hours, closures, holidays, leave, and time off** (e.g. "closed
  on 2026-12-24", "Anna is on leave 2026-07-01 through 2026-07-14") are out
  of scope for the recurring weekly schedule and require a separate,
  future **schedule-exceptions** capability that overlays specific dates on
  top of the recurring weekly template. This is not a UI-only addition — it
  needs its own backend contract and data model, following the existing
  `docs/tasks/04a`–`04c` backend-then-frontend pattern already used for this
  issue, and matches the "Explicit exclusions" already recorded at the top
  of this document (exceptional dates, holidays, leave, temporary
  overrides).
* **Concrete daily/weekly/monthly calendars showing actual appointments and
  clients** belong entirely to the future "Build Business calendar and
  appointment management" issue, not to this one. A monthly view must never
  be simulated from weekday-only recurring data — the recurring schedule
  says which hours are open in general, not which dates have which
  appointments, and collapsing the two would misrepresent exceptions,
  cancellations, and actual bookings as if they followed the recurring
  template exactly.
* Recommended future sequence, in order, each depending on the one before
  it: (1) recurring weekly schedule — this issue, completed; (2)
  date-specific schedule exceptions/time off; (3) availability calculation
  using both the recurring template and the exceptions layer together; (4)
  the appointment calendar and management issue itself, built on top of a
  correct availability calculation.

### Phase 3 final UX correction pass — overflow menu positioning, copy shortcuts, confirmation wording, standalone clearing

A further, bounded correction pass on the accepted weekly-grid design,
addressing five review findings. No backend, migration, dependency,
authentication, authorization, or tenant-isolation change; every previously
accepted Phase 3 behavior (Monday-to-Sunday grid, compact chronological
chips, immediate re-sorting, sorted atomic PUT payload, Add/Edit validation,
copy-to-weekdays, the shared unsaved-changes guard including the dirty
Add/Edit dialog, stale-version recovery, inactive/`SUSPENDED` read-only
behavior, mobile/200%-zoom layouts, and token-based dialog spacing) is
preserved.

**Collision-aware weekday overflow menu** — The `⋯` menu previously used a
fixed CSS offset (`position: absolute; top: 100%; right: 0`) that only
avoided overlapping the day's own chips by coincidence for some grid
columns, and could be clipped by the narrow weekday card. Replaced with:
a new pure, unit-tested helper,
`frontend/src/business/schedule/menuPosition.ts`'s `computeMenuPosition`,
which decides the side (`right` preferred, flips to `left` when the
viewport lacks room) and clamps the final position within the viewport,
given real trigger/menu measurements; and `position: fixed` placement in
`WorkingScheduleEditor.tsx`, computed via `getBoundingClientRect()` on both
the trigger and the (already-rendered, natural-`width: max-content`) menu
inside a `useLayoutEffect` (so there is no visible jump — the menu renders
`visibility: hidden` for one frame, then is revealed at its measured
position), re-run on `window resize` (which also covers the browser-zoom
visual-verification technique used throughout this issue). `position: fixed`
is positioned relative to the viewport, not any ancestor, so it is never
clipped by the weekday card, the weekly grid, or another overflow
container, regardless of which grid column the trigger belongs to.
`.schedule-day-menu` sizes to its content (`width: max-content`, capped)
instead of a fixed `min-width`, and `.schedule-day-menu-item` is
`white-space: nowrap`, so a label is never truncated or ellipsized. The copy
action was renamed from `Копирай към…` to the shorter, complete
`Копирай графика`. A new durable rule for this pattern (collision-aware,
measured, content-sized popup positioning; extract the side/flip decision
as a pure testable function) was added to `docs/ui-design-guidelines.md` §6,
since no existing rule covered popup/menu positioning.

**Copy-dialog shortcuts as one coherent group** — The Copy-to-weekdays
dialog panel gained a dedicated `.schedule-copy-dialog-panel` width
(`min(100%, 30rem)`, wider than the other schedule modals' `26rem`) so its
three shortcuts (`Понеделник–петък`, `Всички останали дни`,
`Изчисти избора`) fit on one row at ordinary desktop width instead of
wrapping two-plus-one; the flex-wrap fallback still applies predictably at
mobile width and 200% zoom, with the same `gap` token in both axes so
wrapped-row spacing matches single-row spacing. `Изчисти избора` was
already a neutral outlined style (never destructive red — it only clears
the pending checkbox selection, no data changes); it is now also `disabled`
whenever no target weekday is selected. Shortcut behavior is otherwise
unchanged: selection-only, never copies automatically, source weekday
always excluded, existing replacement-warning disclosure and
`Копирай`/`Копирай и замени` label switch preserved.

**Destructive confirmation wording and layout** — Both the weekday-clear
and whole-schedule-clear confirmations now render their two sentences as
two separate `<p>` elements (spaced by the same `.schedule-dialog-panel`
`gap` token already established, with no new CSS needed) instead of one
merged paragraph. Headings shortened to `Изчистване на графика за
{weekday}` / `Изчистване на графика` (lowercase sentence-form weekday).
Action order and wording changed: the destructive action now appears first
(`Изчисти графика за деня` / `Изчисти графика`), the safe action second,
relabeled `Отказ` in both dialogs (never `Запази периодите`/`Запази
графика` — the safe action cancels a destructive operation, it does not
save anything). The safe `Отказ` button still receives initial focus
regardless of its new visual position (focus is assigned by ref, not by
DOM/visual order), Escape still behaves identically to clicking it, and
focus still restores to the invoking control on cancel — all unchanged from
the already-accepted guard/focus conventions.

**Add/Edit dialog title wording** — Changed from `Добавяне на часове за
{weekday}` / `Редактиране на часове за {weekday}` to the more precise
`Добавяне на работно време за {weekday}` / `Редактиране на работно време за
{weekday}`, still using the lowercase `WEEKDAY_SENTENCE_LABELS` mapping.
Column headings and copy-target checkbox labels are unaffected (still the
capitalized `WEEKDAY_LABELS`).

**Whole-schedule clearing moved outside editing, as a standalone atomic
operation** — Previously, "Изчисти графика" only existed inside edit mode
and cleared the local `draft` optimistically (no request until the
subsequent "Запази промените"). It is now a page-level, read-only-mode-only
action, next to "Редактирай графика" in one `.action-group` (edit mode now
shows only `Запази промените`/`Отказ`, with no clear-all action at all).
Confirming it calls `replaceWorkingSchedule` directly — `{ expectedVersion:
schedule.version, periods: [] }` — with no intermediate draft or `editing`
state ever touched. A dedicated `clearingAll` boolean and `clearingInProgress`
ref track this request independently of the edit-mode `saving`/
`savingInProgress` used by the ordinary save flow, per the explicit
requirement to keep the two request states separate. Matching the
established `StaffDetail` deactivate-confirmation convention, the
confirmation dialog stays open (both actions disabled, destructive button
reading "Изчистване…") for the duration of the request rather than closing
immediately, so a rapid double-click on the same button cannot fire a
second request even before React re-renders the `disabled` attribute — the
`clearingInProgress` ref is the actual guard; the dialog closes only once
the outcome (success or failure) is known. On success: the authoritative
response replaces `schedule` (version and timestamps included), `draft` is
reset from it for a later editing session, the view remains read-only, and
the existing success-feedback UI shows "Работният график е изчистен." On
failure: the currently displayed schedule is left unchanged, the existing
safe-error-feedback and (for a stale-version conflict) "Зареди актуалните
данни" reload UI apply exactly as for the ordinary save flow. The action is
disabled when the saved schedule already has no periods, and — being
wrapped in the same `{canEdit && ...}` block as `Редактирай графика` — is
not rendered at all for a `SUSPENDED` Business or an inactive StaffMember.

**Test evidence** — New `menuPosition.test.ts` (6 tests: prefers right,
flips left, opens right near the left edge, flips above when short on room
below, clamps within a narrow viewport, respects a custom margin) —
positioning logic tested as a pure function since real collision geometry
is not reliable in jsdom; the actual rendered position was verified
manually in the browser (see below).
`WorkingScheduleEditor.test.tsx` grew from 48 to 58 tests: the former
"clear day / clear all" describe block was split into "clear one weekday
(inside editing)" (updated wording/order/focus assertions, plus a new
cancel-restores-focus test) and a new "standalone whole-schedule clearing
(outside editing)" block (10 tests: both actions present in read-only mode,
edit mode shows only Save/Cancel, disabled when already empty, opening the
confirmation does not enter edit mode or build a draft, cancelling performs
no request, the exact `{expectedVersion, periods: []}` payload with
duplicate-submission prevention and remaining read-only on success, failure
preserves the displayed schedule, stale-version conflict with reload,
hidden for an inactive StaffMember, hidden for a `SUSPENDED` Business); the
"copy dialog shortcuts" block gained 2 tests (`Изчисти избора`
disabled-then-enabled by selection state, and clearing the selection
touches no weekday's draft). `presentation.test.ts`, `api.test.ts`, and
`errors.test.ts` were unaffected (no changes to the logic they cover).
`StaffWorkingSchedule.test.tsx` required no changes (mid-level selector
behavior is unaffected by this pass). Full `npm run test`: 418 tests passed
across 32 files (up from 402). `npm run lint`, `npm run build` (`tsc -b &&
vite build`), and `git diff --check` all clean. No backend, migration,
dependency, or unrelated file was modified.

**Manual visual verification** — Performed in the browser against the same
running local backend/frontend and Business-owner fixture used throughout
this issue's Phase 3 reviews; see the report for this pass for the full
step-by-step results (menu positioning for an early and a late weekday
column at desktop/mobile/200% zoom, full unclipped menu labels, all three
copy shortcuts on one row at desktop with predictable wrapping at mobile/
200% zoom, the corrected Add/Edit title, the two-paragraph clear-weekday
dialog with correct button order/focus, standalone whole-schedule clearing
end-to-end including a real failed/stale-version path, read-only vs.
edit-mode action rows, inactive StaffMember, and `SUSPENDED` Business). No
existing local fixture data was mutated or destroyed for this review; any
schedule mutated during a check (e.g. a test clear) was restored through
the application's own "Откажи промените"/reload flows before moving to the
next check.

**Deviations / limitations** — None from the approved correction scope. This
limitation was later resolved: the open menu now closes on outside click and
on scroll, Escape closes it and restores focus to the trigger, and all
listeners are removed on close/unmount. It still has no full roving-tabindex
arrow-key navigation or focus trap (two items only).

### Mandatory Phase 4 / final-hardening item — field-level validation (resolved in Phase 4, with one backend limitation)

_Status: resolved in Phase 4 for every locally knowable rule; the backend
limitation below remains and is documented in the Phase 4 section._

Confirmed existing UX defect, recorded during Phase 3 rather than fixed (out
of Phase 3's approved scope): when the backend rejects a specific field —
for example an invalid telephone such as `+3598881234561` — the UI currently
shows only the generic message "Проверете въведените данни.", without
identifying which field is invalid. The same generic-message limitation
applies to the new working-schedule `VALIDATION_ERROR` responses (weekday/
time/range/duplicate/overlap/period-count failures are all collapsed to one
code by `BusinessStaffWorkingScheduleExceptionHandler`).

This is a **required** Phase 4/final-hardening item for Issue #14, not an
optional future idea:

* Backend validation responses must identify the invalid field in a stable
  machine-readable form where applicable.
* Frontend forms for Services, StaffMembers, Profile, and working schedules
  must map validation failures to the corresponding field.
* The field must receive an accessible inline Bulgarian error.
* `aria-invalid` and `aria-describedby` must be applied.
* Focus must move to the first invalid field.
* The page-level message may summarize that validation failed but must not
  be the only information.
* Entered values and dirty state must be preserved.
* Unknown/general failures must remain safely sanitized.

## Phase 4 — cross-cutting hardening

Frontend and documentation only; no backend, migration, dependency, or API
contract change. Treated as Standard risk.

**Repository gate** — `main` at `71a3cbf` equal to `origin/main`, clean tree,
empty index, latest `main` CI run green, migrations V1–V8 unchanged.

**Local field-level validation** — Services, StaffMembers, and the schedule
period dialog no longer rely on native browser validation and the generic
backend message. New shared helper `ui/formValidation.tsx`
(`useFieldErrors`, `fieldControlProps`, `FieldError`) plus pure, unit-tested
rule modules `business/services/validation.ts` and
`business/staff/validation.ts` (canonicalization in `business/text.ts`
mirroring the backend's approved whitespace and NFKC handling):

* forms use `noValidate`; every error is a Bulgarian sentence rendered under
  its field in a `.form-field` block, tied with `aria-invalid` and
  `aria-describedby`; all errors appear at once, the first invalid control in
  DOM order receives focus, and entered values are preserved;
* editing a field re-evaluates only that field, so its error disappears once
  the value is valid;
* rules covered: required and whitespace-only names (including non-breaking
  and ideographic spaces), name length in code points (200), description
  length (2000), duration 1–480 whole minutes (including the number input's
  unparseable-text state), EUR price format (`^\d{1,10}(\.\d{1,2})?$`), optional
  email (blank accepted, shape and 320-character limit when non-blank),
  optional telephone (blank accepted; otherwise only a `+`, `00`, or `0`
  prefix, digits after removing the backend's approved separators, and the
  15-digit E.164 ceiling);
* schedule period dialog: a missing start or end time is reported next to its
  own field; a reversed, overlapping, duplicate, or over-limit period is a
  range error tied to both fields; focus moves to the first invalid time
  field; editing clears the affected error;
* Business-owner Profile display name now also rejects a whitespace-only value
  locally (`requireTrimmedValue`). The other Profile fields are pre-existing
  identity forms and were not changed.

**Backend limitation (documented, not changed)** — the backend reports every
rejected value as one generic `VALIDATION_ERROR` (`Проверете въведените
данни.`) with no field metadata, and the phone number is finally checked
against real numbering rules by the backend. The frontend therefore cannot
map an unforeseen backend rejection to a field. It does not guess: it shows
an actionable form-level message instead. For Services it lists the fields to
review; for StaffMembers it names the telephone or the email only when that
is the sole non-blank contact value, and otherwise mentions both. Exposing
machine-readable field names would be a public API contract change and needs
separate approval; it was not made. The earlier required item "backend
validation responses must identify the invalid field" therefore remains open
as a backend follow-up.

**Unsaved-changes audit** — every editable workflow already used the single
shared guard (Service and Staff create/edit, assignment editing, schedule
editing including the dirty period dialog, Profile). One real gap was found and
fixed: the "Зареди актуалните данни" reload after an optimistic-concurrency
conflict discarded a dirty editor without confirmation. It now goes through
`guard.guard` in `ServiceDetail`, `StaffDetail`, `StaffServiceAssignments`, and
`WorkingScheduleEditor`, so the user can continue editing or explicitly discard.

**Destructive confirmation hardening** — the Service and Staff deactivate
confirmations focused the destructive button; they now focus the safe „Отказ“
action first (verified in the browser). The two schedule `alertdialog`s gained
`aria-modal="true"`, and „Редактирай графика“ is disabled while a whole-schedule
clear is in flight so nothing behind the modal can start a competing operation.

**Stale-response and Business-context audit** — reads use `AbortController`
plus request-sequence guards; mutations use synchronous in-progress refs and
`useFeedback` generations; Business-owner sections are keyed by the active
Business (and entity id); the Business selector existed only on the Profile
route, so Business-owner data unmounts before a switch. (Superseded 2026-10-04 by issue #20: Business
selection is now the `Бизнеси` page, outside the Profile; see
`docs/tasks/07a-business-customer-records.md`. Business-owner data still unmounts before a switch.) No defect was found in
that infrastructure. Regression tests were added for the uncovered case of a
create response arriving after the form was left (no navigation) and for
duplicate activation while a create is pending.

**Lifecycle and authorization presentation** — verified against the code and
the backend service: DRAFT/ACTIVE allow mutations, SUSPENDED shows the shell
banner and no mutation controls, an inactive StaffMember keeps profile and
assignment editing (the backend blocks only a suspended Business) while its
schedule is view-only, Business-owner routes are unavailable to
platform-admin-only and non-owner sessions, and the platform-admin shell does
not render Business-owner controls.

**Accessibility and layout** — headings are single-`h1` with section headings
below; dialogs expose `role`, label, and `aria-modal`; sortable headers keep
`aria-sort`; the schedule period dialog fields now wrap instead of squeezing
at narrow widths (`repeat(auto-fit, minmax(9rem, 1fr))`).

**Test evidence** — Frontend (Vitest/Testing Library): new
`services/validation.test.ts` and `staff/validation.test.ts` (rules and
boundaries), form-level tests in `ServiceCreate.test.tsx` and
`StaffCreate.test.tsx` (inline error, focus, ARIA, clearing, generic-backend
replacement, late responses), safe-focus and confirm-before-reload tests in
`ServiceDetail.test.tsx`/`StaffDetail.test.tsx`, schedule-dialog per-field
error, focus, and clearing tests, an `aria-modal`/disabled-edit assertion, and
a Profile whitespace-name test in `App.test.tsx`. Full `npm run test`: 457
tests passed across 34 files (last documented count before this phase: 418 in
32 files). `npm run lint` and `npm run build` clean; `git diff --check` clean;
index empty; no migration, dependency, or backend file changed.

**Visual review** — Performed in the built-in browser against a throwaway
in-memory mock of the Business-owner API (a script kept outside the
repository, on a second Vite instance), because no local Business-owner
credentials were available and none were searched for. The real backend and
PostgreSQL were not touched and no fixture data was created or changed there.
Reviewed: Service and Staff create forms with all error states at desktop and
375 px (wrapped multi-line errors do not touch fields or the action button),
the schedule Add dialog (missing times, overlap range error) at 375 px and
1024 px, the guarded hash navigation from a dirty form, the deactivate
confirmation (focus on „Отказ“), and a `SUSPENDED` Business at 640 px
(≈ 200% zoom of a 1280 px window; banner shown, no mutation controls, no
horizontal overflow). Not covered by this review, and therefore still
requiring the user's opinion on a real fixture: the Services and Staff list
tables, the weekly grid at every width, the assignment editor, the whole-week
clear/copy dialogs, and the DRAFT presentation.

### Phase 4 validation-consistency correction (Strict, approved)

**Backend contract extension (backwards compatible)** — a `VALIDATION_ERROR`
response from the Service and StaffMember endpoints may now carry one optional
property next to the unchanged `status`, `title`, `code`, and `detail`:

```json
{ "code": "VALIDATION_ERROR", "detail": "Проверете въведените данни.",
  "fieldErrors": { "contactPhone": "Въведеният телефонен номер не е валиден." } }
```

`fieldErrors` maps a public HTTP field name to a fixed Bulgarian message and
never contains exception text or submitted values. Named fields: Service
`name`, `description`, `durationMinutes`, `price`; StaffMember `displayName`,
`contactEmail`, `contactPhone`. Page, size, sort, direction, identifier,
version, assignment, and command failures stay generic (no `fieldErrors`). The
validators stop at the first failure, so the backend names one field per
response; the frontend still reports every locally detectable error together.
Implemented in `BusinessServiceExceptionHandler` and
`BusinessStaffMemberExceptionHandler` (`InvalidInput` already carried the
internal field). The working-schedule and platform endpoints are unchanged.

**Telephone stays optional** (approved option A): blank or absent is valid and
clears the value; a non-blank value must be a valid country-aware number. No
schema or StaffMember contract change.

**Frontend** — `ui/formValidation.tsx` now implements the touched/submitted
policy (`useFieldValidation`): no premature required errors, validation on
blur, live revalidation once touched, immediate errors for already-invalid
non-empty values, all-errors-plus-first-focus on submit, and backend
`fieldErrors` mapped inline (`backendFieldErrors`, `ApiError.fieldErrors`,
sanitized to short string messages). Service duration/price messages follow
the specified order (syntax, range/format, negative price). Telephone is
validated with the newly added exact-pinned dependency `libphonenumber-js`
1.13.14 (MIT, no runtime dependencies), using the `max` metadata because the
smaller `min` set accepts numbers the backend's full metadata rejects (for
example `+359800123456`); `package.json` and `package-lock.json` gained only
that entry. The telephone metadata is emitted as its own eagerly preloaded `vendor-libphonenumber` chunk (194 kB, 51 kB gzip) through `manualChunks` in `vite.config.ts`, leaving the application chunk at 317 kB (86 kB gzip) and the build free of the chunk-size warning. A
`VALIDATION_ERROR` without usable `fieldErrors` shows the form-level
fallback. Non-field failures keep using the form alert.

**Audit** — Profile: the display name uses native browser validation for its
single field and its backend validation carries no field metadata, so it was
left unchanged (its whitespace-only check from the first correction remains).
Schedule period dialog: already shows field-specific inline errors that clear
on edit; it does not use the shared hook (its errors are produced on
submission) and was not changed.

**Test-stability fix** — the full suite occasionally failed under CPU load
(reproduced 3 times in 24 loaded runs before the fix): a passive `useEffect`
that moves focus to an error alert, or that registers the unsaved-changes
guard's dirty state, can run after the DOM already shows the new state, so a
click or assertion in that window saw stale focus or a stale "dirty" guard
(failures: my `StaffCreate` "maps backend fieldErrors…" `toHaveFocus`, the
pre-existing `App` "clears the guard after a successful password change" and
`BusinessCreate` "shows safe validation and slug-conflict feedback" tests).
All focus-moving effects and `useGuardedFormState` registration are now
layout effects, so focus and guard state change in the same commit as the DOM.
This is a small production correctness fix, not only a test fix. After it,
6 normal and 16 CPU-loaded consecutive `npm run test` runs passed 465/465
with no `act()` warning, unhandled rejection, or console error.

**Contact-email policy (blocking correction)** — a real defect let `a@a`
(possibly with a Cyrillic `а`, which the browser's email input submits as
`a@xn--80a`) be accepted and later displayed as `a@xn--80a`. Neither the
backend nor the frontend performs IDN conversion; the browser does. The defect
was accepting a single-label domain. The policy is now defined once per side
and asserted with the same examples: `StaffMemberEmailPolicy` (backend, applied
before the general `@Email` check) and `isAcceptableEmail` in
`staff/validation.ts`. Rules: exactly one `@`, a non-empty local part that does not start or end
with `.` or contain `..` (the frontend enforces this itself; the backend
rejects it through its general `@Email` validator), no whitespace, and a dotted domain of at least two labels, none empty, none
starting or ending with `-`, each made of letters, digits, and `-`; the 320
character limit is unchanged. Rejected: `a@a`, `a@а`, `a@xn--80a`,
`@primer.bg`, `a@`, `a@.bg`, `a@primer.`, `a@primer..bg`, hyphen-edged labels,
and whitespace. Accepted: `ime@primer.bg`, `a@ab.bg`, subdomain addresses, and
ASCII addresses with dotted local parts and subdomains. **Only ASCII
addresses are accepted**: any non-ASCII character in the local part or domain
(`иван@primer.bg`, `ime@пример.бг`, `иван@пример.бг`) is rejected. Internationalized
addresses need SMTPUTF8 support from every future mail provider and integration
(invitations, password resets, notifications), so they are intentionally
deferred for the MVP rather than accidentally unsupported; no Punycode
conversion or SMTPUTF8 support is added, and an explicitly typed ASCII
`xn--` domain with a dot is treated as ordinary ASCII. Blank stays valid (the
contact email is optional). The inline message is exactly „Въведете валиден
имейл, например ime@primer.bg.“; the backend `fieldErrors.contactEmail`
message stays „Въведеният имейл адрес не е валиден.“. No schema change; existing rows,
including any already-stored single-label address, are not modified and must be
corrected through the UI.

**Limitations** — libphonenumber-js and the backend's Google libphonenumber
ship separate metadata releases and can disagree on rare numbers; the backend
`fieldErrors.contactPhone` is then shown inline. The backend reports one field
at a time.

**Remaining explicit exclusions** — appointment calendar, availability,
schedule exceptions, booking, Membership management, backend field-level
validation metadata (see above), and browser end-to-end tests (#15).

## Notes

No backend capability gaps were identified for the Services/Business-owner
shell or recurring-schedule timezone display; the recurring working-schedule
response already carries the authoritative Business timezone required by
Phase 3, so no additional business-timezone endpoint is needed. The one gap
that was found — the Staff list endpoint's missing sort/pagination parity —
is documented and fixed above, ahead of the Staff frontend implementation
work in Phase 2, which is itself now complete (see "Phase 2 — Staff
management + service assignments" above).
