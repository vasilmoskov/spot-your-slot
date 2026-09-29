# SpotYourSlot

SpotYourSlot is a planned mobile-first, multi-tenant SaaS appointment-booking
platform for small appointment-based service businesses. It supports hair and
beauty businesses, massage practitioners, makeup artists, and other small
businesses that provide individual services with known durations through one
shared generic booking model.

Each Business has isolated data and one public page at
`https://spotyourslot.bg/{businessSlug}`; locally the equivalent example is
`http://localhost:5173/{businessSlug}`. The production domain is illustrative:
it is not registered or configured by this task, and neither domain nor
trademark availability has been legally verified.

This repository contains the identity and tenancy foundation, platform Business
onboarding, and the Business Services, StaffMember, and StaffMember
working-schedule backends: a Spring Boot backend, a Bulgarian React identity
and platform-admin client, Flyway-managed PostgreSQL, local Docker Compose, and
non-deploying CI. Platform administrators can manage the Business lifecycle,
and active Business owners can administer Services, StaffMembers,
StaffMember-to-Service assignments, and each StaffMember's recurring weekly
working schedule through authenticated APIs and through the Bulgarian
Business-owner configuration interface (Services, Team, assignments, and the
recurring weekly working schedule). Browser end-to-end verification of that
journey, schedule exceptions/time off, Customers, Appointments, booking,
production email, and hosting are not implemented.

## Product identity

| Item | Value |
|---|---|
| Product | SpotYourSlot |
| Human-readable brand form | Spot Your Slot |
| Preferred meaning | “Find the available time that works for you.” |
| Repository/local directory | `spot-your-slot` |
| Java base package | `bg.spotyourslot` |
| Development database | `spotyourslot` |
| Example production origin | `https://spotyourslot.bg` |
| Public Business URL | `https://spotyourslot.bg/{businessSlug}` |
| Local public URL | `http://localhost:5173/{businessSlug}` |
| Default timezone | `Europe/Sofia` |
| MVP currency | `EUR` |
| User-facing MVP language | Bulgarian |
| Source identifiers and technical documentation | English |

Non-binding future marketing possibilities are “Find your time. Book your
slot.”, “See it. Spot it. Book it.”, and “Your time. Your slot.” They do not
expand the MVP.

## Documentation

- [Product specification](docs/product-spec.md)
- [Architecture](docs/architecture.md)
- [Data model](docs/data-model.md)
- [Security](docs/security.md)
- [Testing strategy](docs/testing-strategy.md)
- [UI design guidelines](docs/ui-design-guidelines.md)
- [Implementation plan](docs/implementation-plan.md)
- [Product roadmap](docs/product-roadmap.md)
- [Business Services backend task](docs/tasks/04a-business-services-backend.md)
- [Staff management backend task](docs/tasks/04b-staff-management-and-service-assignments-backend.md)
- [Recurring staff working schedules backend task](docs/tasks/04c-recurring-staff-working-schedules-backend.md)
- [Foundation task](docs/tasks/00-product-foundation.md)

## Selected toolchain

