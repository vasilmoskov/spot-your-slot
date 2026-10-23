# Development guide

How to run SpotYourSlot locally, run its checks, operate the browser E2E suite and clean up. For the overall picture
start with the [README](../README.md); for how the system is built see [architecture.md](architecture.md).

## Prerequisites and ports

- A Java 25 JDK (for example Temurin; `java -version`)
- Node.js `>=24.15.0 <25` (24 LTS; an earlier 24.x release is not enough) and npm 11 (`node --version && npm --version`), enforced by the frontend `engines` field
- Docker with Compose (`docker compose version`)
- Maven is not required: the committed wrapper (`backend/mvnw`) downloads it

| Service | Default address | Override |
|---|---|---|
| PostgreSQL (Compose) | `localhost:5432` | `POSTGRES_PORT` in `.env` |
| Backend | `localhost:8080` | `SERVER_PORT` |
| Frontend (Vite) | `localhost:5173` | fixed; Vite fails rather than switching ports |

## Environment and PostgreSQL

From the repository root:

```bash
cp .env.example .env
```

`.env.example` contains only local placeholders. Change `POSTGRES_PASSWORD` in your `.env` and never commit it (it is
git-ignored). Start and inspect PostgreSQL:

```bash
docker compose up -d postgres
docker compose ps
```

The named volume `spotyourslot-postgres-data` keeps your local data between runs; Compose creates the database
`spotyourslot`. The database port is bound to `127.0.0.1` only.

## Backend

Compose reads `.env`, the shell does not, so export the same credentials, then run the application:

```bash
# from the repository root
cd backend
export POSTGRES_USER=spotyourslot
export POSTGRES_PASSWORD='the-value-from-your-local-env-file'
./mvnw spring-boot:run
```

Health is public at `http://localhost:8080/actuator/health`. Flyway applies all migrations (`V1`–`V15`) on startup and
Hibernate validates the schema. The default profile is `dev`, which ships non-secret development keys (for example for
the booking request fingerprint); production must supply its own secrets and is rejected for development shortcuts.

### First local platform administrator

Creating the first `PLATFORM_ADMIN` is an explicit, one-startup opt-in. Use your own local-only values:

```bash
# in the same shell, from backend/
export BOOTSTRAP_ADMIN_ENABLED=true
export BOOTSTRAP_ADMIN_EMAIL='admin@example.invalid'
export BOOTSTRAP_ADMIN_DISPLAY_NAME='Local Administrator'
export BOOTSTRAP_ADMIN_PASSWORD='choose-a-long-local-only-password'
./mvnw spring-boot:run
```

The operation is idempotent, has no default credential and is rejected in production. Unset the variables afterwards.
The `example.invalid` domain is reserved and never routable; invitation and reset links are not emailed in development:
a platform administrator can read them from `GET /api/dev/mailbox`, which exists only outside production and keeps at
most 50 links in memory.

### Configuration reference

| Variable | Purpose | Default |
|---|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT` | database name, credentials, port (Compose and backend) | `spotyourslot`, `spotyourslot`, none, `5432` |
| `SERVER_PORT` | backend port | `8080` |
| `ALLOWED_ORIGIN` | the one exact origin allowed for credentialed CORS | `http://localhost:5173` |
| `SESSION_COOKIE_SECURE` | `Secure` flag of the session cookie; keep `false` only for local HTTP | `false` |
| `BOOTSTRAP_ADMIN_*` | one-time first administrator (above) | disabled |
| `BOOKING_RATE_LIMIT_*`, `BOOKING_MAX_REQUEST_BODY_BYTES` | public booking limiter budgets and body bound ([ADR-0026](decisions/ADR-0026-expose-guest-booking-through-narrow-public-contracts-with-session-independent-endpoints-and-bounded-abuse-protection.md)) | see `application.yaml` |
| `SPOTYOURSLOT_BOOKING_FINGERPRINT_ACTIVE_KEY_VERSION`, `SPOTYOURSLOT_BOOKING_FINGERPRINT_KEYS_<version>` | HMAC keys of the booking request fingerprint (Base64 of at least 32 random bytes); required in production | development and test profiles ship non-secret keys |

## Frontend

In a second terminal (its own working directory, so start from the repository root):

```bash
# from the repository root
cd frontend
npm ci
npm run dev
```

