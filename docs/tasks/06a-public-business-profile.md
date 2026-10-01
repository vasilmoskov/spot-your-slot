# SpotYourSlot — Public Business Profile Pages

Status: Phases 1 (decisions), 2A (reserved roots and stable slugs) and 2B (the
unauthenticated read-only backend contract) are committed. Phase 3 (the public React page) is
implemented and awaiting the human visual review; it is not committed. The browser acceptance
(Phase 4), the human visual approval and issue #17 itself are not complete, and booking remains
issue #18.
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
| D2 | Public: display name, slug, Business type, optional description, optional telephone, structured address as one optional unit. Not public: `contact_email`, timezone, status, IDs, versions, timestamps, owner data, Memberships, StaffMembers, operational metadata. The platform Business form shows no explanatory copy about it (final decision after human review of Phase 3). No visibility flags or migration. |
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

- DRAFT: slug editable; a new or changed slug that is a reserved root is rejected.
- ACTIVE and SUSPENDED: slug immutable. A changed canonical slug is rejected with
  HTTP 409 `BUSINESS_SLUG_IMMUTABLE` and the detail «Публичният адрес на активиран
  бизнес не може да бъде променян.» (the rule applies even when the requested value
  is reserved or already taken). An update that keeps the same canonical slug is
  allowed, and every other profile field stays editable. The optimistic version
  check runs first and stays authoritative.
- Enforceable without activation history: the lifecycle has no transition into
  DRAFT (`BusinessStatus.canTransitionTo`; create always yields DRAFT), and the
  update statement is version-guarded, so a racing activation makes a slug change
  fail as `BUSINESS_CONCURRENT_UPDATE`. This is an application invariant, not a
  database constraint. No slug history, redirects, lock, or migration.
- The platform form shows an immutable slug as read-only text (not a disabled
  control) with the note «Публичният адрес не може да се променя след активиране.»
  and submits the stored slug.

## Reserved roots (final, Phase 2A)

Exactly these 19 canonical lowercase values: `forgot-password`, `password-reset`,
`invitation`, `login`, `logout`, `profile`, `platform`, `business`, `api`,
`actuator`, `assets`, `admin`, `b`, `book`, `booking`, `cancel`, `cancellation`,
`confirmation`, `appointments`. Matching is exact on the canonical slug (trimmed,
lowercased), so `booking-studio`, `my-book` and `appointments-bg` stay valid.

- **Ownership.** `business.domain.ReservedBusinessSlugs` is the single
  authoritative definition. The frontend keeps a mirror,
  `frontend/src/reservedSlugs.ts`, used only for immediate form feedback and, in
  Phase 3, to route reserved paths away from the public page. The backend remains
  final. No endpoint, code generation, dependency, or filesystem coupling exposes the
  list; instead `ReservedBusinessSlugsTests` (Java) and `reservedSlugs.test.ts`
  (TypeScript) each pin the same 19 values, so a change must be made in the
  definition and both tests. This duplication is accepted and documented.
- **Create.** A reserved slug is rejected: HTTP 400 `VALIDATION_ERROR` with
  `fieldErrors.slug` = «Изберете друг публичен адрес на бизнеса.» The list is never
  returned.
- **DRAFT update.** Changing the slug to a reserved value is rejected with the same
  field error. An unchanged reserved DRAFT slug (grandfathered) may be saved while
  other fields change; moving it to another reserved value is still rejected.
- **Activation.** A DRAFT whose slug is reserved cannot be activated: HTTP 409
  `BUSINESS_SLUG_RESERVED`, detail «Променете публичния адрес на бизнеса преди
  активиране.», no `fieldErrors`. The check follows the version, lifecycle and
  active-owner checks. ACTIVE and SUSPENDED Businesses keep any slug they already
  hold, including a reserved one, and SUSPENDED reactivation is unaffected.
- **Platform form.** The slug shows the field error inline (create, DRAFT edit,
  backend `fieldErrors`), focuses the slug on a failed submit, keeps the value, and
  raises no generic alert. Activation failure with `BUSINESS_SLUG_RESERVED` shows the
  backend detail plus «Редактирайте го в „Данни за бизнеса“.»

