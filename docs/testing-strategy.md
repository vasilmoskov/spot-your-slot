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
booking-window boundaries (the MVP currently fixes them at two hours and 30
Business-local dates per ADR-0013; Business-configured values, buffers, and
breaks are follow-ups),
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
browser E2E coverage at the time of issue #11; issue #14 added the component
tests and issue #15 the browser journey (see "Browser E2E for the Business
configuration journey").

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
or persistence data. Issue #12 adds no frontend or browser E2E coverage; the
Business-owner interface (issue #14) and browser journey (issue #15) were added
later.

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

## Recurring StaffMember working-schedule backend verification

Issue #13 extends the pinned PostgreSQL 18.4 Testcontainer schema through V7.
`WorkingScheduleSchemaIntegrationTests` migrates V1 through V7 from empty,
proves a schema held at V6 upgrades through V7 with all StaffMembers
backfilled at version 0 using StaffMember-derived timestamps, verifies split
and multiple-weekday periods persist in deterministic query order, and asserts
exact generated minute bounds, the latest representable `23:59` boundary,
rejection of `24:00` in any boundary position, adjacency, duplicates, partial
overlap, containment, equality, overnight periods, sub-minute values, invalid
weekdays, composite ownership, restrictive deletion, the installed
`btree_gist` extension and operator classes, and the approved column,
constraint, and index inventory. It confirms V7 introduces only the two new
recurring-schedule tables.

Domain and persistence tests (`StaffWorkingScheduleInputValidatorTests`,
`StaffWorkingScheduleServiceIntegrationTests`,
`StaffWorkingScheduleStoreIntegrationTests`) cover canonical-time and
one-minute-precision validation, duplicate/overlap/range/100-period-cap
rejection, atomic complete replacement, and independent schedule-version
increments including identical replacements.

Application tests (`StaffWorkingScheduleAdministrationServiceTests`,
`StaffWorkingScheduleAdministrationServiceIntegrationTests`) exercise the
published Workforce schedule-administration boundary against the Business
lifecycle and identity owner-access contracts: active owner access, every
nonqualifying role or Membership state, Business A/B isolation,
DRAFT/ACTIVE/SUSPENDED behavior, active and inactive StaffMember read/mutation
behavior, and safe exception mapping.

`StaffWorkingScheduleLockingIntegrationTests` coordinates independent
PostgreSQL transactions without sleeps to prove the Business → Membership →
StaffMember lock order, that same-version replacement races produce exactly
one winner and never a mixed period set, and that a schedule replacement and a
StaffMember deactivation race safely in either interleaving while preserving
data.

Controller and PostgreSQL-backed MockMvc tests
(`BusinessStaffWorkingScheduleControllerTests`,
`BusinessStaffWorkingScheduleApiIntegrationTests`) cover the GET and PUT
routes, session-derived context, canonical `HH:mm` request/response mapping
including rejection of the lenient `24:00` lexical wraparound, CSRF on PUT,
role and tenant isolation, lifecycle and optimistic conflicts, exact Bulgarian
RFC 7807 errors, and sanitized generic failures. Requests and responses cannot
supply or expose Business, user, Membership, role, session, or persistence
data. `ModuleBoundaryTests` continues to verify the complete module graph,
including `workforce`, remains acyclic. Issue #13 adds no frontend or browser
E2E coverage; exceptions, time off, breaks, overrides, and availability remain
future work.

## Schedule-exception persistence verification

Issue #16 Phase 2 tests run against PostgreSQL 18.4 through Testcontainers.
`ScheduleExceptionSchemaIntegrationTests` cover V1 through V9 migration order,
V1–V8 byte integrity, a V8-to-V9 upgrade with existing data, the exact
constraint and index set, immutable generated ranges, all four kinds, scope and
composite tenant foreign keys, full-day and partial shapes, minute precision,
rejected `24:00`, reversed, duplicate, and overlapping periods, allowed
adjacency, and the complete cross-aggregate overlap matrix.
`ScheduleExceptionStoreIntegrationTests` cover create/read/list, inclusive
window edges, deterministic ordering, atomic replace and delete, stale versions,
rollback preservation, timestamps and versions, cross-Business parity, and safe
persistence failures. `ScheduleExceptionStoreConcurrencyIntegrationTests` prove
conflicting inserts (first commits and first rolls back), same-version replace,
replace/delete, and a concurrent date-conflicting replacement using latches and
`pg_stat_activity` lock-wait observation, and a real opposite-order deadlock
reported as a retryable write conflict. `ScheduleExceptionContentTests` and
`ScheduleExceptionInputsTests` cover domain invariants and the translation to
engine inputs, including a precedence round trip through `AvailabilityEngine`.
No sleeps are used as concurrency proof.

## Schedule-exception administration backend verification

Issue #16 Phase 3 tests the private Business-owner API against PostgreSQL 18.4
through Testcontainers. `ScheduleExceptionInputValidatorTests` cover every
validation rule, including the exact accepted limit and first rejected value for
the date bounds, the 366-date span, the 24-period cap, and the 93-date list
window. `ScheduleExceptionAdministrationServiceTests` (mocked collaborators)
cover authentication, the read and mutation authorization paths, the
SUSPENDED and inactive-StaffMember rules, kind-specific StaffMember locking,
stored kind and StaffMember retention on replace, not-found versus concurrent
update, and persistence-failure translation.
`ScheduleExceptionAdministrationServiceIntegrationTests` exercise the published
contract against real PostgreSQL: all four kinds, deterministic list order and
inclusive edges, atomic period replacement, empty overrides, the cross-kind,
cross-StaffMember, adjacency, and duplicate-date overlap matrix, stale and
deleted aggregates, Business A/B isolation, the role matrix, and lifecycle rules.
`StaffMemberReferenceAccessIntegrationTests` cover the published Workforce
contract, including the mandatory caller transaction.

`ScheduleExceptionLockingIntegrationTests` prove the Business, Membership,
StaffMember, aggregate lock order with observing wrappers recording one backend
connection, that reads acquire no such lock, and, using latches and
`pg_stat_activity` lock-wait observation without sleeps, same-version replace
races, conflicting concurrent creates (first commits and first rolls back),
replace versus delete in both orders, Business suspension racing a mutation in
both orders, StaffMember deactivation racing a StaffMember-scoped mutation in
both orders, and an opposite-order deadlock whose victim maps to the retryable
conflict.

`BusinessScheduleExceptionControllerTests` and
`BusinessScheduleExceptionExceptionHandlerTests` cover status codes, `Location`,
request and response mapping, absence of `kind` and StaffMember from the replace
contract, strict date and time rejection, the exception-to-HTTP matrix, named
`fieldErrors`, and sanitized 500 responses.
`BusinessScheduleExceptionApiIntegrationTests` cover the full stack: all five
routes, session-derived context, unauthenticated and missing-selection outcomes,
the role and Membership matrix with `PLATFORM_ADMIN` alone, lifecycle,
cross-Business identifiers, ignored client Business identity, validation
boundaries, strict JSON, CSRF on POST, PUT, and DELETE, and privacy of
responses. `AuthenticationApiIntegrationTests` add the credentialed `DELETE`
CORS preflight while keeping the exact-origin, header, and method policy.
`ModuleBoundaryTests` verify the added `scheduling` edges remain acyclic. Phase 3
adds no frontend or browser E2E coverage.

## Availability orchestration verification

Issue #16 Phase 4 tests the internal orchestration
([ADR-0016](decisions/ADR-0016-orchestrate-availability-through-published-contracts-and-a-scheduling-owned-busy-interval-seam.md)).
`AvailabilityQueryServiceTests` use mocked published contracts and the real
`ScheduleExceptionInputs` and engine. They cover ACTIVE-only Business with the
missing, DRAFT, and SUSPENDED collapse; active-only Service; corrupt duration;
specific versus any StaffMember and the inactive, foreign, and unassigned
collapse; empty eligible set and empty schedule; overrides, additional periods,
closures, and time off; busy windows and every invalid busy-source output; the
single clock read; determinism; sanitized failures; and deep immutability.
`AvailabilityQueryServiceTimeTests` cover the exact notice boundary, the last
included and first excluded horizon dates, Business-local midnight, zone-rule
busy windows, a spring gap, and an autumn overlap with distinct instants and
offsets, deriving every transition from `ZoneRules`.
`AvailabilityQueryServiceIntegrationTests` assemble real PostgreSQL Business,
Service, assignment, StaffMember, recurring-schedule, and V9 exception data with
tenant isolation, inactive and removed-assignment cases, and one and ten
StaffMembers. `AvailabilityQueryStatementCountIntegrationTests` count application
SQL through a test-scoped `DataSource` proxy: four statements for one and for ten
StaffMembers, three when nobody is eligible. The same class covers the
transaction boundary with explicit `TransactionTemplate` cases: a standalone call
uses repeatable-read, a read-committed or default-isolation outer transaction
fails with a sanitized failure before any availability SQL, and repeatable-read
and serializable outer transactions are joined and succeed. `ServiceAvailabilityAccessIntegrationTests`
and `StaffAvailabilityAccessIntegrationTests` cover the published provider
contracts, including the mandatory caller transaction.
`BusyIntervalSourceFailFastTests` and `BusyIntervalSourceWiringIntegrationTests`
prove the placeholder is the single implementation and that a second one fails
startup. `AvailabilityModuleBoundaryTests` and `ModuleBoundaryTests` verify no
`scheduling` dependency on `booking` and no reverse edges.

## Schedule-changes interface verification

Issue #16 Phase 5 adds only frontend Vitest/Testing Library coverage for
"Промени в графика" (no backend or browser-automation change).
`navigation.test.ts` covers the four routes, canonical hrefs, and the normalization
of every unusable window. `presentation.test.ts` and `api.test.ts` cover labels,
dates, the Business-timezone date, window and period validation, and the exact
request shapes. `ScheduleExceptionList.test.tsx` covers the provisional-window
bootstrap (replace, one refetch, no loop), manual filtering (push), populated,
empty and failing lists, full StaffMember pagination, and stale-response discard.
`ScheduleExceptionCreate.test.tsx` covers the progressive fields of all four
kinds, the period dialog, inline validation policy, backend field errors,
duplicate-submit protection, and the unsaved-changes guard.
`ScheduleExceptionDetail.test.tsx` covers reading, editing with `expectedVersion`,
conflicts with guarded reload, the delete confirmation, and SUSPENDED and
inactive-StaffMember read-only behavior. `App.scheduleExceptions.test.tsx` covers
routing, tabs, role access, history, guarded sidebar/Back/logout, Business
switching, and the absence of browser-storage writes. A rendered-browser review
against a disposable database is a separate, human-approved checkpoint.

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

## Browser E2E for the Business configuration journey

Issue #15 extends the Playwright layer above with
`frontend/e2e/business-configuration-journey.spec.ts` and the shared
`frontend/e2e/support/` helpers, using the same isolated runner, Flyway
migrations V1–V9 from an empty database, and redaction, artifact, and cleanup
policy described above. It covers the journey delivered by issues #11–#14; it
does not repeat their validation, concurrency, or tenant-isolation matrices,
which remain in the backend PostgreSQL integration tests and the Vitest
component tests.

Each setup attempt provisions a DRAFT Business and its owner through the
administrator UI, the invitation flow, and the protected development mailbox,
with random slug and email suffixes so a worker restart cannot collide with
earlier data. The administrator, owner A, and owner B always use separate
browser contexts.

- **Desktop journey** — sole-Business selection and Services landing; Service
  create, edit, deactivate, reactivate, EUR price and duration presentation, and
  reload persistence; StaffMember create, edit, deactivate, reactivate, with
  optional email and telephone validation; assignment add, remove, and restore,
  with inactive Services not offered; the recurring weekly schedule with
  multiple weekdays, a split day, deterministic ordering, edit and removal,
  persistence after navigation and reload, and an inactive StaffMember's
  read-only schedule with a forced `STAFF_MEMBER_INACTIVE` rejection.
- **Unsaved changes** — one representative dirty Service form through the shared
  dialog (initial focus, continue, discard).
- **Lifecycle** — DRAFT and ACTIVE editing, SUSPENDED read-only rendering with
  forced mutations rejected as `BUSINESS_SUSPENDED`, Profile, password section,
  and logout still available, and reactivation restoring editing with all data.
- **Isolation and context** — Business B and owner B cannot read or mutate
  Business A entities by identifier; a `PLATFORM_ADMIN` without a Membership
  receives 403 from private configuration endpoints; the owner shell exposes no
  platform navigation; `localStorage`, `sessionStorage`, IndexedDB, and
  script-visible cookies hold no authentication or Business context; session
  cookies differ per context.
- **Mobile smoke** — a Pixel 7 context signs in as the owner, operates the
  navigation by keyboard, identifies the active Business, reaches Services,
  Staff, and Working Schedule, checks readable list and schedule presentation
  and the SUSPENDED banner, and asserts no horizontal overflow.
- **Focused negatives** — local Bulgarian Service validation and the backend's
  duplicate-name rejection.

Not asserted by design: visible schedule timezone text, the removed StaffMember
account explanation, and a DRAFT/ACTIVE status badge (intentionally removed in
issue #14). StaffMember/account separation is a backend invariant; see the task
record for the cited schema tests and the documented lack of a row-count test.

Troubleshooting: a serial-describe timeout restarts the Playwright worker and
re-runs file-level setup; because fixtures are unique per attempt, the first
reported failure is the real one. After an interrupted run, use the exact
cleanup command in `README.md`.
