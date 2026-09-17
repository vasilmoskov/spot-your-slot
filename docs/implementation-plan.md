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
- Generic Business settings, Services, StaffMembers without mandatory accounts,
  qualifications, weekly intervals, breaks, time off, and overrides.
- Enforce optional same-Business one-to-one StaffMember/Membership linkage with
  constraints/tests. Use “Екип” and “Член на екипа” in generic Bulgarian
  administration while preserving `StaffMember` internally; public booking uses
  contextual wording such as “При кого искаш да запазиш час?” and “Без
  предпочитание”.
- Exit: availability inputs can be configured for every BusinessType; role and
  tenant tests pass without industry branches.

Completing issue #11 does not complete this phase or parent issue #10. The
Business-owner Services UI, Staff management and Service assignments, schedules,
availability inputs, and the end-to-end Business configuration journey remain
pending.

## Phase 5 — availability engine

- Timezone-aware intervals, buffers, notice/window, overrides/absence,
  qualification, and deterministic no-preference assignment.
- Preserve the 30-day default while allowing each Business to configure how
  many days ahead Customers may book. Keep that booking window independent of
  daily and weekly administrative calendar views.
- Generic public Business/Profile/Service APIs and Bulgarian UI through slot
  selection.
- Exit: DST/boundary/cancelled-slot/assignment and BusinessType parity pass.

## Phase 6 — transactional booking and Customers

- Customer, Appointment/event, and cancellation-token migrations with
  `business_id` and `staff_member_id`.
- Add GiST exclusion on `staff_member_id`, transactional revalidation, online
  booking, conservative Business-scoped Customer matching, automatic
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
