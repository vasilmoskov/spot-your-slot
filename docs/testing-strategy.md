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

## Reliability, diagnosis, and verification reporting

This section is the authoritative source for how tests are written, how failures are
diagnosed, and how verification is reported. `AGENTS.md` and `CLAUDE.md` only point here.

### Writing reliable tests

- **Wait for the state being asserted.** An asynchronous UI test waits, with Testing Library's
  asynchronous queries or Playwright's web-first assertions, for the observable state it asserts
  next. An earlier success message, route change, or request does not prove that the destination
  has finished loading or that the data it shows has arrived.
- **Control time.** A test that depends on a date or the time of day uses an injected or fake
  clock, or an explicit test date. It never relies on a calendar date whose meaning changes as
  real time passes, a machine time zone, or the day of the week it happens to run.
- **Order overlapping work deterministically.** Tests for overlapping requests, session updates,
  and navigation use deferred promises (a promise the test resolves or rejects) to exercise each
  relevant completion order, including a result that arrives after the user has moved on. They
  do not depend on timing, load, or the scheduler.
- **Prefer deterministic regression coverage.** A bug found in CI is covered by a test that fails
  deterministically before the fix and passes after it. Repeated or stress runs (many runs, parallel
  load) are targeted diagnostic tools for reproducing a suspected race, not required checks for
  every change.

