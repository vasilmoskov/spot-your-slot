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
| `catalog` | Services, prices, durations, StaffMember qualifications |
| `workforce` | StaffMembers, weekly hours, breaks, time off, overrides |
| `scheduling` | timezone-aware availability and deterministic assignment |
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
Schedule terminology. DTOs use validation, pagination where needed, and RFC
7807-style errors with stable code, safe Bulgarian message, status, field
errors, timestamp, path, and correlation ID. OpenAPI is development-only.

Appointments persist `[start_at, occupied_until)` as UTC `timestamptz` values;
the latter captures Service duration plus buffer. Weekly schedules use local
weekday/time and the Business IANA timezone. Availability intersects working
intervals/overrides, subtracts breaks/time off/blocking Appointments, and applies
qualification, notice, window, and DST rules with an injected clock.
The booking window is a Business setting defining how many days ahead Customers
may book, defaulting to 30 days. Daily and weekly administrative calendars are
query/presentation views over Appointments; they do not constrain that horizon.
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
