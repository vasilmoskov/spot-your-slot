# SpotYourSlot architecture

## Architectural style

SpotYourSlot is a monorepo and deployable modular monolith: one Spring Boot REST
API, one React SPA, and one shared PostgreSQL database. It is one generic system
for every approved BusinessType, not separate applications, schemas, engines,
branches, or deployments by profession.

```text
Browser: public Business booking | Business administration | platform administration
                 │ HTTPS/JSON and secure session cookie
                 ▼
Spring Boot REST API (bg.spotyourslot)
 identity | platform | business | catalog | workforce | scheduling
 booking | customer | notification | audit
                 │ JPA/Flyway + transactional outbox
                 ▼
PostgreSQL ── asynchronous dispatcher ── EmailService
```

The future backend uses Java 25 LTS. Exact Spring Boot and Maven versions are
chosen during bootstrap after official Java 25 compatibility verification. The
frontend uses compatible stable React, TypeScript, and Vite releases on the
latest suitable Node.js LTS available then.

## Module boundaries

All backend packages live below `bg.spotyourslot`.

| Module | Responsibility |
|---|---|
| `identity` | users, credentials, sessions, invitations, password reset, Memberships, roles |
| `platform` | PLATFORM_ADMIN operations and Business lifecycle |
| `business` | Business profile, BusinessType, slug, settings, status, tenant context |
| `catalog` | Business-owned Services, prices, durations, lifecycle, and versioned administration |
| `workforce` | StaffMembers, Service qualifications, recurring weekly hours |
| `scheduling` | timezone-aware availability, Business closures, StaffMember time off, working-day overrides, additional working periods, and deterministic assignment (ADR-0013) |
| `publicprofile` | read-only unauthenticated public Business profile (`GET /api/public/businesses/{slug}`) orchestrated over the published `business.PublicBusinessProfileAccess` and `catalog.PublicServiceAccess` contracts (ADR-0017; backend contract, public React page and browser E2E verification implemented in issue #17; booking is not part of it) |
| `booking` | transactional Appointment lifecycle and conflicts |
| `customer` | Business-scoped Customers and safe matching |
| `notification` | outbox, delivery attempts, reminders, `EmailService` |
| `audit` | immutable security/business audit events |
| `shared` | small cross-cutting primitives, errors, clocks, configuration |

Controllers call application use cases; authorization and transactions are not
controller concerns. Entities/repositories remain module-internal. Modules use
stable IDs and published interfaces/events, and `shared` is not a business-code
dumping ground.

## Multi-tenant strategy

All tenant-owned tables contain non-null `business_id`. Tenant-scoped unique
keys and foreign keys include `business_id` unless globally unique, such as the
Business slug. Authenticated Business context comes from server-side Membership;
public context comes from `{businessSlug}`. Every repository operation is scoped,
and mutations prove referenced records share the Business.

Application authorization is primary; PostgreSQL composite constraints and
tests provide defense in depth. RLS is deferred because pooled connection
context adds complexity and would not replace application checks.

## APIs, time, and availability

APIs expose generic Business, StaffMember, Service, Customer, Appointment, and
Schedule terminology. DTOs use validation and pagination where needed. Current
RFC 7807 responses contain the standard status/title/detail fields as applicable
and a stable `code`, plus an optional `fieldErrors` map naming a correctable body
field where an API defines one; they do not add timestamps, paths, or
correlation IDs. OpenAPI is development-only.

Appointments persist `[start_at, occupied_until)` as UTC `timestamptz` values;
the latter captures Service duration plus buffer. Weekly schedules use local
weekday/time and the Business IANA timezone. Availability intersects working
intervals/overrides, subtracts breaks/time off/blocking Appointments, and applies
qualification, notice, window, and DST rules with an injected clock.
Service buffers belong to future availability work and are not persisted or
configurable by the current Service backend; breaks are not implemented.
The intended product direction is a Business-configurable booking window
(default 30 days) and minimum notice (default two hours). The current MVP
implementation fixes them, per ADR-0013 and ADR-0016, at 30 Business-local dates
(today through today + 29) and an inclusive two-hour notice on a 15-minute grid
with zero buffers; configuration is a recorded follow-up. Daily and weekly
administrative calendars are query/presentation views over Appointments; they do
not constrain that horizon.
`StaffMember` remains the internal English term, while generic Bulgarian
administration uses “Екип” and “Член на екипа” and public booking may use
contextual wording instead of a fixed performer label.

## Conflict-safe booking

PostgreSQL is the final arbiter. A Flyway-created GiST exclusion constraint
uses `staff_member_id WITH =` and
`tstzrange(start_at, occupied_until, '[)') WITH &&` for status `CONFIRMED`.
`btree_gist` supplies scalar equality and half-open ranges permit adjacency.

Within one transaction, booking reloads Business settings and StaffMember/
Service state, revalidates availability, creates or matches the Customer, and
inserts the Appointment. The specific overlap violation becomes HTTP 409.
Rescheduling follows the same path. `COMPLETED` and `NO_SHOW` require current
time at or after `start_at`, preventing premature release of future time.

## Authentication, notifications, and deployment

Administrators use server-managed Secure/HttpOnly/SameSite sessions with CSRF
protection. Invitations, resets, and cancellation links are high-entropy tokens
stored only as hashes. Detailed rules are in `security.md`.

Phase 2 implements `identity`, `business`, and `shared` as Spring Modulith-
verified package boundaries. Phase 3A adds `platform` orchestration. Platform
code depends on the published Business administration API and the published
identity active-owner query; it does not access either module's infrastructure.
Business owns validation, persistence, lifecycle rules, and atomic transitions.
Identity owns Membership persistence and the active-owner query. There are no
reverse dependencies or cycles.

The implemented `catalog` module owns the Business-scoped Service model,
canonicalization, validation, persistence, application orchestration, and HTTP
adapter. It depends only on the published Business lifecycle contract and the
published identity contracts for authenticated context and active-owner access;
it does not use another module's repositories or persistence records. Service
responses omit Business identity because tenant selection remains server-side.

Service reads run in read-only transactions and use non-locking Business and
Membership checks. Each mutation runs in one transaction and acquires shared
locks in the order Business lifecycle row, exact user Membership row, then the
tenant-scoped optimistic Service mutation. The final Service statement retains
the expected-version predicate; no pessimistic Service-row lock replaces it.
The authenticated API exposes list, detail, create, update, deactivate, and
reactivate operations under `/api/business/services`.

The implemented `workforce` module owns the Business-scoped StaffMember model,
canonicalization, validation, persistence, application orchestration, Service
assignments, and HTTP adapter. Its published `StaffMemberAdministration`
contract accepts the server-derived authenticated Business context and exposes
no repository or persistence record. Workforce depends on the published
Business lifecycle and identity owner-access contracts and on Catalog's narrow
published `ServiceReferenceAccess`; Catalog has no reverse dependency on
Workforce.

Workforce reads use non-locking Business and Membership authorization.
Assignment listing runs at repeatable-read isolation and resolves the
StaffMember aggregate, relationship IDs, and ordered Catalog summaries as one
consistent snapshot. Assignment replacement acquires shared locks on the
Business lifecycle row and exact user Membership row, then executes the
conditional StaffMember `UPDATE` that performs the expected-version guard and
takes PostgreSQL's row-level write lock. Catalog next locks only added Service
rows using `FOR SHARE` in deterministic UUID order before Workforce reconciles
the relationships. The version guard and relationship reconciliation run in one
transaction, so validation or persistence failure rolls both back.

Profile update, deactivation, reactivation, and complete desired-set assignment
replacement share one StaffMember aggregate version. Every successful mutation,
including same-set replacement, increments that version once. StaffMember
activity is not an authorization or version predicate: active and inactive
StaffMembers remain administratively configurable. Only added Services must be
active; retained or removed existing assignments are permitted regardless of
current Service activity. Endpoint deactivation preserves assignments.

The authenticated Workforce API exposes list, detail, create, update,
deactivate, reactivate, assignment listing, and complete assignment replacement
under `/api/business/staff-members`. HTTP requests and responses omit Business,
user, Membership, credential, role, session, normalized, and persistence state.
StaffMember account linkage, availability, and booking remain outside the
implemented Workforce slice.

The implemented recurring StaffMember working-schedule slice adds one
independent `staff_working_schedule` aggregate per StaffMember under the same
Business, with zero or more child `staff_working_period` rows. `V7` backfills
an empty version-0 schedule for every existing StaffMember, and StaffMember
creation inserts the new empty schedule inside the same transaction. The
schedule version is independent of the StaffMember aggregate version; every
accepted complete replacement, including an identical one, increments it
exactly once. Periods use ISO weekday and local `HH:mm` clock values with
one-minute precision from `00:00` through `23:59`; PostgreSQL's special
`24:00:00` value is rejected, and a generated `int4range` plus a `btree_gist`
multicolumn exclusion constraint reject overlaps scoped to Business,
StaffMember, and weekday while permitting half-open adjacency.

Schedule reads use a repeatable-read transaction so aggregate metadata and
ordered periods come from one consistent snapshot; non-locking Business and
Membership checks authorize an active `BUSINESS_OWNER` Membership. Complete
replacement locks, in order, the Business lifecycle row, the exact owner
Membership row, then the StaffMember row to stabilize its active state, before
the conditional expected-version schedule update and complete child-period
replacement in the same transaction. Only an active StaffMember may receive a
mutation; deactivation and Business suspension preserve all schedule data. The
authenticated API exposes `GET`/`PUT`
`/api/business/staff-members/{staffMemberId}/working-schedule`; the PUT request
carries only `expectedVersion` and the complete desired period list, and the
response carries StaffMember ID, live Business timezone, ordered periods,
independent schedule version, and timestamps. Exceptions, time off, breaks,
overrides, and availability remain outside this slice.

The `scheduling` module also owns internal persistence of schedule exceptions
(Issue #16 Phase 2, [ADR-0014](decisions/ADR-0014-store-schedule-exceptions-as-versioned-aggregates-with-same-kind-date-exclusion.md)).
`ScheduleExceptionStore` in `scheduling.infrastructure` inserts, finds by
Business and id, lists by inclusive date window (optionally with StaffMember
filtering that always includes Business closures), conditionally replaces, and
conditionally hard-deletes a versioned `schedule_exception` aggregate with its
composed periods, in one joined read for aggregate plus periods. Stored
aggregates are immutable `scheduling.domain` records, separate from engine
inputs; `ScheduleExceptionInputs` translates them purely. Replace and delete are
conditional on Business, id, and expected version; an empty result or `false`
means only that no such row matched, so the store does not distinguish missing,
stale, or concurrently deleted. The future application service reads first and
treats a later failed mutation as a concurrent change. The store takes no
Business, Membership, or StaffMember locks and applies no authorization or
lifecycle rules; the administration service of Phase 3 owns those. Concurrent
conflicting inserts are serialized by PostgreSQL exclusion constraints.

Issue #16 Phase 3 ([ADR-0015](decisions/ADR-0015-administer-schedule-exceptions-through-a-versioned-business-owner-api.md))
adds the private Business-owner administration of schedule exceptions. The
published `scheduling.ScheduleExceptionAdministration` contract accepts the
server-derived authenticated Business context and is implemented by an
application service that depends only on the published Business schedule-context
and identity owner-access contracts and on Workforce's narrow published
`StaffMemberReferenceAccess`, which exposes only a StaffMember's ID and active
state and locks its row `FOR SHARE` inside the caller's transaction; Workforce has
no reverse dependency. Reads run at repeatable-read isolation with non-locking
checks. Each mutation runs in one transaction that locks, in order, the Business
lifecycle row, the exact owner Membership row, the StaffMember row for
StaffMember-scoped kinds, and finally the aggregate through the store's
conditional statement. Replace and delete first read the aggregate in that
transaction, report not-found only when the read is empty, and treat a failed
conditional mutation afterwards, or a deadlock or serialization victim, as a
concurrent update. Overlap is decided only by the PostgreSQL exclusion
constraints; there is no pre-check. The authenticated API under
`/api/business/schedule-exceptions` exposes list by inclusive date window, get,
create, atomic replace (which retains the stored kind and StaffMember), and hard
delete, both mutating variants carrying `expectedVersion`; responses omit
Business identity. Validation bounds, the unpaginated list, and the inactive
StaffMember trade-off are recorded in the ADR. A frontend remains outside this
slice.

Issue #16 Phase 4 ([ADR-0016](decisions/ADR-0016-orchestrate-availability-through-published-contracts-and-a-scheduling-owned-busy-interval-seam.md))
adds the internal published `scheduling.AvailabilityQuery`, which assembles the
pure engine's inputs from narrow published contracts: the existing Business
schedule context, `catalog.ServiceAvailabilityAccess` (identity and duration of
an active Service), and `workforce.StaffAvailabilityAccess` (active StaffMembers
assigned to the Service with their recurring periods, one joined statement).
The result is deeply immutable published records; the internal engine records
stay private. A standalone call creates a read-only repeatable-read transaction; a call
inside an existing transaction joins it and fails before any read unless that
transaction is already repeatable-read or serializable. The method itself
performs no writes or explicit locks, though a joined outer transaction may be
read-write, and PostgreSQL's first-statement snapshot then covers every
committed database read; the result is a current view that reserves nothing and booking
must revalidate. The Scheduling-owned `BusyIntervalSource` is the seam for
occupied time. Its real implementation belongs to the future Booking issue and
must join the caller's transaction with one bulk query; a temporary
`NoBookingBusyIntervalSource` placeholder is a required ordinary bean, so a
second implementation makes startup fail until the placeholder is deleted. The
orchestration issues four application SQL statements regardless of team size.
There is no public availability endpoint.

Issue #17 adds the `publicprofile` module (the backend public read contract, the
public React page and its browser E2E verification are implemented; booking, availability
selection and Appointments are not and belong to later issues) ([ADR-0017](decisions/ADR-0017-expose-public-business-profile-through-an-allowlisted-read-only-contract.md),
[ADR-0018](decisions/ADR-0018-serve-public-business-pages-at-a-top-level-path-with-reserved-roots-and-stable-slugs.md),
[task 06a](tasks/06a-public-business-profile.md)). It depends only on a
narrow published `business.PublicBusinessProfileAccess` (ACTIVE Business by slug,
explicit public columns, slug validation and reserved roots) and a
`catalog.PublicServiceAccess` (every active Service in `normalized_name, id` order,
no cap), never on their stores or private records, and no module depends on it.
`business` cannot own the page because `catalog` already depends on `business`. One
read-only repeatable-read transaction, whose isolation is verified before any read,
runs two statements for an ACTIVE Business, one for any other well-formed slug, and
none for a malformed or reserved slug, independent of the number of Services. The public page will be a top-level
`/{businessSlug}` route in the React application, chosen before the
administration application; the backend stays JSON-only. Reliable per-Business
link previews and server-level SEO require later server-side, pre- or edge
rendering.

The platform Business API provides bounded deterministic listing, retrieval,
DRAFT creation, profile update, initial activation, suspension, and
reactivation. Mutations carry `expectedVersion`; PostgreSQL compare-and-update
predicates increment the version once and prevent lost updates. Initial
activation is one read-write platform transaction. Its identity query locks one
qualifying active `BUSINESS_OWNER` Membership using deterministic
`ORDER BY id LIMIT 1 FOR SHARE`, so concurrent deactivation waits until the
Business transition commits or rolls back.

The React platform-admin client uses application-owned hash routes for the
Business list, creation, and detail views without adding a routing dependency.
It keeps backend-returned Business versions in memory and returns the current
`expectedVersion` for profile and lifecycle mutations. Concurrent-update
responses require an explicit reload instead of an automatic overwrite;
versions remain technical state and are not displayed. Creation omits timezone
so the backend applies `Europe/Sofia`, while updates preserve the stored value
without exposing timezone in the current UI. Detail starts read-only and uses
explicit edit/save/cancel inside accessible collapsible profile, invitation,
and activation sections with operation-local feedback. Owner invitation input
is separate from Business contact data; invitation requests use the identity
API and never persist invitation credentials in frontend-managed browser
storage. The shared request helper accepts successful empty responses,
including the invitation endpoint's HTTP 202 response, without attempting JSON
decoding.

Business transactions atomically add outbox records. A worker claims/retries
with bounded backoff; idempotency keys prevent duplicate reminders. Development
and tests never send real email.

Local development will use PostgreSQL through Docker Compose and database name
`spotyourslot`. Production proposals (Cloudflare Pages, paid Render service and
PostgreSQL, Resend) remain unconfigured. The example domain is not registered,
and availability has not been legally verified.

## Version and evolution policy

Bootstrap verifies versions in official primary documentation; records exact
versions and compatibility reasoning; pins direct dependencies, tools, and
Docker image tags; commits generated lockfiles; and avoids pre-release or
experimental dependencies without separate approval. PostgreSQL is the latest
stable major supported by both local Docker and the approved host. Upgrades are
controlled and reviewable.

### Verified bootstrap versions

Verified on **2026-08-13** using official primary documentation and package
metadata:

| Technology | Selected version | Compatibility basis |
|---|---:|---|
| Java | Temurin 25.0.4 LTS | Required project LTS; Spring Boot 4.1 supports Java 17–26 |
| Spring Boot | 4.1.0 GA | Current stable GA; official system requirements include Java 25 and Maven 3.6.3+ |
| Maven / Wrapper | 3.9.16 / 3.3.4 | Installed stable Maven satisfies Boot; wrapper pins the same distribution |
| Node.js / npm | 24.19.0 LTS / 11.17.0 | Node 24 is the current suitable LTS and satisfies Vite/Vitest/jsdom engines |
| React / React DOM | 19.2.8 | Latest stable React release resolved during bootstrap |
| TypeScript | 6.0.3 | Latest stable release compatible with stable `typescript-eslint` 8.67.0 (`<6.1`) |
| Vite / React plugin | 8.2.1 / 6.0.5 | Stable releases; Vite supports Node 24 and plugin supports Vite 8 |
| Vitest / jsdom | 4.1.10 / 30.0.1 | Stable releases with Node 24 support |
| PostgreSQL | 18.4 (`postgres:18.4-bookworm`) | Current stable major/minor; PostgreSQL recommends current minor and Render supports major 18 |
| Testcontainers | 2.0.5 | Stable release managed by Spring Boot 4.1; uses the official 2.x prefixed module coordinates |
| Bouncy Castle | 1.84 | Stable provider required by Spring Security Argon2 |
| Spring Modulith | 2.1.0 | Stable test-only module verification for the Spring Boot 4.1 line |

Primary sources: [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html),
[Node release status](https://nodejs.org/en/about/previous-releases),
[React versions](https://react.dev/versions),
[TypeScript download/version guidance](https://www.typescriptlang.org/download/),
[Vite releases](https://vite.dev/releases),
[Vite Node requirements](https://vite.dev/guide/),
[PostgreSQL version policy](https://www.postgresql.org/support/versioning/),
[Render PostgreSQL support](https://render.com/docs/postgresql-upgrading), and
[Testcontainers PostgreSQL module](https://java.testcontainers.org/modules/databases/postgres/).

Direct npm dependencies are exact-pinned and the lockfile fixes the complete
graph. Spring Boot dependency management fixes the supported backend graph;
explicit build plugins and Maven distribution are pinned. Docker images use
full non-floating tags. Upgrades require the same official compatibility review,
lockfile regeneration, full checks, and a focused reviewed change.
GitHub Actions are pinned to reviewed major release lines (`checkout@v6`,
`setup-java@v5`, and `setup-node@v6`); Dependabot or a focused maintenance
change may advance them only after release-note review and green CI. Immutable
commit SHAs may replace major pins if the repository later adopts that stricter
supply-chain policy.

Flyway owns schema evolution. Structured redacted logs, correlation IDs,
health checks, and audit/delivery records support operations. BusinessType is
descriptive only. Scaling begins with measurement, indexes, stateless replicas,
pool tuning, and worker coordination; speculative industry abstractions, Redis,
Kafka, microservices, and Kubernetes are excluded.
