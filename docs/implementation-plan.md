# SpotYourSlot implementation plan

## Delivery and version policy

Implementation begins only after foundation review/approval. Each phase is
small and reviewable, includes relevant migrations/tests/docs, and does not
silently expand the generic MVP.

Bootstrap uses Java 25 LTS. It verifies official primary documentation and
selects the latest stable GA Spring Boot officially supporting Java 25, a
supported stable Maven, latest suitable Node.js LTS, mutually compatible stable
React/TypeScript/Vite, and the latest stable PostgreSQL major supported by local
Docker and the approved host. It records exact versions and reasoning, pins
direct dependencies/tools and non-floating Docker tags, commits generated
lockfiles, and excludes alpha/beta/milestone/RC/snapshot/preview/experimental
dependencies absent separate approval.

## Phase 0 — foundation approval

- Review scope, terminology, architecture, data, security, testing, version
  policy, assumptions, and unresolved decisions.
- Confirm the domain/vendor directions are examples/proposals only.
- Exit: internally consistent documentation approved; current task stops here.

## Phase 1 — repository bootstrap

- Verify and record exact versions under the policy above.
- Generate Java 25 Spring Boot/Maven under `backend/` with `bg.spotyourslot` and
  React/TypeScript/Vite under `frontend/`.
- Add only approved dependencies, local PostgreSQL database `spotyourslot`,
  pinned Compose images, profiles, non-sending email, health, checks, lockfiles,
  exact setup/compatibility notes, and CI without hosting.
- Exit: clean builds/tests and no real email or committed credentials.

## Phase 2 — schema, tenancy, and identity

- Flyway baseline for Businesses/BusinessType, users, Memberships, platform
  roles, sessions, invitations, and resets using `business_id`.
- Implement sessions, password flows, CSRF/CORS, roles, errors, token lifecycle,
  tenant context, and module-boundary tests.
- Exit: owner invitation and Business A/B identity isolation pass.

## Phase 3 — platform onboarding

- PLATFORM_ADMIN APIs/UI list/create/edit Business, type, slug, invitation,
  activation, suspension, and reactivation.
- Enforce DRAFT/ACTIVE/SUSPENDED behavior and Bulgarian unavailable state.
- Backend Business management is complete: seven PLATFORM_ADMIN-only endpoints,
  bounded deterministic pagination, expected-version concurrency, profile
  fields, and active-owner locking for initial activation. The platform-admin
  React UI implements the Business list, creation, read-only detail with
  explicit profile editing, structured Bulgarian addresses, owner-invitation
  request, and confirmed lifecycle actions. Technical versions and timezone are
  retained for backend correctness but hidden from the current UI. Final issue
  #6 human visual and invitation retesting and issue #7 end-to-end lifecycle
  verification remain pending.
- Exit: unique URL and lifecycle flows pass.

## Phase 4 — Business configuration and workforce

- The Business Services backend slice in issue #11 is complete: V5, the
  Business-scoped catalog model, canonical-first validation, persistence,
  active-owner authorization, lifecycle and optimistic-concurrency
  orchestration, the authenticated HTTP API, and PostgreSQL-backed verification.
- The StaffMember and Service-assignment backend slice in issue #12 is complete:
  V6, the Business-scoped Workforce model, active and inactive StaffMember
  administration, complete-set assignments, shared aggregate versioning,
  owner-only authorization, the authenticated eight-route HTTP API, and
  PostgreSQL-backed concurrency and rollback verification.
- Generic Business settings, Services, StaffMembers without mandatory accounts,
  qualifications, weekly intervals, breaks, time off, and overrides.
- Enforce optional same-Business one-to-one StaffMember/Membership linkage with
  constraints/tests. Use “Екип” and “Член на екипа” in generic Bulgarian
  administration while preserving `StaffMember` internally; public booking uses
  the neutral formal step headings of ADR-0026 («Избор на услуги», «Избор на служител», «Дата и час», «Вашите
  данни», «Преглед и потвърждение») and “Без предпочитание”; the informal question “При кого искаш да запазиш
  час?” is withdrawn.
- Exit: availability inputs can be configured for every BusinessType; role and
  tenant tests pass without industry branches.

- The recurring StaffMember working-schedule backend slice in issue #13 is
  complete: V7, the independent versioned schedule aggregate, canonical
  `HH:mm`/one-minute-precision validation, atomic complete replacement,
  owner-only authorization and lifecycle enforcement, the approved
  Business/Membership/StaffMember lock order with deterministic race
  coverage, the authenticated GET/PUT HTTP API, and PostgreSQL-backed
  verification.