The exact Phase 1 versions and official compatibility basis are recorded in
[architecture.md](docs/architecture.md#verified-bootstrap-versions). Locally,
use Java 25, Node.js 24 LTS, npm 11, and Docker with Compose. Maven itself is
downloaded by the committed wrapper. Production hosting remains unconfigured.

## Planned repository layout

```text
spot-your-slot/
├── backend/
├── frontend/
├── docs/
│   ├── tasks/
│   ├── product-spec.md
│   ├── architecture.md
│   ├── data-model.md
│   ├── security.md
│   ├── testing-strategy.md
│   └── implementation-plan.md
├── .github/workflows/
├── compose.yaml
├── AGENTS.md
├── README.md
└── .gitignore
```

## Local development

### Prerequisites and ports

- Temurin or another Java 25 JDK (`java -version`)
- Node.js 24 LTS and npm 11 (`node --version && npm --version`)
- Docker and Docker Compose (`docker compose version`)

PostgreSQL uses `localhost:5432`, the backend `localhost:8080`, and the Vite
frontend `localhost:5173`. Override the database port with `POSTGRES_PORT` and
the backend port with `SERVER_PORT` when necessary.

### Environment and PostgreSQL

From the repository root:

```bash
cp .env.example .env
```

Change the local-only password in `.env`; never commit `.env`. Start and inspect
PostgreSQL:

```bash
docker compose up -d postgres
docker compose ps
```

The named volume preserves local data. Compose creates database `spotyourslot`.

### Backend

Export the same local credentials as `.env` (Compose reads `.env`, the shell
does not), then run:

```bash
cd backend
export POSTGRES_USER=spotyourslot
export POSTGRES_PASSWORD='the-value-from-your-local-env-file'
./mvnw spring-boot:run
```

Health is public at `http://localhost:8080/actuator/health`. Flyway applies the
six current migrations on startup and Hibernate validates the schema.

To create the first local `PLATFORM_ADMIN`, explicitly opt in for one startup:

```bash
export BOOTSTRAP_ADMIN_ENABLED=true
export BOOTSTRAP_ADMIN_EMAIL='admin@example.invalid'
export BOOTSTRAP_ADMIN_DISPLAY_NAME='Local Administrator'
export BOOTSTRAP_ADMIN_PASSWORD='replace-with-a-long-local-only-password'
./mvnw spring-boot:run
```

This is idempotent, has no default credential, and is rejected in production.
Remove the variables afterward.

### Frontend

In a second terminal:

```bash
cd frontend
npm ci
npm run dev
```

Open `http://localhost:5173`. The client supports login, forgotten/reset
password, invitation acceptance, Business selection, password change, and
logout. It stores no authentication token in browser storage.

### Identity API and local links

State-changing calls require the `X-XSRF-TOKEN` returned by
`GET /api/auth/csrf` and browser credentials. Main endpoints are:

| Method and path | Purpose |
|---|---|
| `POST /api/auth/login`, `POST /api/auth/logout` | Shared login and logout |
| `GET /api/auth/session` | Current user and authorized Businesses |
| `POST /api/auth/profile` | Update only the authenticated user’s display name and return the refreshed session |
| `POST /api/auth/business` | Select an already-authorized Business |
| `POST /api/auth/password/change` | Change password and revoke other sessions |
| `POST /api/auth/password/forgot`, `POST /api/auth/password/reset` | Reset flow |
| `POST /api/auth/invitations/accept` | Accept an owner invitation |
| `POST /api/platform/identity/businesses/{id}/owner-invitation` | Create/replace owner invitation |
| `GET /api/dev/mailbox` | Platform-admin-only local/test links; absent in production |

### Platform Business API

All routes below require authenticated `PLATFORM_ADMIN` authority. Business
Membership roles do not grant platform access. Writes require the CSRF header;
updates and lifecycle operations use `expectedVersion` to reject stale writes.

| Method and path | Purpose |
|---|---|
| `GET /api/platform/businesses?page=0&size=10&sort=displayName&direction=asc` | List Businesses; `size` is exactly 10, 25, or 50; `sort` is `displayName`, `slug`, `businessType`, or `status` |
| `GET /api/platform/businesses/{businessId}` | Retrieve approved Business metadata |
| `POST /api/platform/businesses` | Create a `DRAFT` Business at version 0 |
| `PUT /api/platform/businesses/{businessId}` | Update approved profile fields |
| `POST /api/platform/businesses/{businessId}/activate` | Initially activate a DRAFT Business |
| `POST /api/platform/businesses/{businessId}/suspend` | Suspend an ACTIVE Business |
| `POST /api/platform/businesses/{businessId}/reactivate` | Reactivate a SUSPENDED Business |

Initial activation requires an active `BUSINESS_OWNER` Membership for the same
Business. The implemented lifecycle is `DRAFT → ACTIVE`, `ACTIVE → SUSPENDED`,
and `SUSPENDED → ACTIVE`; other transitions are rejected. The platform-admin UI
provides the matching list, create, read-only detail/edit, owner-invitation, and
confirmed lifecycle flows. The browser verification below exercises the complete
implemented onboarding and lifecycle journey.

### Business Services API

The selected Business comes from the authenticated server-side session. An
active `BUSINESS_OWNER` Membership for that Business is required; a
`PLATFORM_ADMIN` role alone does not grant access. Writes require CSRF and
update, deactivate, and reactivate requests carry `expectedVersion`.

| Method and path | Purpose |
|---|---|
| `GET /api/business/services?page=0&size=10&sort=name&direction=asc` | List active and inactive Services; `size` is exactly 10, 25, or 50; `sort` is `name`, `duration`, `price`, or `status` |
| `GET /api/business/services/{serviceId}` | Retrieve one tenant-scoped Service |
| `POST /api/business/services` | Create an active Service and return `201` with its `Location` |
| `PUT /api/business/services/{serviceId}` | Update an active or inactive Service |
| `POST /api/business/services/{serviceId}/deactivate` | Deactivate an active Service |
| `POST /api/business/services/{serviceId}/reactivate` | Reactivate an inactive Service |

Service requests and responses do not contain `businessId`. Lists use the
requested server-side sort with a deterministic tie-breaker (normalized name,
then ID). DRAFT and ACTIVE Businesses permit reads and
mutations; SUSPENDED Businesses remain readable but reject Service mutations.
The Business-owner UI is documented in
[task 04d](docs/tasks/04d-business-owner-configuration-frontend.md).

### Business StaffMember API

The selected Business and user identity come only from the authenticated
server-side session. Access requires an active `BUSINESS_OWNER` Membership for
that Business; `PLATFORM_ADMIN` alone, `MANAGER`, `STAFF`, inactive or missing
Memberships, and Memberships from another Business do not grant access. Writes
require CSRF, and every update, lifecycle transition, and assignment replacement
uses the StaffMember's `expectedVersion`.

| Method and path | Purpose |
|---|---|
| `GET /api/business/staff-members?page=0&size=10&sort=name&direction=asc` | List active and inactive StaffMembers; `size` is exactly 10, 25, or 50; `sort` is `name`, `status`, `phone`, or `email` |
| `GET /api/business/staff-members/{staffMemberId}` | Retrieve one tenant-scoped StaffMember |
| `POST /api/business/staff-members` | Create an active StaffMember and return `201` with its `Location` |
| `PUT /api/business/staff-members/{staffMemberId}` | Update an active or inactive StaffMember profile |
| `POST /api/business/staff-members/{staffMemberId}/deactivate` | Deactivate an active StaffMember |
| `POST /api/business/staff-members/{staffMemberId}/reactivate` | Reactivate an inactive StaffMember |
| `GET /api/business/staff-members/{staffMemberId}/service-assignments` | List safe ordered assigned-Service summaries |
| `PUT /api/business/staff-members/{staffMemberId}/service-assignments` | Replace the complete assigned-Service set atomically |

StaffMember profile and assignment administration remains available while the
StaffMember is inactive. Deactivating a StaffMember or Service preserves its
existing assignments. Replacement accepts active Services as additions;
already assigned inactive Services may be retained or removed, but a removed
inactive Service cannot be restored until reactivated. Every successful
replacement, including the same desired set, increments the shared StaffMember
aggregate version exactly once.

Requests and responses expose no Business, user, Membership, role, credential,
session, normalized, SQL, or persistence fields. DRAFT and ACTIVE Businesses
permit reads and mutations. SUSPENDED Businesses remain readable and reject
mutations with the safe public error contract. The Business-owner interface is
implemented by issue #14; issue #15 adds its browser end-to-end verification
(see "Browser end-to-end tests" below and `docs/testing-strategy.md`).

### Business StaffMember working-schedule API

The selected Business and user identity come only from the authenticated
server-side session. Access requires an active `BUSINESS_OWNER` Membership for
that Business; `PLATFORM_ADMIN` alone, `MANAGER`, `STAFF`, inactive or missing
Memberships, and foreign Memberships do not grant access. The PUT request
carries only `expectedVersion` and the complete desired period list; it never
supplies Business identity or timezone.

| Method and path | Purpose |
|---|---|
| `GET /api/business/staff-members/{staffMemberId}/working-schedule` | Retrieve a StaffMember's recurring weekly schedule |
| `PUT /api/business/staff-members/{staffMemberId}/working-schedule` | Atomically replace the complete desired week |

Each StaffMember owns exactly one independent schedule aggregate with its own
optimistic version, separate from the StaffMember aggregate version. Periods
use `MONDAY` through `SUNDAY` and canonical `HH:mm` times with one-minute
precision from `00:00` through `23:59`; PostgreSQL's special `24:00:00` value
and non-canonical lexical forms are rejected. Periods on one weekday cannot
overlap, but adjacent half-open periods are valid, and a request may contain
at most 100 periods. Responses order periods by weekday, then start time, then
end time, and also expose the live authoritative Business timezone.

DRAFT and ACTIVE Businesses permit reads and mutations; SUSPENDED Businesses
remain readable and reject mutations. Active and inactive StaffMembers retain
readable schedules, but only an active StaffMember may receive a mutation.
Deactivation and Business suspension preserve all schedule data. Requests and
responses expose no Business, user, Membership, role, credential, session,
normalized, SQL, or persistence fields. Schedule exceptions, time off, breaks,
working overrides, and availability calculation remain future work.

The development mailbox retains at most 50 links in memory and never logs,
writes, or persists raw tokens. Sessions use an opaque `SPOTYOURSESSION` cookie
with `HttpOnly`, `SameSite=Lax`, path `/`, a 12-hour lifetime, and `Secure` in
production. Idle expiry is two hours. Invitations last 48 hours and resets 30 minutes.
The process-local authentication limiter permits 10 attempts per 15-minute
fingerprint window, retains at most 10,000 SHA-256-only keys, removes expired
counters, and fails closed for unseen keys at capacity. Saturation can
temporarily reject new attempts; multi-instance enforcement remains a future
deployment concern.

### Checks

```bash
cd backend
./mvnw test
./mvnw verify

cd ../frontend
npm ci
npm run lint
npm run test
npm run build
```

### Browser end-to-end verification

Browser verification requires Java 25, Node.js `>=24.15.0 <25`, npm 11,
Docker with Compose, and free local ports `55432`, `18080`, and `15173`. After
`npm ci`, install the pinned Playwright Chromium browser once:

```bash
cd frontend
./node_modules/.bin/playwright install chromium
```

Run the browser suite from the repository root:

```bash
./scripts/run-e2e.sh
./scripts/run-e2e.sh --headed
./scripts/run-e2e.sh --headed --slow
./scripts/run-e2e.sh --headed --slow=1500
```

`--headed` displays Chromium. For local visual observation, `--slow` adds the
default 800 ms delay between Playwright browser actions, while
`--slow=<milliseconds>` selects a delay from 0 to 10000 ms. The test continues
automatically without manual interaction. CI remains headless with zero
slow-motion delay. Slow motion does not increase Playwright test timeouts, so
large values can cause otherwise valid tests to reach their existing timeout.
The tested 800 ms default is intended for visual observation; prefer moderate
values and reduce the delay if a local observation run times out.

The runner creates only the validated `spotyourslot-e2e` Compose project. It
uses PostgreSQL on port `55432`, Spring Boot on `18080`, and Vite on `15173`.
Every run recreates the dedicated database volume, starts Spring Boot so Flyway
applies all current Flyway migrations (V1–V8), generates local test credentials in
memory, and removes the E2E containers, network, and volume on exit. It neither
reuses nor removes the ordinary development Compose project or its volume.

The suite contains the Business onboarding and lifecycle journey, the startup
smoke check, and the Business-owner configuration journey (Services, Staff,
Service assignments, recurring weekly schedules, unsaved-changes protection,
DRAFT → ACTIVE → SUSPENDED → ACTIVE behavior, two-Business isolation, storage
and browser-context isolation, and a focused Pixel 7 mobile smoke check). Each
configuration setup attempt provisions its own Businesses and owners through
the administrator UI, the invitation flow, and the protected development
mailbox, using a random slug and email suffix, so a Playwright worker restart
never collides with earlier data. Credentials, invitation URLs, and cookies stay
in memory; screenshots, videos, traces, reports, and storage-state files are
disabled, and terminal output is redacted. Browser E2E complements, and does
not replace, the Vitest component tests and the backend PostgreSQL integration
tests, which own detailed validation, concurrency, and tenant-isolation
matrices.

If a run is interrupted before its exit trap completes, use this exact cleanup
command from the repository root:

```bash
docker compose --project-name spotyourslot-e2e --file compose.yaml down --volumes --remove-orphans
```

### Stop local services

```bash
docker compose down
```

This keeps the named database volume. Removing it is intentionally not part of
the normal command because that destroys local data.

### Troubleshooting

- **Port already in use:** set `POSTGRES_PORT` in `.env`, `SERVER_PORT` for the
  backend, or stop the conflicting process. Vite intentionally fails rather
  than silently switching from port 5173.
- **Backend database authentication failure:** ensure exported shell values
  match `.env`, then check `docker compose ps` and `docker compose logs postgres`.
- **Wrong Java/Node version:** Maven Enforcer and npm `engines` reject unsupported
  toolchains; select Java 25 and Node 24 LTS.
- **Stale frontend install:** use `npm ci`, which recreates dependencies exactly
  from `package-lock.json`.
- **Database not ready:** wait for the Compose health status before starting the
  backend.
- **403 on a browser POST:** fetch `/api/auth/csrf`, send `X-XSRF-TOKEN`, and
  confirm the frontend origin exactly matches `ALLOWED_ORIGIN`.
- **Cookie missing locally:** keep `SESSION_COOKIE_SECURE=false` only for local
  HTTP development; production uses `true` and HTTPS.
- **Unexpected logout:** check the two-hour idle and 12-hour absolute limits or
  whether credentials were changed/reset.
- **E2E port conflict:** free ports `55432`, `18080`, and `15173`, or provide
  distinct non-development values through `E2E_POSTGRES_PORT`,
  `E2E_BACKEND_PORT`, and `E2E_FRONTEND_PORT`.
- **E2E Docker startup failure:** confirm `docker info` and `docker compose
  version` succeed and Docker has enough resources for PostgreSQL and Spring
  Boot.
- **Playwright browser missing:** from `frontend`, run
  `./node_modules/.bin/playwright install chromium` after `npm ci`.
- **Unsupported E2E Node version:** select Node.js `>=24.15.0 <25`; the frontend
  package engine rejects other versions.
- **Interrupted E2E run:** run only the explicit `spotyourslot-e2e` cleanup
  command above. Do not add `--volumes` to the ordinary development cleanup.

## Current scope boundary

The MVP uses one booking engine for `HAIR_SALON`, `BARBERSHOP`, `NAIL_STUDIO`,
`MASSAGE_STUDIO`, `MAKEUP_STUDIO`, `BEAUTY_STUDIO`, and `OTHER`. BusinessType is
descriptive only. Medical/health workflows, groups, shared-resource scheduling,
recurrence, travel-time calculation, multiple locations, industry-specific
forms/workflows, and marketplace discovery are explicitly excluded alongside
the broader exclusions in the product specification.
