# ADR-0005: Use server-managed cookie authentication

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-08-12
- **Recorded date:** 2026-08-25
- **Related issues:** #1, #2, #3, #4, #5, #6, #8
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

SpotYourSlot needs browser authentication for platform and Business
administration while keeping current identity, session lifecycle, and
authorization state authoritative on the server. Authentication also needs to
support prompt logout, credential-change invalidation, idle and absolute
expiry, and a selected Business context that does not itself grant tenant
access.

The product foundation selected a secure server-managed session transported by
a browser cookie. This record explains that decision and its implemented
security boundaries without claiming that cookies are universally safer than
token-based designs.

## Evidenced constraints

Repository evidence establishes that:

- administrative authentication uses server-managed cookie sessions;
- cookie-authenticated state-changing requests require CSRF protection;
- credentialed cross-origin browser access is limited to one configured exact
  origin;
- authentication and authorization are recalculated from current server-side
  User, platform-role, Membership, Business, and session state as applicable;
- tenant selection is not proof of tenant authorization; and
- script-accessible or frontend-managed persistence such as `localStorage`,
  `sessionStorage`, or IndexedDB must not hold authentication tokens or session
  identifiers; the browser-managed `HttpOnly` cookie stores and sends the
  authentication credential.

The foundation selected the session approach on 2026-08-12 but left exact
persistence and lifetime values for later approval. Phase 2 subsequently set a
12-hour absolute lifetime, a two-hour idle timeout, and PostgreSQL-backed
session lifecycle rules.

ADR-0003 owns Membership and tenant authorization modelling, ADR-0006 will own
invitation and reset token lifecycle, and ADR-0008 will own authentication rate
limiting. Frontend interaction design and future identity-provider integration
are outside this record.

## Options considered

Server-managed cookie sessions are the only option directly documented as
selected in the historical repository. The alternatives below are a
retrospective assessment, not evidence that they were debated on 2026-08-12.

### Server-managed opaque sessions referenced by cookies

The browser automatically transports an opaque credential while the server
retains mutable session and authorization state. This supports immediate
server-side revocation and keeps roles out of a client-managed token, but it
requires session storage, cleanup, database availability, and explicit CSRF
defences.

### Self-contained JWT access tokens managed by the client — retrospective assessment

JWTs can be verified without a session lookup, carry signed claims across
services, and reduce centralized session-state reads. Client handling and key
rotation add complexity; claims can become stale until expiry unless a
revocation or introspection mechanism is added. Their storage and transport
also need deliberate XSS, CSRF, and leakage controls.

### Opaque bearer tokens in an Authorization header — retrospective assessment

Explicit headers avoid the browser's ambient cookie attachment and are useful
for non-browser API clients. The frontend must acquire and protect the token,
attach it to requests, and handle renewal. A JavaScript-accessible token can be
exfiltrated by successful script injection, while server-side lookup and
revocation are still needed if the token is opaque.

### Framework-managed default HTTP sessions — retrospective assessment

Framework sessions provide mature lifecycle integration and reduce custom
filtering code. Their default storage and behavior may not provide the explicit
PostgreSQL schema, hash-only credential lookup, credential-version checks, and
tenant-context semantics selected here without further configuration or
extension.

### External identity provider or OAuth/OIDC — retrospective assessment

An external provider can supply federation, mature login controls, MFA, and
delegated credential operations. It introduces provider availability,
configuration, redirect and token lifecycle, account-linking, tenant-role
mapping, privacy, and commercial dependencies. Social login is outside the
current MVP, but OIDC may become appropriate under later requirements.

## Decision

Authenticate administrative browser requests with a high-entropy opaque
session credential stored in the `SPOTYOURSESSION` cookie. Persist only its
SHA-256 hash and mutable session state in PostgreSQL. The cookie contains no
User profile, role, Membership, Business status, or authorization claims.

Resolve the credential on each request and construct authentication from
current server-side state. Keep selected Business context in the session as a
convenience pointer; authorize tenant operations independently through current
Membership, role, Business-state, and resource-ownership checks.

## Authentication and session semantics

Successful login verifies the normalized User identity, active and unlocked
account state, and password before generating a new 256-bit random opaque
credential. The raw credential is returned only in the authentication cookie;
its SHA-256 hash indexes a new `user_session` row.

Server-side session state contains the session ID, User ownership, optional
selected Business ID, credential version, creation and last-activity times,
absolute expiry, and revocation time. On a request carrying the cookie, the
session filter hashes the credential, loads the session and current User and
platform-role state, and accepts it only when:

- the session is not revoked;
- the User remains active and unlocked;
- the session credential version matches the User credential version; and
- neither the two-hour idle timeout nor 12-hour absolute lifetime has elapsed.

