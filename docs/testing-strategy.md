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
  matching, deterministic StaffMember assignment, availability intervals, DST.
- API/slice tests: validation, Bulgarian errors, DTOs, authorization, CSRF/CORS.
- Spring Boot/Testcontainers PostgreSQL integration: Flyway, constraints,
  transactions, isolation, idempotency, concurrency; never substitute H2
  for PostgreSQL-specific behavior.
- Frontend component tests: forms/accessibility, role controls, errors, 409
  recovery, Bulgarian content.
- Playwright: critical public, Business, and platform journeys on mobile/desktop.
- Static gates: Maven (including Enforcer), Spring Modulith architecture rules, TypeScript, ESLint and the production
  build.

**Planned, not implemented:** tests for the transactional outbox, email delivery and reminders, Customer cancellation
links, `COMPLETED`/`NO_SHOW` statuses, configurable buffers, breaks, notice and booking window, and dependency, secret
and container scans. They are listed in the planned-checks sections below so they are not mistaken for current coverage.

## Reliability, diagnosis, and verification reporting

This section is the authoritative source for how tests are written, how failures are
diagnosed, and how verification is reported. `AGENTS.md` and `CLAUDE.md` only point here.

### Writing reliable tests

- **Derive a date and a start from the final booking state (E2E).** A journey that confirms a booking obtains its
  availability for exactly the final selection (Services, StaffMember) it later submits, and picks a date that still
  offers a start after the contested start is taken (`dateSurvivingFirstBooking`). Today, late in the day, has few
  starts, and one multi-Service visit blocks every overlapping start; a date taken from an earlier or broader answer
  fails only at certain hours.

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
  commit it checked. CI state is advisory for starting the next phase (see AGENTS.md, "Phase-start
  preconditions and CI"); it never replaces the local checks required before handing work over.

## Mandatory high-risk tests

### Concurrent booking

Create an ACTIVE Business, qualified StaffMember, Service, schedule, and two
overlapping requests. Coordinate independent transactions against PostgreSQL;
assert exactly one success, one 409, one blocking Appointment, and a healthy
connection afterward. Cover identical/partial overlap, adjacent half-open
ranges, and rescheduling, cancellation and reassignment on the Business side. The database constraint must key on
`staff_member_id`. The dated evidence is in [testing-records.md](testing-records.md) ("Appointment and guest booking
verification" and "Business Appointment administration verification"), and the browser-level evidence is described
under "Business calendar journeys" below.

### Multi-Service booking

The plan is in [task 08b](tasks/08b-multi-service-guest-booking-plan.md); every item runs against real PostgreSQL where persistence, locks, or concurrency are claimed, with deterministic coordination and no sleeps.
Required evidence: the **upgrade and backfill** of a database migrated to V12 by `V13` (one line per existing Appointment, totals and headline equal to it, the migration failing on a corrupt fixture, earlier migrations' checksums
unchanged); the **visit constraints** (line count, positions, sums, headline, distinct Services, cross-Business references, deferred violation at commit, immutable lines, the overlap exclusion over the whole visit);
**historical replay** (rows written by the current code replay with the legacy request, the new shape against a v1 row is a mismatch, an unknown stored encoding or key version is uncertain and never a mismatch, golden
vectors for encoding v2, the click order not changing the fingerprint); **atomic rollback** (a failure after every step leaves no Appointment, line, or Customer); **money capacity** (the widened visit total at the maximum of five maximum-price Services, values and the table file unchanged by the widening, exact arithmetic in Java, SQL, and the browser); **UUID order** (the unsigned bytewise order, never Java's signed `UUID.compareTo`, proven by identifiers across the high-bit boundary in the comparator, PostgreSQL's `ORDER BY id`, the lock statements, the fingerprint golden vectors, and a deadlock test); **concurrency** (identical visits, overlapping visits for one
StaffMember, partially overlapping Service sets, one attempt with different Services, a Service or assignment change racing the booking, opposite click orders without deadlock); **price and duration drift** (every changed
reviewed fact is `BOOKING_REVIEW_CHANGED` with nothing written, the precedence against `BOOKING_SERVICE_UNAVAILABLE`, a vanished slot, and the Customer, a replay after the terms changed still replaying, the claims never
persisted); and the **frontend uncertainty** rules (a first-send review change drops the attempt and requires a new explicit confirmation with refreshed figures; after an earlier uncertain send the attempt stays frozen with the
same identifier and exact bytes; no automatic retry; privacy sentinels).

**Evidence delivered by M2 (availability and eligibility):** the real engine over mocked published contracts with explicit instants and the controlled clock (summed duration, an interval that fits each Service but not the sum, busy time over any part of the whole visit, half-open adjacency, a visit across the repeated and across the skipped clock hour measured in elapsed time, no-preference aggregation, explicit StaffMember rules, the validation and ordering of failures, one bulk read per kind); real PostgreSQL for the assignment intersection (a union of partial assignments is not eligible), the committed busy time, the boundaries of one, five, 480, and 481 minutes, and a constant statement count; and mutation controls for the intersection and the sum. Concurrency is not claimed by M2: the read-only calculation takes no lock and relies on the enforced repeatable-read snapshot, which the set-based method shares with the one-Service method and a test pins.

**Evidence delivered by M3 (orchestration and fingerprinting):** encoding v2 against vectors from an independent implementation (which also reproduces the committed v1 HMACs), the boundary identifiers across the unsigned/signed split, all permutations, and every changed fact; historical v1 rows replaying only for the legacy shape; real-PostgreSQL visits (profile-order lines, exact maximum totals, replay after any change of the Services, review drift with zero writes and no retry, eligibility, overlap and adjacency); lock order proven with `pg_stat_activity` lock-wait evidence and `FOR UPDATE NOWAIT` probes in both directions across the high-bit boundary; races (identical visits, competing slots, Service and assignment and schedule changes after the snapshot, changes waiting for an open visit) coordinated by latches with distinct retry transactions; injected-fault tests (a test-only trigger) kept separate from PostgreSQL-produced behavior; and mutation controls for the review check, the lock order, the unsigned order, and the profile order.

**Evidence delivered by M5 (the frontend journey, automated tests only):** component tests over a deterministic fake of the public HTTP API (`frontend/src/public/booking/`): native checkbox semantics and the labelled group, the limits at five Services and 480/481 minutes with a visible reason and always-removable selected options, exact integer-cent totals (a drifting decimal example and five maximum prices), click-order independence of the read URLs and of the frozen request bytes, canonical request order against profile display order, invalidation that keeps the details, stale options and availability answers ignored, the intersection and the empty-intersection state, `BOOKING_SELECTION_TOO_LONG`, the whole-visit review, strict decoding of the visit response (exact two-decimal prices within the per-Service and per-visit capacities, the total against the exact sum of the line cents, every violation uncertain and never a confirmation) and a confirmation that shows the server lines and totals, the exact frozen bytes and the single POST, every first-send `BOOKING_REVIEW_CHANGED` recovery (vanished Service, changed duration or price, lost preference, failed refresh, a new attempt identifier), sticky uncertainty followed by a review change, and privacy across the URL, storage, cookies, history, title, and console; mutation controls for the single selection, floating-point cents, unconditional rounding of wire prices, kept stale choices, a new attempt after uncertainty, click-order bytes, and client totals. Real keyboard events, rendering, and zoom were reviewed in M6 (task 08b); the browser journeys are M7.

**Evidence delivered by M6 and M7 (kept apart by kind).** *Developer-rendered (M6):* the five widths, the zoom boundary, and every listed state were inspected in the built-in browser against a real backend (task 08b, M6 record); the product owner then gave explicit human visual approval. *Real-browser E2E (M7):* 29 new Playwright tests in `frontend/e2e/guest-booking-*.spec.ts` (shared support in `frontend/e2e/support/booking.ts`) run against the unmodified isolated runner, the real backend, and fixtures created only through supported HTTP APIs and the invitation UI, with no sleeps and with a request audit that is stopped and settled before any context closes: the one-Service path through the new request shape; three Services (display order, eligible staff, canonical request order against a different click order, server lines and totals); the limits (five Services, 480 minutes, exactly 480, always-removable selections); the empty intersection and the eligible-but-no-time state; invalidation that keeps the details; no needless options reload; review editing; the real `BOOKING_REVIEW_CHANGED` after an owner price or duration change (no automatic POST, a new attempt identifier), the real `BOOKING_SERVICE_UNAVAILABLE`; a slot taken in another browser and two guests confirming at the same moment; identical concurrent submissions and replay; Customer matching and the identity conflict; the repeated hour of the clock change; Back and Forward, the leave guard, keyboard, focus and DOM order; 375 and 320 px layout with the calendar day-cell exception and the 44 px target elsewhere; and privacy of the URL, storage, cookies, `history.state`, title, console, and the public traffic. *Simulated transport (M7, one test, its own file):* the response of a really committed booking is dropped in the browser to prove the frozen state, its leave protection, and the real replay. *Not browser-observable and cited from the backend instead:* appointment and Service-line rows and rollback (`GuestBookingVisitIntegrationTests`, `AppointmentServiceStoreIntegrationTests`, `GuestBookingConcurrencyIntegrationTests`, `GuestBookingReplayIntegrationTests`, `GuestBookingRejectionIntegrationTests`). The public booking limiter allows 10 bookings per address and Business, 30 per address, and 5 per Business and contact in 15 minutes, so the new specs spread their POSTs over several Businesses and stay under those budgets in one run (25 POSTs, at most 9 in one Business); a future spec that books must count against them. The DST scenario needs the last Sunday of October inside the 30-day booking window and is skipped with an announced reason outside it.

### Tenant isolation and workforce linkage

Create Business A and Business B with distinct Memberships/fixtures. For every
tenant-owned API, prove A cannot read, list, create against, update, cancel, or
indirectly reference B records. Include guessed IDs, StaffMember/Service/Customer
cross-links, bulk/list filtering, roles, inactive Membership, and platform-route
separation. Assert no B data leaks in responses/errors/audit visible to A.

Prove one StaffMember cannot link to multiple Memberships, one Membership cannot
link to multiple StaffMembers, cross-Business links fail, and StaffMembers
without accounts remain valid.

### Customer identity, uniqueness, and privacy

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

### Time, lifecycle and tokens (current)

Availability tests cover the fixed MVP policy (a 15-minute grid, two-hour notice and 30 Business-local dates for guests;
the owner's Business-side policy; zero buffers; no breaks), time off, working-day overrides, additional periods,
cancelled-slot release, qualifications, no-preference ties, fixed clocks, `Europe/Sofia` daylight-saving gaps and
repeated hours, and UTC storage with Business-local presentation. Invitation and password-reset tokens and sessions are
tested for expiry, tampering, revocation, reuse, hash-only storage, races, rotation and invalidation, together with CSRF,
exact CORS and enumeration-safe errors.

### Planned checks (not implemented)

When the capabilities exist, add tests for: Service buffers, breaks and Business-configured notice and booking window;
`COMPLETED`/`NO_SHOW` rejection before start and acceptance at or after start (deferred by ADR-0022; the MVP statuses are
`CONFIRMED` and `CANCELLED`); Customer cancellation tokens (expiry, tamper, revocation, reuse, hash-only storage, races);
and notifications (a provider failure cannot roll back an Appointment, and retried or overlapping outbox and reminder jobs
produce one logical delivery with redacted failures).

### Generic BusinessType behavior

Parameterize representative booking flows across every BusinessType. Assert the
same schema, Services, StaffMembers, schedules, availability, Customers,
Appointments and roles are used, with no industry-specific engine or authorization
branch. Owner cancellation is implemented and is part of the Appointment paths above; Customer self-service cancellation
and notification paths are planned and will join this list when they exist.

## Critical end-to-end flows

The target flows below; flow 6 (Customer self-service cancellation) is planned and not implemented, and the browser suite covers the others.

1. PLATFORM_ADMIN creates a DRAFT Business with BusinessType and unique slug.
2. Owner consumes the invitation and configures Business, StaffMember, Service,
   qualification, and schedule.
3. DRAFT blocks public booking; activation enables it.
4. Guest passes «Избор на услуги», «Избор на служител» (a person or “Без
   предпочитание”), «Дата и час», «Вашите данни», and «Преглед и потвърждение», and
   books without an account.
5. Business calendars show the automatically CONFIRMED Appointment.
6. *(Planned)* Guest cancels securely and the slot returns.
7. The Business owner creates (one or several Services), reschedules, reassigns,
   cancels, and edits the internal note of Appointments within the lifecycle and time
   rules. Manager and Staff access, complete, and no-show are
   future capabilities.
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
and the Playwright flows. Dependency, secret and container security scans are not configured. Before
release: migrate a clean database, run the full suite, inspect production
configuration, test approved backup restore, review security/privacy decisions,
and perform a Bulgarian mobile smoke test.

## Current suites and how to run them

- **Backend:** `./mvnw --batch-mode verify` from `backend/` runs unit, slice and Testcontainers PostgreSQL integration tests (Docker required) and the Spring Modulith module verification. Concurrency, rollback, constraint and migration behaviour is proven against real PostgreSQL, never H2.
- **Frontend:** `npm run lint`, `npm run test` (Vitest with Testing Library and jsdom) and `npm run build` (`tsc -b` plus Vite) from `frontend/`.
- **Browser E2E:** `./scripts/run-e2e.sh` from the repository root runs the Playwright (Chromium) suite against a disposable stack: a dedicated PostgreSQL Compose project, the real Spring Boot backend and the Vite dev server. See [development.md](development.md#browser-end-to-end-tests).
- **CI:** `.github/workflows/ci.yml` runs the backend job, the frontend job and, after both, the E2E job on every push and pull request. CI status is advisory for starting or continuing work; report local verification and CI verification separately.

### E2E date and slot choice, and overflow checks

A browser journey must not take the Nth start of the first offered date: today, late in the Business day, offers only the starts that remain. Use `dateWithSlots` (e2e support) with the number of starts the journey needs; the rule is pinned by `booking-date-selection.spec.ts` with synthetic answers. `expectNoHorizontalOverflow(page, checkpoint)` reports the checkpoint, the route (without its query), the widths and the structure of the offending elements on both edges (no text), ignoring approved internal scroll regions; `overflow-diagnostics.spec.ts` pins it.

### Font-dependent overflow, and typed date fields

A layout that fits in the developer's font can overflow in the CI browser's wider one (CI run 39443685396: a select's intrinsic width widened the page column). The Pixel 7 journey therefore repeats its overflow check with `letter-spacing: 0.08em` on every element, which reproduces the effect on any machine without a font dependency; never fix such a failure with a timeout, retry or looser tolerance. Read a failing overflow message's checkpoint and offenders before changing anything.

A date text field is tested with edits that carry the browser's real shape: `keyboardTestSupport.ts` sets the text, the caret and the `inputType` and fires the native `input` event key by key (typing, backspace, selection replacement, paste), which a single `fireEvent.change` with a finished value cannot reproduce. `HistoryDateField.test.tsx` covers `22102026` and `22.10.2026` typed and pasted, the year typed digit by digit (the reported `dd.mm.0026`), partial input, blur errors, clearing, replacement, caret-sensitive editing, invalid dates and leap years, and the calendar choice; `CustomerAppointmentHistory.test.tsx` covers the requests (none for incomplete or invalid input, one per newly complete date, page reset, order and size kept). `customer-administration-journey.spec.ts` types the same dates key by key in Chromium; its inserted dotted text (`keyboard.insertText`) stands in for a paste, and the native calendar popup is not driven in a browser.

### Business calendar journeys: real, proxy and synthetic evidence

`business-calendar-journey.spec.ts` drives the owner's Calendar, detail, manual create (with an inline Customer and two Services), reschedule, reassignment, cancellation, internal note, Customer history, concurrency, authorization and lifecycle journeys against the real backend and PostgreSQL. Evidence is labelled by what produced it:

- **Real:** every availability answer, conflict (a real online guest booking, or the owner's own competing visit, commits between the form's review and its submit), version conflict (a second request changes the Appointment while the page holds the old version), simultaneous creates (three at once for one StaffMember: exactly one 201, the others `APPOINTMENT_SLOT_UNAVAILABLE`, no overlap in the final calendar), lifecycle change (the platform administrator suspends and reactivates the Business) and authorization refusal.
- **Proxy:** one scenario, marked `PROXY` in its title: the real cancellation request is sent and committed, and only its response is dropped, to show the unresolved-outcome interface against a real final state. It is not evidence of how the backend behaves on a failure.
- **Synthetic (not in this spec):** the frontend unit tests answer known-rollback, uncertain and unexpected responses with scripted problems; they prove the interface, never transaction behaviour. Real rollback is proven by the backend integration tests (`AppointmentStoreFailureTranslationTests`, `*UnavailableApiIntegrationTests`, the concurrency tests) with injected faults only where a real database failure cannot be forced.

Fixtures are created through supported APIs (the Business API for Appointments, the public API for the competing guest, which counts against the booking limiter once). A screen mounted before its fixtures exist keeps its earlier reads, so a journey opens its route and reloads. The wall clock cannot be controlled in the browser, so daylight-saving behaviour is covered by backend and frontend tests, not by this spec.

The authorization and lifecycle assertions of `business-calendar-journey.spec.ts` pin the exact status and problem code of the existing contracts (anonymous: 401 `AUTH_REQUIRED`; platform administrator without a selected Business: 403 `ACTIVE_BUSINESS_REQUIRED`; foreign or missing Appointment: 404 `APPOINTMENT_NOT_FOUND` with identical bodies; `SUSPENDED` cancellation: 409 `BUSINESS_SUSPENDED`), so a malformed request or a missing route cannot pass as enforcement. The history date fallback is exercised with real keyboard input in the native control (`customer-administration-journey.spec.ts`), not only a finished value; its native popup is not observable in headless Chromium.