- **Settle browser audits before asserting or closing.** A test that audits the page's network
  traffic (for example the public profile's "no cookie was sent") collects every asynchronous
  read (`request.allHeaders()`), stops listening, and awaits all of them before it asserts and
  before it navigates on or closes the context. A read that fails (a closed page) is a thrown
  failure, never a default of "no cookie"; a request the page itself aborted is dropped explicitly.
  `recordApiRequests(page).stop()` in `frontend/e2e/support/publicProfile.ts` does this, and
  `frontend/e2e/request-audit.spec.ts` proves it with a fake page and deferred reads.

### Diagnosing a CI failure

Investigate before calling a failure flaky. Read the failure log and the DOM or assertion output
it contains, reproduce it locally where possible (including under load), and classify it:

- a **test synchronization defect** (the test asserts before the state exists);
- a **date or environment assumption** (clock, time zone, locale, runtime version, parallelism);
- a **production race or defect** (the application misbehaves for some completion order).

Fix the classified cause. Do not "fix" a failure only by increasing a timeout, adding a sleep,
weakening an assertion, skipping the test, or rerunning until it passes. A timeout change
requires evidence that the existing ceiling is inappropriate for the work being awaited, not
merely that the test is slow when it fails. A failure that is not understood is reported as such.

### Verification and reporting

- Run the required checks against the final code before handing it over for commit. Report the
  tested `HEAD` and whether uncommitted changes were included. A later code change requires the
  affected checks to be run again; documentation-only edits do not require another full run.
- A production navigation or session fix is verified by the deterministic regression tests and,
  in addition, by the relevant existing browser journey. A new browser test is added only when it
  covers behavior no deterministic test can.
- Report local verification and CI verification separately. A green CI run approves only the exact
  commit it checked.

## Mandatory high-risk tests

### Concurrent booking

Create an ACTIVE Business, qualified StaffMember, Service, schedule, and two
overlapping requests. Coordinate independent transactions against PostgreSQL;
assert exactly one success, one 409, one blocking Appointment, and a healthy
connection afterward. Cover identical/partial overlap, adjacent half-open
ranges, and rescheduling. The database constraint must key on
`staff_member_id`. The issue #18 verification (planned by phase, not yet executed) is in "Appointment and
guest booking verification" below.

### Tenant isolation and workforce linkage

Create Business A and Business B with distinct Memberships/fixtures. For every
tenant-owned API, prove A cannot read, list, create against, update, cancel, or
indirectly reference B records. Include guessed IDs, StaffMember/Service/Customer
cross-links, bulk/list filtering, roles, inactive Membership, and platform-route
separation. Assert no B data leaks in responses/errors/audit visible to A.

Prove one StaffMember cannot link to multiple Memberships, one Membership cannot
link to multiple StaffMembers, cross-Business links fail, and StaffMembers
without accounts remain valid.

### Customer identity, uniqueness, and privacy (issue #20)

Against real PostgreSQL, cover: per-Business uniqueness of normalized phone and email, NULL
multiplicity, the same identifier at another Business, and each canonical check; races with
deterministic coordination and no sleeps (two creates with the same phone, two with the same
email, two updates claiming one identifier, a stale version, find-or-create races, and an
outer-transaction rollback preserving the previous record); the complete find-or-create truth
table in `docs/tasks/07a-business-customer-records.md`, including that a partial match is an
`IdentityConflict`, nothing is written, and the Customer is never merged or changed;
behavior under `READ_COMMITTED`; serialization (`40001`) and deadlock (`40P01`) failures
producing the sanitized typed `CustomerConcurrentConflict`, the caller transaction rolling
back with no Customer or partial consumer write remaining, and a completely new outer
transaction retrying successfully, while ordinary identical-create races still resolve to one
created and one existing Customer without a duplicate; Business A/B isolation, guessed IDs, and `PLATFORM_ADMIN` alone; DRAFT,
ACTIVE, and SUSPENDED behavior; golden vectors shared by JUnit and Vitest for phone and email
canonicalization; and privacy using unique sentinel values that must never appear in public
responses, errors, captured logs, URLs, browser storage, test names, failure output, or review
archives. Fixtures are generated at run time with synthetic data.

### Customer persistence verification (issue #20 Phase 2)

Phase 2 tests the shared contact policy, the Customer domain, `V10`, and `CustomerStore` against
PostgreSQL 18.4 through Testcontainers (details and counts in
`docs/tasks/07a-business-customer-records.md`). `ContactPolicyVectorTests` (backend) and
`contactPolicy.test.ts` (frontend) run the single golden-vector file
`shared-test-data/contact-policy-vectors.json`; `ContactEmailPolicyTests`,
`ContactPhoneNumbersTests`, and a Jakarta characterization test cover every rule directly.
`CustomerSchemaIntegrationTests` cover V1–V10 order, V1–V9 byte integrity, a V9-to-V10 upgrade,
exact columns, constraints, definitions, and indexes, the generated normalized-name expression and its
agreement with Java, every constraint, same-Business uniqueness, cross-Business reuse, NULL
distinctness, and the 200/201-character boundary. `CustomerStoreIntegrationTests` and
`CustomerStoreFailureTranslationTests` cover every store operation, tenant isolation, versioned
updates, rollback preservation, single-statement and no-lock evidence, and structural, sanitized
failure translation. `CustomerModuleBoundaryTests` pin the module dependencies and the `shared::contact` named interface. The races belong to Phase 3.

### Customer matching verification (issue #20 Phase 3)

`CustomerIdentificationServiceTests` (unit, mocked store) cover every truth-table row, all invalid-field
combinations and the immutable field set, no SQL, clock, or UUID work for invalid, existing, or
conflicting input, canonicalization before lookup, the bounded re-read, the failure mapping, and
redaction. `CustomerIdentificationIntegrationTests` (PostgreSQL) cover the rows with unchanged matched
Customers, same-Business isolation, the caller-owned transaction, `READ_COMMITTED` and stronger
isolation, rollback and rollback-only behavior, and the statement bounds (0, 1, 1, 2, and 3 for a race
loser, counted by a recording `DataSource` that records only SQL naming the `customer` table).
`CustomerIdentificationConcurrencyIntegrationTests` cover the creation races without sleeps (latches
plus `pg_stat_activity` lock-wait evidence), a real `40001` from a `REPEATABLE_READ` snapshot, a real
`40P01` from opposite-order inserts, rollback with no leftover Customer or probe row, and a retry in a
new transaction. A test-only consumer (`CustomerConsumerProbe`) uses only the published contracts and
writes into a test-created probe table that is never part of Flyway. `CustomerModuleBoundaryTests` also
pin the published root-package types, their dependencies, the consumer's use of the root package only,
the absence of any Booking module, and the absence of logging.

### Customer administration frontend verification (issue #20 Phase 5)

Vitest and Testing Library only (no Playwright, which is Phase 6): the shared contact golden vectors run
against the Customer form rules; route parsing, normalization, and round trips; list requests (GET versus the
POST body), sorting in both directions, sizes, partial and out-of-range pages, shared pagination, and cards;
search (explicit submit, trim, the 100 code point boundary, clear, blank, pending, stale and aborted
responses, term absent from the URL, both storages, cookie, and title); recovery of an empty later page
(`total` above and equal to 0) by history replacement to the last valid page or page 0 with the size, sort,
direction, and search kept, no pushed entry, and no loop; create and edit validation,
`fieldErrors` mapping, duplicate conflicts, contact removal, stale-version guarded reload, safe generic errors,
and every unsaved-changes exit; DRAFT, ACTIVE, and SUSPENDED behavior including a mutation that reveals the
suspension; Business switching; access by role; and durable CSS rules in `ui/layoutRules.test.ts`. Rendered
review used a disposable PostgreSQL container and synthetic fixtures created through the APIs. Human visual
approval is separate and outstanding.

### Navigation, Business selection, live search, and contextual SUSPENDED wording (issue #20 Phase 5 correction)

Vitest only: `ShellNavigation` through both shells (global links without a Business, a separate group headed by the
selected Business, no technical role value, the user and not the Business above Logout, a second link for an
administrator who manages Businesses, mobile focus and Escape); `BusinessSelection` and `App.businessSelection`
(landing, the list of managed Businesses with lifecycle wording, `Покажи` buttons on heading-named cards, a
checkmark and `aria-current` instead of an `Избран` badge, selecting DRAFT, ACTIVE, and
SUSPENDED, no Customer data across Businesses, refresh through the session, return to the selection when the
Business is unavailable, no selector in the Profile, guard around Business context); live search with fake timers
and no sleeps (no request before the debounce, one request for the final value, deletion and clearing, page reset,
preserved size, sort, and direction, aborted and stale responses, previous result kept busy, term absent from
URL and storage, catalog-empty versus no-results, stale-page recovery with and without a term); the Customer
SUSPENDED notice exactly once and no "configuration" wording; and the guard focus fallback. The rendered review
(Playwright Chromium, disposable stack) also measured real requests during typing.

### Business-context recovery and single lifecycle notice (issue #20 Phase 5, second correction)

`App.contextRecovery.test.tsx` mocks only the network helper, so the real feature API modules, their error handling, and
the application's session recovery run: recovery from every Business-scoped screen (Services, Staff and assignments,
Working Hours, Schedule Changes, Customers), one refresh for simultaneous failures, replace-not-push, no loop when the
session still carries the Business, the guard around a dirty form, late old-Business responses, the outcomes that must
not clear the context (missing record, `ACCESS_DENIED`, SUSPENDED, `401`, `401` during the refresh, network failure),
one notice per SUSPENDED screen, and the approved owner and administrator labels. A real Membership revocation has no
supported API, so the browser check intercepted the two server answers in the browser only (documented as a
simulation).

