# ADR-0017: Expose the public Business profile through an allowlisted read-only contract

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-09-30
- **Recorded date:** 2026-09-30
- **Related issues:** #17
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

Issue #17 gives every ACTIVE Business a public, unauthenticated profile page. No
public endpoint exists. The Business and Service data were introduced for
administration; nothing in the schema separates public from administrative data,
and Business profile fields can be edited only by `PLATFORM_ADMIN`. A public
contract must therefore be defined by an explicit allowlist, must not reveal
lifecycle state, and must respect the module boundaries: `catalog` already
depends on `business`, so `business` cannot depend on `catalog`.

## Constraints

- Only ACTIVE Businesses are public. DRAFT, SUSPENDED, unknown, and malformed
  identifiers must be externally indistinguishable (issue #17, security.md).
- Private administration DTOs, persistence rows, and identifiers must never be
  serialized publicly (ADR-0003, security.md).
- Modules interact through narrow published contracts (ADR-0001); `scheduling`
  set the precedent for orchestration by published contracts (ADR-0016).
- No migration is approved for issue #17; no visibility flags.
- Issue #18, not #17, owns Appointments, public availability, and the booking
  entry point. ADR-0016 deliberately made no public-exposure choice for
  StaffMember IDs.
- Spring Security currently authenticates every route except a short allowlist.
- The existing process-local limiter covers only authentication flows (ADR-0008).

## Options considered

### Reuse the private administration contracts

`BusinessDetails` and `ServiceDetails` already carry every value. They also carry
IDs, versions, timestamps, `contactEmail`, and timezone, so a public mapping
would be a denylist and a future field would leak by default. Rejected.

### Business module owns the page and calls Catalog

Simple placement, but `business → catalog` creates a cycle with the existing
`catalog → business`. Rejected.

### Catalog module owns the page

No cycle, but Catalog would own Business identity and contact data. Rejected as
the wrong semantic owner.

### One SQL join across `business` and `service`

One statement and an inherent snapshot, but one module's store would read the
other's table. Rejected.

### A new orchestration module over two narrow published contracts

Selected, matching the ADR-0016 pattern.

### Distinct responses for DRAFT, SUSPENDED, and unknown

Friendlier to owners, but reveals lifecycle state and allows tenant enumeration.
Rejected.

### Application-level rate limiting for the public GET

Adds a second in-process limiter with capacity, expiry, and multi-instance
caveats for a read-only, index-backed request. Rejected for issue #17.

### Truncating the Service list

A fixed cap bounds response size but silently hides valid Services. Rejected.

## Decision

**Module.** A new `publicprofile` module (`web` and `application`) orchestrates
the page and owns the public HTTP contract. It depends only on
`publicprofile → business` and `publicprofile → catalog`; there is no reverse
dependency, and Spring Modulith verification must remain acyclic.

**Published contracts.**

- `business.PublicBusinessProfileAccess.findActiveBySlug(slug)` returns, for an
  ACTIVE Business only (`status = 'ACTIVE'` in the predicate), an immutable record
  with the Business ID (for internal orchestration only) and the public fields
  below, read with an explicit column list, never `SELECT *`.
- `catalog.PublicServiceAccess` returns every active Service of a Business ID
  with name, description, duration, and price, ordered by
  `normalized_name ASC, id ASC`, with no limit.
- Neither contract reuses `BusinessDetails`, `ServiceDetails`, or a persistence
  row. Both are read-only and join the caller's transaction.

**Endpoint.** `GET /api/public/businesses/{slug}` is the only new route and the
only new unauthenticated surface. One read-only `REPEATABLE_READ` transaction in
the orchestrator gives both reads one snapshot.

**Public allowlist.**

| Public | Field |
|---|---|
| Business | `slug` (canonical lowercase), `displayName`, `businessType` (enum name), optional `description`, optional `phone`, optional `address` as one unit (`city`, `postalCode`, `street`, `streetNumber`, `details`) |
| Service | `name`, optional `description`, `durationMinutes`, `price` (EUR; the MVP currency is fixed, so no currency field) |

Never public: `contact_email`, timezone (in issue #17), lifecycle status, any
identifier, version, or timestamp, owner data, Memberships, StaffMembers,
inactive Services, normalized values, and operational metadata. `address` is
`null` when every address part is empty; empty optional fields are omitted from
the presentation. There is no `onlineBookingAvailable` field and no booking
property; issue #18 introduces the real booking-entry contract.

```json
{
  "slug": "example-studio",
  "displayName": "Примерно студио",
  "businessType": "HAIR_SALON",
  "description": null,
  "phone": "+359 88 000 0000",
  "address": {
    "city": "София",
    "postalCode": "1000",
    "street": "Примерна улица",
    "streetNumber": "1",
    "details": null
  },
  "services": [
    {
      "name": "Примерна услуга",
      "description": null,
      "durationMinutes": 45,
      "price": 25.00
    }
  ]
}
```

**Lifecycle collapse.**

| Case | Status | Body |
|---|---:|---|
| ACTIVE with active Services | 200 | profile and Services |
| ACTIVE without active Services | 200 | profile, `services: []` |
| DRAFT, SUSPENDED, unknown, malformed, or formerly used slug | 404 | one unavailable problem |

The single 404 is RFC 7807 with `code` `BUSINESS_PAGE_UNAVAILABLE`, title
`Заявката не може да бъде изпълнена.`, and detail `Страницата не е налична.`. A
malformed slug (not `^[a-z0-9]+(-[a-z0-9]+)*$` after strip and lowercasing, or
longer than 100 characters) is rejected before any query and receives the same
response; there is no 400 for slug format. Unexpected failures use the existing
sanitized `INTERNAL_ERROR`. Path characters the servlet firewall rejects (encoded
slash, semicolon, backslash) receive its bare 400 before any controller runs; that
discloses no tenant information.

**Ordering, size, and cost.** Services are returned in `normalized_name ASC, id
ASC` and are never truncated or capped. The application issues exactly two SQL
statements for an ACTIVE Business, one for any unavailable case, and none for a
malformed slug, independent of the number of Services. If response size becomes
material, a later decision must introduce an explicit product limit or a
deliberately designed public pagination contract.

**Security.** One rule permits unauthenticated `GET /api/public/businesses/*`;
every other verb and route stays authenticated. The response never depends on
identity. Public frontend requests use `credentials: 'omit'`, so the session
filter's activity update is not triggered by the page itself. The existing exact
origin CORS policy is unchanged. The default Spring Security cache-control
headers (`no-store`) apply to 200 and 404 responses and are asserted by tests;
CDN caching needs a separate invalidation design. The use case writes nothing and
creates no Customer or Appointment. No application-level rate limiter is added:
the endpoint is read-only and index-backed, and edge or CDN limiting and the
higher-risk public availability and booking endpoints belong to later work.

**No StaffMember data** is public in issue #17.

## Rationale

An allowlist mapped from purpose-built records fails closed: a new private column
cannot appear publicly. Collapsing non-ACTIVE states removes lifecycle and tenant
enumeration. Two narrow contracts under a read-only repeatable-read transaction
keep module ownership intact and give one consistent snapshot. Returning every
active Service is honest; a silent cap would misrepresent the offering.

## Tradeoffs and disadvantages

- A new module, two published contracts, and mapping records add code.
- The owner or administrator cannot tell why a page is unavailable from the page.
- Telephone and address become public by product policy even though the schema
  has no visibility flag; a Business that does not want them shown must leave
  them empty.
- The Service list is unbounded, so a very large catalog produces a large
  response. Owner-controlled data and the small-Business MVP make this
  acceptable for now.
- Without a limiter, cheap unauthenticated reads can be repeated by a client.
- Public responses are not cacheable by intermediaries.

## Risks and mitigations

- **Private data leakage:** explicit allowlisted records, an exact JSON key-set
  test, and a leakage test using unique sentinel values in every private field.
- **Cross-tenant data:** the Business is resolved only by slug and Services only
  by the resolved Business ID; Business A/B tests.
- **Lifecycle enumeration:** one query, one response, no distinguishing status,
  body, or header.
- **Read amplification:** two indexed statements; a documented limitation.
- **Session side effects:** `credentials: 'omit'`, and a test that a valid session
  yields an identical response.

## Consequences

Issue #18 adds the booking-entry contract and any public Service reference,
StaffMember option, and availability endpoint under its own decisions. The public
page shows a static neutral notice that online booking is not yet available until
then. `security.md`, `architecture.md`, and the task record describe this surface.

## Evidence

Direct evidence: issue #17; `docs/product-spec.md` (public page and contact
information); `docs/security.md` (public context comes from `{businessSlug}`);
V1, V3, V4, and V5 migrations; `BusinessRecords` and `ServiceRecords` (private
DTOs); `SecurityConfiguration`; ADR-0001, ADR-0003, ADR-0008, ADR-0016.

Inference: telephone and address were introduced for later public booking
(roadmap row 3, task 03a) and are otherwise unused, which supports treating them
as public. That is the product owner's approved decision for issue #17, not a
historical fact.

## Conditions for revisiting

Revisit for per-field visibility controls, a public StaffMember or Service
reference, a public availability or booking endpoint, CDN caching, measured
abuse or load, material response size, or server-rendered profile pages.
