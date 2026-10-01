# ADR-0021: Administer Customers through a private owner-only versioned API with body-based search

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-10-01
- **Recorded date:** 2026-10-01
- **Related issues:** #20 (future consumers: #18, #21)
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

An authorized Business owner must list, search, view, create, and correct the
Customers of the selected Business (issue #20). Customer data is private operational
personal data: names, phone numbers, and email addresses. It must not leak across
Businesses, through the public surface, URLs, browser storage, safe errors, logs,
screenshots, or test artifacts. The product specification also described MANAGER
access to Customers, while issue #20 and the Service precedent grant owner-only
access.

## Constraints

- The Business is derived server-side from the authenticated Membership; a
  client-supplied Business ID is never trusted (AGENTS.md).
- Only an active `BUSINESS_OWNER` Membership of the selected Business grants access;
  `PLATFORM_ADMIN` authority alone does not.
- DRAFT and ACTIVE Businesses allow mutations; SUSPENDED is readable and not
  writable (issue #20, existing Business-owner contracts).
- A foreign or missing Customer ID must not reveal that a Customer exists.
- The mandatory table standard applies (`docs/ui-design-guidelines.md` §15 and §15.1).
- Search terms contain contact data and must not appear in URLs, history, or storage.
- Existing APIs use `/api/business/{resource}`, `ProblemDetail` with a stable `code`,
  and optional `fieldErrors`.

## Options considered

### Authorization: owner only, or owner and MANAGER

The specification names MANAGER, but no MANAGER Business-owner API exists, and
security.md states that MANAGER access to such resources needs a separate approved
design. **Owner only is selected.**

### Search transport: GET query parameter or POST body

A GET `?search=` is conventional, restorable, and consistent with other lists, but a
phone or email in a URL reaches proxy and request logs and browser history.
**A POST body is selected.** The unsearched list stays a plain GET with only paging
and sorting.

### Search state: URL or component state

Putting the term in the route would persist contact data in browser history, session
restore, and screenshots. **Ephemeral component state is selected**, a recorded
exception to the §15.1 rule that complete filter state lives in the URL.

### Search matching: broad CRM search, `LIKE`, or narrow `strpos`

**A narrow `strpos`-based search on the normalized name and the canonical email, plus
a canonical-prefix match on the phone, is selected.** It has no wildcard syntax to
escape, does not search anything else, and needs no new index extension.

### List contact display: full or masked

Masking reduces shoulder-surfing but defeats the stated need to distinguish similar
names and complicates searching by number. **Full values are selected** for the
authenticated owner.

## Decision

**Endpoints** (base `/api/business/customers`, session-authenticated, CSRF on POST
and PUT):

| Method and path | Behavior |
|---|---|
| `GET /` | paged list; query `page`, `size`, `sort`, `direction` |
| `POST /search` | paged list filtered by `search`; body `search`, `page`, `size`, `sort`, `direction` |
| `GET /{customerId}` | detail |
| `POST /` | create; body `displayName`, `phone`, `email`; 201 with `Location` |
| `PUT /{customerId}` | explicit correction; body `displayName`, `phone`, `email`, `expectedVersion` |

There is no delete, deactivate, merge, or public endpoint, and no operation touches
identity, Membership, or any account table.

**DTOs.** List item: `id`, `displayName`, `phone`, `email`. Detail: those plus
`version`, `createdAt`, `updatedAt`. The page response carries `page`, `size`, and
`total`. `businessId`, the normalized name, and every internal matching value are
never returned. Wire names are `phone` and `email`.

**Authorization and lifecycle.** The use case follows the existing owner pattern:
`BusinessLifecycleAccess` and `SelectedBusinessOwnerAccess`. Reads use non-locking
checks. Each mutation runs in one transaction and takes shared locks in the order
Business lifecycle row, the user's Membership row, then the optimistic Customer write.
`PLATFORM_ADMIN` alone, MANAGER, STAFF, inactive, and other-Business Memberships
receive `ACCESS_DENIED`; an absent selection returns `ACTIVE_BUSINESS_REQUIRED`.
DRAFT and ACTIVE permit mutations. SUSPENDED permits reads and `POST /search`, and
rejects create and update with `BUSINESS_SUSPENDED`. A missing or foreign Customer ID
returns the identical `CUSTOMER_NOT_FOUND`.

**Corrections.** An update replaces name, phone, and email atomically with
`expectedVersion`. The current holder of an identifier is checked first so every
conflicting field is reported together; the unique indexes remain the final arbiter
for races. An identifier held by another Customer is never moved implicitly: the owner
edits the holder, then the other Customer, as two explicit operations. Removing one
contact is allowed only while the other remains. There is no merge.

**Search.** The term is trimmed and limited to 100 code points; blank means no filter.
Name: case-insensitive substring of `normalized_display_name` using `strpos` (no
accent folding and no Cyrillic-Latin transliteration). Email: substring of the
lowercased term. Phone: input that is a full valid number matches exactly; a partial
`+`, `00`, or local `0` input is converted to its canonical prefix and prefix-matched
on the canonical phone. Results are scoped to the Business, ordered like the list, and
capped by the page-size allowlist. No other field is searched. No trigram or full-text
index is added; Phase 4 records `EXPLAIN` evidence at about 10,000 rows, and an index
is a follow-up only if that evidence fails.

**Pagination and sorting.** Server-side, sizes 10, 25, and 50 with default 10, any
other size rejected. Sorting happens before pagination. Sort allowlist: `name`
(default, ascending), `phone`, `email`. Ordering is `normalized_display_name ASC,
id ASC` for name, and the chosen value with `NULLS LAST` in both directions followed
by `normalized_display_name ASC, id ASC` for phone and email. Route state is `page`,
`size`, `sort`, `direction`; the search term is absent from it. Changing size, sort,
direction, or the search resets to page 0, and out-of-range recovery replaces the
history entry. Desktop columns are Name, Phone, Email with the shared sortable
headers; mobile uses cards with the shared responsive sort select. The row or name
opens the detail; there is no actions column.

**Errors.** Stable codes and Bulgarian messages: `VALIDATION_ERROR` (400) with
`fieldErrors` naming every invalid body field (`displayName`, `phone`, `email`, and
`contact` when neither identifier is supplied), `CUSTOMER_NOT_FOUND` (404),
`CUSTOMER_CONTACT_CONFLICT` (409, with `fieldErrors` for the conflicting field or
fields), `CUSTOMER_CONCURRENT_UPDATE` (409, stale version),
`CUSTOMER_CONCURRENT_CONFLICT` (409, deadlock or serialization), and
`BUSINESS_SUSPENDED` (409), plus the existing `AUTH_REQUIRED`,
`ACTIVE_BUSINESS_REQUIRED`, `ACCESS_DENIED`, and `INTERNAL_ERROR`. Invalid paging,
sort, or search values return a generic `VALIDATION_ERROR` without a field. No error
echoes a submitted value, a constraint name, or a SQL diagnostic.

**Privacy boundary.**

- No public Customer endpoint. `publicprofile` and every other module have no
  dependency on `customer`; a boundary test enforces it, and a public-profile test
  seeded with sentinel Customer values proves no value appears.
- Only opaque UUIDs appear in paths. The search term is not in any URL, history
  entry, `localStorage`, or `sessionStorage`; it lives in component state cleared on
  Business switch and logout.
- Errors use fixed messages. The Customer persistence layer rethrows without the
  original cause, the application emits no Customer log statements, and tests capture
  logs around conflicts and unexpected failures to prove sentinel values are absent.
  PostgreSQL's server log can contain a key value for a rare administrative unique
  violation; this is a recorded operational limitation.
- Customer API responses carry Spring Security's default `no-store` headers, asserted
  by tests.
- Customer form fields use `autocomplete="off"` because they describe other people.
- E2E and test fixtures are generated at run time with synthetic names,
  `@example.test` emails, and libphonenumber example numbers; test names describe
  scenarios, never data; traces and videos stay off; the redacting reporter and
  archive validation scan for the fixture patterns.

**No history section.** Customer detail shows no appointment history until real
Appointment history exists (issue #21). No placeholder rows, no counts, and no
dependency from `customer` to `booking`.

## Rationale

Owner-only access reuses the proven Service and StaffMember authorization and locking
pattern. A body-based search with ephemeral state keeps contact data out of URLs,
logs, and history at a small cost in restorability. Narrow search satisfies "search
using approved contact information" without becoming CRM functionality.

## Tradeoffs and disadvantages

- A search is not restorable by refresh, Back, or a shared link.
- A POST for a read needs CSRF handling and departs from the other list endpoints.
- Full contact values appear in the list on screen, visible to a bystander.
- MANAGER and STAFF cannot administer Customers until a separate decision.
- Substring search over a Business's Customers is a sequential scan within one
  Business; acceptable at the expected size and re-evaluated by measurement.
- An owner resolving a shared phone must perform two explicit edits.

## Risks and mitigations

- **Cross-Business access:** server-derived Business, scoped queries, identical 404,
  A/B tests including guessed IDs and `PLATFORM_ADMIN` alone.
- **Personal data in logs and artifacts:** no Customer logging, sanitized persistence
  exceptions, log-capture sentinel tests, synthetic fixtures, redaction and archive
  scans.
- **Stale edits:** `expectedVersion` with `CUSTOMER_CONCURRENT_UPDATE`.
- **Search performance:** page-size cap and measured evidence before adding an index.
- **Client-side policy drift:** golden vectors shared with the backend (ADR-0019).

## Consequences

Phase 4 implements the API and Phase 5 the Bulgarian Business-owner interface (list
and search first, then create, detail, and edit). `security.md`,
`architecture.md`, `ui-design-guidelines.md`, `testing-strategy.md`, and the task record
describe the surface. #21 adds history without changing this contract.

## Evidence

Direct evidence: issue #20; `BusinessStaffMemberController`, its exception handler, and
`StaffMemberAdministrationService` (authorization, locking, versioning, `fieldErrors`);
`StaffMemberStore` (pagination and ordering); `SecurityConfiguration` (authenticated
catch-all, default headers); `docs/ui-design-guidelines.md` §15 to §16 and §21;
`docs/security.md` (Service authorization and error contract); ADR-0007, ADR-0010,
ADR-0015, ADR-0017.

Inference: that request URLs reach edge or request logs in a hosted deployment is a
general operational assumption that has not been verified for the eventual provider.

## Conditions for revisiting

Revisit for MANAGER or STAFF Customer access, a CRM-style or fuzzy search, a measured
search-performance problem, Customer export or deletion, appointment history, or a
provider whose logging is shown not to capture query strings.