### SUSPENDED Customer update exception (issue #20 Phase 5, third correction)

`BusinessCustomerSuspendedApiIntegrationTests` (PostgreSQL): an owner updates an existing Customer in SUSPENDED with the
canonical profile stored; creation (phone-only, email-only, both) returns `BUSINESS_SUSPENDED` and creates no row; DRAFT and
ACTIVE behave as before; the optimistic version, validation, and contact uniqueness still apply and leave the row unchanged;
a foreign or unknown ID is the same safe `CUSTOMER_NOT_FOUND`; `MANAGER`, `STAFF`, inactive, unselected, and platform-only
callers are rejected before any update; and Service and StaffMember creation in SUSPENDED still return `BUSINESS_SUSPENDED`.
`CustomerAdministrationServiceTests` pin the lock order and checks of a suspended update, and
`CustomerAdministrationLockingIntegrationTests` prove with a real lock wait that an update queued behind a committing
suspension still succeeds while a create is rejected. `BusinessCustomerReadApiIntegrationTests` add the surname and
middle-fragment search regression. The published matching tests are unchanged and green.

### Customer browser E2E (issue #20 Phase 6)

`frontend/e2e/customer-administration-journey.spec.ts` (12), `customer-lifecycle-isolation.spec.ts` (5), and
`customer-mobile-smoke.spec.ts` (3, an actual Pixel 7 context) run on the existing disposable stack
(`./scripts/run-e2e.sh`) with run-time synthetic fixtures, a unique slug and owner email per provisioning, and data created only
through the supported APIs and the invitation flow. They cover Business selection and context, creation and editing with canonical
contacts, duplicates, live search (observable polling and responses; no sleeps and no debounce-timing claim), sorting and server-side
pagination with an independently computed order, detail and Back, stale-page recovery through supported updates, the unsaved-changes
guard, the Business lifecycle (SUSPENDED edit but no creation, restored in `finally`), a version conflict, tenant isolation,
Platform Administrator and anonymous rejection, and privacy (URL, storage, cookies, title, request URLs and headers, public surfaces).
Failure messages compare booleans, never Customer bodies. Browser tests do not prove that no account, Membership, or Appointment row
is created: that rests on `BusinessCustomerMutationApiIntegrationTests.createNeverUsesTheOwnersContactOrCreatesAnyAccountRecord`, and
conservative matching and the Booking seam rest on the `CustomerIdentification*Tests`. No request-interception simulation was added; the
only intercepted lost-context evidence is the Phase 5 developer review, labeled as a simulation. The existing journeys were updated to
the redesigned navigation (owner links, first mobile menu link, the single SUSPENDED notice) without weakening their assertions.
Executed 2026-10-06: backend 2,229 tests, frontend 1,440 tests in 61 files, two consecutive full E2E runs of 74 passing tests each.
The acceptance matrix is in `docs/tasks/07a-business-customer-records.md`.

### Customer administration verification (issue #20 Phase 4)

`BusinessCustomerAuthorizationApiIntegrationTests` (MockMvc and PostgreSQL) cover authentication, the
absent selection, `MANAGER` and `STAFF`, platform-only, inactive, missing, and foreign Memberships,
Business A/B isolation, byte-identical 404 bodies for foreign and unknown IDs, an ignored client
`businessId`, the DRAFT, ACTIVE, and SUSPENDED matrix, CSRF on every POST and PUT, and an enumeration of
every Spring MVC mapping (exactly five Customer endpoints, none public).
`BusinessCustomerReadApiIntegrationTests` cover the exact key allowlists, `no-store`, the allowed sizes,
invalid paging, sort, and direction, every sort in both directions, stable pages of identical names,
empty and partial pages, the search matrix (name, email, phone exact and prefix, wildcard and malformed
terms, the length limit, a malformed body), the absence of the term from the URI, and a constant two
Customer statements whatever the page or result size.
`BusinessCustomerMutationApiIntegrationTests` cover canonical create and update, validation of every
field, the shared golden vectors through the API, duplicate phone and email (alone and together),
cross-Business reuse, no account side effect, version and timestamp, stale versions, contact removal
rules, explicit two-step identifier moves, and four concurrent races decided by the database.
`BusinessCustomerPrivacyApiIntegrationTests` cover sentinel values in Problem Details and DEBUG logs,
the fixed `instance`, injected persistence failures (`XX000`, `40001`, `40P01`, unknown Business, invalid
data) with a rollback proved after a real `UPDATE`, and the public profile. Unit tests:
`CustomerSearchCriteriaTests`, `CustomerInputValidatorTests`, `CustomerAdministrationServiceTests` (lock
order, translation, hygiene), and `BusinessCustomerExceptionHandlerTests`. Store and database:
`CustomerStoreListIntegrationTests` (ordering, tie-breakers, paging, search, scoping, statement counts),
`CustomerAdministrationLockingIntegrationTests` (a mutation blocked behind a Business or Membership row
lock, then decided by the committed state; reads unblocked), and
`CustomerSearchExplainIntegrationTests` (`EXPLAIN ANALYZE` of the production SQL at about 10,000 rows).
`CustomerModuleBoundaryTests` pin the approved dependencies on `shared`, `identity`, and `business`,
the layer directions, the single private controller, and the unchanged published root types.

