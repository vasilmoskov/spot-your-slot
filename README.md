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

This repository contains the Phase 2 identity and tenancy foundation and the
Phase 3 platform Business onboarding foundation: a Spring Boot backend, a
Bulgarian React identity and platform-admin client, Flyway-managed PostgreSQL,
local Docker Compose, and non-deploying CI. Platform administrators can list,
create, inspect, edit, invite an initial owner for, activate, suspend, and
reactivate Businesses. Services, StaffMembers, Customers, Appointments,
booking, production email, and hosting are not implemented.

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
four current migrations on startup and Hibernate validates the schema.

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
| `GET /api/platform/businesses` | List Businesses (`page=0`, `size=50`, maximum 100) |
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
confirmed lifecycle flows; corrected visual and invitation retesting remains
pending.

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

## Current scope boundary

The MVP uses one booking engine for `HAIR_SALON`, `BARBERSHOP`, `NAIL_STUDIO`,
`MASSAGE_STUDIO`, `MAKEUP_STUDIO`, `BEAUTY_STUDIO`, and `OTHER`. BusinessType is
descriptive only. Medical/health workflows, groups, shared-resource scheduling,
recurrence, travel-time calculation, multiple locations, industry-specific
forms/workflows, and marketplace discovery are explicitly excluded alongside
the broader exclusions in the product specification.