Completing issues #11, #12, and #13 does not complete this phase or parent
issue #10. The Business-owner Services, StaffMember, assignment, and schedule
interface was delivered by issue #14. The complete browser journey is
implemented by issue #15
(`docs/tasks/04e-business-configuration-e2e-verification.md`); the issue and
parent remain open until that work is reviewed and closed. Breaks remain pending;
Business closures, StaffMember time off, working overrides, and additional
working periods were delivered by issue #16 (see Phase 5).

## Phase 5 — availability engine

- Timezone-aware intervals, buffers, notice/window, overrides/absence,
  qualification, and deterministic no-preference assignment.
- Preserve the 30-day default while allowing each Business to configure how
  many days ahead Customers may book. Keep that booking window independent of
  daily and weekly administrative calendar views. Issue #16 currently fixes the
  window at 30 Business-local dates and the notice at two hours (ADR-0013,
  ADR-0016); Business configuration remains a follow-up.
- Issue #16 Phase 4 delivers the internal availability orchestration and the
  Scheduling-owned busy-interval seam with a temporary placeholder. The public
  endpoint, StaffMember-exposure decision, and the real busy-interval source
  (Booking) remain later work.
- Issue #16 Phases 5 and 6 deliver the Business-owner "Промени в графика"
  interface (human visually approved) and its Playwright journey, and complete
  the issue's implementation and acceptance record
  (`docs/tasks/05a-availability-and-schedule-exceptions.md`). The issue itself
  stays open until it is reviewed and closed. Business-configurable horizon,
  notice, grid and buffers, confirmed-appointment impact warnings, and a
  single-calendar date-range picker are recorded follow-ups.
- Generic public Business/Profile/Service APIs and Bulgarian UI through slot
  selection. Issue #17 delivers the read-only public Business profile first
  (Phase 1 decisions recorded in `docs/tasks/06a-public-business-profile.md`,
  ADR-0017 and ADR-0018; Phases 1, 2A, 2B and 3 committed, including the human-approved visual
  redesign; Phase 4 browser acceptance and documentation reconciliation complete, pending
  review and closure of the issue);
  the public availability endpoint, Service and StaffMember selection, and slot
  selection remain issue #18 work, decided and phased by Phase 1 of issue #18
  (`docs/tasks/08a-appointment-core-and-guest-booking.md`, ADR-0022 to ADR-0026) and not yet
  implemented.
- Exit: DST/boundary/cancelled-slot/assignment and BusinessType parity pass.

## Phase 6 — transactional booking and Customers

- Business-scoped Customer records are delivered first by issue #20 (decisions in
  `docs/tasks/07a-business-customer-records.md` and ADR-0019 to ADR-0021; `V10` Customer
  schema, conservative matching, and private owner administration; Phase 1 documentation and
  Phase 2 (shared contact policy, `V10` schema, domain model, and persistence) are complete,
  Phase 3 (conservative matching and the published `CustomerIdentification` and
  `CustomerReferenceAccess` contracts) and Phase 4 (the private owner-only administration backend)
  and Phase 5 (the Business-owner interface: list, body-based search, create, detail, and
  edit; human visual approval received) are committed; Phase 6, the browser E2E and documentation
  reconciliation, is committed, and Issue #20 is closed as completed; Customer appointment history and manual-appointment Customer selection
  remain with #21). Issues #18 (closed, now consuming the matching contract) and #21 are consumers, not
  prerequisites; they add Appointment, event, and cancellation-token migrations with
  `business_id` and `staff_member_id`; issue #18 decided that an Appointment keeps no Customer contact snapshot (ADR-0022).
- Add GiST exclusion on `staff_member_id`, transactional revalidation, online
  booking through the published Customer matching contract delivered by issue #20, automatic
  `CONFIRMED`, cancellation/late flag, audit, and Bulgarian 409 recovery.