### Time, lifecycle, tokens, and notifications

Test Service plus buffer, breaks/time off/overrides, minimum-notice and
booking-window boundaries (the MVP currently fixes them at two hours and 30
Business-local dates per ADR-0013; Business-configured values, buffers, and
breaks are follow-ups),
cancelled-slot release, qualifications, no-preference ties, fixed clocks,
`Europe/Sofia` DST gaps/overlaps, UTC storage/display, and, once those statuses exist (deferred by ADR-0022; the
MVP statuses are `CONFIRMED` and `CANCELLED`), `COMPLETED`/`NO_SHOW` rejection before start and acceptance at/after
start.

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
4. Guest passes «Избор на услуга», «Избор на служител» (a person or “Без
   предпочитание”), «Дата и час», «Вашите данни», and «Преглед и потвърждение», and
   books without an account.
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

Issue #16 Phase 5 added only frontend Vitest/Testing Library coverage for
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
against a disposable database was a separate checkpoint; it received explicit
human visual approval. Browser automation for the same journey was added in
Phase 6 (see "Browser E2E for the Business schedule-changes journey").

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
all current Flyway migrations (V1–V10) from an empty database, and redaction, artifact, and cleanup
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

## Reserved public roots and stable slug verification

Issue #17 Phase 2A adds backend and frontend tests for the reserved-root and
stable-slug rules (ADR-0018). Browser coverage of public routing for reserved roots is in
"Browser E2E for the public Business profile" below; the stable-slug rules themselves stay
proven by the backend and component tests.

- **Definition** — `ReservedBusinessSlugsTests` pins the exact 19 values, canonical
  matching, near-misses (`booking-studio`, `my-book`, `appointments-bg`) and
  immutability; `reservedSlugs.test.ts` pins the frontend mirror to the same values,
  because a filesystem-coupled parity check would be brittle.
- **Validation and service** — `BusinessInputValidatorTests` and
  `BusinessAdministrationServiceIntegrationTests` (PostgreSQL) cover every reserved
  value on create and DRAFT update, unchanged grandfathered DRAFT slug, activation
  rejection, ACTIVE and SUSPENDED rejection and unchanged-slug updates, and that
  uniqueness, immutability, reserved and version failures stay distinct.
- **Race** — activation holding the Business row lock while a slug change waits, and
  the reverse, are proven with a `pg_stat_activity` lock-wait observation before
  release (no sleeps), plus an unsynchronized concurrent run asserting that no ACTIVE
  Business ever carries a changed slug.
- **API contract** — `PlatformBusinessApiIntegrationTests` and
  `PlatformBusinessExceptionHandlerTests` assert statuses, codes, Bulgarian details,
  the single `fieldErrors.slug`, no reserved-list disclosure, and no SQL, constraint,
  identifier or submitted value in errors.
- **Form** — `BusinessForm`, `BusinessCreate` and `BusinessDetail` component tests
  cover inline errors, focus, backend field-error mapping without a generic alert, the
  read-only ACTIVE and SUSPENDED slug and its note, other fields staying editable, the
  activation guidance, and the unchanged unsaved-changes behavior.
- **Rendered review** — on a disposable stack (own PostgreSQL container, backend and
  Vite on non-standard ports; fixtures created through the supported APIs) the platform
  Business form was inspected as DRAFT, ACTIVE and SUSPENDED at 1280px, 640px (the CSS
  width of a 200% zoom) and 375px. It found and fixed a pre-existing horizontal overflow
  caused by a 100-character slug or long name in the detail header and section summary.
  Human visual approval remains a separate gate.

## Public Business profile backend verification

Issue #17 Phase 2B tests the unauthenticated `GET /api/public/businesses/{slug}` contract
(ADR-0017) on PostgreSQL 18.4 through Testcontainers. Browser acceptance is recorded under
"Browser E2E for the public Business profile".

- **Contract and privacy** — `PublicProfileApiIntegrationTests` drives the complete servlet and
  security chain: exact key sets for the profile, address and Service, values and EUR price
  scale, canonical slug, active-only and deterministic order, another Business absent, `null` for
  absent optionals, an empty list for no active Services, identical repeated responses, and an
  identical representation for anonymous, PLATFORM_ADMIN and owner callers without a cookie. A
  sentinel test populates the contact email, timezone, version, timestamps, StaffMember name,
  contact and schedule, Membership and identifiers, and asserts none appears.