A valid request updates last activity. An invalid lifecycle state causes the
known session row to be revoked and no authenticated identity is installed.
Missing or unknown credentials likewise leave protected requests
unauthenticated.

Logout revokes the current database row and returns an expired session cookie.
Changing a password advances the User credential version, updates the current
session to that version, and revokes the User's other sessions. Password reset
revokes all sessions. Account active/locked changes and credential-version
mismatches therefore take effect when a session is next evaluated rather than
waiting for a self-contained client claim to expire.

Selected tenant context remains separate from authorization. When its active
Membership is no longer valid, the filter clears only that context and
preserves otherwise valid authentication. `PLATFORM_ADMIN` is derived
separately from current platform-role state, and Business roles are not stored
in the cookie.

## Cookie, CSRF, and CORS security model

`SPOTYOURSESSION` is `HttpOnly`, `SameSite=Lax`, scoped to path `/`, persistent
for 12 hours, and `Secure` in production. No `Domain` attribute is set, so it is
host-only. Local HTTP development may explicitly disable `Secure`; production
configuration enables it and requires HTTPS.

These attributes reduce exposure but do not eliminate session theft or XSS
risk. `HttpOnly` prevents ordinary script reads of the session credential; it
does not prevent CSRF, and injected script may still act with the browser's
authority. `SameSite=Lax` reduces some cross-site attachment but is not the sole
CSRF control.

Spring Security uses a separate `XSRF-TOKEN` cookie and requires its value in
the `X-XSRF-TOKEN` header for unsafe requests, including login. That CSRF cookie
is deliberately readable so the frontend can send the header; it is not the
authentication cookie. The frontend obtains the CSRF token through
`GET /api/auth/csrf`, keeps it in process memory, and sends browser credentials
with requests.

CORS permits credentials only for the configured exact origin and the approved
methods and headers. CORS controls whether an allowed frontend origin may read
cross-origin responses and whether credentialed or preflighted cross-origin
requests are permitted by the browser. It does not prevent every cross-origin
request, is not authentication, and does not replace CSRF protection. CSRF
protects against forged ambient-credential mutations; it does not replace CORS.

## Rationale

Keeping session state authoritative on the server allows revocation, account
disablement, password changes, and current role changes to affect later
requests without waiting for embedded authorization claims to expire. It also
keeps the browser-facing credential small and free of trusted identity or role
data.

The selected model fits a browser-first administrative application and the
existing PostgreSQL-backed identity module. It gives logout a server-side
effect and permits stale selected-Business context to be removed without
destroying valid global authentication.

These benefits are contextual. Other transport and identity models can be
preferable for public APIs, independently deployed services, native clients,
federation, or different scalability and availability requirements.

## Tradeoffs and disadvantages

- Authentication requires a PostgreSQL session lookup and activity update on
  authenticated requests, adding latency and database load.
- A database outage can prevent session validation and therefore authenticated
  operation.
- Session rows consume durable storage; the current implementation has no
  evidenced scheduled cleanup for expired or revoked rows.
- Cookie transport creates an ambient credential and therefore requires robust
  CSRF protection and careful cross-origin configuration.
- `HttpOnly` limits direct credential theft by script but cannot prevent an XSS
  payload from issuing authorized requests in the victim's browser.
- A shared session store couples authentication availability and scaling to the
  database. The repository does not yet prove production readiness for multiple
  application instances, connection load, cleanup, or failover.
- Non-browser clients may find cookie and CSRF handling less natural than an
  explicit Authorization header.

## Risks and mitigations

Session theft permits use until expiry or server-side revocation. Use HTTPS,
`Secure`, `HttpOnly`, `SameSite=Lax`, a host-only cookie, restrictive browser
headers, bounded lifetimes, credential-change invalidation, and redacted logs.
Do not expose cookie or raw session values in API responses, script-accessible
or frontend-managed persistence, diagnostics, or normal logs.

CSRF remains a risk because the browser attaches the authentication cookie
automatically. Require the separate CSRF token on unsafe methods, retain exact-
origin credentialed CORS, and return safe filter-level failures. Neither control
compensates for a broken authorization check.

Stale or attacker-selected tenant context could be mistaken for permission.
Treat it only as a selected identifier, revalidate active Membership and
operation-specific authorization, and clear invalid context without elevating
or impersonating a Business user.

Operationally, define bounded retention and cleanup for expired/revoked
sessions before production scale, monitor session-store load, and assess shared
storage and availability needs before adding application instances. Cookie
domain, proxy, TLS, deployment, and failover settings require explicit
production approval rather than assumptions from local defaults.

## Consequences

Browser requests use credentialed fetches; unsafe operations first obtain and
send the CSRF token. Application code must never treat cookie contents or
frontend session state as authorization facts. Protected requests depend on
current server-side session and identity data.

