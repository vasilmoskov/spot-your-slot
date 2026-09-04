# ADR-0008: Use bounded process-local authentication rate limiting for the MVP

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-08-14
- **Recorded date:** 2026-08-25
- **Related issues:** #1, #3, #4, #6, #8
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

Public authentication operations can be automated to guess credentials, submit
many recovery requests, or repeatedly try invitation and reset tokens. Generic
authentication responses reduce account-enumeration information but do not
bound request volume. The MVP therefore needs an initial abuse control without
introducing distributed infrastructure before it is justified.

The product foundation selected conservative per-endpoint limits and allowed an
in-process limiter for one application instance. Phase 2 made that direction
concrete with a bounded, deterministic implementation and safe rejection
behavior.

## Evidenced constraints

Repository evidence establishes that:

- the initial deployment assumption permits a simple process-local protection,
  while multi-instance enforcement is deferred;
- Redis and other unproven infrastructure are outside the current MVP scope;
- login and recovery responses must not expose whether an account exists;
- raw passwords, emails and security tokens must not be retained in limiter
  state or logs;
- public failures require stable, safe Bulgarian responses; and
- memory reachable through public requests must be bounded and have explicit
  expiry and saturation behavior.

ADR-0005 owns cookie/session authentication, ADR-0006 owns invitation and reset
token lifecycle, and ADR-0009 will own real-PostgreSQL testing. Account state,
password policy and session invalidation remain separate controls.

## Options considered

The bounded process-local design is the only option directly documented as
selected. The other options below are a retrospective assessment, not evidence
that they were debated on 2026-08-14.

### No application-level authentication rate limiting — retrospective assessment

Relying only on correct credentials and generic errors keeps the application
simple and avoids false positives. It leaves the implemented authentication
endpoints without an application-owned request-volume bound and depends wholly
on controls that may or may not exist upstream.

### Unbounded process-local counters — retrospective assessment

Per-process counters are simple, fast and do not require another service. If
arbitrary request characteristics can create entries without a capacity bound,
an attacker can grow memory consumption indefinitely.

### Bounded process-local rate limiting

A bounded in-memory limiter is inexpensive to operate, deterministic to test
with a controllable clock, and adequate as an initial control for the evidenced
single-instance MVP assumption. Its limits are local to one process, disappear
on restart, and cannot provide a cluster-wide view.

### Database-backed or distributed rate limiting — retrospective assessment

Shared state can enforce one policy across application instances and survive an
individual process restart. It adds network or database load, coordination,
expiry, availability and operational concerns. That cost is not currently
justified, but the option becomes relevant before horizontal scaling.

### Reverse proxy, API gateway, WAF or external service — retrospective assessment

Upstream enforcement can reject traffic before application work, aggregate
across instances and offer broader network signals. It requires a defined and
trusted deployment topology, external configuration, monitoring and failure
policy that the repository has not yet selected. It may complement rather than
replace application-level limits.

### Account lockout as the primary defence — retrospective assessment

Account lockout can impose a durable barrier against repeated attempts on one
known identity. It can also enable denial of service against a victim account,
creates recovery and support requirements, and does not address token-oriented
or network-wide abuse. The current `locked` account state is not used as an
automatic rate-limit counter.

## Decision

For the MVP, use a bounded process-local fixed-window limiter on the implemented
public authentication operations. Keep its state in application memory, use a
composite fingerprint rather than raw sensitive request input, expire counters
after a fixed interval, and reject new keys when the bounded store is saturated.

This is an initial defence for the current deployment assumptions. It is not a
global or durable rate limit and does not replace safe errors, password hashing,
token lifecycle protections, account controls, monitoring or future upstream
protection.

## Rate-limit semantics

The current controller applies the limiter before these operations:

- `POST /api/auth/login`, with flow `login` and the submitted email;
- `POST /api/auth/password/forgot`, with flow `forgot` and the submitted email;
- `POST /api/auth/password/reset`, with flow `reset` and the submitted token;
  and
- `POST /api/auth/invitations/accept`, with flow `invitation` and the submitted
  token.