- **Lifecycle collapse** — unknown, DRAFT, SUSPENDED, former, malformed, over-length, and reserved
  slugs (including a grandfathered ACTIVE reserved slug) return one byte-equivalent 404 with no
  slug, status, identifier, SQL or exception text.
- **Security** — anonymous access only to the one route; neighbouring private routes,
  `/api/public/businesses/`, deeper paths and trailing slashes stay 401; every non-GET verb is
  refused (401/403, never 500); firewall-rejected paths return an empty 400; CORS is the unchanged
  exact-origin policy; viewing creates no business, service, membership, user, session or
  invitation row.
- **Transaction and cost** — `PublicProfileTransactionIntegrationTests` counts statements with the
  DataSource-proxy approach used for availability: two for an ACTIVE Business with one, 25, or no
  Services, one for an unavailable slug, none for a malformed or reserved slug; asserts explicit
  public columns, no `SELECT *`, no write or lock keyword, `REPEATABLE_READ` and read-only on the
  connection, failure before any SQL inside a READ_COMMITTED or default-isolation transaction,
  success inside repeatable-read or serializable, MANDATORY behavior of both published contracts,
  and, with a change committed on another connection between the two reads, a snapshot-consistent
  response. There are no sleeps.
- **Unit and boundary** — `PublicBusinessProfileAccessServiceTests`,
  `PublicServiceAccessServiceTests`, `PublicProfileControllerTests`,
  `PublicProfileModuleBoundaryTests` (`publicprofile → business, catalog` only, no reverse
  dependency), and the existing module-boundary tests.

## Browser E2E for the Business schedule-changes journey

Issue #16 Phase 6 adds `frontend/e2e/schedule-changes-journey.spec.ts` and
`frontend/e2e/support/scheduleChanges.ts` to the Playwright layer, using the same
isolated runner, all current migrations (V1–V10), redaction, artifact and cleanup policy. The
spec provisions its own DRAFT Business and owner (administrator UI, invitation
flow, protected mailbox, random slug and email suffix) and a second Business for
isolation; the administrator, both owners and the mobile session use separate
browser contexts. Each test seeds the records it needs through the private API,
uses a fresh owner page, and deletes its records in `finally`; nothing depends on
another test except that the Business starts DRAFT and ends ACTIVE (every test
works in either state, and the lifecycle test restores what it changes). Waits are
on visible UI, HTTP responses or route state, never fixed sleeps. Business-local
dates are computed for `Europe/Sofia`, not from the machine zone. API helpers
return only status, the public problem code and the public response text, never
cookies or CSRF values.

- **Navigation and list state** — one page heading and the two tabs; the
  provisional window is replaced by the canonical Business-local window, page 0,
  size 10, dates ascending; with 27 records exactly ten appear on the first page,
  Next and Previous, the range summary, kind sorting resetting to page 0, page-size
  change, and detail → "Обратно към графика" restoring window, page, size, sort and
  direction, each asserted on the visible rows as well as the route.
- **Derived status** — `Минала`, `В сила`, `Предстояща` from the Business-local
  date, sorting by status, and a past record surviving a reload (no automatic
  deletion).
- **CRUD and terminology** — a Business-wide non-working period and a
  StaffMember absence with adjacent periods, persisted presentation after reload,
  edit, and detail-only delete with the confirmation focusing "Отказ" first; no
  internal word or enum name is shown.
- **Conflict and validation** — the kind-specific overlap message for a closure and
  for additional hours, entered values preserved, adjacent dates accepted, and the
  precise overlap and duplicate period messages in the dialog.
- **Lifecycle** — DRAFT/ACTIVE mutation; SUSPENDED keeps records readable, shows the
  shared banner once, hides mutation controls and rejects forced create, replace
  and delete with `BUSINESS_SUSPENDED`; reactivation restores editing.
- **Authorization** — another Business owner reads an empty list and receives
  `SCHEDULE_EXCEPTION_NOT_FOUND` or `STAFF_MEMBER_NOT_FOUND` without leakage; a
  `PLATFORM_ADMIN` without a Membership receives 403 and no navigation.
- **Mobile smoke** — Pixel 7: tabs, cards, sorting control, pagination, create
  form, one confirmation dialog, and no horizontal overflow.

Evidence boundaries: the browser proves the interface and the private HTTP
boundary only. Precedence, half-open and adjacency behavior, the 15-minute grid,
notice, horizon, DST, deterministic aggregation, optimistic versioning and lock
order are proven by the backend unit and PostgreSQL integration tests listed
above, and no browser test claims them. No public availability endpoint exists, so
no browser test proves slot availability, and nothing reserves time. The automated
mobile smoke does not replace the completed human visual approval.

## Public Business page frontend verification

Issue #17 Phase 3 tests the unauthenticated React page with Vitest and jsdom; browser acceptance
is Phase 4 ("Browser E2E for the public Business profile"). Fetch is stubbed or the API module mocked with promises the test settles,
so races are deterministic and use no timers.