New account-security operations must specify their effect on the current and
other sessions. New tenant operations must continue to enforce ADR-0003 rather
than rely on `active_business_id`. Production readiness must include session
retention, cleanup, database capacity and availability, TLS/proxy behavior, and
cookie configuration review.

## Direct historical evidence

- The original [architecture](../architecture.md) shows browser administration
  using an HTTPS/JSON secure session cookie and states that administrators use
  server-managed `Secure`/`HttpOnly`/`SameSite` sessions with CSRF protection.
  Git commit `9cf10e0` first recorded this on 2026-08-12 for issue #1.
- The original [security design](../security.md) selects opaque server-managed
  sessions, cookie hardening, login and unsafe-method CSRF protection, and
  exact-origin credentialed CORS. It explicitly left persistence and lifetime
  details for later approval.
- The [foundation task](../tasks/00-product-foundation.md) records cookie
  sessions, CSRF, and exact-origin CORS as approved security requirements.
- The [implementation plan](../implementation-plan.md) assigns sessions,
  CSRF/CORS, roles, and tenant context to Phase 2.
- The [identity and tenancy task](../tasks/02-identity-and-tenancy.md) approves
  secure server-managed HTTP-only cookie authentication, PostgreSQL session
  state, the 12-hour/two-hour lifecycle, server-side authorization
  revalidation, and prohibition of browser authentication storage. Issue #3
  implemented those rules in commit `ed27ee0` on 2026-08-14.
- [V1](../../backend/src/main/resources/db/migration/V1__identity_and_tenancy.sql)
  defines the hash-only `user_session` record and its lifecycle and selected-
  Business fields.
- Current login and lifecycle behavior is implemented by
  [AuthenticationService](../../backend/src/main/java/bg/spotyourslot/identity/application/AuthenticationService.java),
  [DatabaseSessionFilter](../../backend/src/main/java/bg/spotyourslot/identity/infrastructure/DatabaseSessionFilter.java),
  [IdentityStore](../../backend/src/main/java/bg/spotyourslot/identity/infrastructure/IdentityStore.java),
  [AuthController](../../backend/src/main/java/bg/spotyourslot/identity/web/AuthController.java),
  and [SessionPolicy](../../backend/src/main/java/bg/spotyourslot/identity/domain/SessionPolicy.java).
- [SecurityConfiguration](../../backend/src/main/java/bg/spotyourslot/identity/configuration/SecurityConfiguration.java)
  defines the current authentication filter, cookie CSRF repository,
  exact-origin credentialed CORS, protected routes, and safe pre-controller
  failures. Issue #5 extended the allowed methods and structured denial behavior
  for the platform API under parent issue #4.
- [Authentication API tests](../../backend/src/test/java/bg/spotyourslot/integration/AuthenticationApiIntegrationTests.java)
  verify login cookies, CSRF, logout revocation, expiration, account state,
  selected-Business revalidation, role separation, and exact-origin CORS.
  [Identity lifecycle tests](../../backend/src/test/java/bg/spotyourslot/integration/IdentityLifecycleIntegrationTests.java)
  verify password-change and reset invalidation. The
  [production-profile tests](../../backend/src/test/java/bg/spotyourslot/integration/ProductionProfileIntegrationTests.java)
  verify production CSRF-cookie attributes. These tests establish current
  behavior, not the original comparative reasoning or production readiness.
- The frontend [request helper](../../frontend/src/identity/api.ts) uses
  credentialed requests, retrieves and retains CSRF state in memory, and does
  not manage the authentication cookie. Issue #6 preserves the prohibition on
  authentication secrets in script-accessible or frontend-managed persistence
  in the
  [platform onboarding frontend task](../tasks/03b-platform-admin-onboarding-frontend.md).
- The current [product specification](../product-spec.md),
  [README](../../README.md), and [testing strategy](../testing-strategy.md)
  record the approved and implemented session behavior and its verification.

## Retrospective inference

The comparison with client-managed JWTs, Authorization-header bearer tokens,
default framework sessions, and external identity providers is later analysis.
The repository does not show that those options were historically evaluated in
August 2026.

Server-authoritative state is a good fit for the current need for revocation and
fresh authorization, while accepting a database request and availability cost.
That comparative balance is consistent with the implementation but is not
proof of the original motivation. Current tests likewise demonstrate present
behavior rather than historical reasoning or production-scale resilience.

## Conditions for revisiting

Revisit this decision if independently deployed services, public API clients,
native applications, federation, SSO/MFA requirements, database load, regional
availability, or measured multi-instance scaling needs make the current model
inadequate.

Any replacement must define credential transport and storage, revocation,
rotation, CSRF/XSS exposure, authorization freshness, tenant-context handling,
logout semantics, key or session lifecycle, migration of active sessions,
observability, and failure behavior. A change should follow measured needs and
explicit threat analysis; moving away from server-managed cookies is not
automatically safer or simpler.
