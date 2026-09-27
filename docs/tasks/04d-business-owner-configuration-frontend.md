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

1. **Business-owner shell + Services management** (this phase — in progress).
   New `BusinessOwnerShell`, active-Business identification via the existing
   session flow, navigation for Услуги/Екип/Работно време with only Услуги
   fully implemented, complete Services list/create/detail/edit/cancel/
   deactivate/reactivate UI, optional starter presets, exact EUR handling,
   `SUSPENDED` read-only behavior, safe feedback, focused tests.
2. **Staff management + service assignments.** StaffMember list/create/detail/
   edit/deactivate/reactivate UI and active-service assignment management,
   following the same shape as Phase 1.
3. **Recurring working-schedule management.** Per-staff weekly editor, split
   working days, weekday/full-schedule clearing with confirmation, timezone
   display, backend overlap/concurrency error rendering.
4. **Cross-cutting hardening, visual review, and documentation.** Audit
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

## Notes

No backend capability gaps were identified for this task; the recurring
working-schedule response already carries the authoritative Business
timezone required by Phase 3, so no additional business-timezone endpoint is
needed.