- **Routing** — `route.test.ts` and `AppRoot.test.tsx`: exact `/{slug}`, uppercase and trailing-slash
  canonicalization by `replaceState` (never `pushState`), 100/101-character slugs, invalid,
  percent-encoded, deeper and reserved paths (every one of the 19 roots), valid slugs that contain
  a reserved word, hash routes staying with administration, refresh keeping the Business, history
  navigation between two slugs, the existing identity paths, and that a public path sends no
  `/api/auth/*` request.
- **API client** — `api.test.ts`: URL encoding, `credentials: 'omit'`, no body or CSRF header, exact
  field decoding (extra properties dropped), the unavailable answer, malformed and unexpected
  bodies, network failure and abort.
- **Page** — `PublicBusinessPage.test.tsx`: loading, the hero with identity, contacts and booking panel, Services as standalone
  items, one `h1` and heading order, focus, missing and partial contacts, plain-text phone, unsafe phone text, empty and
  many Services in backend order, text-not-markup, unknown Business type, unavailable and failure
  states, exactly one request per retry, stale/aborted responses of a changed slug or attempt,
  unmount, metadata for every state and its restoration, and no storage or cookie write.
- **Shared and platform** — `businessType.test.ts` (one mapping, unknown value), the platform form
  and detail tests (neither retired public-visibility sentence, empty note container, group or
  dangling `aria-describedby` in create, DRAFT, ACTIVE and SUSPENDED; labels, no visibility
  controls, dirty tracking unchanged), and CSS guards in `layoutRules.test.ts` for
  the durable public layout rules jsdom cannot evaluate.

Executed: focused selection 14 files, 264 tests; full frontend suite 50 files, 1004 tests.
Rendered developer review at 1280, 1024, 800, 640, 412 and 375 px is recorded in
`docs/tasks/06a-public-business-profile.md`; it is not the human visual approval.

## Browser E2E for the public Business profile

Issue #17 Phase 4 adds `frontend/e2e/public-business-profile.spec.ts` and
`frontend/e2e/support/publicProfile.ts` to the Playwright layer, on the same isolated runner,
all current Flyway migrations (V1–V10) from an empty database and redaction, artifact and cleanup policy as above.
It reuses `provisioning.ts`, `browser.ts` and the mailbox helper; it adds only public-page helpers
(API reads through a request context, head metadata, request audit, layout assertions).

Fixtures are created only through supported APIs and the invitation UI, with a random suffix per
setup attempt: a full ACTIVE Business (description, telephone, structured address, three active and
one inactive Service), a minimal ACTIVE Business, an ACTIVE Business with no active Services, a
SUSPENDED Business, a DRAFT Business (its slug is changed once, which is allowed for a DRAFT, to
obtain a former slug), a second ACTIVE Business with another owner, and a long-content Business
(200-character name, near-2000-character description, ten long Services). The two scenarios that
mutate data provision their own Business and restore what they change. Visitors, owners and the
Platform Administrator always use separate browser contexts.

- **Access and allowlist** — direct `/{slug}` access, reload and a fresh context; the visible
  allowlist (name, Bulgarian type label, description, `tel:` link, address, Service name,
  description, duration, EUR price, booking-unavailable notice); the inactive Service absent; the
  JSON key set of the response checked key for key, and the raw body checked for identifiers,
  contact email, owner data, status, version and timestamps; minimal and empty-catalog pages.
- **Lifecycle and isolation** — DRAFT, SUSPENDED, unknown, former-slug and reserved-word slugs give
  an identical page (same markup, title, `noindex`, no canonical) and an identical 404 body;
  two Businesses never show each other's data; cross-Business identifiers and deeper API paths give
  only the safe result; an owner and an administrator receive byte-identical public responses.
- **Changes** — an administrator's profile edit and an owner's Service edit and deactivation appear
  publicly only after the successful save.
- **Routing and metadata** — uppercase and trailing-slash canonicalization by history replacement
  (history length unchanged, Back leaves the page), Back/Forward between Businesses and the
  administration application, deeper paths, reserved roots and hash routes staying with the
  authenticated application; title, canonical, description and fallback, `noindex` states, and no
  metadata leaking across a traversal of two Businesses and an unavailable page in one document.
- **Failure and retry** — a forced network failure and server error through `page.route` (no
  production change), keyboard retry sending one request per activation, and no stale Business
  while another loads or fails.
- **Responsive and accessibility** — desktop 1280px, Pixel 7 device emulation and 640px (the CSS
  width of a 200% zoom): one `h1`, no heading level skipped, no horizontal overflow, no clipped
  telephone, address, price or duration, real Tab focus on the telephone with a visible focus
  ring, text labels for every fact, and usable unavailable and failure states.
- **Administration boundary** — administration, the Business form without the retired notes, the
  owner shell and hash routes still work after a public visit; a public visit creates no cookie,
  storage entry or `/api/auth/*` request.

