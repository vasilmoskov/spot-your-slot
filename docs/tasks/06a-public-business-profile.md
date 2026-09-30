# SpotYourSlot — Public Business Profile Pages

Status: Phase 1 (decisions and documentation) complete pending review; no implementation exists
GitHub issue: #17 — Build public Business profile pages
Depends on: #11, #12, #14, #16
Decision records: [ADR-0017](../decisions/ADR-0017-expose-public-business-profile-through-an-allowlisted-read-only-contract.md),
[ADR-0018](../decisions/ADR-0018-serve-public-business-pages-at-a-top-level-path-with-reserved-roots-and-stable-slugs.md)

## Task purpose

Give every ACTIVE Business a stable, mobile-friendly, unauthenticated public
profile page that presents its approved public information and active Services
without exposing private or cross-Business data. Issue #17 is Strict risk: it adds
the first unauthenticated API surface, changes security routing, defines the
public data boundary and URL namespace, and changes an existing administration
contract (slug rules). Each phase needs separate explicit approval before
persistent changes; Phases 2A and 2B are separate approval and commit gates.

## Approved decisions

| # | Decision |
|---|---|
| D1 | Public URL is the documented top-level `/{businessSlug}`, matched exactly. |
| D2 | Public: display name, slug, Business type, optional description, optional telephone, structured address as one optional unit. Not public: `contact_email`, timezone, status, IDs, versions, timestamps, owner data, Memberships, StaffMembers, operational metadata. The platform Business form states that telephone and address are shown publicly. No visibility flags or migration. |
| D3 | No booking route exists. No CTA, disabled button, Service-selection control, or dead link. A neutral notice is shown. No `onlineBookingAvailable` property. |
| D4 | After first activation the slug is immutable; DRAFT slugs are editable; reserved roots are rejected for new or changed slugs. |
| D5 | No application-level rate limiter in issue #17. |
| D6 | All active Services are returned, in deterministic order, with no cap or silent truncation. |
| D7 | New `publicprofile` module over narrow published Business and Catalog contracts; ADR-0017 and ADR-0018. |

## Public data allowlist

**Business (public):** `slug`, `displayName`, `businessType`, optional
`description`, optional `phone`, optional `address` (`city`, `postalCode`,
`street`, `streetNumber`, `details`) as one unit, omitted when every part is empty.

**Service (public):** `name`, optional `description`, `durationMinutes`, `price`
(EUR; the MVP currency is fixed, so no currency field).