- Issue #18 delivers this in eight phases (`docs/tasks/08a-appointment-core-and-guest-booking.md`;
  current state: Issue #18 is closed as completed (2026-10-14) and its Project item is Done. M1 to M7 are committed (the final #18 commit is `1d6b450`, whose CI passed: https://github.com/vasilmoskov/spot-your-slot/actions/runs/38519989453); M6 received explicit human visual approval. Recorded final local evidence: backend 3476 tests, frontend 76 files / 1841 tests, and two consecutive full browser E2E runs of 110/110 each. M7 (browser E2E, replacing the original Phase 8) carries the final acceptance matrix in task 08b): (1) decisions, ADRs, and documentation; (2) the `appointment` table created
  with its GiST exclusion in `V11`, domain and store, the real busy source, and removal of the placeholder;
  (3) the Business schedule revision guard (`V12`, the `business` contracts) and the schedule mutations'
  participation; (4) booking
  orchestration, contracts, Customer integration, retries, and idempotency; (5) the public API, security, and
  abuse protection; (6) the frontend; (7) rendered browser review and human visual approval (a stop gate);
  (8) browser E2E and final acceptance. Production public booking waits for the Business calendar (issue #21).
- Multi-Service booking (a visit of one to five Services) was decided on 2026-10-07 (ADR-0027, `docs/tasks/08b-multi-service-guest-booking-plan.md`) and is planned as seven separately approved phases M1 to M7
  (persistence `V13`; availability and eligibility; orchestration and fingerprinting; HTTP; frontend; rendered review; browser E2E). **Phases M1 (persistence, `V13`), M2 (availability and eligibility), M3 (orchestration and fingerprinting), M4 (the public HTTP contract), M5 (the frontend journey), M6 (the rendered review, explicitly approved), and M7 (browser E2E and the acceptance reconciliation) are implemented, verified, and committed; issue #18 is closed as completed, and production public booking still depends on issue #21**; the original Phase 8 of issue #18 is replaced by M7 and does not start before it.
- Amended by ADR-0022 (the earlier text said: "Use only `CONFIRMED`, `CANCELLED_BY_CUSTOMER`,
  `CANCELLED_BY_BUSINESS`, `COMPLETED`, and `NO_SHOW`."): use only `CONFIRMED` and `CANCELLED`; cancellation
  attribution, the late flag, and `COMPLETED`/`NO_SHOW` arrive with the issues that implement them.
- Exit: exactly one overlapping booking succeeds; cancellation releases time;
  no raw token is stored.

## Phase 7 — calendar and Appointment operations

Decided for issues #19 and #21 (ADR-0028, ADR-0029, `docs/tasks/09a-business-calendar-and-appointment-management.md`; Phases 1 to 7 are committed (Phase 6: the Business cancellation and internal-note edit API, `82edadd`; Phase 7: the read-only calendar, Appointment detail, and Customer history frontend, `6ea345f`); a discovered Phase 8 prerequisite, the backend manual-create read routes `GET /api/business/appointment-options` and `…/availability`, is implemented in the working tree and awaits review as its own change, before the Phase 8 form; Phases 8 to 12 are not started) *(superseded status text: the previous version said that Phase 7 awaited review)* *(superseded status text: the earlier version said that Phase 6 awaited review)*: twelve phases (decisions; `V14` and the persistence and availability foundations; the read APIs and measured `V15` indexes; manual multi-Service create; form reschedule and reassignment; cancellation and the internal note; the read-only, create, reschedule, and cancellation interfaces; the rendered human visual review, an explicit approval gate; browser E2E and reconciliation, which do not start until that approval is recorded). Only the Business owner has access, and drag-and-drop is a separate future issue.

- Responsive daily, weekly, and agenda Business calendar views, with owner creation (one to five Services), reschedule, reassignment, internal-note editing, cancellation, and a Customer appointment history. *(Amended; the earlier text said: "Responsive daily and weekly Business calendar views and staff creation/edit/reschedule/cancel/complete/no-show.")*
- Ensure both calendar views can navigate/query the configured booking horizon
  and do not impose a separate limit on future booking.
- Separate Customer and internal notes; a started Appointment is read-only except for the internal note; `DRAFT` and `SUSPENDED` are read-only. *(Amended; the earlier text said: "Reject completed/no-show before start; separate Customer and internal notes; enforce STAFF ownership and SUSPENDED read-only mode." Completed/no-show, STAFF ownership, and Manager access are future capabilities.)*
- Exit: operational, accessibility, role, and isolation flows pass.

## Phase 8 — reliable notifications

- Transactional outbox, delivery records, claiming, idempotency, bounded retry,
  redacted diagnostics, and Bulgarian invitation/account/Appointment/reminder
  templates behind `EmailService`.
- Keep local/test non-sending; Resend integration needs separate approval.
- Exit: email failure cannot roll back Appointments and retries do not duplicate.

## Phase 9 — end-to-end hardening

- Complete Playwright journeys, mobile/accessibility, rate limits, headers,
  errors/logging, query/index and module-boundary review, deterministic fixtures,
  troubleshooting docs, scans, and threat modeling.
- Exit: CI and generic MVP acceptance flows pass without high-severity issue.

## Phase 10 — production readiness/hosting (separate approval)

- Approve legal/privacy, retention, vendors/regions/budget, backups/recovery,
  monitoring/incident response, domain/trademark review, DNS, and secrets.
- Only then configure any proposed Cloudflare/Render/Resend resources and test
  migration, health, restore, rollback, and smoke flows.

## Deferred and excluded work

Possible separately approved work includes manual confirmation/PENDING, privacy
export/anonymization, slug redirects, and evidence-led controlled presentation.
Medical/health workflows, group capacity, resource scheduling, recurring
Customer Appointments, travel time, multiple locations, industry forms/workflows,
marketplace discovery, and every other MVP exclusion remain outside all phases.
BusinessType never creates separate engines, schemas, or deployments.

Never commit or push without explicit approval.
