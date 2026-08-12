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

Flyway owns schema evolution. Structured redacted logs, correlation IDs,
health checks, and audit/delivery records support operations. BusinessType is
descriptive only. Scaling begins with measurement, indexes, stateless replicas,
pool tuning, and worker coordination; speculative industry abstractions, Redis,
Kafka, microservices, and Kubernetes are excluded.