**Never public:** `contact_email`, timezone (issue #17), lifecycle status, any
Business, Service, or user identifier, versions, timestamps, `normalized_name`,
owner data, Memberships, invitations, StaffMembers and their names, contact data,
schedules, assignments, exceptions, inactive Services, and operational metadata.
The response is built from purpose-built records, never from `BusinessDetails`,
`ServiceDetails`, or a persistence row.

Example (fictional):

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

## Endpoint, lifecycle, and error contract

`GET /api/public/businesses/{slug}` is the only new route.

| Case | Status | Result |
|---|---:|---|
| ACTIVE with active Services | 200 | Profile and Services |
| ACTIVE without active Services | 200 | Profile, `services: []` |
| DRAFT | 404 | Unavailable problem |
| SUSPENDED | 404 | Unavailable problem |
| Unknown slug | 404 | Unavailable problem |
| Malformed slug (no query issued) | 404 | Unavailable problem |
| Former or renamed slug | 404 | Unavailable problem |

The unavailable problem is RFC 7807: `code` `BUSINESS_PAGE_UNAVAILABLE`, title
`Заявката не може да бъде изпълнена.`, detail `Страницата не е налична.`, and it is
identical for every 404 case. Unexpected failures use `INTERNAL_ERROR`. The servlet
firewall's bare 400 for encoded slash, semicolon, or backslash discloses nothing
and is documented by a test. Responses carry the default `no-store` cache
headers; CDN caching is out of scope.

Ordering is `normalized_name ASC, id ASC`. SQL statements: two for ACTIVE, one for
any unavailable case, none for a malformed slug, constant in the number of
Services. One read-only `REPEATABLE_READ` transaction gives a consistent snapshot.

## Public URL and routing

- `/{slug}`: exactly one lowercase slug segment; a trailing slash is canonicalized
  away; an uppercase slug is normalized by the backend and the URL is replaced with
  the lowercase form; never `includes()`.
- Deeper paths are reserved for issue #18; hash routes are unchanged.
- The public application is chosen at load before the administration application,
  never calls `/api/auth/session`, and its requests use `credentials: 'omit'`.
- The backend is JSON-only. Direct refresh relies on the existing SPA-fallback
  assumption (Vite in development and E2E; the identity email links already depend
  on it). Production host behavior is verified in the hosting phase.
- Reserved roots: `forgot-password`, `password-reset`, `invitation`, `login`,
  `logout`, `profile`, `platform`, `business`, `api`, `actuator`, `assets`,
  `admin`, `b`, plus the defensive additions `book`, `booking`, `cancel`,
  `cancellation`, `confirmation`, `appointments`. Defined once in the backend;
  mirrored in the frontend only where routing requires it, with a parity test.

## Stable slug policy

- DRAFT: slug editable (reserved roots rejected).
- ACTIVE and SUSPENDED: slug immutable; a changed slug is rejected with a safe
  conflict response (proposed `BUSINESS_SLUG_IMMUTABLE`, 409); an unchanged slug
  in a profile update is allowed.
- Enforceable without activation history: the lifecycle has no transition into
  DRAFT (`BusinessStatus.canTransitionTo`; create always yields DRAFT), and the
  update statement is version-guarded, so a racing activation makes a slug change
  fail as a concurrent update. This is an application invariant, not a database
  constraint. No slug history, redirects, or migration.
- The platform form shows an immutable slug as read-only with an explanation.
- Open item for Phase 2A: read-only inspection of existing slugs for reserved-root
  collisions before enforcement, and the treatment of an unchanged reserved DRAFT
  slug on save and activation.

## Module ownership

`publicprofile → business` and `publicprofile → catalog`; no reverse dependency.

- `business.PublicBusinessProfileAccess.findActiveBySlug(slug)`: ACTIVE only,
  explicit column list, includes the Business ID for internal orchestration.
- `catalog.PublicServiceAccess`: every active Service of a Business ID, ordered
  `normalized_name ASC, id ASC`, no limit.
- `publicprofile.web` owns the controller and allowlisted response records;
  `publicprofile.application` owns the read-only transaction.
- Security: one rule permitting unauthenticated `GET /api/public/businesses/*`;
  all other routes and verbs stay authenticated. CORS and CSRF policy unchanged.

## Booking-entry boundary

Issue #18 owns Appointments, the guest booking flow, any public availability
endpoint, the public Service reference, and any public StaffMember option. Issue
#17 renders no booking route, no CTA, no disabled button, no Service-selection
control, and no dead link. It shows the static Bulgarian notice
`Онлайн запазването на час все още не е налично.`, which #18 replaces. Service
cards are semantic list items with room for a future action so #18 needs no page
redesign. No availability, slot, or reservation appears.

## Public presentation (Bulgarian)

| State or element | Text |
|---|---|
| Loading (`role="status"`) | «Зареждане на страницата…» |
| Unavailable (identical for every 404) | Heading «Страницата не е налична»; body «Проверете адреса или опитайте по-късно.»; no link |
| Load failure | «Страницата не може да бъде заредена.» / «Проверете връзката си и опитайте отново.»; button «Опитайте отново» |
| Header | `<h1>` display name; content-sized chip with the Business-type label |
| Description | Paragraph beneath the header (text only, wrapping) |
| Contacts (only when data exists) | «Контакти»: «Телефон» (sanitized `tel:` link, plain text if nothing dialable remains), «Адрес» (plain structured text) |
| Services | «Услуги»: card with name, description, «Продължителност», «Цена» (existing `formatServiceDuration` and `formatServicePrice`) |
| No active Services | «В момента няма налични услуги за онлайн записване.» |
| Booking notice | «Онлайн запазването на час все още не е налично.» |

Address is plain text (`street streetNumber`, `postalCode city`, `details`); no
map SDK, embedded map, or third-party map link. Missing parts are omitted. Focus
moves to the heading after load or failure; the only interactive elements are the
telephone link and the retry button. Long names and descriptions wrap
(`overflow-wrap`, `min-width: 0`); there is no horizontal overflow. The layout is
mobile-first and follows `docs/ui-design-guidelines.md`; public composition
requires human visual approval before the guide records it (guide §13).

**Table standard.** Services are a semantic list of cards, not a data-list table
(guide §15.1). The presentation is read-only, has no sortable columns or paging
controls, and shows every active Service in the documented deterministic order
`normalized_name ASC, id ASC`; therefore the sorting and pagination standard does
not apply, as recorded for `StaffServiceAssignments`. If the catalog ever needs
limiting, an explicit product limit or a designed public pagination contract must
be approved; nothing is silently hidden.

## Metadata and discoverability

Allowed: browser title `{displayName} – SpotYourSlot` (unavailable and failure:
`Страницата не е налична – SpotYourSlot`); client-updated meta description (the
Business description truncated at a word boundary, otherwise a generated line);
canonical URL from the serving origin, only for an available page;
`noindex` on unavailable and failure states; defaults restored on unmount.

Limits, stated honestly: the page is client-rendered. The title is reliable in
the tab and history. Metadata set by JavaScript is visible only to crawlers that
execute JavaScript, with no guarantee of indexing. Link-preview scrapers
generally do not run JavaScript and will show the static shell metadata for every
Business. The static host answers 200 for every path, so an unavailable page is a
soft 404. Reliable per-Business previews, Open Graph tags, real HTTP 404 and
`robots` headers, and server-level SEO need server-side rendering,
prerendering, or edge rendering and are out of scope. No sitemap (it would also
enumerate Businesses).

## Security and privacy

- No authentication, no CSRF (GET, no state change), existing exact-origin CORS.
- Business resolved only by slug; Services only by the resolved Business ID.
- Allowlisted records; exact key-set and sentinel-leakage tests.
- One indistinguishable 404 for DRAFT, SUSPENDED, unknown, and malformed.
- No SQL, exception text, or identifiers in responses; no query-string logging.
- No Customer, Appointment, or session side effect; the use case writes nothing.
  A valid cookie sent to the endpoint may still update session activity in the
  existing session filter; the page avoids this with `credentials: 'omit'`.
- **No application-level rate limiter (D5).** The endpoint is read-only,
  index-backed, and bounded to two statements. Limiting belongs to edge or CDN
  configuration and to the higher-risk availability and booking endpoints. Slugs
  of ACTIVE Businesses can be probed by guessing; ACTIVE pages are public by
  definition, and there is no list endpoint or sitemap. This limitation is
  documented, not mitigated.
- **No Service cap (D6).** Response size grows with the owner's active Services.

## Verification plan

- Backend (PostgreSQL 18.4 Testcontainers): API contract; exact JSON keys; a
  sentinel test placing unique values in `contact_email`, owner and StaffMember
  data, IDs, versions, and timestamps and asserting none appears in the raw body;
  ACTIVE, DRAFT, SUSPENDED, unknown, malformed, uppercase, over-length, and
  formerly used slugs; encoded-path firewall behavior; inactive Service excluded;
  empty catalog; Business A/B isolation; deterministic order; statement count
  (constant across one and many Services) via the existing `DataSource` proxy
  approach; read-only transaction and no writes; identical body with and without a
  valid session; unauthenticated non-GET rejected; CORS exact origin; `no-store`;
  no `Set-Cookie`; both published contracts; `ModuleBoundaryTests` acyclic.
- Phase 2A: reserved-root and stable-slug tests, including DRAFT edit, ACTIVE and
  SUSPENDED rejection, unchanged-slug update, racing activation versus slug change,
  and the platform form behavior.
- Frontend (Vitest): exact-match routing including `/salon-invitation`, reserved
  paths, trailing slash and case; states; metadata hook and cleanup; formatting;
  telephone sanitizing; no `/api/auth/session` call; `credentials: 'omit'`; no
  browser-storage writes; reserved-list parity.
- Playwright on the disposable E2E stack (never the developer database): an
  unauthenticated context opens `/{slug}`, refreshes and keeps the Business; title
  and metadata; DRAFT, SUSPENDED, and unknown look identical; inactive Service
  hidden; a second Business is isolated; long names; Pixel 7 keyboard and no
  horizontal overflow; 200% zoom.
- Human visual review (mobile, tablet, desktop, 200% zoom) is a separate gate that
  automated checks do not replace.

## Phase plan and approval gates

| Phase | Scope | Risk | Gate |
|---|---|---|---|
| 1 | Decisions, this task record, ADR-0017, ADR-0018, and narrow documentation updates. Documentation only. | Strict (defines a public contract) | Review of this change |
| 2A | Reserved roots and stable-slug enforcement in the platform Business API; the platform form shows an immutable slug and reserved-root feedback; read-only collision inspection of existing data first. Expected: `business` slug validation and update, `platform`, platform frontend form, tests. | Strict (changes an existing authenticated contract) | Own approval and commit; approved reserved list and inspection result |
| 2B | Published Business and Catalog contracts, `publicprofile` module, one security rule, allowlisted records, PostgreSQL integration tests. | Strict (first unauthenticated surface) | Own approval and commit; Phase 2A accepted |
| 3 | Public application, exact-match routing, page and states, metadata hook, explanatory copy on the platform form for telephone and address, shared Business-type labels, component tests, human visual approval. | Standard | Human visual approval before Phase 4 |
| 4 | Playwright acceptance, documentation reconciliation (README, roadmap, testing strategy, implementation plan, UI guide §13, stale status text), completion report, review archive. | Standard | Green verification; issue closure only with explicit approval |

Phases 2A and 2B are separate because 2A changes an existing administration
contract and 2B introduces the first public surface.

## Exclusions

Issue #17 excludes: Appointments, booking, guest booking flow, public
availability endpoint, Service selection or a public Service reference, StaffMember
or team exposure, Customer records, sessions, payments, reviews, galleries, Service
images, a directory or marketplace, sitemap, Open Graph tags, structured data,
advanced SEO, server-side rendering, prerendering, edge rendering, custom domains,
multiple locations, visibility flags, slug history or redirects, migrations,
dependencies, rate limiting, a Service cap or public pagination, a map SDK,
embedded map or map link, hosting and deployment changes, and Business-configurable
booking settings.

## Follow-ups

- Server-side or edge rendering for reliable per-Business link previews and SEO.
- Slug history and redirects, or an approved correction process for an activated slug.
- Per-field public visibility controls and an owner Business-profile editor.
- A public Service reference, StaffMember option, and availability endpoint (issue #18).
- An explicit Service limit or public pagination if response size becomes material.
- Edge or CDN rate limiting and caching with invalidation design.
- An optional external map link after a privacy and vendor decision.
- Displaying the Business timezone once times are shown.

## Phase 1 record

Phase 1 changes documentation only: this task record, ADR-0017, ADR-0018, the
ADR index, and narrow updates to `architecture.md`, `security.md`,
`product-spec.md`, `implementation-plan.md`, and `product-roadmap.md`. No code,
API contract, security rule, validation, migration, test, fixture, dependency,
service, database, or GitHub change was made. The complete reading of ADR-0001,
ADR-0003, ADR-0016, and the task records found no conflict with these decisions.
