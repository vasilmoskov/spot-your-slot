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
3. **Staff management + service assignments.** StaffMember list/create/detail/
   edit/deactivate/reactivate UI and active-service assignment management,
   following the same shape as Phase 1.
4. **Recurring working-schedule management.** Per-staff weekly editor, split
   working days, weekday/full-schedule clearing with confirmation, timezone
   display, backend overlap/concurrency error rendering.
5. **Cross-cutting hardening, visual review, and documentation.** Audit
   stale-response and Business-context-change handling across all three
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

Staff management (Phase 3 in this document) and recurring working-schedule
management (Phase 4) are unaffected and not started by this phase.

## Notes

No backend capability gaps were identified for this task; the recurring
working-schedule response already carries the authoritative Business
timezone required by Phase 3, so no additional business-timezone endpoint is
needed.