Evidence boundary: no safe public endpoint counts Customers, Memberships or Appointments, so the
claim that a public read creates none is proven by the backend integration tests
(`PublicProfileApiIntegrationTests`), while the browser tests prove the observable part (only the
public GET, sent without cookies, and no session or storage state). The Vite development server
renders under React `StrictMode`, which can send the first public read twice with the first
aborted by the page; the request audit ignores client-aborted requests, and retries are measured
as a delta, so the claim "one activation sends one request" is independent of that.

Not covered by design: real browser zoom (640px is the CSS-width equivalent), hosting and production
SPA fallback, server-rendered metadata (the page is client-rendered), and the human visual approval,
which Phase 3 recorded separately.

## Appointment and guest booking verification (issue #18; Phases 2 and 3 executed, Phases 4 to 8 planned and not executed)

Decisions: ADR-0022 to ADR-0026; phases and the per-phase evidence table:
`docs/tasks/08a-appointment-core-and-guest-booking.md`. Every test follows the reliability rules above
(wait for the asserted state, control time, deferred promises, no sleeps, diagnose before calling anything
flaky) and uses real PostgreSQL through Testcontainers for persistence, concurrency, and tenant isolation.

- **Phase 2 (schema and overlap; executed, 244 new tests in nine classes, full suite 2469 tests, details and the full-suite result in the Phase 2
  record of the task document):** every `V11` constraint, same-Business composite keys and cross-Business
  rejection, the exclusion for identical, partial, and adjacent ranges, different StaffMembers and Businesses,
  `CANCELLED` rows that do not block, DST instants, concurrent two-writer inserts with lock-wait evidence, the
  real busy source (only `CONFIRMED` windows, one bulk query, joined transaction), placeholder removal, and
  module boundaries.
- **Phase 3 (schedule guard; executed, 117 new tests in seven classes and 8 added to four existing classes, full
  suite 2594 tests; the Phase 3 record of the task document has the details and limits):** the `V12`
  migration (byte-identical V1 to V11, a pinned V12, the backfill of existing Businesses upgraded from V11, the
  trigger that gives every new Business its row, a rolled-back insert, exact columns and constraints); the
  bump and guard contracts against PostgreSQL (one increment per call, Business isolation, a missing row or
  Business as a sanitized failure, a mandatory caller transaction, the guard's repeatable-read requirement, shared
  guards that never block each other, a held guard that blocks a later bump, an uncommitted bump that blocks the
  guard and then fails it with `40001` or lets it succeed after a rollback, a snapshot that predates a committed
  bump failing without waiting); each of the 13 audited mutation paths (weekly replacement and every
  kind times create, replace, delete) advancing the revision exactly once, rolling back with the write, never
  advancing it when rejected for validation, authorization, lifecycle, StaffMember, missing record, stale version or
  exclusion, and coordinating with a stand-in booking transaction through `pg_blocking_pids` lock-wait evidence and
  a stale-snapshot `40001`; writes that must **not** bump (lifecycle, timezone, Service, StaffMember,
  assignment); lock-order observation and concurrent-writer serialization with a deliberate order violation
  producing a deadlock victim; sanitized failures and the HTTP contract; module-boundary tests; and
  `AvailabilityMutationInventoryTests`, the source scan that classifies every write statement. Coordination uses
  latches, futures, and PostgreSQL's own blocking report, never fixed sleeps (`ConcurrencyTestSupport`). The
  booking-against-schedule commit-order tests with real Appointments and Customers belong to Phase 4.
- **Phase 4 (orchestration; executed, see the Phase 4 record of the task document for exact counts):**
  `GuestBookingServiceTests` (unit, scripted body and fake transaction manager: entry guard before any work,
  definition of every attempt, rollback-only for every non-`Created` result, each retryable failure in a new
  transaction, the three-attempt bound and exhaustion, never-retried failures, and the commit-phase classification with
  **injected** exceptions, labelled as such); domain and fingerprint tests (attempt ID forms, normalization, golden
  encoding and HMAC vectors from an independent implementation, a matrix of every changed field, key ring
  validation, rotation, missing key, unsupported encoding, no key leakage); `BookingFingerprintConfigurationTests`
  (startup failure reasons, `prod` rejection of non-secret keys); real PostgreSQL classes
  `GuestBookingIntegrationTests` (ownership, success, assignment rule including the Business-local date),
  `GuestBookingRejectionIntegrationTests` (every rejection and the atomic rollback of Customer, Appointment, and a
  consumer-probe write), `GuestBookingReplayIntegrationTests` (exact, equivalent, mismatch for each field and for the
  preference, replay after suspension and Service/StaffMember/timezone changes, cancelled fixture, the replay holding
  only the Business lock, unverifiable stored versions), `GuestBookingConcurrencyIntegrationTests` (observed lock
  order through `FOR UPDATE SKIP LOCKED` probes, exclusion races with lock-wait evidence, identical and mismatched
  simultaneous attempts, real `40001` and `40P01` through the orchestration, bounded retry, administrative changes
  waiting for an open booking), `GuestBookingScheduleRaceIntegrationTests` (the ADR-0025 commit orders for all 13
  audited paths with an availability oracle and two control runs), `GuestBookingCommitFailureIntegrationTests`
  (real PostgreSQL and transaction-manager commit evidence, labelled; the completion-status classification, the silent-rollback verification, and misleading after-commit exceptions), `GuestBookingKeyRotationIntegrationTests`,
  `BookingContractsIntegrationTests`, and the boundary tests. Test wiring (`BookingHookConfiguration`) wraps the real
  collaborators with latches and a recording transaction manager; the wrappers delegate unchanged. Deadlock-victim tests fix the victim with `SET LOCAL deadlock_timeout` on the participant that must not be chosen, because PostgreSQL aborts whichever participant's check finds the cycle first, not the one that waited first.