Open `http://localhost:5173`. The client supports login, password reset, invitation acceptance, Business selection,
the platform-administrator and Business-owner interfaces, and the public Business page at `/{businessSlug}`. It stores no
authentication token in browser storage. A typical first run: sign in as the platform administrator, create a Business
and invite its owner, read the invitation link from the development mailbox endpoint, accept it as the owner, and
configure Services, StaffMembers and schedules.

## Checks

Run each block from the repository root; the parentheses keep the root as the working directory.

```bash
(cd backend && ./mvnw --batch-mode verify)     # unit, slice and Testcontainers integration tests (needs Docker)
(cd frontend && npm ci && npm run lint && npm run test && npm run build)   # build is tsc -b && vite build
```

## Browser end-to-end tests

The Playwright suite drives real Chromium against the real backend and a disposable PostgreSQL. It needs Java 25,
Node.js `>=24.15.0 <25`, npm 11, Docker with Compose and the free local ports `55432`, `18080` and `15173`. After
`npm ci`, install the pinned browser once:

```bash
# from the repository root
(cd frontend && ./node_modules/.bin/playwright install chromium)
```

Run the suite from the repository root:

```bash
./scripts/run-e2e.sh
./scripts/run-e2e.sh --headed
./scripts/run-e2e.sh --headed --slow          # 800 ms between actions, for watching
./scripts/run-e2e.sh --headed --slow=1500     # 0 to 10000 ms
```

The runner creates only the validated `spotyourslot-e2e` Compose project: PostgreSQL on `55432`, Spring Boot on `18080`
and Vite on `15173`. Every run recreates that project's database volume, lets Flyway migrate from empty, generates test
credentials in memory and removes the E2E containers, network and volume on exit. It never reuses or removes the
ordinary development project or its volume. Override the ports with `E2E_POSTGRES_PORT`, `E2E_BACKEND_PORT` and
`E2E_FRONTEND_PORT` (distinct, non-development values).

Each journey provisions its own Businesses and owners through supported APIs and the invitation flow with random
suffixes, keeps credentials, invitation URLs and cookies in memory, and disables screenshots, videos, traces and reports;
terminal output is redacted. Slow motion does not raise Playwright's timeouts, so large delays can time out valid tests.
Browser E2E complements the component and PostgreSQL integration tests, which own the detailed validation, concurrency
and tenant-isolation matrices. What the suite proves, and the real, proxy and synthetic distinction, is in
[testing-strategy.md](testing-strategy.md).

## Stopping and cleanup

Normal shutdown keeps your data:

```bash
docker compose down
```

**Destructive cleanup.** Removing the development volume deletes all local development data and is never part of the
normal shutdown. Run it only if you want a clean database:

```bash
docker compose down --volumes
```

If an E2E run was interrupted before its exit handler completed, remove only the disposable E2E project (the explicit
project name is what keeps the development project safe):

```bash
docker compose --project-name spotyourslot-e2e --file compose.yaml down --volumes --remove-orphans
```

## Troubleshooting

- **Port already in use:** set `POSTGRES_PORT` in `.env`, `SERVER_PORT` for the backend, or stop the conflicting process.
- **Backend database authentication failure:** the exported shell values must match `.env`; check `docker compose ps` and
  `docker compose logs postgres`.
- **Database not ready:** wait for the Compose health status before starting the backend.
- **Wrong Java or Node version:** Maven Enforcer and the npm `engines` field reject unsupported toolchains; select Java 25
  and Node `>=24.15.0 <25`.
- **Stale frontend install:** use `npm ci`, which recreates dependencies exactly from `package-lock.json`.
- **403 on a browser POST:** fetch `/api/auth/csrf`, send `X-XSRF-TOKEN`, and confirm the frontend origin exactly matches
  `ALLOWED_ORIGIN`.
- **Session cookie missing locally:** keep `SESSION_COOKIE_SECURE=false` for local HTTP only; production uses `true`
  and HTTPS.
- **Unexpected logout:** sessions idle out after two hours and last at most 12 hours; a password change or reset also
  revokes sessions.
- **E2E Docker startup failure:** confirm `docker info` and `docker compose version` succeed and Docker has enough
  resources for PostgreSQL and Spring Boot.
- **Playwright browser missing:** run `(cd frontend && ./node_modules/.bin/playwright install chromium)` from the repository root after `npm ci`.
- **Unsupported E2E Node version:** select Node.js `>=24.15.0 <25`.
