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

Planned CI runs backend static/unit/integration checks, frontend lint/type/unit/
build, Playwright flows, and security scans. Before release: migrate a clean
database, run the full suite, inspect production configuration, test approved
backup restore, review security/privacy decisions, and perform a Bulgarian
mobile smoke test.
