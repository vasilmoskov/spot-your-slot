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
  contextual wording such as “При кого искаш да запазиш час?” and “Без
  предпочитание”.
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
  selection remain issue #18 work.
- Exit: DST/boundary/cancelled-slot/assignment and BusinessType parity pass.

## Phase 6 — transactional booking and Customers

- Business-scoped Customer records are delivered first by issue #20 (decisions in
  `docs/tasks/07a-business-customer-records.md` and ADR-0019 to ADR-0021; `V10` Customer
  schema, conservative matching, and private owner administration; Phase 1 documentation and
  Phase 2 (shared contact policy, `V10` schema, domain model, and persistence) are complete,
  Phase 3 (conservative matching and the published `CustomerIdentification` and
  `CustomerReferenceAccess` contracts) and Phase 4 (the private owner-only administration backend)
  are committed; Phase 5 (the Business-owner interface: list, body-based search, create, detail, and
  edit) is implemented and awaits human visual approval and review; the browser E2E is not started). Issues #18 and #21 are future consumers, not
  prerequisites; they add Appointment, event, and cancellation-token migrations with
  `business_id` and `staff_member_id` and decide any Appointment contact snapshot.
- Add GiST exclusion on `staff_member_id`, transactional revalidation, online
  booking through the published Customer matching contract delivered by issue #20, automatic
  `CONFIRMED`, cancellation/late flag, audit, and Bulgarian 409 recovery.
- Use only `CONFIRMED`, `CANCELLED_BY_CUSTOMER`, `CANCELLED_BY_BUSINESS`,
  `COMPLETED`, and `NO_SHOW`.
- Exit: exactly one overlapping booking succeeds; cancellation releases time;
  no raw token is stored.

## Phase 7 — calendar and Appointment operations

- Responsive daily and weekly Business calendar views and staff creation/edit/
  reschedule/cancel/complete/no-show.
- Ensure both calendar views can navigate/query the configured booking horizon
  and do not impose a separate limit on future booking.
- Reject completed/no-show before start; separate Customer and internal notes;
  enforce STAFF ownership and SUSPENDED read-only mode.
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