- **Phase 5 (public API, implemented):** real PostgreSQL through the complete servlet and security filter chain, the
  controllable clock, and a distinct remote address per request (so the shared limiter never couples tests):
  `PublicBookingAvailabilityApiIntegrationTests` (exact key sets, window, ordering, DST repeated hour, tenant isolation,
  collapsed 404 and generic 409s, validation, denied routes and verbs), `PublicBookingCreationApiIntegrationTests`
  (201, replay 200 including the cancelled fixture, mismatch, replay after suspension and snapshot changes, every
  rejection, every field error, unknown properties, 415, the real known-rollback and uncertain-commit faults with the same
  attempt repeated, no-store, no cookie, fixed instances, sentinel privacy including captured logs),
  `PublicBookingSessionSecurityApiIntegrationTests` (owner, administrator, invalid, expired, and anonymous callers
  byte-identical; every session row unchanged, with private-route controls that show a refresh and a revocation are
  detectable; the narrow CSRF exemption and the private-mutation regression; exact-origin CORS and preflight),
  `PublicBookingRateLimitApiIntegrationTests` and `PublicBookingRateLimitCapacityApiIntegrationTests` (small configured
  budgets: the 429 contract, charging rules, windows with the injected clock, spoofed forwarded headers, IPv6 /64,
  saturation, and no database or orchestration work for a rejected request), `PublicBookingDefaultRateLimitApiIntegrationTests`
  (the shipped 10, 5, and 300), and `PublicBookingResultMappingApiIntegrationTests` (every `BookingResult` through the
  full chain with a mocked orchestration), `PublicBookingBodyLimitApiIntegrationTests` (MockMvc evidence only: the exact
  byte boundary, bytes versus characters, a valid multibyte Bulgarian payload, the 413 contract, charging once to the address
  budgets, and no database work), and `PublicBookingBodyLimitContainerIntegrationTests` (real embedded Tomcat on a real socket:
  chunked bodies, a declared length far above or below the body; MockMvc cannot produce an absent or false `Content-Length`). Unit and architecture tests: `BookingRateLimiterTests` (every budget, the
  window boundary, expiry, capacity, canonicalization, aggregates, concurrency with 64 threads released together by a
  latch), `PublicBookingRequestParserTests`, `PublicBookingRoutesTests` (every verb against every route and sibling),
  `PublicStaffAccessServiceTests`, `BookingBodyLimitFilterTests`, and `PublicBookingModuleBoundaryTests`. The public-profile consumers are checked by the frontend public-profile Vitest suites and the existing `public-business-profile` Playwright journey against the final backend. **Evidence boundary:** injected outcomes
  prove only the HTTP mapping; transactions, commits, retries, and idempotency are proven by the Phase 4 classes, and the
  two real-fault 503 tests reuse their mechanism. No test sleeps; time moves only when a test moves the injected clock.
- **Phase 6 (frontend, implemented; not rendered or visually approved):** Vitest and Testing Library over a fake HTTP server that speaks the real Phase 5 response shapes
  (`src/public/booking/testSupport.tsx`), with responses held open by deferred promises where ordering matters and `vi.useFakeTimers` (only `setTimeout`, `clearTimeout`, and `Date`) for `Retry-After`;
  no test sleeps and none relies on a larger timeout. `BookingJourney.flow.test.tsx` (happy path, preference, dependent-state reset, date and timezone handling, the repeated hour, abort and stale answers, Business switching),
  `BookingJourney.submission.test.tsx` (duplicate-submit protection, the exact ID and body across eleven retry outcomes, the frozen uncertain state, an uncertain attempt followed by each of eleven later answers, `Retry-After`, 201, 200, a cancelled replay, every rejection),
  `BookingJourney.states.test.tsx` (unavailable, empty, network, and rate-limited reads, validation and focus, leaving and the browser history, memory-only privacy), and the unit suites `api.test.ts`, `attempt.test.ts`, and `dates.test.ts`.
  The date tests were also run with the browser timezone set to `Pacific/Kiritimati`, `America/Los_Angeles`, and `UTC`. These tests prove client behavior; they do not prove the booking transaction, idempotency, or tenant isolation, which the Phase 4 and 5
  backend tests prove. The `public-business-profile` Playwright spec was updated for the new booking buttons and executed after the Phase 6 correction (19 passed, in the script's own disposable Compose project).
- **Phase 7 (rendered review):** rendered browser review and explicit human visual approval, separate from every
  automated test, before Phase 8.
- **Phase 8 (browser E2E):** Playwright journeys with fixtures created only through supported APIs, conflict
  recovery, lifecycle states, mobile, tenant isolation, and unchanged administration.
