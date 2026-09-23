# SpotYourSlot testing strategy

## Principles and toolchain

Tests are deterministic, risk-based, and layered. Inject clocks/randomness, use
stable fixtures and non-sending email, and use real PostgreSQL for database
semantics. Valid tests are fixed with the product, never weakened to pass.

Java compilation/tests run on Java 25 LTS. During bootstrap, official primary
documentation determines exact compatible stable Spring Boot/Maven versions,
Node.js LTS, React/TypeScript/Vite, and PostgreSQL. CI and future Docker images
pin those recorded versions and use committed lockfiles; no floating `latest`
tags or pre-release/experimental dependencies.

## Test layers

- JUnit unit tests: lifecycle transitions, roles, normalization, Customer
  matching, deadlines, deterministic StaffMember assignment, intervals, DST.
- API/slice tests: validation, Bulgarian errors, DTOs, authorization, CSRF/CORS.
- Spring Boot/Testcontainers PostgreSQL integration: Flyway, constraints,
  transactions, isolation, outbox, idempotency, concurrency; never substitute H2
  for PostgreSQL-specific behavior.
- Frontend component tests: forms/accessibility, role controls, errors, 409
  recovery, Bulgarian content.
- Playwright: critical public, Business, and platform journeys on mobile/desktop.
- Static gates: Maven, formatting, architecture rules, TypeScript/lint/build,
  dependency/secret/container scans when those artifacts exist.

## Mandatory high-risk tests

### Concurrent booking

Create an ACTIVE Business, qualified StaffMember, Service, schedule, and two
overlapping requests. Coordinate independent transactions against PostgreSQL;
assert exactly one success, one 409, one blocking Appointment, and a healthy
connection afterward. Cover identical/partial overlap, adjacent half-open
ranges, and rescheduling. The database constraint must key on
`staff_member_id`.

### Tenant isolation and workforce linkage

Create Business A and Business B with distinct Memberships/fixtures. For every
tenant-owned API, prove A cannot read, list, create against, update, cancel, or
indirectly reference B records. Include guessed IDs, StaffMember/Service/Customer
cross-links, bulk/list filtering, roles, inactive Membership, and platform-route
separation. Assert no B data leaks in responses/errors/audit visible to A.

Prove one StaffMember cannot link to multiple Memberships, one Membership cannot
link to multiple StaffMembers, cross-Business links fail, and StaffMembers
without accounts remain valid.

### Time, lifecycle, tokens, and notifications

Test Service plus buffer, breaks/time off/overrides, minimum-notice and
Business-configured booking-window boundaries (including the 30-day default),
cancelled-slot release, qualifications, no-preference ties, fixed clocks,
`Europe/Sofia` DST gaps/overlaps, UTC storage/display, and
`COMPLETED`/`NO_SHOW` rejection before start and acceptance at/after start.

Test invitation/reset/cancellation tokens for expiry, tamper, revocation, reuse,
hash-only storage, and races; sessions for rotation/invalidation; CSRF and exact
CORS; and enumeration-safe errors. Prove provider failure cannot roll back an
Appointment and retried/overlapping outbox/reminder jobs produce one logical
delivery with redacted failures.

### Generic BusinessType behavior

Parameterize representative booking flows across every BusinessType. Assert the
same schema, Services, StaffMembers, schedules, availability, Customers,
Appointments, roles, cancellation, and notification paths are used, with no
industry-specific engine or authorization branch.

## Critical end-to-end flows

1. PLATFORM_ADMIN creates a DRAFT Business with BusinessType and unique slug.
2. Owner consumes the invitation and configures Business, StaffMember, Service,
   qualification, and schedule.
3. DRAFT blocks public booking; activation enables it.
4. Guest selects a Service, answers “При кого искаш да запазиш час?” with a
   person or “Без предпочитание”, selects date/time, and books without an
   account.
5. Business calendars show the automatically CONFIRMED Appointment.
6. Guest cancels securely and the slot returns.
7. Staff create/edit/reschedule/cancel/complete/no-show within permission and
   time rules.
8. SUSPENDED shows a Bulgarian unavailable state, rejects booking, and permits
   read-only administration; PLATFORM_ADMIN can reactivate.

UI tests prove that daily and weekly administrative calendar views are only
presentation/query modes and do not limit booking up to the configured horizon.
Generic administration uses “Екип” and “Член на екипа”; internal fixtures and
technical APIs continue to use `StaffMember`.

## Accessibility, fixtures, and gates

