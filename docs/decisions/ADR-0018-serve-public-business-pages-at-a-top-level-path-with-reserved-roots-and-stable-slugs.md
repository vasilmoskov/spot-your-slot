# ADR-0018: Serve public Business pages at a top-level path with reserved roots and stable slugs

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-09-30
- **Recorded date:** 2026-09-30
- **Related issues:** #17
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

Every approved document defines the public Business URL as
`https://spotyourslot.bg/{businessSlug}` (locally
`http://localhost:5173/{businessSlug}`). The frontend is a hybrid: the
authenticated area uses hash routes (`/#/business/...`, `/#/platform/...`), while
the identity pages use real paths (`/`, `/forgot-password`, `/password-reset`,
`/invitation`) matched by `pathname.includes(...)`, and backend emails link to
`/invitation?token=` and `/password-reset?token=`. The slug grammar has no
reserved words, and a Business can rename its slug, freeing the old value for a
different Business. The issue requires a stable, shareable URL that keeps
resolving to the same Business.

## Constraints

- The documented URL is top-level; changing it later has no redirect mechanism.
- The backend stays JSON-only; there is no server-side rendering.
- The authenticated hash routes and identity paths must keep working unchanged
  (issue #17: existing administration behavior unchanged).
- Production hosting is unconfigured; the identity email links already require
  SPA fallback for path URLs, so no new deployment work is introduced.
- No migration, slug-history table, or redirect mechanism is approved.
- `BusinessStatus.canTransitionTo` permits only DRAFT to ACTIVE, ACTIVE to
  SUSPENDED, and SUSPENDED to ACTIVE; create always yields DRAFT. No code path
  returns a Business to DRAFT.

## Options considered

### Hash URL `/#/{slug}`

Works with no server configuration and refreshes correctly. The fragment never
reaches a server, so crawlers and link scrapers see one page, and the namespace
is shared with administration routes. It contradicts the documented URL. Rejected.

### Prefixed path `/b/{slug}`

Avoids nearly all reserved words and identity-router changes. It reverses an
approved public identity, needs a documentation supersession, and cannot be moved
later without redirects. A valid fallback, not selected.

### Top-level path `/{slug}`

Matches the documented identity and is shortest for sharing and QR codes. It
requires a reserved-root list and exact-match routing. Selected.

### Allow slug edits at any time

Simple, but an old shared link or QR code can later resolve to a different
Business. Rejected.

### Slug history with redirects

Preserves old links but needs a table, a migration, redirect semantics, and reuse
rules. Deferred; not approved.

### Freeze the slug after first activation

Needs no history: because no transition returns a Business to DRAFT, a status
other than DRAFT means the Business has been activated. Selected.

## Decision

**URL.** The public page is `/{businessSlug}`.

- The path is matched exactly: one lowercase slug segment that matches
  `^[a-z0-9]+(-[a-z0-9]+)*$`, never `includes()`.
- An optional trailing slash is canonicalized to the form without it. An
  uppercase slug is accepted, normalized by the backend, and the page replaces the
  URL with the lowercase canonical form.
- Deeper paths, for example `/{slug}/book`, stay reserved for future booking
  routes and are not public profile routes in issue #17.
- Authenticated hash routes are unchanged.
- The browser selects the public application before the administration
  application at load. The two never link to each other, so a crossing is always
  a full document load. The public page does not call `/api/auth/session`.
- The backend stays JSON-only. Direct refresh relies on the existing SPA-fallback
  assumption (Vite in development and E2E; the production host is verified in the
  hosting phase).

**Reserved roots.** These exact single-segment values cannot be a new or changed
slug. The minimum approved set is `forgot-password`, `password-reset`,
`invitation`, `login`, `logout`, `profile`, `platform`, `business`, `api`,
`actuator`, `assets`, `admin`, and `b`. Inspection of actual routes confirmed
`api` and `actuator` (backend roots), `forgot-password`, `password-reset`,
`invitation`, and `/` (identity paths), `platform`, `business`, and `profile`
(hash-route names), and `assets` (Vite build output). It found no missing existing
root. The following are added defensively for the booking, confirmation, and
cancellation routes the specification requires: `book`, `booking`, `cancel`,
`cancellation`, `confirmation`, and `appointments`. The list is defined once in
the backend (`ReservedBusinessSlugs`, 19 values, exact match on the canonical
lowercase slug); the frontend mirrors it (`frontend/src/reservedSlugs.ts`) only for
immediate form feedback and, later, routing, and the backend stays authoritative.
Parity is kept by two pinned tests, one per language, each asserting the same 19
values; no endpoint, code generation, dependency, or filesystem coupling was added.

Phase 2A decisions (2026-09-30). A read-only inspection of the local development
database found no Business using a reserved slug, and committed fixtures contain
none. Enforcement: create and a DRAFT slug change to a reserved value fail with
`VALIDATION_ERROR` and `fieldErrors.slug`; an unchanged reserved DRAFT slug
(grandfathered) may be saved while other fields change; a DRAFT with a reserved slug
cannot be activated (`BUSINESS_SLUG_RESERVED`, 409); ACTIVE and SUSPENDED Businesses
keep whatever slug they hold and can still be reactivated.

**Stable slug.**

- A DRAFT Business may change its slug, subject to the reserved-root rule.
- After first activation (status ACTIVE or SUSPENDED) the slug is immutable.
  The platform Business API rejects a changed slug with a safe conflict response
  (code `BUSINESS_SLUG_IMMUTABLE`, status 409, detail «Публичният адрес на активиран
  бизнес не може да бъде променян.»; finalized in Phase 2A).
  A profile update that leaves the normalized slug unchanged remains allowed. The
  optimistic version check runs before the slug rule and stays authoritative.
- The rule is an application invariant, not a database constraint, and needs no
  activation-history storage. It is reliable because the lifecycle has no
  transition into DRAFT and `update` runs a version-guarded statement: an
  activation that commits between the read and the write increments the version,
  so a concurrent slug change fails as a concurrent update rather than succeeding.
  A future change that adds a transition to DRAFT must revisit this ADR. Phase 2A
  proves the race on PostgreSQL in both directions (row lock held, waiter observed in
  `pg_stat_activity`, then released) and adds no lock or migration.
- The platform form must show the slug as read-only with an explanation when it is
  immutable, instead of offering an edit that will fail.
- Businesses already ACTIVE or SUSPENDED keep their current slugs, including any
  that would now be reserved.

**Metadata limits.** The page is client-rendered. Browser title, a client-updated
meta description, a canonical URL built from the serving origin, and
`noindex` on the unavailable and failure states are allowed. They are visible in
the tab and history; only crawlers that execute JavaScript may read the others,
and nothing guarantees indexing. The static host answers HTTP 200 for every path,
so an unavailable page is a soft 404. Reliable per-Business social link previews,
Open Graph tags, real 404 status codes, and server-level SEO require later
server-side rendering, prerendering, or edge rendering and are out of scope. There
is no sitemap.

## Rationale

The documented URL is the approved public identity and costs the least to share.
Its risks are bounded and testable: exact matching removes the substring hazard
(today `/salon-invitation` would render the invitation form), the reserved list
removes real collisions, and freezing the slug after activation delivers the
issue's stability requirement without a history model.

## Tradeoffs and disadvantages

- Reserved words permanently remove some names from use.
- Two mirrored lists need a parity test.
- Slugs cannot be corrected after activation; a typo in an activated Business
  needs a new decision (slug history or an approved exception).
- A DRAFT that is renamed before activation never had a public URL, so nothing
  stable is lost; an old link to it simply resolves to nothing.
- Client rendering limits SEO and link previews as described.

## Risks and mitigations

- **Collision with existing data:** read-only inspection before enforcement.
- **Divergent reserved lists:** one backend definition and a parity test.
- **A future path into DRAFT:** recorded here as a revisit condition.
- **Routing regression for identity pages:** exact-match tests, including the
  substring cases, and the existing identity tests.

## Consequences

Phase 2A adds the reserved-root and stable-slug rules to the platform Business
API and form. Phase 3 adds the public application and exact-match routing. Issue
#18 adds routes below `/{slug}`. Slug redirects, slug history, and a different
correction process remain follow-ups requiring separate approval.

## Evidence

Direct evidence: `docs/product-spec.md`, `docs/security.md`, `AGENTS.md`, and
`README.md` (documented URL); `frontend/src/navigation.ts` (`readIdentityPage`,
hash routes); `InvitationService` and `RecoveryService` (email link paths);
`BusinessSlug` and the V1 slug check (no reserved words); `BusinessStatus` and
`BusinessAdministrationService` (lifecycle and version-guarded update);
`SecurityConfiguration`; ADR-0007; `product-spec` (slug redirects and reserved
words listed as an open decision).

Phase 2A evidence: `ReservedBusinessSlugsTests`,
`BusinessInputValidatorTests`, `BusinessAdministrationServiceIntegrationTests`
(including the lock-wait races), `PlatformBusinessApiIntegrationTests`,
`PlatformBusinessExceptionHandlerTests`, and the frontend `reservedSlugs`,
`BusinessForm`, `BusinessCreate` and `BusinessDetail` tests.

Inference: that a status other than DRAFT implies prior activation holds only
while the transition table stays as it is; no test proves it against a direct
database write.

## Conditions for revisiting

Revisit for a transition into DRAFT, slug history or redirects, server-side or
edge rendering, custom domains, multiple locations, or a requirement to correct an
activated slug.