Logout, current-session lookup, Business selection, authenticated password
change, CSRF-token retrieval and platform invitation creation are not currently
rate-limited by this component. Future public booking endpoints are outside the
implemented Phase 2 scope even though permanent security requirements call for
their own limits.

For each request, the limiter constructs one composite key from the flow,
`HttpServletRequest.getRemoteAddr()`, and the supplied email or token, separated
by null characters. It stores only the lowercase hexadecimal SHA-256 digest of
that material. This is one combined dimension, not an independent per-address
bucket plus an independent per-identity bucket. The limiter does not normalize
the three inputs itself. The password is not included in the login key.

Each distinct digest receives ten allowed attempts in a fixed 15-minute window
starting with its first accepted attempt. The eleventh attempt is rejected.
Every allowed call consumes an attempt before the underlying operation runs,
regardless of that operation's outcome. A genuinely successful login removes
only the exact `login` fingerprint after credentials have been verified. A key
that has already reached its limit is rejected before authentication, so even
correct credentials cannot bypass the active window. Failed login, forgot,
reset and invitation calls do not reset counters; successful non-login flows do
not currently reset them either.

The synchronized in-memory map retains at most 10,000 digests. Before each
decision it removes entries whose fixed window has expired. At capacity, an
already-known key can continue within its existing allowance, but a previously
unseen key is rejected until capacity becomes available. This fail-closed
behavior keeps memory bounded at the cost of availability for new keys during
saturation.

A rejected request becomes HTTP 429 with stable code `RATE_LIMITED` and safe
Bulgarian detail “Твърде много опити. Опитайте по-късно.” The implementation
does not currently return `Retry-After`.

## Trust and deployment boundaries

The application passes `getRemoteAddr()` directly to the limiter and does not
parse forwarding headers in limiter code. Production configuration defaults
Spring's forwarded-header strategy to `none`; no trusted-proxy allow-list or
final production proxy topology is implemented. If forwarding behavior is
enabled later, the deployment must define which proxies are trusted before a
forwarded client address can be treated as reliable.

Counters belong to one application process. A restart clears them, and multiple
instances would maintain independent allowances. The composite key also means
that attempts against one identity from multiple addresses, or many identities
from one address, are not aggregated into a separate identity-only or
network-only limit. Shared NATs, proxies and address churn can therefore produce
both false-positive and evasion tradeoffs depending on the traffic pattern.

## Rationale

The selected mechanism adds a deterministic bound around the four implemented
public authentication flows without adding excluded infrastructure. Hash-only
keys reduce sensitive data retained in diagnostic state, while expiry and the
10,000-entry ceiling prevent unbounded memory growth. Synchronization makes each
in-process decision atomic, and fail-closed saturation favors bounded resource
use over availability for new fingerprints.

Generic responses and rate limiting address different risks: safe errors reduce
identity disclosure, while the limiter slows repeated operations for one exact
composite key. Neither control alone, nor both together, stops every brute-force,
credential-stuffing, enumeration or denial-of-service strategy.

## Tradeoffs and disadvantages

- Limits are neither durable nor shared across instances.
- The fixed window permits bursts around a window boundary.
- One composite key is easier to operate but provides less coverage than
  coordinated identity-only and address-only dimensions.
- Case, whitespace or other representational changes in input produce different
  fingerprints because limiter-side normalization is absent.
- Shared addresses and proxies can affect fairness, while distributed sources
  can evade an address component.
- Fail-closed capacity protects memory but can deny legitimate first attempts.
- No `Retry-After` header tells clients when to retry.
- Synchronized access is simple but can become a contention point at higher
  request volume.

## Risks and mitigations

Attackers can distribute requests across addresses or input variants. Preserve
generic errors and credential/token controls, monitor abuse when operational
telemetry is approved, and add independently keyed or upstream dimensions only
after their privacy and denial-of-service effects are reviewed.

Untrusted forwarding headers can let a client influence an apparent source
address. Keep forwarded-header handling disabled by default and require an
explicit trusted-proxy design before enabling it.