Forms need visible programmatic labels, keyboard operation, focus/error
management, contrast, and no pointer-only essentials. Test small mobile and
desktop viewports, supported by—never replaced by—automated scanning.

Factories create deterministic Businesses of all BusinessTypes, users,
Memberships, Services, StaffMembers, schedules, Customers, and Appointments.
Integration suites start isolated PostgreSQL and migrate from empty. Fixtures
use fictional Bulgarian data and no production credentials/recipients.

CI runs backend static/unit/integration checks, frontend lint/type/unit/build,
and the implemented Playwright flows. Security scans remain planned. Before
release: migrate a clean database, run the full suite, inspect production
configuration, test approved backup restore, review security/privacy decisions,
and perform a Bulgarian mobile smoke test.

Phase 2 migrates PostgreSQL 18.4 Testcontainers from empty, verifies identity
schema constraints for `BUSINESS_OWNER`, `MANAGER`, and `STAFF`, and runs Spring
Modulith verification. Identity tests cover normalization, password/token/session
lifecycle, rate limits, cookie/CSRF/CORS behavior, authorization, and Business
A/B isolation using deterministic fixtures and test-only credentials.

Phase 3A adds deterministic domain/validation tests and PostgreSQL integration
coverage for Flyway V3/V4, structured-profile constraints, preservation of the
legacy address value, unique slugs, bounded ordering,
optimistic compare-and-update behavior, and coordinated same-version races with
no sleeps. The active-owner query tests inspect PostgreSQL lock/activity metadata
to prove a concurrent Membership deactivation waits for its `FOR SHARE` lock.
Platform orchestration tests prove activation ordering and same-Business owner
readiness. PostgreSQL-backed MockMvc tests cover all seven platform Business
routes, the role matrix, safe errors, CSRF, exact-origin credentialed CORS with
PUT, response privacy, and Phase 2 authentication regressions. Modulith tests
verify `platform → business` and `platform → identity` without reverse edges.

## Business Services backend verification

Issue #11 uses the pinned PostgreSQL Testcontainer across all persistence and
concurrency boundaries. Schema tests migrate V1 through V5 from empty and verify
the exact Service columns, constraints, generated normalized-name expression,
Unicode behavior, inactive-name reservation, restrictive Business ownership,
and approved index inventory. Domain and persistence tests compare Java
canonicalization with PostgreSQL, preserve exact accepted prices, enforce
tenant-scoped deterministic pagination, classify only the approved name
constraint, and coordinate version and normalized-name races without sleeps.

Application tests exercise the published Business lifecycle and identity
contracts through the Service administration entry point. They cover active
owner access, every nonqualifying role or Membership state, platform
administrator with and without a qualifying owner Membership, Business A/B
isolation, DRAFT/ACTIVE/SUSPENDED behavior, authoritative row mapping, fixed
Clock values, safe exceptions, and the Business-then-Membership shared-lock
order. Separate-transaction tests prove one winner for same-version updates and
prove suspension and Membership deactivation cannot be authorized from stale
state.

Controller and PostgreSQL-backed MockMvc tests cover all six authenticated
Service routes, exact request/result mapping, canonical-first validation,
pagination, CSRF for every mutation style, real session-selection behavior,
role and tenant boundaries, lifecycle conflicts, stale versions, stable
Bulgarian RFC 7807 responses, and generic sanitization of unexpected failures.
They also verify that client payloads and responses cannot supply or expose
Business identity. There is no Business-owner Services frontend component or
browser E2E coverage yet; issues #14 and #15 remain responsible for that user
journey.

## StaffMember and Service-assignment backend verification

Issue #12 extends the pinned PostgreSQL 18.4 Testcontainer schema through V6.
Schema and persistence tests verify canonical StaffMember fields, generated
ordering values, duplicate display names and contacts, default activity and
version, deterministic tenant pagination, composite ownership, restrictive
foreign keys, assignment uniqueness, exact indexes, and the absence of account
linkage or later schedule tables. V1 through V5 remain unchanged.

Application tests exercise the published Workforce administration boundary
against the Business lifecycle, identity owner-access, and Catalog Service-
reference contracts. They cover active and inactive StaffMember profile and
assignment administration, complete desired-set replacement, same-set version
increments, preservation across endpoint deactivation, active-Service checks
only for additions, retained and removed inactive Services, missing and foreign
references, DRAFT/ACTIVE mutations, SUSPENDED reads, and owner-only tenant
authorization.

