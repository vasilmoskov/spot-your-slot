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
4. **Phase 3 — Recurring working-schedule management.** Per-staff weekly
   editor, split working days, weekday/full-schedule clearing with
   confirmation, timezone display, backend overlap/concurrency error
   rendering.
5. **Phase 4 — Cross-cutting hardening, visual review, and documentation.**
   Audit stale-response and Business-context-change handling across all three
   features, perform the full desktop/mobile/200%-zoom human visual review,
   update authoritative documentation, run the full frontend regression
   suite.

## Phase 1 acceptance criteria

* An authorized `BUSINESS_OWNER` can reach a Business-owner shell that is
  visibly separate from the `PLATFORM_ADMIN` interface and exposes no
  platform-admin-only controls.
* The active Business is clearly identified, including for an owner with only
  one Business; existing multi-Business selection behavior is unchanged.
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
the editable table's headers, with a non-interactive `✓` plus visually
hidden `Да` text (not a disabled checkbox) in the second column; no status
badge and no `✕` (unassigned Services are not listed there at all). The
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

## Notes

No backend capability gaps were identified for the Services/Business-owner
shell or recurring-schedule timezone display; the recurring working-schedule
response already carries the authoritative Business timezone required by
Phase 3, so no additional business-timezone endpoint is needed. The one gap
that was found — the Staff list endpoint's missing sort/pagination parity —
is documented and fixed above, ahead of the Staff frontend implementation
work in Phase 2, which is itself now complete (see "Phase 2 — Staff
management + service assignments" above).