Capacity exhaustion can reject legitimate unseen keys. The current mitigation
is deterministic expiration after 15 minutes and an explicit 10,000-entry bound;
production observation must determine whether that availability tradeoff and
capacity remain appropriate. The digest limits raw-value retention but is not a
substitute for log redaction or careful access to process memory.

## Consequences

Authentication endpoints added to this protection must choose a deliberate flow
name and sensitive-input component, preserve safe rejection behavior, and add
deterministic boundary, expiry and isolation tests. A reset must occur only
after the protected operation has genuinely succeeded and must target only the
intended fingerprint.

Deploying more than one application instance requires review of shared or
upstream enforcement before treating limits as global. Operational readiness
also requires explicit proxy trust, monitoring, alerting and denial-of-service
planning; those facilities are not supplied by this ADR.

## Direct historical evidence

- [`docs/security.md`](../security.md) records the initial in-process direction,
  exact implemented limits, hash-only keys, memory bound, expiry, saturation
  behavior and multi-instance limitation. Its initial rate-limit direction was
  committed on 2026-08-12 in `9cf10e0` for issue #1.
- [`docs/tasks/02-identity-and-tenancy.md`](../tasks/02-identity-and-tenancy.md)
  selects a simple in-process strategy for the initial single-instance MVP,
  names the four public authentication flows, prohibits Redis, requires safe
  429 responses and warns against untrusted forwarding headers.
- [`AuthenticationRateLimiter.java`](../../backend/src/main/java/bg/spotyourslot/identity/application/AuthenticationRateLimiter.java)
  implements the synchronized fixed window, SHA-256 composite key, 10-attempt
  limit, 15-minute expiry, 10,000-entry capacity and fail-closed saturation.
- [`AuthController.java`](../../backend/src/main/java/bg/spotyourslot/identity/web/AuthController.java)
  shows the protected endpoints, exact key inputs, use of `getRemoteAddr()`,
  pre-operation limiting and post-authentication login reset.
- [`ApiExceptionHandler.java`](../../backend/src/main/java/bg/spotyourslot/shared/web/ApiExceptionHandler.java)
  maps the rejection to the safe 429 problem response without `Retry-After`.
- [`application-prod.yaml`](../../backend/src/main/resources/application-prod.yaml)
  defaults forwarded-header processing to `none` while allowing an explicit
  environment override.
- [`IdentityDomainTests.java`](../../backend/src/test/java/bg/spotyourslot/identity/IdentityDomainTests.java)
  exercises the eleventh-attempt rejection, capacity behavior, expiry cleanup
  and exact-key reset.
- [`AuthenticationApiIntegrationTests.java`](../../backend/src/test/java/bg/spotyourslot/integration/AuthenticationApiIntegrationTests.java)
  exercises the safe 429 contract, successful-login reset and the rule that a
  currently blocked key cannot bypass the limit with correct credentials.
- Commit `ed27ee0` implemented the bounded limiter and authentication flows on
  2026-08-14 for issue #3. Commit `14a4107` added exact-key reset after genuine
  login success on 2026-08-17 for issue #6, a subissue of parent issue #4.

These files and tests demonstrate the selected and implemented behavior. They do
not prove that every retrospective alternative was originally considered or
that the limiter is sufficient for production traffic and attack patterns.

## Retrospective inference

The comparative assessment of no limiter, unbounded counters, shared storage,
upstream enforcement and account lockout is later analysis. The repository does
not document those alternatives as an original deliberation.

The bounded local design is a reasonable low-operational-cost starting point
under the documented single-instance MVP assumption. Its fit depends on actual
deployment topology, traffic, abuse patterns and acceptable false positives;
current tests do not establish those production conditions.

## Conditions for revisiting

Revisit this decision before multiple application instances serve traffic, or
when restarts, distributed attacks, proxy topology, shared NAT effects, request
volume, memory saturation, abuse response, fairness or regulatory requirements
make process-local enforcement inadequate.

A replacement should explicitly define independent or composite key dimensions,
normalization, algorithms and windows, shared-state consistency, expiry,
capacity, fail-open or fail-closed behavior, proxy trust, privacy, response
headers, observability and failure modes. Upstream and application controls may
be layered; migration to them is not automatic.