Coordinated PostgreSQL tests use independent transactions and no sleeps to
prove the Business → Membership → StaffMember version guard → added-Service
lock order. Catalog locks additions with `FOR SHARE` in deterministic UUID
order. The tests cover competing StaffMember mutations, Service deactivation
against assignment addition, same-version assignment races, and rollback of the
version and relationships after validation or persistence failure. Assignment
listing runs at repeatable-read isolation and is tested as a consistent
aggregate snapshot with authoritative version and timestamps.

Controller, exception-handler, and PostgreSQL-backed MockMvc tests cover all
eight authenticated StaffMember routes, session-derived context, pagination,
request/response mapping, CSRF on every POST and PUT, role and tenant isolation,
lifecycle and optimistic conflicts, exact Bulgarian RFC 7807 errors, privacy,
and sanitized generic 500 behavior. Requests and responses cannot supply or
expose Business, user, Membership, credential, role, session, normalized, SQL,
or persistence data. Issues #14 and #15 still own the Business-owner interface
and browser journey; issue #12 adds no frontend or browser E2E coverage.

Phase 3 frontend component tests cover typed hash navigation, platform-only
navigation visibility, responsive Business listing and pagination, request
cancellation, structured-address creation and profile-edit payloads,
authoritative internal version updates, safe conflict recovery, read-only/edit
transitions, status-specific confirmed lifecycle actions, and the separate
owner-invitation flow. Identity tests cover new and existing User acceptance,
replacement-token invalidation, and single use. API-client tests verify exact
credentialed request paths and bodies through the shared CSRF-aware request
helper, including successful empty HTTP 202 responses. These
checks complement the rendered-browser verification below and do not replace
explicit human visual review.

## Browser E2E for Business onboarding and lifecycle

Issue #7 adds a Playwright layer above the existing MockMvc/PostgreSQL and
Vitest/jsdom layers. MockMvc integration tests verify HTTP, security, persistence,
and concurrency without a real browser. Vitest/jsdom tests verify React component
behavior and accessible DOM structure without running Spring Boot. Playwright
verifies the implemented journey through rendered React, the real Spring Boot
HTTP boundary, and PostgreSQL migrated from empty by Flyway.

The desktop journey signs in as the bootstrapped `PLATFORM_ADMIN`, creates and
inspects a deterministic fictional DRAFT Business, proves activation is blocked
before owner acceptance, sends and replaces the initial owner invitation, and
retrieves the replacement only through the protected development mailbox. A
separate signed-out browser context proves the replaced invitation is rejected.
Another isolated context accepts the replacement, signs in as the owner, renders
the owner Profile, and verifies the exact `BUSINESS_OWNER` association through
that context's authenticated session response. The original administrator
context then activates, suspends, and reactivates the Business, verifies each
rendered status and feedback message, and finishes at ACTIVE. A fourth signed-out
context proves the consumed invitation cannot be replayed.

After that lifecycle, a Pixel 7 Chromium context performs a focused mobile smoke
check. It verifies keyboard-operated responsive navigation, reaches the created
Business through the list, observes its final ACTIVE state, confirms the critical
lifecycle action remains usable, and checks for horizontal viewport overflow. It
does not repeat or depend on a separately ordered lifecycle project.

The runner uses the fixed Compose project `spotyourslot-e2e` and dedicated ports
`55432`, `18080`, and `15173`. Each run removes any prior disposable E2E project,
creates a fresh PostgreSQL volume, starts Spring Boot with the current Flyway
migrations and a generated local administrator credential, starts Vite against
that backend, runs serially, and removes the project and volume on exit. The data,
recipient addresses, and Business values are deterministic and fictional; raw
passwords and invitation URLs/tokens remain only in process memory.

Playwright screenshots, traces, videos, HTML/blob reports, and copied error
context are disabled. The reporter redacts generated credentials, invitation
tokens, and cookie values from failure text. Tests remove invitation query strings
from browser history after React consumes them, assert browser storage remains
empty, never inspect cookie values, and do not attach mailbox payloads. Generated
output paths are ignored and CI uploads no browser artifacts.

Locally, install Chromium once from `frontend` with
`./node_modules/.bin/playwright install chromium`, then run
`./scripts/run-e2e.sh` from the repository root. CI waits for the backend and
frontend verification jobs, installs locked npm dependencies plus pinned
Playwright Chromium and Linux dependencies, runs the same script, and always
performs validated cleanup of only the disposable E2E Compose project.