### Collision inspection (2026-09-30, before enforcement)

Read-only session (`default_transaction_read_only=on`) against the configured local
development database (3 Businesses): no Business uses any of the 19 reserved slugs,
so there is no ACTIVE, SUSPENDED or DRAFT collision and no grandfathered row exists
locally. Committed fixtures, seeds, tests and E2E specs contain no exact reserved
slug (only prefixed forms such as `business-<suffix>`). The grandfathered-DRAFT
behavior above is therefore defensive and is proven with test-only rows in disposable
PostgreSQL databases. No row was mutated.

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
| Hero | One coherent section: content-sized Business-type chip, `<h1>` display name, optional description, compact contacts, booking message |
| Description | Paragraph beneath the header (text only, wrapping) |
| Contacts (only when data exists) | Icon list inside the hero, no separate heading or card: the telephone (sanitized `tel:` link, plain text if nothing dialable remains) and the address (plain structured text); the labels «Телефон:» and «Адрес:» are visually hidden for assistive technology |
| Services | «Услуги» heading directly above one standalone item per Service: name, description, «Продължителност» and «Цена» with the price emphasized (existing `formatServiceDuration` and `formatServicePrice`) |
| No active Services | «В момента няма налични услуги за онлайн записване.» |
| Booking notice | «Онлайн запазването на час все още не е налично.», inside the hero, in its own content-sized panel that a future booking action (issue #18) can replace or accompany |

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
  and the platform form behavior. The race is proven on real PostgreSQL by holding
  the Business row lock in one transaction, observing the second transaction in a
  `pg_stat_activity` lock wait, then releasing the first (no sleeps), in both
  directions, plus an unsynchronized concurrent run asserting the invariant
  (see `BusinessAdministrationServiceIntegrationTests`).
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
| 2A (committed) | Reserved roots and stable-slug enforcement in the platform Business API; the platform form shows an immutable slug and reserved-root feedback; read-only collision inspection of existing data first. Expected: `business` slug validation and update, `platform`, platform frontend form, tests. | Strict (changes an existing authenticated contract) | Own approval and commit; approved reserved list and inspection result |
| 2B (committed) | Published Business and Catalog contracts, `publicprofile` module, one security rule, allowlisted records, PostgreSQL integration tests. | Strict (first unauthenticated surface) | Own approval and commit; Phase 2A accepted |
| 3 (implemented, awaiting human visual review) | Public application, exact-match routing, page and states, metadata hook, shared Business-type labels, component tests, human visual approval. | Standard | Human visual approval before Phase 4 |
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

## Phase 2A record

Phase 2A is implemented and awaiting review; it is not committed. It changes the
existing authenticated Platform Business contract only: `ReservedBusinessSlugs`,
the create/update/activation rules in `BusinessInputValidator` and
`BusinessAdministrationService`, the new `BusinessSlugImmutable` and
`BusinessSlugReserved` application exceptions and their mappings in
`PlatformBusinessExceptionHandler`, the slug field of the platform Business form
(`BusinessForm`, `BusinessCreate`, `BusinessDetail`), the frontend mirror
`reservedSlugs.ts`, and tests. No migration, dependency, database constraint, public
endpoint, security rule, or later-phase behavior was added; V1–V9 are unchanged.
Concurrency evidence and error contracts are recorded above and in ADR-0018,
`security.md`, and `testing-strategy.md`.

### Phase 2A rendered review (before commit)

The platform Business form was reviewed in a browser on a disposable stack at 1280px,
640px (the CSS width of a 200% zoom) and 375px for DRAFT, ACTIVE and SUSPENDED with
100-character slugs and long names. DRAFT: inline reserved-slug error directly under
the field, cleared when corrected, no overflow, logical focus order. ACTIVE and
SUSPENDED: the slug is read-only text with the note grouped beneath it, no disabled
control, no duplicated explanation, other fields editable, and the unsaved-changes
guard fires only for a real edit. The review found one pre-existing defect that long
slugs and names expose (the detail header eyebrow and the section summary widened the
page); it is fixed in `styles.css` (wrap the eyebrow, truncate the redundant summary
text on one line). The `.field-note` class was checked against existing styles; no
equivalent shared class exists (`.exception-hint` is schedule-specific), so it stays.
This is a developer review, not the human visual approval gate.

Focused frontend evidence uses two valid selections: the 8-file selection
(`reservedSlugs.test.ts`, `src/platform/businesses`, `useFeedback.test.ts`) passes 124
tests, and the wider 12-file affected-area selection (`src/platform`,
`reservedSlugs.test.ts`, `src/ui`) passes 166. The full frontend suite passes 861 tests
in 44 files.

## Phase 2B record (committed)

Phase 2B adds only the backend contract. No frontend, migration, dependency, workflow, booking,
availability, or metadata change was made.

**Endpoint.** `GET /api/public/businesses/{slug}`, unauthenticated, read-only.

```json
{
  "slug": "example-studio",
  "displayName": "Примерно студио",
  "businessType": "HAIR_SALON",
  "description": null,
  "phone": "+359 88 000 0000",
  "address": {"city": "София", "postalCode": "1000", "street": "Примерна улица",
              "streetNumber": "1", "details": null},
  "services": [{"name": "Примерна услуга", "description": null,
                "durationMinutes": 45, "price": 25.00}]
}
```

Absent optional values serialize as `null`; `address` is `null` when every part is empty;
`services` is `[]` for an ACTIVE Business without active Services. Keys appear in this order and
no other key exists. The 404 body, identical for every unavailable case, is:

```json
{"detail":"Страницата не е налична.","instance":"/api/public/businesses",
 "status":404,"title":"Заявката не може да бъде изпълнена.","code":"BUSINESS_PAGE_UNAVAILABLE"}
```

**Allowlist reading.** `phone` and the structured `address` are in the allowlist because D2 of this
record and ADR-0017 approve them as public when entered (there are no visibility flags and no
schema change). Phase 3 adds the public page, not the data; the platform form carries no explanation of public visibility.
`contact_email`, timezone, status, every identifier, version, timestamp, owner and Membership
data, StaffMembers, schedules and exceptions, inactive Services, and any currency or booking
property are never serialized.

**Lifecycle collapse.** An unknown, DRAFT, SUSPENDED, former, malformed, over-length, or reserved
slug returns the one 404 above with `Cache-Control: no-store`, no cookie, and no slug, status, or
identifier in the body. The RFC 7807 `instance` is fixed because Spring would otherwise echo the
request path, including the submitted slug. A reserved slug is unavailable even if a
grandfathered ACTIVE Business holds it (ADR-0018 lets ACTIVE Businesses keep such a slug; the
public frontend cannot route it either).

**Module ownership.** `publicprofile → business` and `publicprofile → catalog`; nothing depends
on `publicprofile`, and `business` and `catalog` have no cycle. `business.PublicBusinessProfileAccess`
(root package; `business.application.PublicBusinessProfileAccessService`) owns slug canonicalization
and validation (`BusinessSlug`) and the reserved list, reads an explicit column list with an
`ACTIVE`-only predicate, and returns purpose-built records that carry the Business ID for
orchestration only. `catalog.PublicServiceAccess` (`catalog.application.PublicServiceAccessService`)
returns name, description, duration and price of every active Service ordered
`normalized_name ASC, id ASC`, unlimited. Both use `Propagation.MANDATORY, readOnly`. The response
records live in `publicprofile.web`; the ID never leaves `publicprofile.application`.

**Transaction and cost.** `PublicProfileService.findBySlug` is read-only `REPEATABLE_READ`. `REQUIRED`
would silently join a weaker transaction, so the effective isolation (repeatable-read or
serializable) is verified before any read and otherwise fails with no SQL (the ADR-0016 lesson).
Statements: two for an ACTIVE Business (Business, then Services, whatever the number of
Services), one for any other well-formed slug, none for a malformed or reserved slug. No write and
no explicit lock is issued. The snapshot claim is limited to that transaction: a row committed on
another connection between the two reads is not visible to the response.

**Security.** `GET /api/public/businesses/{slug}` (one non-empty segment) is `permitAll`; every
other verb or deeper path under `/api/public/businesses/**` is `denyAll` (401 anonymous, 403
authenticated), so no other request reaches MVC. Without that rule an authenticated non-GET
request reached the catch-all handler and returned 500. `/api/public/businesses/` and a trailing
slash after a slug therefore require authentication. CSRF, CORS (exact origin), and every other
route are unchanged; the endpoint neither needs nor creates a session.

**Deviations from ADR-0017 wording** (clarifications, no product decision changed): the matcher
is `{slug}` instead of `*` because `*` also matched an empty segment and returned 500; the
`denyAll` rule and the fixed `instance` above; reserved slugs are unavailable.

**Test evidence.** `PublicProfileApiIntegrationTests` (full servlet and security chain on
PostgreSQL 18.4: exact keys, allowlist, ordering, isolation, privacy sentinels, lifecycle collapse,
security, CORS, no data created), `PublicProfileTransactionIntegrationTests` (statement counts,
statement shape, no write or lock, isolation and read-only, precondition, MANDATORY contracts,
snapshot between reads), `PublicBusinessProfileAccessServiceTests`,
`PublicServiceAccessServiceTests`, `PublicProfileControllerTests`,
`PublicProfileModuleBoundaryTests`, and the existing module-boundary tests. Executed counts are in
the completion report.

**Remaining.** Phase 3: the public React page, exact-match routing, states, metadata hook, the
shared Business-type labels, and human visual approval.
Phase 4: browser acceptance and documentation reconciliation. Issue #18 owns booking.

## Phase 3 record (implemented, awaiting human visual review, not committed)

Phase 3 adds only the public React page, its routing and metadata hook, shared Business-type
labels and the platform-form explanations. No backend file, migration (V1–V9 unchanged),
dependency, booking, availability, Customer or Playwright work was added.

**Routing** (`frontend/src/public/route.ts`, `AppRoot.tsx`). The application is chosen once at load:
an exact `/{slug}` path opens the public page, everything else the existing application. A slug
is one ASCII segment matching `^[A-Za-z0-9]+(-[A-Za-z0-9]+)*$` of at most 100 characters,
lowercased, and not one of the 19 reserved roots. An uppercase slug or a trailing slash is
canonicalized with `history.replaceState` (query and hash kept); the public page never pushes
history. The root, deeper paths (`/{slug}/book`), reserved roots, invalid slugs and percent-encoded
paths are not public routes and stay with the existing application unchanged (the login page
for an unknown path, as before). A valid slug that merely contains a reserved word
(`/salon-invitation`) is a Business. The hash is never inspected, so `/#/business/...` and
`/#/platform/...` stay with administration. A popstate re-reads the URL (switching slug aborts the
old request); an entry that belongs to the other application forces a full reload, chosen again at
load. The public page never calls `/api/auth/session` or the CSRF endpoint.

**Request.** Exactly `GET {API}/api/public/businesses/{encodeURIComponent(slug)}` with
`credentials: 'omit'`, an `Accept` header only, no body and no CSRF token; no browser storage or
cookie is written. The response is decoded by copying only the documented fields; anything else is
a load failure. The previous request is aborted on slug change and unmount, a response of a
superseded slug or attempt is never rendered, nothing of the previous Business shows while another
loads, there is no automatic retry, and one activation of «Опитайте отново» sends one request.

**States** (Bulgarian, text as tabled above): loading (`role="status"`, no heading, shell metadata),
profile, profile without active Services, the single unavailable page (no slug echo, no link) and the
load failure (distinct text, one retry button, no raw detail). Focus moves to the `h1` after load or
failure. Loading has no `h1`; every other state has exactly one. The page has a `header` (plain
text wordmark, not a link) and a `main`.

**Presentation.** The functional page was committed as a savepoint first; a visual pass then
replaced the administration-like stack of nested cards. The page is a `header` (plain-text
wordmark, aligned to the content column) and a `main` of max 60rem. The hero is a single section
with a restrained tinted background and one border: a content-sized Business-type chip (labels from
the shared `business/businessType.ts`; an unknown value degrades to «Друг»), the `h1`, the
description (line breaks kept, text only), a contacts list and the booking panel. The contacts
list exists only when a telephone or address exists; it uses two small inline SVG icons (no
library, `aria-hidden`), wraps to one column when narrow, and its hidden «Телефон:» / «Адрес:»
labels keep the meaning for screen readers. The phone is a `tel:` link only when at least three
digits remain and keeps only an optional `+` and digits; otherwise plain text. The address is plain
text lines (`street number`, `postal code city`, `details`), missing parts omitted, no map link.
«Услуги» is a heading plus a semantic list with one bordered item per Service (no surrounding
card): name and description take the flexible column, duration and the emphasized EUR price form a
compact right-aligned area from 48rem up and stack under the text below it. The empty state is
plain muted text. There is no sorting, pagination or search (§15.1 does not apply: a read-only
list). Long names wrap (`overflow-wrap: anywhere`, `min-width: 0`). The page has no «Контакти»
section heading any more, so the heading hierarchy is `h1`, «Услуги» `h2`, Service `h3`.

**Metadata** (`public/usePageMetadata.ts`, one hook). Profile: title `{name} – SpotYourSlot`,
description (the Business description collapsed and cut at a word boundary within 160 characters,
otherwise «Информация и услуги на {name}.»), canonical `{origin}/{slug}`. Unavailable and failure:
title `Страницата не е налична – SpotYourSlot`, `noindex`, no canonical, shell description kept. Loading uses
the shell defaults (no distinct loading metadata was approved). Changing metadata or unmounting
restores the previous values and removes elements the hook created. Values are written through
attributes and `document.title` only. No Open Graph, structured data, sitemap or robots file.

**Platform form.** Final product decision (human review correction): telephone and address are
public when populated (D2), but the administration form shows no explanatory copy about it and
no visibility control. The two notes first implemented in Phase 3 were removed again; the form,
its labels, validation, ARIA and unsaved-changes guard are as before Phase 3 (only the
immutable-slug note from Phase 2A remains). The contact email stays private. Tests prove that
neither retired sentence nor an empty note container, group or dangling `aria-describedby`
exists in create, DRAFT, ACTIVE and SUSPENDED modes.

**Automated evidence.** Focused selection (public, `AppRoot`, `businessType`, platform Business,
`reservedSlugs`, `layoutRules`): 14 files, 264 tests. Full frontend suite: 50 files, 1004 tests (861 before Phase 3). `npm run lint`, `npm run build` and `git diff --check` pass. The backend suite and
Playwright were not run (no backend or E2E change).

**Rendered developer review** (built-in browser; not the human approval). Disposable PostgreSQL
18.4 container, backend and Vite on fresh ports; fixtures created only through the supported API
(ACTIVE full, minimal, zero active Services, long text with a 100-character slug and ten Services,
DRAFT, SUSPENDED, unknown slug, an inactive Service proven hidden); the temporary failure was
simulated by a review-only proxy outside the repository. Functional pass: 1280, 1024, 800, 640,
412 and 375 px, no horizontal overflow on the long fixture. Visual redesign pass (screenshots by
eye): full profile at 1280, 1024 (long fixture, including its contacts and Services after
scrolling), 640 and 375; the minimal, no-Services, SUSPENDED (unavailable) and retryable-failure
states at 375; keyboard focus on the telephone at 1280; one request per retry click; overflow
measured at 1280, 1024, 640 and 375. Defects found and fixed across both passes: Service facts
stacking on narrow screens, heavy nested padding, an oversized phone box whose focus ring
overlapped its label, and a tall right-hand facts column (now side by side). Known limitations: on
wide screens the hero's right side is empty by design (restrained, no invented content); the
platform form was not viewed in a rendered browser in the redesign pass (unchanged since Phase 2A);
the Pixel 7 check used its width, not device emulation; the two administration-form notes stay
removed. Human visual approval is still required.

**Remaining.** Human visual approval; Phase 4 (Playwright acceptance, documentation
reconciliation, UI guide §13 composition rules); issue #18 owns booking.
