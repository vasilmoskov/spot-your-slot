# SpotYourSlot — Business-scoped Customer Records

Status: all six phases are implemented and verified. Phases 1 to 5 are committed (Phase 5, the Business-owner
Customer administration frontend, received human visual approval on 2026-10-06). Phase 6 (browser E2E
verification and this documentation reconciliation) awaits final user review and commit. Issue #20 remains open
until the user closes it; this record does not close it. Not delivered by #20 and not claimed: Booking and
Appointments (#18, #21), Customer accounts, deletion, merge, notes, history, and lifecycle.
GitHub issue: #20 — Build Business-scoped customer records
Depends on: the approved identity, authorization, and Business-isolation foundation,
and the Business-owner configuration and navigation where Customer administration is
rendered.
Future consumers (not prerequisites): #18 (appointment core and guest booking flow) and
#21 (Business calendar and appointment management).
Decision records: [ADR-0019](../decisions/ADR-0019-model-business-scoped-customers-with-canonical-contact-identifiers-and-no-lifecycle.md),
[ADR-0020](../decisions/ADR-0020-match-customers-conservatively-and-publish-narrow-customer-contracts.md),
[ADR-0021](../decisions/ADR-0021-administer-customers-through-a-private-owner-only-versioned-api-with-body-search.md)

## Task purpose

Provide the minimum Business-scoped Customer capability that lets a Business owner
maintain Customers and lets later guest and manual appointment flows find or create the
correct same-Business Customer without creating an account or Membership, leaking data
across Businesses, or silently merging different people. Issue #20 is privacy-sensitive
Strict work: it adds a migration, a tenant-isolated private API, a cross-module
contract, and personal-data handling. Each phase needs separate approval before
persistent changes.

## Customer record versus Customer account

- A Customer record belongs to exactly one Business. It is **not** a SpotYourSlot login or account, has no
  password, session, or Membership, and is never created by, or linked to, a User.
- **Automatic creation (future, issue #18):** in the public booking flow the Booking capability will use the
  already published `CustomerIdentification.findOrCreate` to find or create the Business-scoped Customer record
  from the phone and/or email the guest supplies (matching rules above). Booking never creates an authenticated
  Customer account.
- **Manual creation (this issue):** the owner's `Добави клиент` remains necessary for telephone bookings,
  walk-ins, imports, and bookings received outside the public flow. It uses the same canonical identifiers and
  uniqueness, so manual and automatic creation never produce a duplicate.
- The same person may have separate Customer records in different Businesses; they share nothing and must never
  leak across tenants.
- An authenticated Customer account, and any safe linkage of one to Business-owned Customer records, is a
  separate capability that needs its own explicit approval and design. Nothing in issue #20 anticipates it.

## Corrected dependency wording

Issue #20 precedes #18 and #21. The issue text lists them under "Depends on"; that is
corrected here. The GitHub issue is not edited by this phase. Proposed replacement for
the issue's "Depends on" section:

> ## Depends on
> * The approved identity, authorization, and Business-isolation foundation
> * Business-owner configuration and navigation where Customer administration is rendered
>
> ## Future consumers (not prerequisites)
> * #18 — Appointment core and guest booking flow: will find or create a Customer
>   through the Customer-identification contract delivered here.
> * #21 — Business calendar and appointment management: will use the Customer
>   reference contract for manual appointments and add Customer appointment history.
>
> This issue delivers the Customer foundation and administration. It creates no
> Appointment, history rendering, or booking behavior. Acceptance criteria that mention
> Appointments are verified here only at the Customer boundary and are completed by #18
> and #21.

### Acceptance criteria that cannot close in issue #20

| Issue criterion | Verified in #20 | Completed by |
|---|---|---|
| Customer history shows only appointments from the selected Business | Not applicable (no Appointment exists) | #21 |
| Customer administration shows the Customer's appointments when appointments exist | No history section is rendered | #21 |
| Contact updates do not reassign or corrupt historical appointment relationships | A contact update never changes the Customer ID; `UNIQUE (business_id, id)` prepares the composite foreign key | #18 (Appointment foreign key and end-to-end proof) |
| Every Appointment references exactly one same-Business Customer | `CustomerReferenceAccess` and the matching contract with a test-only consumer | #18 |
| Preserving relationships when Customer contact details are corrected | Customer ID is stable | #18 |

## Approved decisions

| # | Decision |
|---|---|
| D1 | A Customer requires a non-blank name and at least one valid phone or email. It may be created with one and gain the other later. One contact may be removed only while the other remains. |
| D2 | One required `displayName`: NFKC, whitespace collapsed, trimmed, 1–200 code points, case preserved. A generated normalized name is used only for sorting and search. Names never participate in matching or uniqueness. |
| D3 | Shared country-aware phone policy; canonical compact E.164 only; `0…` is Bulgaria, `+…` and `00…` are international; prefix-less values, letters, and extensions are rejected; blank is NULL. Backend authoritative, frontend mirror, shared golden vectors. |
| D4 | Shared ASCII-only email policy; trim, NFKC, whole-address lowercase; one canonical stored value; dotted domain; local part without edge dots or `..`. |
| D5 | One canonical value per identifier. `UNIQUE (business_id, phone)` and `UNIQUE (business_id, email)`; NULLs distinct; same value allowed at another Business; translation by SQLState `23505` and exact constraint name, sanitized without cause. |
| D6 | Conservative matching (truth table below). Any partial match is an `IdentityConflict`. Never merge, move, add, or replace an identifier. |
| D7 | Explicit versioned owner corrections of name, phone, and email; uniqueness applies; no implicit transfer; no merge; no hard delete. A shared phone belongs to one Customer per Business. |
| D8 | No Customer lifecycle, deactivation, archive, deletion, merge, or note. Retention, anonymization, and legal deletion are deferred. |
| D9 | `customer` publishes `CustomerIdentification` and `CustomerReferenceAccess`. Four normal outcomes only: existing Customer ID, created Customer ID, invalid identity, identity conflict. A concurrent database failure is not an outcome: it raises the sanitized typed `CustomerConcurrentConflict`, which marks the caller-owned transaction for rollback (retry after rollback, finalized by #18). Caller-owned transaction; documented and tested under `READ_COMMITTED`; stronger isolation not rejected; Appointment snapshots deferred to #18. |
| D10 | No history section, no placeholder, no counts, no seam now. #21 adds a Booking-owned history query. |
| D11 | Owner-only private API: `GET /`, `POST /search`, `GET /{id}`, `POST /`, `PUT /{id}`. |
| D12 | Narrow search: normalized name substring, lowercase email substring, canonical phone prefix or exact match. |
| D13 | Server-side pagination (10/25/50, default 10); sortable Name, Phone, Email; full contact values in the owner list. |
| D14 | Bulgarian validated form following the shared form, feedback, guard, table, and pagination standards. |
| D15 | No Customer data in public surfaces, URLs, storage, errors, logs, screenshots, archives, or test names. Search term in component state and a POST body. |
| D16 | Persistence-only normalized name; list, detail, and persistence field sets below; optimistic versioning with injected-clock `updated_at`. |

Issue #20's "Product decisions to resolve" map as follows: both-or-one contact → D1;
phone normalization → D3; email normalization → D4; one or two name fields → D2; shared
family phone → D7; later merge → deferred (D7, D8); compact list fields → D13;
pagination and sorting → D13; Customer without an Appointment → allowed, `POST /`
exists (D11); fields changeable after Appointments exist → name, phone, and email, since
Appointments reference the stable Customer ID (D7, D9); retention, export,
anonymization, deletion → deferred (D8).

## Contact-policy findings

Recorded from inspection of the existing StaffMember policy:

1. Backend telephone parsing accepts and silently drops a trailing extension
   (`+359888123456 ext 5` and `x5` both become `+359888123456`), while the frontend
   rejects it with `^\+?\d+$`. As a matching key, this would collapse different
   numbers.
2. The email local-part dot rules (no leading or trailing `.`, no `..`) exist in the
   frontend and the generic `@Email` check, but are not centralized in
   `StaffMemberEmailPolicy`.
3. The policy classes live in `workforce.domain`. `customer` must not depend on
   `workforce` for contact policy.

Resolution (ADR-0019): a small shared package holds the text canonicalizer, phone policy, and
email policy (implemented in `bg.spotyourslot.shared.contact`, exposed as the `shared::contact` named interface; see the Phase 2 record). In Phase 2, StaffMember delegates to it
and its existing behavior is verified before any Customer persistence is added. The
extension and letter rejection is a behavior change for API-only StaffMember input.
On the frontend, a shared contact-validation module replaces the StaffMember-specific
functions. A shared golden-vector file (accepted, rejected, canonical output; libphonenumber
example numbers only) is run by JUnit and Vitest. Nothing is implemented in Phase 1.

## Matching truth table

Only supplied identifiers are considered. The name is never used to match.

| # | Phone | Email | Holders | Outcome |
|---|---|---|---|---|
| 1 | none | none | – | `InvalidIdentity` (contact) |
| 2 | malformed, extension, or letters | any | – | `InvalidIdentity` (field) |
| 3 | only phone | – | nobody | create → `CreatedCustomer` |
| 4 | only phone | – | Customer A | `ExistingCustomer(A)` |
| 5 | – | only email | nobody | create → `CreatedCustomer` |
| 6 | – | only email | Customer A | `ExistingCustomer(A)` |
| 7 | both | both | phone and email both A | `ExistingCustomer(A)` |
| 8 | both | both | phone A, email B (A ≠ B) | `IdentityConflict`, no write |
| 9 | both | both | phone A, email nobody (A has no email or a different one) | `IdentityConflict`, no write |
| 10 | both | both | email A, phone nobody (A has no phone or a different one) | `IdentityConflict`, no write |
| 11 | both | both | nobody | create → `CreatedCustomer` |
| 12 | concurrent identical new submissions | | | one `CreatedCustomer`; the other re-reads and returns `ExistingCustomer` (row 7) |
| 13 | concurrent partially overlapping submissions | | | re-read evaluated by rows 4–10 |
| 14 | race reveals phone A and email B | | | `IdentityConflict` |
| 15 | serialization failure (`40001`), deadlock (`40P01`), or inconsistent after two attempts | | | **not an outcome:** throws `CustomerConcurrentConflict`; the caller's transaction is rolled back |

Rows 9 and 10 apply even when the matched Customer has no value for the second
identifier. The future guest-facing response is generic and reveals nothing about which
identifier matched: `Не можем да завършим резервацията онлайн. Моля, свържете се с
бизнеса.` An authenticated owner corrects the Customer explicitly and the booking is
retried. Consequence: a Customer stored with one identifier is not matched by a
submission that supplies both until the owner adds the other; a family sharing a phone
needs distinct identifiers per record.

## Schema (proposal for `V10__add_customers.sql`; not written in Phase 1)

New table `customer`; no existing table changes; no backfill; no extension; no account,
Membership, or Appointment reference.

| Column | Type and constraints |
|---|---|
| `id` | `uuid PRIMARY KEY`, application-generated |
| `business_id` | `uuid NOT NULL`, foreign key to `business(id)` `ON DELETE RESTRICT` |
| `display_name` | `varchar(200) NOT NULL`; canonical-form and non-blank checks as `staff_member` |
| `normalized_display_name` | `text COLLATE pg_unicode_fast` generated stored, the `staff_member` expression; persistence-only |
| `phone` | `varchar(16)` nullable; check `^\+[1-9][0-9]{7,14}$` |
| `email` | `varchar(320)` nullable; check non-blank, lowercase NFKC canonical, no edge whitespace |
| `version` | `bigint NOT NULL DEFAULT 0`, check `>= 0` |
| `created_at`, `updated_at` | `timestamptz NOT NULL`, check `updated_at >= created_at` |

Table constraints: `customer_contact_present` (`phone IS NOT NULL OR email IS NOT NULL`),
`customer_business_phone_unique` (`UNIQUE (business_id, phone)`),
`customer_business_email_unique` (`UNIQUE (business_id, email)`), and
`customer_business_id_id_unique` (`UNIQUE (business_id, id)`) for the future composite
foreign key. Index: `(business_id, normalized_display_name, id)` for the default sort;
the unique indexes lead with `business_id` and also cover phone and email ordering and
lookup. No trigram index until Phase 4 `EXPLAIN` evidence requires one. Migration
safety: a new table on a database holding V1–V9 data, verified on a fresh database and
on one with existing data, with V1–V9 checksums unchanged.

Fields by exposure: persistence-only `normalized_display_name`; list `id`,
`displayName`, `phone`, `email`; detail the list fields plus `version`, `createdAt`,
`updatedAt`; `businessId` never in a response. "Exposure" here means the API response only:
`version` is transport state for `expectedVersion`, and `version`, `createdAt`, and `updatedAt` are
not decided as visible Customer page content (the Phase 5 interface shows none of them; see its record). An accepted update increments `version`
once and sets `updated_at` from the injected clock, including an unchanged update;
matching never writes to an existing Customer.

## Contracts

### HTTP (`/api/business/customers`, owner-only)

| Method and path | Behavior |
|---|---|
| `GET /` | `page`, `size` (10/25/50, default 10), `sort` (`name` default, `phone`, `email`), `direction`; returns `{items, page, size, total}` |
| `POST /search` | body `search`, `page`, `size`, `sort`, `direction`; same response |
| `GET /{customerId}` | detail |
| `POST /` | `{displayName, phone, email}` → 201 and `Location` |
| `PUT /{customerId}` | `{displayName, phone, email, expectedVersion}` |

Business from the server-side context only. Missing and foreign IDs return the identical
`CUSTOMER_NOT_FOUND`. `PLATFORM_ADMIN` alone, MANAGER, STAFF, inactive, and other-Business
Memberships are denied. DRAFT and ACTIVE allow mutations; SUSPENDED allows reads and
`POST /search`, rejects create with `BUSINESS_SUSPENDED`, and (amended 2026-10-06, approved exception) allows
update of an existing Customer. No delete,
deactivate, merge, public, account, or Membership operation exists. Mutations lock the
Business row, then the user's Membership row, then perform the optimistic write.

### Internal (`customer`, consumed later by `booking`)

```java
interface CustomerIdentification {
    CustomerMatchOutcome findOrCreate(UUID businessId, CustomerIdentity identity);
}
record CustomerIdentity(String displayName, String phone, String email) { }
sealed interface CustomerMatchOutcome permits ExistingCustomer, CreatedCustomer,
        InvalidIdentity, IdentityConflict { }
/** Unchecked, sanitized, retryable; thrown by findOrCreate, never returned. */
final class CustomerConcurrentConflict extends RuntimeException { }
interface CustomerReferenceAccess {
    Optional<CustomerReference> find(UUID businessId, UUID customerId);
}
record CustomerReference(UUID id) { }
```

The outcome exposes no match basis, flags, name, phone, or email. No locking reference
operation is published; #18 may request one only if its real transaction design needs
it. The future `(business_id, customer_id)` foreign key is the authoritative persistence
guarantee.

## Error taxonomy

| Status | Code | Bulgarian message |
|---:|---|---|
| 400 | `VALIDATION_ERROR` with `fieldErrors` | `Проверете въведените данни.` |
| | `displayName` | `Въведете име на клиента до 200 знака.` |
| | `phone` | `Въведеният телефонен номер не е валиден.` |
| | `email` | `Въведеният имейл адрес не е валиден.` |
| | `contact` (neither supplied) | `Въведете телефон или имейл.` |
| 401 | `AUTH_REQUIRED` | `Необходим е вход.` |
| 403 | `ACTIVE_BUSINESS_REQUIRED` | `Изберете бизнес, за да продължите.` |
| 403 | `ACCESS_DENIED` | `Нямате достъп до тази операция.` |
| 404 | `CUSTOMER_NOT_FOUND` | `Клиентът не е намерен.` |
| 409 | `CUSTOMER_CONTACT_CONFLICT` with `fieldErrors.phone` and/or `fieldErrors.email` | `Този телефонен номер вече е записан за друг клиент.` / `Този имейл адрес вече е записан за друг клиент.` |
| 409 | `CUSTOMER_CONCURRENT_UPDATE` | `Данните за клиента са променени. Обновете данните и опитайте отново.` |
| 409 | `CUSTOMER_CONCURRENT_CONFLICT` | `Операцията не можа да бъде завършена. Опитайте отново.` |
| 409 | `BUSINESS_SUSPENDED` | `Спрян бизнес може само да преглежда данните си.` |
| 500 | `INTERNAL_ERROR` | `Възникна неочаквана грешка.` |

Invalid paging, sort, or search values return a generic `VALIDATION_ERROR` without a field.
No response echoes submitted data, a constraint name, or a SQL diagnostic. All invalid
body fields are named together, unlike the single-field StaffMember handler.

## Transaction and concurrency strategy

- Administrative mutations run in one transaction with the lock order above. An update
  checks the identifiers' holders to report every conflicting field, then writes with
  `WHERE business_id AND id AND version = :expected`; the unique indexes decide races
  and map to `CUSTOMER_CONTACT_CONFLICT`. Deadlock and serialization failures map to
  `CUSTOMER_CONCURRENT_CONFLICT`.
- `findOrCreate` and `find` require a caller-owned transaction (no own transaction),
  are tested under PostgreSQL `READ_COMMITTED`, and do not reject stronger isolation.
  Creation is `INSERT … ON CONFLICT DO NOTHING` plus a bounded re-read, which resolves
  ordinary uniqueness races without aborting the transaction. A PostgreSQL serialization
  failure (`40001`), a deadlock (`40P01`), or an unrecoverable race or inconsistent re-read
  is **not** a return value: it raises the sanitized, typed, unchecked
  `CustomerConcurrentConflict`, which propagates out of the Customer capability and marks the
  caller-owned transaction for rollback. The caller must never continue (for example by
  creating an Appointment) inside that aborted transaction. Retry belongs at the outer
  transaction boundary, after rollback, as a completely new transaction, and #18 finalizes it
  together with the Appointment transaction. The capability never uses `REQUIRES_NEW` and
  promises no savepoint recovery unless Phase 3 deliberately implements and proves it with
  PostgreSQL integration tests.
- Caller rollback removes a Customer created in it; an existing Customer is never
  written by matching.

Races Phase 2 and Phase 3 must test against PostgreSQL, coordinated without sleeps:
two creates with the same phone; two with the same email; two updates claiming the same
identifier; find-or-create races (identical, partially overlapping, and phone A with
email B); a stale-version update; and an outer-transaction rollback preserving the
previous record.

## Module boundaries

New module `bg.spotyourslot.customer` (`domain`, `application`, `infrastructure`,
`web`, and published interfaces). Dependencies: `identity` (owner access and
authenticated context), `business` (`BusinessLifecycleAccess`), and
`shared.contact`. No existing module depends on it; `booking` will depend on `customer`
and `scheduling`; `customer` never depends on `booking`; `publicprofile`, `workforce`,
`catalog`, `scheduling`, `business`, `identity`, and `platform` must not depend on
`customer`. `identity` is not modified when a Customer is created. A leaf module
consuming only modules that do not depend on it cannot create a cycle; the Modulith
verification and a dedicated boundary test prove it.

## Privacy and fixtures

See ADR-0021. In summary: no public endpoint; opaque UUID path IDs only; search term in
component state and a POST body; fixed error messages; sanitized persistence exceptions;
no Customer log statements and log-capture sentinel tests; Spring Security `no-store`
headers asserted; no browser storage; `autocomplete="off"`; run-time-generated
synthetic fixtures (synthetic names, `@example.test` emails, libphonenumber example
numbers) with scenario-only test names; traces and videos off; reporter and archive
scans for fixture patterns. PostgreSQL's server log can contain a key value for a rare
administrative unique violation; this is a recorded operational limitation.

## Frontend and table standard

Before any implementation, the table checklist in `docs/ui-design-guidelines.md` §15.1
is stated for the Customer list:

- **Default order:** name ascending.
- **Sortable columns:** Name, Phone, Email. There is no actions column; the row or name
  opens the detail.
- **Tie-breakers:** `normalized_display_name`, then `id`; phone and email sort with
  `NULLS LAST` in both directions.
- **Pagination:** server-side, because a Business may have many Customers; sizes 10, 25,
  50, default 10.
- **Route:** `#/business/customers?page&size&sort&direction`. The search term is
  deliberately absent (recorded exception: privacy, ephemeral state).
- **Reset rules:** size, sort, direction, or search changes reset to page 0; a Business
  switch clears the search and returns page, size, sort, and direction to the defaults (Phase 5
  decision, replacing the earlier "keeps size, sort, and direction": nothing from the previous
  Business, including its search, may survive); out-of-range pages recover to the last valid page by
  replacing history.
- **Responsive:** desktop table, mobile cards with the same ordering and the shared
  sort select.
- **After a mutation:** create navigates to the detail and returns to the same list
  state; empty-page recovery as above.
- **Why each column is displayed:** name identifies the Customer; phone and email
  distinguish similar names and are the minimum contact data an owner needs. `updated_at`
  is not shown because it does not materially help administration.

Forms: Bulgarian labels "Име" (required), "Телефон", "Имейл", with the hint
"Попълнете телефон или имейл."; navigation label "Клиенти"; create title "Нов клиент".
Validation follows `useFieldValidation`: untouched shows nothing, blur validates, a
non-empty invalid value shows immediately, submit shows every error and focuses the first
invalid control. Every field error is directly under its control; the `contact` error
attaches to the contact group. Backend `fieldErrors` map inline, unknown names are
ignored, and the generic alert appears only without usable fields. The shared unsaved-changes
guard covers create and edit. Concurrency and identifier conflicts use shared feedback.
SUSPENDED shows the shared notice and no create button (amended 2026-10-06: editing an existing Customer stays available).
Fields use `autocomplete="off"`. The detail edit pattern is confirmed against the existing
detail screens in Phase 5. Wrapped errors, dialogs, and desktop, tablet, mobile, and 200%
zoom review are required, with explicit human visual approval.

## Phase plan and approval gates

| Phase | Scope | Risk | Gate |
|---|---|---|---|
| 1 | Decisions, this task record, ADR-0019 to ADR-0021, and narrow documentation reconciliation. Documentation only. | Strict | Review of this change |
| 2 (committed) | **First** extract the shared contact policy (backend and frontend module), add golden vectors, make StaffMember delegate, and verify existing StaffMember behavior. **Then** the `customer` domain, `V10__add_customers.sql`, repository/store, uniqueness and concurrency tests, and the boundary test. No controller or frontend Customer UI. Internal checkpoints are allowed within this one phase. | Strict | Own approval and commit; Phase 1 accepted |
| 3 (committed) | `CustomerIdentification` and `CustomerReferenceAccess`, normalization, typed outcomes, transaction and race tests, test-only consumer. No Appointment. | Strict (cross-module contract) | Own approval and commit |
| 4 (implemented, awaiting review) | Private administration backend: authorization, list, search, detail, create, update, lifecycle behavior, tenant-isolation and privacy tests, `EXPLAIN` evidence. | Strict | Own approval and commit |
| 5 (committed; human visual approval received 2026-10-06) | Business-owner interface: navigation, list and search **first**, then create, detail, and edit; validation, guards, SUSPENDED mode, rendered desktop, tablet, mobile, and zoom review. Two human visual checkpoints are allowed within this one phase. | Standard (human visual approval) | Human visual approval |
| 6 (implemented and verified; awaiting commit) | Playwright administration journey, tenant isolation, lifecycle, privacy evidence, Booking-seam evidence, full gates, documentation completion, roadmap update, review archive. | Standard | Green verification; closure only with explicit approval |

## Tests required per phase

- **Phase 2:** golden-vector tests (accepted, rejected, canonical) for the phone and
  email policy, including extension, letter, prefix-less, and edge-dot rejection;
  unchanged StaffMember tests green after delegation; Flyway `V10` on a fresh database and
  on a database holding V1–V9 data with unchanged V1–V9 checksums; each constraint
  (contact present, canonical forms, unique per Business, NULL multiplicity, same value at
  another Business, restrict on Business delete, `UNIQUE (business_id, id)`, version and
  timestamp checks); store create, find, update, list, sort, search, and count; the
  concurrency races above; sanitized-translation tests with sentinel values absent from
  messages and causes; `CustomerModuleBoundaryTests` and the Modulith test.
- **Phase 3:** a parameterized test for every truth-table row against PostgreSQL; race tests
  for rows 12–14, where ordinary identical-create races still resolve to exactly one created
  Customer and one existing Customer with no duplicate; no write on `ExistingCustomer` or
  `IdentityConflict` (version and `updated_at` unchanged); a call without a transaction fails;
  behavior under `READ_COMMITTED`. For row 15, tests prove that a serialization failure
  (`40001`) and a deadlock (`40P01`) each produce the sanitized typed
  `CustomerConcurrentConflict` (no personal data in message or cause); that the exception
  propagates and the caller-owned transaction rolls back; that no Customer and no partial
  consumer write remains; and that a completely new outer transaction can then retry
  successfully. Also: outer rollback leaves no orphan Customer; same-Business `find`; a
  test-only consumer; no personal data in logs.
- **Phase 4:** MockMvc status matrix; role matrix (owner, other-Business owner, MANAGER,
  STAFF, `PLATFORM_ADMIN` alone, `PLATFORM_ADMIN` plus owner, inactive Membership); DRAFT,
  ACTIVE, and SUSPENDED behavior; identical 404 for missing and foreign IDs; CSRF on POST
  and PUT; multiple `fieldErrors`; paging and sort allowlists; search matching including
  literal `%`, `_`, and `\`; local, `+`, and `00` phone search; optimistic concurrency;
  lock-order tests in the style of `ServiceAuthorizationLockingIntegrationTests`;
  sentinel tests for errors, captured logs, and the public profile; `no-store` headers;
  `EXPLAIN` evidence at about 10,000 rows.
- **Phase 5 (executed, see its record):** Vitest for the shared contact validation and golden vectors, route parsing
  and normalization, list state, search absent from URL and storage, form validation,
  `fieldErrors` mapping, guards, and SUSPENDED mode; rendered review and human approval.
- **Phase 6:** Playwright journey; Business A versus B isolation through the UI; SUSPENDED
  read-only; privacy checks on storage, URL, public page, and console; backend integration
  evidence that creating a Customer creates no account or Membership; Booking-seam evidence
  through the Phase 3 test-only consumer.

## Deferred to later issues

- **#18:** the Appointment relationship and composite foreign key, whether Appointments
  snapshot the submitted name, phone, or email (duplicated personal data, notification
  destination, historical presentation, correction semantics, retention and anonymization),
  the Appointment transaction and retry policy, rate limiting, and the generic guest
  response. Issue #20 invents no Appointment column.
- **#21:** Booking-owned Customer appointment history and manual-appointment Customer
  selection.
- **Later:** MANAGER and STAFF access, merge, retention, export, anonymization, deletion,
  trigram search, internationalized email, and phone extensions.

## Exclusions

Issue #20 excludes: Appointments, appointment tables, columns or APIs, history UI or a
history seam, a public Customer endpoint, Customer accounts, login, Memberships or public
profiles, notifications and SMS, Customer notes, marketing preferences, merge, hard delete,
archive, anonymization, export, retention automation, MANAGER or STAFF access,
cross-Business identity, name-based or fuzzy matching, internationalized email, phone
extensions, trigram or full-text search, dependency changes, and any change to the
GitHub issue or Project board.

## Phase 1 record

Phase 1 changes documentation only: this task record, ADR-0019, ADR-0020, ADR-0021, the
ADR index, and narrow updates to `README.md`, `product-spec.md`, `architecture.md`,
`data-model.md`, `security.md`, `testing-strategy.md`, `implementation-plan.md`,
`product-roadmap.md`, and `ui-design-guidelines.md`, plus a superseded-note annotation on the
historical `docs/tasks/00-product-foundation.md` (its content is otherwise unchanged). No code, migration, dependency,
workflow, or GitHub change is made. The repository records Issue #20 as in progress even
though the Project board reported `Todo` when this phase was prepared.

### Contradictions resolved

| # | Contradiction | Resolution |
|---|---|---|
| C1 | Issue #20 lists #18 and #21 as dependencies | Future consumers (above) |
| C2 | `data-model.md` and `product-spec.md` described a staff note, an active/blocked state, and original/normalized values | No note, no lifecycle, one canonical value (ADR-0019) |
| C3 | The specification said ambiguous matches remain separate records | Impossible under unique identifiers; every partial match is an `IdentityConflict` (ADR-0020) |
| C4 | The specification gave MANAGER Customer management | Owner-only now; MANAGER needs a separate design (ADR-0021) |
| C5 | `implementation-plan.md` placed the Customer migration with Appointments | The Customer migration belongs to issue #20; the Appointment migration to #18 |
| C6 | The roadmap Customer row had no issue or status | In progress, issue #20 |
| C7 | Appointment-related acceptance cannot be proven before #18 and #21 | Split table above |
| C8 | Backend accepts and drops phone extensions; the frontend rejects them | Rejected by the shared policy |
| C9 | The StaffMember handler names at most one field error | The Customer handler names all invalid fields |
| C10 | Contact policy lived in `workforce` and was duplicated in the frontend | Shared package and shared frontend module with golden vectors |
| C11 | Email local-part dot rules not in the central backend policy | Centralized in the shared policy |
| C13 | The historical foundation task still described the note, active/blocked, and original/normalized Customer model | Annotated as superseded by ADR-0019 and this record; not the current contract |
| C14 | A concurrent database failure was modeled as an ordinary matching outcome although it aborts the PostgreSQL transaction | Four normal outcomes; the failure is the typed `CustomerConcurrentConflict` exception that rolls back the caller's transaction (ADR-0020) |
| C12 | Existing constraint translation inspects the error message, which contains the value for Customer | Exact constraint name and a sanitized exception without cause |

## Phase 2 record (implemented, verified, and committed)

Phase 2 delivered the shared contact policy, the Customer domain model, `V10`, and internal
persistence. It did **not** start Phase 3: there is no `findOrCreate`, no matching truth table, no
`CustomerIdentification`, no `CustomerReferenceAccess`, no `CustomerConcurrentConflict`, no HTTP
endpoint, no list or search, no frontend screen, and no booking or Appointment behavior.

### Shared contact policy (ownership and behavior)

Owned by the `shared` module, in `bg.spotyourslot.shared.contact`, declared `@NamedInterface("contact")` (the build adds the BOM-managed `spring-modulith-api` dependency, no version pinned): `ContactTextCanonicalizer`
(display name, trimmed contact text, the approved whitespace set), `ContactPhoneNumbers`, and
`ContactEmailPolicy`. StaffMember delegates to it; the three former `workforce.domain` classes are
removed and their tests moved. `customer` uses it directly and has no `workforce` dependency.

- **Phone:** unchanged prefix rules (`+`, `00`, `0` with default region BG), full
  `isValidNumber`, canonical compact E.164. **New:** after separator removal only ASCII digits with
  an optional leading `+` are accepted, and a parsed extension is also rejected, so letters,
  vanity text, and extensions are rejected rather than silently dropped. `+359 (0) 888 123 456`
  stays accepted (canonical `+359888123456`) because it is recorded as a golden vector that the
  backend and frontend treat identically.
- **Email (final rules):** trim, NFKC, ASCII only, no whitespace, total length at most 320, exactly
  one `@`, non-empty parts, local part at most 64 characters made of single-dot-separated atoms of
  the RFC 5322 `atext` characters (`A-Z a-z 0-9 ! # $ % & ' * + / = ? ^ _ ` { | } ~ -`), no leading,
  trailing, or repeated dot, domain of at least two labels of at most 63 characters of ASCII
  letters, digits, and `-` without edge hyphens, and the whole address lower-cased. No IDN,
  Punycode, or SMTPUTF8. The policy is self-contained: the Jakarta `@Email` check was removed from
  the StaffMember path.
- **StaffMember behavior change:** phone extensions and letters (previously accepted with the
  extension dropped) are now rejected as a `contactPhone` field error; quoted-string email local parts
  (previously accepted by the Jakarta check) and local parts over 64 characters are now rejected as a
  `contactEmail` field error. Everything else is unchanged and covered by the existing StaffMember
  tests. One existing frontend test used a 300-character local part; it now uses a valid long address.
- **Frontend mirror:** `frontend/src/contact/contactPolicy.ts` (`checkPhone`, `canonicalPhone`,
  `canonicalEmail`); `business/staff/validation.ts` delegates and keeps its Bulgarian wording. The
  approved whitespace character class is exported from `business/text.ts` so separators and trimming
  cannot drift.
- **Golden vectors:** one file, `shared-test-data/contact-policy-vectors.json` (16 accepted and 27
  rejected phones, 13 accepted and 35 rejected emails, blank inputs; one expected result each, no
  backend-only or frontend-only flags). The backend `ContactPolicyVectorTests` and the frontend
  `contactPolicy.test.ts` both run it; Maven (working directory `backend/`), Vitest, `tsc -b`,
  ESLint, and `vite build` consume the root-level file with no dependency or build change. The Java
  and JavaScript phone libraries agreed on every vector; the backend remains authoritative.
- **Differential check:** a characterization test of the former StaffMember path (old dotted-domain
  rules plus Jakarta `@Email`) proves every address the shared policy accepts was previously accepted
  and that the only rejected vector previously accepted is the quoted-string local part.

### V10 (`V10__add_customers.sql`)

One new table; V1–V9 are byte-for-byte unchanged (SHA-256 verified against `HEAD` and pinned in
`CustomerSchemaIntegrationTests`); V10's SHA-256 is pinned there too. `customer`:
`id uuid PRIMARY KEY`, `business_id uuid NOT NULL`, `display_name varchar(200) NOT NULL`,
`normalized_display_name text COLLATE pg_unicode_fast GENERATED ALWAYS AS (…) STORED` (the exact
`staff_member` expression), `phone varchar(16)`, `email varchar(320)`, `version bigint NOT NULL
DEFAULT 0`, `created_at` and `updated_at timestamptz NOT NULL`.

| Constraint or index | Definition |
|---|---|
| `customer_pkey` | primary key `(id)` |
| `customer_business_fk` | `FOREIGN KEY (business_id) REFERENCES business(id) ON DELETE RESTRICT` |
| `customer_display_name_canonical` | name equals its NFKC, whitespace-collapsed, trimmed form (as `staff_member`) |
| `customer_display_name_not_blank` | `char_length(display_name) > 0` |
| `customer_phone_canonical` | `phone IS NULL OR phone ~ '^\+[1-9][0-9]{7,14}$'` |
| `customer_email_canonical` | database-level canonical *storage* only: NULL, or non-blank, equal to its own lower-case NFKC form, no leading or trailing approved whitespace. It does not validate address syntax |
| `customer_contact_present` | `phone IS NOT NULL OR email IS NOT NULL` |
| `customer_version_nonnegative` | `version >= 0` |
| `customer_timestamps_finite_ordered` | both timestamps finite and `updated_at >= created_at` |
| `customer_business_phone_unique` | `UNIQUE (business_id, phone)` |
| `customer_business_email_unique` | `UNIQUE (business_id, email)` |
| `customer_business_id_id_unique` | `UNIQUE (business_id, id)` |
| `customer_business_normalized_display_name_id_idx` | btree `(business_id, normalized_display_name, id)` |

No lifecycle, note, account, Membership, Appointment, raw-contact, or deletion column; no
Appointment table or foreign key; no extension or speculative index. Upgrade V9 to V10 with a
Business, StaffMember, and Service present preserves all data and adds an empty usable table.

### Customer domain (`customer.domain`)

`CustomerProfile(displayName, phone, email)` accepts only canonical values and enforces: canonical
non-blank display name of at most 200 code points, canonical E.164 phone, canonical lower-case email,
at least one contact. `CustomerProfile.fromInput` canonicalizes raw input with the shared policy.
Violations raise `InvalidCustomerData`, which carries an immutable `Set<CustomerField>`
(`DISPLAY_NAME`, `PHONE`, `EMAIL`, `CONTACT`) listing every invalid field of one pass, with a fixed
message, no cause, and no value. `Customer` (id, businessId, profile, version at least 0, createdAt,
updatedAt not before createdAt) and `NewCustomer` are deeply immutable records; there is no wither
that changes the Business. Names are never identifiers and are not unique.

### Persistence (`CustomerStore`, internal to `customer.infrastructure`)

Returns domain `Customer` values rather than row records, an intentional Customer-module design
choice. It opens no transaction (it joins the caller's), every operation is one statement, reads take
no lock, and every statement filters by `business_id`.

| Operation | Behavior |
|---|---|
| `insert(NewCustomer)` | plain `INSERT … RETURNING`; version 0, equal instants; duplicates are translated, never matched or merged |
| `findById`, `findByPhone`, `findByEmail` | `Optional<Customer>`; another Business behaves like missing |
| `update(businessId, id, profile, expectedVersion, updatedAt)` | one guarded `UPDATE … WHERE business_id AND id AND version`; version plus one; `updated_at = GREATEST(updated_at, :updatedAt)` so it never moves backwards; returns empty for missing, cross-Business, and stale alike, with no preliminary query; the Business is never a `SET` value |

Removing one contact is allowed while the other remains (a contactless profile cannot be built, and
the database also rejects it). Updating onto another Customer's phone or email raises
`DuplicatePhone` or `DuplicateEmail` and leaves the previous state; an outer rollback restores the
previous record and removes an insert.

### Failure translation and privacy

`CustomerPersistenceException` is sealed: `DuplicatePhone`, `DuplicateEmail`, `UnknownBusiness`,
`InvalidData`, `UnexpectedFailure` (optionally keeping only the five-character SQLState, so Phase 3 can
recognize `40001` and `40P01` later). Every message is fixed and no instance retains a cause or a
suppressed exception, so the original database exception is not reachable. Stack traces: the expected
classifications are lightweight (none), while `UnexpectedFailure` keeps its own normal stack trace so a
production failure identifies the Customer operation and call path; a stack trace holds application
class and method names only. Classification uses the driver's types directly (`PSQLException`,
`ServerErrorMessage`: `getConstraint()`, `getTable()`, `getColumn()`) plus the SQLState, found by safely
traversing the cause chain (`23505` approved unique constraints, `23503` the Business foreign key,
`23514` the seven check constraints, `23502` NOT NULL of a `customer` column, `22001`); an unknown
constraint, table, column, or SQLState, or a missing `ServerErrorMessage`, is an `UnexpectedFailure`, and
message text is never parsed. `40001` and `40P01` are `UnexpectedFailure` for now; the typed
`CustomerConcurrentConflict` belongs to Phase 3.

**Email validity is application-level.** Address syntax is enforced only by the authoritative
`ContactEmailPolicy`; the `customer_email_canonical` constraint proves only the stored representation
(see the table above).

### Verification evidence (executed 2026-10-01)

- Full backend `./mvnw --batch-mode verify`: 1,848 tests, 0 failures, 0 errors.
- New backend tests: `ContactPolicyVectorTests` 94, `ContactEmailPolicyTests` 29,
  `ContactPhoneNumbersTests` 15, `ContactTextCanonicalizerTests` 55,
  `ContactEmailPolicyJakartaDifferentialTests` 2, `CustomerProfileTests` 33, `CustomerTests` 8,
  `CustomerSchemaIntegrationTests` 34, `CustomerStoreIntegrationTests` 23,
  `CustomerStoreFailureTranslationTests` 27, `CustomerModuleBoundaryTests` 7; plus 2 new StaffMember
  API regression tests (extensions and letters, quoted and overlong local parts) in
  `BusinessStaffMemberApiIntegrationTests`. `ModuleBoundaryTests` passes.
- Existing tests edited mechanically for V10 and the new table: the Flyway version lists in three
  schema tests, the V9-upgrade `max(version)`, four "no `customer` table yet" absence checks, and
  test names that said "nine" or "V9 newest".
- Full frontend suite: 51 files, 1,103 tests; ESLint and `vite build` (including `tsc -b`) clean.

### Deviations, limitations, and remaining work

- **Dependencies:** two existing entries in `backend/pom.xml` changed, both version-managed with no
  version pinned: `org.springframework.modulith:spring-modulith-api` was added at compile scope
  (BOM 2.1.0) for `@NamedInterface`, and `org.postgresql:postgresql` (42.7.11, Spring Boot managed)
  moved from runtime to compile scope for the direct driver types. No new library and no duplicate
  version appears in the dependency tree.
- **Email label limit:** the 63-character label limit is added to preserve the former Jakarta
  behavior; the quoted-string local-part narrowing is the only intentional loss.
- **No concurrency tests:** the races (same phone, same email, find-or-create) belong to Phase 3.
- **Not done (Phase 3 onward):** matching and its truth table, `CustomerIdentification`,
  `CustomerReferenceAccess`, `CustomerConcurrentConflict`, administration API, search, frontend
  screens, E2E.

## Phase 3 record (implemented, verified, and committed)

Phase 3 delivered conservative matching, the published Customer contracts, caller-owned transaction
semantics, and PostgreSQL concurrency evidence. It did **not** start Phase 4: there is no HTTP
endpoint, controller advice, authorization, list or search, Customer administration service,
frontend, Appointment, Booking class, history, snapshot, merge, delete, lifecycle, E2E test, or
migration beyond `V10`. No file in `pom.xml`, `db/migration`, the frontend, or the security
configuration changed.

### Published types (all in the public root package `bg.spotyourslot.customer`)

| Type | Shape |
|---|---|
| `CustomerIdentification` | `CustomerMatchOutcome findOrCreate(UUID businessId, CustomerIdentity identity)` |
| `CustomerIdentity` | `record(displayName, phone, email)`: raw input only; `toString()` is the constant `CustomerIdentity[redacted]` |
| `CustomerMatchOutcome` | sealed interface with exactly four nested records: `ExistingCustomer(customerId)` and `CreatedCustomer(customerId)` (both reject a null ID), `InvalidIdentity(Set<IdentityField> fields)` (copied into a private unmodifiable `EnumSet`, never empty), and `IdentityConflict()` (no components) |
| `IdentityField` | `DISPLAY_NAME`, `PHONE`, `EMAIL`, `CONTACT`; mirrors the internal `CustomerField` through an exhaustive switch (tested one to one) |
| `CustomerReferenceAccess` | `Optional<CustomerReference> find(UUID businessId, UUID customerId)` with nested `CustomerReference(UUID id)` (rejects a null ID) |
| `CustomerConcurrentConflict` | final unchecked; fixed message; no cause, no suppressed exceptions, own stack trace; retryable |
| `CustomerOperationFailure` | final unchecked; fixed message; no cause, no suppressed exceptions, own stack trace; non-concurrency failure |

The outcome and reference records are nested in their interfaces (the repository precedent for
`ServiceReferenceAccess`), which does not change the contract. Internal and not published:
`CustomerIdentificationService` (`customer.application`, implements both interfaces),
`CustomerStore`, `CustomerPersistenceException`, the domain types, the clock, and the ID supplier.
Nothing outside `customer` depends on it; the test-only consumer is not a Modulith module.

### Matching algorithm and query bounds

The display name is validated and canonicalized but never matches. Input is canonicalized once by
`CustomerProfile.fromInput` (the Phase 2 shared policy). Invalid input returns `InvalidIdentity`
with every invalid field of that pass and runs no SQL, reads no clock, and generates no UUID.
`CONTACT` is reported only when neither a phone nor an email was supplied; a supplied but invalid
phone or email is reported as `PHONE` or `EMAIL` and not as `CONTACT`, so a client can map each field
without duplicating the policy.

1. One statement, `findHolders` (`WHERE business_id = ? AND (phone = ? OR email = ?)`, no lock, at
   most two rows, order never relied on). The holder of the phone is the row whose phone equals the
   supplied phone and the holder of the email the row whose email equals the supplied email. A
   holder in another Business is never returned.
2. The approved truth table (rows 3 to 11) decides. One identifier supplied: holder, then
   `ExistingCustomer`; nobody, then create. Both supplied: the same Customer holds both, then
   `ExistingCustomer`; nobody holds either, then create; every other combination (two different
   holders, or one holder and a free other identifier, even when the holder has no value for it) is
   `IdentityConflict`. Nothing is attached, updated, renamed, moved, or merged.
3. Only when nobody holds a supplied identifier: read the clock once, generate one UUID, and run at
   most one `INSERT ... ON CONFLICT DO NOTHING ... RETURNING` (no conflict target, so a held phone,
   a held email, and an ID collision all return no row instead of raising). A returned row is
   `CreatedCustomer`.
4. When the insert returns no row: one final `findHolders`, evaluated by the same truth table. If it
   finds nobody, or cannot decide, the call throws `CustomerConcurrentConflict`. An ID collision can
   therefore never become a match: a result comes only from the supplied identifiers.

ADR-0020's "two attempts" means the initial resolution followed by this one bounded re-read, not two
insert attempts. There is no second insert and no loop. **Bounds** (tested by counting only SQL that
names the `customer` table, through a recording `DataSource`): invalid input 0 statements, an
existing match or an immediate conflict 1, a creation 2 (`SELECT`, then the insert), a race loser 3,
and never more than 3 (one initial lookup, at most one insert, at most one re-read). `find` runs one
`SELECT` by Business and ID.

### Transactions, isolation, and failures

Both operations are `@Transactional(propagation = MANDATORY)` (`find` also `readOnly`), the narrowest
Spring semantics, matching `ServiceReferenceAccess`. A call without a transaction fails in the
proxy (`IllegalTransactionStateException`) before the clock, the ID supplier, or persistence is
touched, even for input that would be invalid. Neither operation opens a transaction, uses
`REQUIRES_NEW`, or uses a savepoint, and no global transaction setting changed. `findOrCreate` joins
the caller's read-write transaction and `find` the caller's transaction, with no write and no
explicit lock. `READ_COMMITTED` is supported and tested; `REPEATABLE_READ` and `SERIALIZABLE` are
accepted and tested. The four normal outcomes return normally and do not mark the transaction
rollback-only.

Any thrown exception crosses the transactional proxy and marks the caller's transaction
rollback-only even if the caller catches it. Persistence failures are classified in the application
service and discarded, so no `customer.infrastructure` type crosses the boundary:

| Internal failure | Published exception |
|---|---|
| `UnexpectedFailure` with SQLState `40001` or `40P01` | `CustomerConcurrentConflict` |
| a re-read that finds nobody or is inconsistent | `CustomerConcurrentConflict` |
| every other `CustomerPersistenceException` (unavailable database, unknown SQLState, `UnknownBusiness`, `InvalidData`, an unresolved duplicate, a corrupt stored row) | `CustomerOperationFailure` |

Both exceptions invalidate the current transaction. `CustomerConcurrentConflict` is retryable in a
completely new outer transaction. `CustomerOperationFailure` is the sanitized internal failure and is
not declared retryable by this capability: the caller abandons the transaction and applies its own
higher-level failure policy; a connection outage is never classified
as concurrency merely because it is unexpected. Neither exception holds a value, ID, SQL, constraint
text, reason, cause, or suppressed exception, and each keeps its own application stack trace. A caller
that catches either inside its transaction can neither continue an Appointment-like write (PostgreSQL has
aborted the transaction) nor commit (`UnexpectedRollbackException`).

### Privacy

The capability has no logger at all (a test scans its sources), so no name, phone, email, Customer ID,
Business ID, SQL, or constraint text is logged. `CustomerIdentity.toString()` is redacted, and tests
with sentinel values prove the identity, every outcome, and both exceptions (message, cause,
suppressed, `toString()`) carry no submitted value.

### Verification evidence (executed 2026-10-02)

- Focused: `CustomerIdentificationServiceTests` 30 (every truth-table row, 100 or more invalid
  combinations, immutability, field mapping, bounds with a mocked store, failure mapping, sanitized
  exceptions, redaction), `CustomerIdentificationIntegrationTests` 27 (PostgreSQL rows, no mutation,
  isolation, reference lookup, no transaction, the three isolation levels, rollback and rollback-only,
  and the statement bounds), `CustomerIdentificationConcurrencyIntegrationTests` 11 (races, a real
  `40001`, a real `40P01`), `CustomerModuleBoundaryTests` 13.
- Races (latches plus `pg_stat_activity` lock-wait evidence, no sleeps; timeouts are only ceilings):
  identical phone-only, email-only, and phone-and-email submissions give one `CreatedCustomer`, one
  `ExistingCustomer` of the same ID, and no duplicate; a broader racer, a narrower racer, and a racer
  holding a different phone resolve by the truth table; a race revealing phone A and email B returns
  `IdentityConflict`; a rolled-back winner lets the waiting insert create the Customer itself. The
  race loser runs exactly three Customer statements.
- Real `40001`: a `REPEATABLE_READ` transaction whose snapshot predates a committed insert of the same
  phone raises `CustomerConcurrentConflict` through the public operation; the Customer and the probe
  row of the failed transaction do not remain; a new transaction then returns `ExistingCustomer`. A
  caller that catches it cannot write or commit.
- Real `40P01`: two transactions create a Customer each, then each inserts the other's phone, so the
  unique-index waits deadlock (second transaction proven blocked through PostgreSQL first). Exactly one
  gets `CustomerConcurrentConflict`, the other `CreatedCustomer`, and either victim is accepted.
  Two Customers and two probe rows remain, with no duplicates, and a new transaction can retry.
- The Appointment-like probe table is created and dropped by test code inside each test class, is not
  in Flyway or production resources, and every probe row has a composite foreign key to
  `customer(business_id, id)`.
- Full backend `./mvnw --batch-mode verify`: 1,922 tests, 0 failures, 0 errors, 0 skipped (BUILD SUCCESS); the previous total was 1,848.

### Deviations, limitations, and remaining work

- The new `Customer` exceptions are two (`CustomerConcurrentConflict` and `CustomerOperationFailure`);
  ADR-0020 named only the first, and its implementation clarification records the second.
- `findOrCreate` canonicalizes with `CustomerProfile.fromInput`; a stored row that violates a domain
  invariant surfaces as `CustomerOperationFailure`.
- Rate limiting, the guest-facing message, retry policy, and Appointment snapshots stay with #18.
- Statement counting uses a recording `DataSource` in tests only. A conflicting row that is deleted
  between the insert and the re-read cannot be produced because no Customer deletion exists; if it
  could occur the call would throw `CustomerConcurrentConflict`.
- **Not done (Phase 4 onward):** private administration API and authorization, list, search,
  `EXPLAIN` evidence, frontend screens, E2E, Booking, Appointments.

## Phase 4 record (implemented and verified; awaiting review and commit)

Phase 4 delivered the private, owner-only Customer administration backend of ADR-0021. It did **not**
start Phase 5: there is no frontend screen, route, navigation entry, or Vitest/Playwright test, no
Booking or Appointment behavior, no public Customer endpoint, no Customer deletion, lifecycle, note,
history, anonymization, or merge, no new matching semantics, and no migration beyond `V10`
(V1 to V10 are byte-for-byte unchanged). `pom.xml`, the frontend, and the security configuration did
not change.

### Final HTTP contract (`/api/business/customers`, session-authenticated, CSRF on POST and PUT)

| Method and path | Request | Response |
|---|---|---|
| `GET /` | query `page` (default 0), `size` (10, 25, 50; default 10), `sort` (`name` default, `phone`, `email`), `direction` (`asc` default, `desc`) | 200 `{items, page, size, total}`; item `{id, displayName, phone, email}` |
| `POST /search` | body `{search, page, size, sort, direction}`, every key optional | the same page response |
| `GET /{customerId}` | | 200 `{id, displayName, phone, email, version, createdAt, updatedAt}` |
| `POST /` | body `{displayName, phone, email}` | 201, `Location: /api/business/customers/{id}`, the detail body |
| `PUT /{customerId}` | body `{displayName, phone, email, expectedVersion}` | 200, the detail body |

`businessId`, the normalized name, version and timestamps in list rows, and every internal value are
never returned; a `businessId` in a body or query is ignored. There is no delete, merge, deactivate,
or public operation. Customer responses carry Spring Security's `no-store` headers, including errors.
Every Customer problem carries the fixed `instance` `/api/business/customers`, so a Customer ID in the
request path is never echoed; the malformed body, parameter, and path-ID failures are handled by the
Customer advice for the same reason.

### Authorization and lifecycle matrix

The Business is the authenticated selection. Reads use the non-locking `findLifecycle` and `authorize`
checks. Each mutation runs in one transaction and takes the shared Business lifecycle lock, then the
shared Membership lock (`lockLifecycle`, `lockAndAuthorize`), then performs the optimistic write; the
lock order is proved against PostgreSQL (below).

| Caller | Read and search | Create and update |
|---|---|---|
| Unauthenticated | 401 `AUTH_REQUIRED` | 401 `AUTH_REQUIRED` |
| Session without a selected Business | 403 `ACTIVE_BUSINESS_REQUIRED` | 403 `ACTIVE_BUSINESS_REQUIRED` |
| `PLATFORM_ADMIN` alone, inactive Membership, Membership in another Business | 403 `ACTIVE_BUSINESS_REQUIRED` | 403 `ACTIVE_BUSINESS_REQUIRED` |
| `MANAGER`, `STAFF` of the selected Business | 403 `ACCESS_DENIED` | 403 `ACCESS_DENIED` |
| `BUSINESS_OWNER` (also when `PLATFORM_ADMIN`) of a DRAFT or ACTIVE Business | allowed | allowed |
| `BUSINESS_OWNER` of a SUSPENDED Business | allowed | create: 409 `BUSINESS_SUSPENDED` (before validation); update: allowed (amended 2026-10-06, approved exception) |

Note on ADR-0021: the ADR says `PLATFORM_ADMIN` alone, inactive, and other-Business Memberships
receive `ACCESS_DENIED`. In the implemented session model (identical for the Service and StaffMember
APIs) a session whose Membership is not an active one of the selected Business has no usable selection,
so those cases are rejected earlier with `ACTIVE_BUSINESS_REQUIRED`; `ACCESS_DENIED` is returned when a
selection exists but the role is not owner. In both cases no Customer is read or written and no
existence is revealed. The tests pin the implemented behavior.

### Validation and safe errors

| Status | Code | Condition |
|---:|---|---|
| 400 | `VALIDATION_ERROR` with `fieldErrors` | invalid `displayName`, `phone`, `email`; `contact` when neither is supplied (every invalid field is named together) |
| 400 | `VALIDATION_ERROR` without `fieldErrors` | invalid `page`, `size`, `sort`, `direction`, a search term over 100 code points, missing or negative `expectedVersion`, a malformed body, or a malformed path ID |
| 404 | `CUSTOMER_NOT_FOUND` | unknown or foreign ID (byte-identical bodies) |
| 409 | `CUSTOMER_CONTACT_CONFLICT` | `fieldErrors.phone` and/or `fieldErrors.email`; reported from a holder lookup, with the unique indexes as final arbiter |
| 409 | `CUSTOMER_CONCURRENT_UPDATE` | stale `expectedVersion`, or a lost guarded write |
| 409 | `CUSTOMER_CONCURRENT_CONFLICT` | SQLState `40001` or `40P01` |
| 409 | `BUSINESS_SUSPENDED` | create in a SUSPENDED Business (update is allowed there since the 2026-10-06 amendment) |
| 500 | `INTERNAL_ERROR` | `CustomerOperationFailure` (any other persistence failure) |

The messages are exactly those of the error taxonomy above. A duplicate on update or create reports the
conflicting field only and never says which Customer holds the identifier.

### List and search semantics

The list is `GET` with paging and sorting only. `POST /search` takes the term in the JSON body only:
trimmed with the approved whitespace set; blank or absent means no filter; at most 100 code points after
trimming, otherwise a generic `VALIDATION_ERROR`. The criteria are OR-ed: the term canonicalized like a
display name is a `strpos` substring of the generated `normalized_display_name` (the needle gets the same
`casefold` and `NFKC`); the lower-case term is a `strpos` substring of `email`; a term that is a full valid
phone matches `phone` exactly; otherwise a term of digits with a leading `+`, `00`, or `0` becomes a
canonical prefix (`0` becomes `+359`) matched with `starts_with(phone, ...)`. `%`, `_`, and `\` are literal
because `strpos` and `starts_with` have no wildcard syntax; a term such as `+`, `00`, `+%`, `0_89`, or
`123` adds no phone criterion, so malformed phone-like input never broadens the search. No fuzzy,
accent-folding, transliterating, name-identity, trigram, or full-text matching exists.

Paging is server-side and zero-based; sizes 10, 25, and 50 only. Ordering: `normalized_display_name`
then `id` for `name`; `phone` or `email` with `NULLS LAST` in both directions, then
`normalized_display_name ASC, id ASC`. The statement count is constant: one page statement and one
count statement per list or search, whatever the page or result size (proved with a recording
`DataSource` through MockMvc). The `CustomerStore` SQL fragments are closed constants selected by an enum
or by which criteria exist; the term is only ever a bound parameter.

### Create and update

Create is the explicit administration operation, not `findOrCreate`: it canonicalizes with the shared
policy, rejects an already held phone or email (even an identical submission) with the safe conflict,
creates exactly one `customer` row, and writes no User, Membership, session, Appointment, or other
record (table counts compared in a test). Update replaces name, phone, and email atomically behind
`expectedVersion`; the Customer's own identifiers are ignored in the holder check; an unchanged update
still advances the version once and `updated_at` (from the injected clock, never backwards); removing a
contact is allowed only while the other remains; an identifier moves only through two explicit
operations, never implicitly; no merge exists. A failed update keeps the previous row exactly (verified
for validation, conflict, stale version, and a failure injected after the real `UPDATE` statement).

### Transaction and persistence behavior

`CustomerAdministrationService` (package `customer.application`; an internal class, not a published
contract, so the published root package is unchanged) uses `@Transactional(readOnly = true)` for reads and
`@Transactional` for mutations, no `REQUIRES_NEW`, no savepoint, and no global change. Persistence
failures are classified in the service and discarded: `DuplicatePhone` and `DuplicateEmail` become
`ContactConflict`, SQLState `40001` and `40P01` become the published `CustomerConcurrentConflict`, and
everything else the published `CustomerOperationFailure`; Phase 3 matching, outcomes, and rollback
semantics are untouched. `CustomerStore` gained `list`, `count`, and the package-private `listSql` and
`countSql` (so the `EXPLAIN` evidence runs the production SQL); classification still uses SQLState and
the structured driver fields only. Controllers and HTTP records live in `customer.web` and see only
`customer.application` types; persistence records stay internal. The module now depends on `shared`
(`contact`), `identity` (`AuthenticatedBusinessContext`, `SelectedBusinessOwnerAccess`,
`SelectedBusinessRequired`), and `business` (`BusinessLifecycleAccess`), as ADR-0021 approved, and on
nothing else.

### Privacy and tenant-isolation evidence

Business A/B isolation is tested for list, search, detail, create, and update, including guessed IDs, a
client-supplied `businessId`, and byte-identical 404 bodies for foreign and unknown IDs. Exact key
allowlists are asserted for list rows, detail, and page. Sentinel names, phones, emails, IDs, and
Business IDs never appear in Problem Details, in Logback events captured at DEBUG around searches,
conflicts, validation errors, stale updates, suspension, and injected failures, in exception messages,
causes, suppressed exceptions, or `toString()` (all Customer commands, requests, and responses redact
theirs). The Customer source tree still contains no logger (scanned by `CustomerModuleBoundaryTests`).
The search term is absent from the URL, query string, and redirects. A public-profile request for a
Business with a sentinel Customer, and anonymous Customer requests, contain no sentinel. A test
enumerates every Spring MVC mapping: exactly the five approved Customer endpoints exist, none under
`/api/public`. One recorded observation: Spring's own DEBUG request line (off by default) prints the
request path, which can contain an opaque Customer ID; nothing at INFO or above carries it.

### `EXPLAIN` evidence (about 10,000 Customers in one Business, 20,000 in the table; PostgreSQL 18.4)

Executed by `CustomerSearchExplainIntegrationTests` against the production SQL. The default first page
reads `customer_business_normalized_display_name_id_idx` with no sort (about 0.02 ms); name descending
reads it backward (about 0.04 ms); a search page filters the Business through that index in 1.3 to 2.0 ms
and its count by one scan of the Business's rows in 1.3 to 1.9 ms; sorting by phone or email scans the
Business's rows with a top-N sort in about 3 to 12 ms; the last page (offset 9,990) chooses a scan and a
sort in about 4 ms. Every statement filters by `business_id`. Timings are recorded here and are not
asserted by the test. No trigram, full-text, or extra index is added: the evidence does not fail the
ADR-0021 criterion, and an index remains a follow-up only if later measurement fails.

### Verification evidence (executed 2026-10-03)

- Full backend `./mvnw --batch-mode verify`: 2,217 tests, 0 failures, 0 errors, 0 skipped (BUILD
  SUCCESS); the previous total was 1,922 (+295: 289 new tests in new classes, 4 new boundary tests, and
  2 new tests in the shared `ApiExceptionHandlerTests`, now 6).
- New tests (executed counts): `BusinessCustomerAuthorizationApiIntegrationTests` 21 (including the exact routing-error tests),
  `BusinessCustomerReadApiIntegrationTests` 39, `BusinessCustomerMutationApiIntegrationTests` 119 (including
  the shared golden vectors through the API), `BusinessCustomerPrivacyApiIntegrationTests` 10,
  `CustomerStoreListIntegrationTests` 16, `CustomerSearchExplainIntegrationTests` 3,
  `CustomerAdministrationLockingIntegrationTests` 3, `CustomerAdministrationServiceTests` 24,
  `CustomerInputValidatorTests` 25, `CustomerSearchCriteriaTests` 22,
  `BusinessCustomerExceptionHandlerTests` 7; `CustomerModuleBoundaryTests` 17 (was 13).
- Regressions green in the same run: `CustomerSchemaIntegrationTests` 34, `CustomerStoreIntegrationTests` 23,
  `CustomerStoreFailureTranslationTests` 27, `CustomerProfileTests` 33, `CustomerTests` 8,
  `CustomerIdentificationServiceTests` 30, `CustomerIdentificationIntegrationTests` 27,
  `CustomerIdentificationConcurrencyIntegrationTests` 11, `ContactPolicyVectorTests` 94, and the global
  `ModuleBoundaryTests`, `PublicProfileModuleBoundaryTests`, and `AvailabilityModuleBoundaryTests`.
- No sleeps: races use latches, `pg_stat_activity` lock-wait evidence, and database-decided outcomes
  (exactly one winner); timeouts are only ceilings.
- Migration SHA-256 comparison of V1 to V10 against `HEAD`: identical. No frontend, `pom.xml`, migration,
  Booking, or Appointment file changed. The frontend suite was not rerun because no frontend file changed.

### Deviations, limitations, and remaining Phase 5 work

- The ADR's `ACCESS_DENIED` for platform-only, inactive, and other-Business Memberships is returned as
  `ACTIVE_BUSINESS_REQUIRED`, as for every private API (see the matrix note). Not an ADR redesign.
- Routing errors (post-review correction): the shared `ApiExceptionHandler` now maps Spring's
  `HttpRequestMethodNotSupportedException` to 405 `METHOD_NOT_ALLOWED` ("Методът не е разрешен за този
  адрес.", with the `Allow` header) and `NoResourceFoundException`/`NoHandlerFoundException` to 404
  `ROUTE_NOT_FOUND` ("Адресът не е намерен."), both with the fixed `instance` `/api`; they previously fell
  to the generic 500. Final decisions, pinned by exact tests: `DELETE /{validId}` is 405;
  `POST /{validId}/merge` is 404; `GET` and `PUT /search` are 405 with `Allow: POST` because the ID
  mappings exclude the literal segment `search` (regex `^(?!search$).+`); every other malformed ID
  (`not-a-uuid`, `searching`) stays a generic 400 `VALIDATION_ERROR`. No delete, merge, or catch-all
  operation exists, the five-endpoint inventory is unchanged (the two ID patterns now carry the regex),
  and anonymous callers still get 401 and missing CSRF still 403 before routing. The Customer row and
  count are asserted unchanged.
- The lock order is proved with mocks (`InOrder`) and, against PostgreSQL, by two transactions that hold
  the Business and Membership row locks while a mutation is blocked (lock-wait evidence from
  `pg_stat_activity`); it is not a full Service-style pausing-bean matrix.
- Retry policy for `CUSTOMER_CONCURRENT_CONFLICT` stays with the caller (the interface) and issue #18.
- PostgreSQL's server log can contain a key value for a rare administrative unique violation (recorded
  operational limitation, unchanged).
- Not done (Phase 5 and 6): the Business-owner navigation, list and search interface, create, detail and
  edit screens, validation and guards in the browser, SUSPENDED read-only mode, rendered review at
  desktop, tablet, mobile, and 200% zoom with human approval, the shared frontend table behavior, and the
  Playwright journey.

## Phase 5 record (implemented and verified; awaiting human visual approval, review, and commit)

Phase 5 delivered the authenticated Business-owner Customer administration frontend. It did **not**
start Phase 6: there is no Playwright journey, no Customer deletion, archive, lifecycle, note, history,
anonymization, or merge, no Booking or Appointment behavior, no public Customer page, no frontend retry
loop, and no backend, migration, schema, dependency, or security change (`pom.xml`, `package.json`, and
V1 to V10 are unchanged).

### Final routes and Bulgarian terminology

| Route (hash) | Page heading (`h1`) | Notes |
|---|---|---|
| `#/business/customers?page=0&size=10&sort=name&direction=asc` | `Клиенти` | list; sorts `name`, `phone`, `email`; the search term is never part of the route |
| `#/business/customers/new[?page&size&sort&direction]` | `Нов клиент` | the optional query is the list state to return to |
| `#/business/customers/{customerId}[?page&size&sort&direction]` | `Клиент` | same return state |

Navigation item `Клиенти` is one of the Business-scoped links under the selected Business's name (final
navigation: see the correction below; the original placement after `Работно време` and the Profile-page owner links
are superseded); never for Platform Administrators without an owner Membership, `MANAGER`, `STAFF`, or
unauthenticated users (the owner-only route guard sends them to the Business selection, or the Profile when the user
manages no Business). Copy: `Добави клиент`,
`Търсене` (placeholder `Име, телефон или имейл`; live search, no `Търси` or `Изчисти` button, see the
correction below), columns `Име`, `Телефон`, `Имейл`, `Добави`,
`Запази промените`, `Редактирай`, `Отказ`, `Обратно към клиентите`, `Зареди актуалните данни`, the hint
`Попълнете телефон или имейл.`, `Не са намерени клиенти по това търсене.` (empty search),
`Все още няма добавени клиенти.` (empty catalog), `Клиентът е добавен.`, `Промените са запазени.`. The Customer
messages of the error taxonomy are frontend-owned (`business/customers/errors.ts`): the backend text is never
rendered for the stable codes, field names are mapped to the approved text, unknown names are ignored, and an
unknown code uses a generic fallback.

### Route state and search privacy

- Page, size, sort, and direction live in the URL; invalid values normalize field by field, and automatic
  canonicalization or out-of-range recovery replaces history while user changes (sort, size, page, create or
  open) push it. Sorting, size, and search changes reset to page 0 (a search from a later page is one push).
- The search term is held in the authenticated application's React state only (`AuthenticatedApplication`),
  keyed to the active Business, and sent only in the body of `POST /api/business/customers/search`. A blank term
  uses the ordinary `GET`. The term never reaches the URL, hash, history, `localStorage`, `sessionStorage`,
  IndexedDB, cookies, the document title, or any log (tests assert URL, both storages, cookie, and title). It
  survives list, detail or create, and return in the mounted session, is dropped when the user leaves the
  Customer area or switches Business, and is not reconstructed by a refresh or a direct link.
- A Business switch returns the list to the defaults (and the create and detail routes drop their carried
  list state); the new Business's list is rendered with the defaults from its first render, and a late
  response of the previous Business is ignored (aborted requests and a per-effect abort signal).
- The term is trimmed with the approved whitespace set (no NFKC) and limited to 100 code points locally. (The
  original explicit-submit interaction and its focus handling are superseded by the live search below.)

### Create, edit, and conflicts

One `CustomerForm` serves both (a confirmed discard that leaves it mounted remounts it from the
loaded values, like the Business and schedule-change forms): `Име`, `Телефон`, `Имейл`, all `autocomplete="off"`, `noValidate`, the shared
`useFieldValidation` policy (blur, immediate error for an invalid non-empty value, submit shows all and focuses
the first). Phone and email use the shared `contactPolicy` and the golden vectors (every vector is run against the
Customer form rules). The `contact` error (neither value) appears only after a submit attempt or from the backend,
once, under the two fields, and replaces the hint; each control is described by it. Backend `VALIDATION_ERROR`
and `CUSTOMER_CONTACT_CONFLICT` field names map inline (duplicate phone and email on their own fields); the entered
values are kept; `CUSTOMER_CONCURRENT_CONFLICT`, `INTERNAL_ERROR`, access errors, and unnamed validation errors use
the safe alert and are never retried. Create navigates to the detail only after the response, clears the guard
first, and the success message appears after the detail has loaded. Edit sends `expectedVersion`, allows removing
one contact while the other remains, and rejects removing both locally. `CUSTOMER_CONCURRENT_UPDATE` keeps the
entered values, shows the conflict message, and offers `Зареди актуалните данни`, which goes through the shared
unsaved-changes guard before replacing the form with the latest server data and version. `version`, `createdAt`,
and `updatedAt` are transport-only and not displayed. **Metadata decision (confirmed against this record and
ADR-0021):** both documents list these three fields only as members of the detail *response* (API exposure) and
nowhere require or describe them as visible page content; `docs/ui-design-guidelines.md` and `architecture.md`
treat versions as technical state that is kept and not displayed (Businesses, Services, StaffMembers). The detail
page therefore shows the name as the page header (like the Service, Staff, and Business details), then phone and
email only when present; no version badge, timestamp, ID, or status is shown. Showing any of them would need a
separate product decision.

### Lifecycle and guards

DRAFT and ACTIVE: list, search, create, and edit. (Superseded by the Third correction below for SUSPENDED: edit of
an existing Customer is now allowed.) SUSPENDED: list, search, and detail stay readable; the existing
shared banner appears once, with Customer-specific wording (see the correction below); `Добави клиент`,
`Редактирай`, and every editable field are absent; a direct create route shows that banner and the way back. When a mutation reveals the suspension
(`BUSINESS_SUSPENDED`) the safe message is shown and the session is refreshed so the screen enters the same read-only
presentation (the entered values of that failed create cannot be saved and are no longer shown). Create and edit
register the shared guard: sidebar links, the back action, browser Back, Business switching, logout, `Отказ`, and the
reload after a conflict all show the shared dialog (`Остани` first and focused, Escape stays and restores focus,
`Напусни` destructive); a successful save shows no false dialog.

### Table standard (section 15.1) as implemented

Default `name` ascending; sortable Name, Phone, Email through the shared `SortableColumnHeader` and, in card
layout, `ResponsiveSortSelect`; server-side sorting and pagination; 10, 25, and 50 through the shared
`ListPagination` (one size selector, the range summary, `Предишна` and `Следваща`); no actions column; the row opens
the detail through the name link; cards at the same breakpoints as the Staff table; missing phone or email shown as
an accessible `—`; long values wrap inside their cells.

### Verification evidence (executed 2026-10-03)

- Full frontend: `npm ci`, `npm run test` (58 files, 1,345 tests; the previous total was 1,103 in 51 files),
  `npm run lint`, and `npm run build` pass.
- New and extended Vitest: `business/customers/validation.test.ts` (shared vectors and rules, search bounds),
  `api.test.ts` (GET versus POST body), `errors.test.ts`, `CustomerList.test.tsx` (columns, sorting both
  directions, sizes, partial and out-of-range pages, search, stale responses, privacy, states),
  `CustomerCreate.test.tsx`, `CustomerDetail.test.tsx` (edit, contact removal, conflicts, guarded reload, guard),
  `App.customers.test.tsx` (routes, canonicalization, history, access by role, in-memory search, Business
  switch, guards, suspended mode, created message), `navigation.test.ts` (Customer routes), the shell tests
  (navigation order), and `ui/layoutRules.test.ts` (durable Customer layout rules).
- Rendered review (Chromium through Playwright against a disposable PostgreSQL container, backend, and Vite server,
  synthetic data created through the supported APIs, separate from the development database): 1280, 1024, 800,
  640 (200% zoom equivalent), 375, and Pixel 7 (412) widths with no horizontal overflow on the list, a later page,
  and the create form; screenshots inspected for the multi-page list, sorting, search and clear, an empty search,
  an empty catalog, the too-long search error, create with inline errors and duplicate phone and email, the
  unsaved-changes dialog (Escape restores focus to the invoking link), detail, edit, the stale-version flow with
  the guarded reload, SUSPENDED list, detail, and create, long Bulgarian name and long valid email at 375, and the
  retryable load failure. One spacing defect (the heading action and the search sat too far apart because a
  margin stacked on the layout gap) was fixed and re-reviewed. This is developer review, not human approval.

### Correction after human visual review (2026-10-04): navigation, Business selection, live search, SUSPENDED wording

Human review found that personal settings and Business selection were mixed, that Business links appeared
abruptly after a selection, that submit-based search with a `Изчисти` button was awkward, and that Customer pages
called Customers "configuration". Decisions, which replace the earlier behavior where they differ:

- **Information architecture.** Global destinations (`Бизнеси`, `Профил`) are always present and stand above a
  separate group headed by the selected Business's name (`Услуги`, `Екип`, `Работно време`, `Клиенти`). That group
  exists only while a Business the user manages is selected; Business-scoped links never exist without their context.
  The bottom of the sidebar shows the signed-in user and `Изход`, never the Business. A shared
  `ui/ShellNavigation` renders the same structure in both shells, so every page shows one model.
- **Business selection is not a personal setting.** The selector is removed from the Profile, which now holds only
  personal data and the password change. `Бизнеси` (`#/businesses`) lists the Businesses the user manages (cards, not
  a data table: the list is the user's own Memberships), with lifecycle wording (`Предстои активиране`, `Активен`,
  `Временно спрян`), a visible `Избран` marker, and `Управлявай` (accessible name `Управлявай {Business}`; both superseded by the
Third correction below: the button is `Покажи` and the badge is a checkmark on a pale-green card), which
  selects the Business through the existing session endpoint and opens `Услуги`. DRAFT, ACTIVE, and SUSPENDED are all
  selectable; no technical role or enum value is shown; only owner Memberships are offered.
- **Routing.** With several Businesses and none selected the user lands on `Бизнеси`; with exactly one the existing
  automatic selection and `Услуги` landing are unchanged and `Бизнеси` stays available. A direct link to a Business
  screen without a selected Business opens `Бизнеси` (or the Profile for a user who manages none). The selection
  persists through the server session across refresh. If the selected Business becomes unavailable (the session no
  longer carries it, e.g. `ACTIVE_BUSINESS_REQUIRED` from a Customer screen, which refreshes the session), the user
  returns to `Бизнеси` (now for every Business-scoped screen; see the second correction below). The guard still
  covers sidebar navigation, browser Back/Forward, logout, and the `Управлявай` switch.
- **Administrators.** A Platform Administrator's `Бизнеси` remains the platform list. An administrator who also
  manages Businesses gets a second link, `Моите бизнеси`, to the owner selection. This naming is an **approved,
  intentional exception** (see the second correction below); no other combination has two Business links.
- **Live search.** The `Търси` and `Изчисти` buttons are gone. The effective search follows the typed text about
  300 ms after the last keystroke (`SEARCH_DEBOUNCE_MS`); removing characters searches again; an empty or
  whitespace-only field restores the unfiltered list; Enter applies at once and Escape clears at once; a changed
  effective search resets to page 0 (one history push only when the page was not already 0), keeps size, sort, and
  direction, and a repeated identical effective term sends nothing. A superseded request is aborted and an old
  response can never replace a newer one. The previous result stays visible, dimmed and `aria-busy`, with a polite
  status, while a newer one loads (the table is not blanked per keystroke); the empty message follows the term that was
  searched (catalog-empty versus no-results stay distinct). A term over 100 code points shows its error at once, is
  never sent, and leaves the last valid result. The term stays in memory only (still never in the URL, storage,
  cookies, title, or logs).
- **Contextual SUSPENDED wording.** The shared banner takes its text from `business/lifecycleNotice.ts` by route.
  Customer routes say `Бизнесът е временно спрян. Можете да преглеждате клиентите, но не можете да добавяте или
  редактирате.`; other screens keep their existing generic sentence (their own legacy repeats are untouched). The
  message appears once; create and edit are absent, not disabled; the backend still rejects forced mutations.
- **Unsaved-changes focus.** When cancelling the shared dialog and the invoker is gone or hidden (a link in a closed
  mobile menu), focus now goes to the declared `data-focus-fallback` control (the menu button) instead of the page
  body (found in the rendered review).

- **Evidence (2026-10-04).** Full frontend suite: 60 files, 1,395 tests (before this correction 58 files, 1,355);
  `npm run lint` and `npm run build` clean; focused selection (shells, selection, Profile, routes, guard, shared
  UI, Customers) 23 files, 580 tests; broader regression (Services, Staff, Schedule, public) 33 files, 669 tests.
  Rendered review (Playwright Chromium against a disposable stack, synthetic data through the APIs) at 1280, 800,
  640, 375, and Pixel 7 (412): no Business selected, the selection list with a clear selection, DRAFT, ACTIVE, and
  SUSPENDED selected, the Customer list, live search (no request before the pause, one request for the final
  value, one for deleted characters, a plain `GET` after clearing, nothing in URL or storage), no matches, an
  empty catalog, a SUSPENDED list, detail, and create, long Business and Customer names, the mobile menu with
  keyboard navigation and Escape, focus rings in a scrolling sidebar, and a dirty form followed by global navigation:
  no horizontal overflow and no clipped focus. One defect found and fixed (focus after cancelling the guard dialog
  on mobile). Developer review only; human visual approval is pending.

### Second correction (2026-10-05): consistent Business-context recovery, one lifecycle notice, approved naming

**Backend facts established first.** Every Business-scoped API family (Services, StaffMembers and their assignments,
working schedules, schedule exceptions, Customers) answers a request whose selected Business the session no longer
supports with `403 ACTIVE_BUSINESS_REQUIRED`. The server's session filter has by then cleared the selection and persisted
that, so `GET /api/auth/session` no longer reports `activeBusinessId`. The other outcomes are different codes and are never
read as a lost context: `404 *_NOT_FOUND` (a missing or foreign record), `403 ACCESS_DENIED` (a retained selection whose
role is not owner), `409 BUSINESS_SUSPENDED`, `401 AUTH_REQUIRED`, and network or server failures. No backend change was
needed.

**One shared mechanism.** `identity/businessRequest.ts` wraps `request` for every Business-scoped API module (the five
feature `api.ts` files). A `403 ACTIVE_BUSINESS_REQUIRED` is announced once to the application, and the error is rethrown
unchanged, so each screen still shows its own safe message. `AuthenticatedApplication` registers the handler:

- **Refresh once.** `refreshSession` runs one `GET /api/auth/session` at a time; reports that arrive while one is in
  flight, or when the session already has no selection, start nothing. After a refresh that confirms the Business is
  still valid, further reports are ignored for 5 seconds (`CONTEXT_RECHECK_COOLDOWN_MS`), so a screen that refetches on
  every render cannot create a request loop. A `401` found while refreshing follows the ordinary authentication flow;
  any other refresh failure changes nothing.
- **Leave only when the context is really gone.** If the refreshed session carries no valid owner Business, the route is
  **replaced** (never pushed) with `#/businesses`; Back cannot return to a screen without context, and the Business
  group disappears from the sidebar at once. If access is still valid the context stays and the screen's safe error
  remains. A SUSPENDED Business keeps its selection and read-only access (the Customer screens that reveal a suspension
  through a rejected mutation still refresh the session, forced past the cooldown).
- **A lost selection is not a Business switch.** The "Business changed" route reset no longer runs when the selection
  merely disappears, so it cannot override the redirect; the screens also keep their key (no remount) while the selection
  is gone, and `Business-scoped` search and list state are cleared by the existing leaving-the-area rules.
- **Dirty forms.** The redirect goes through the shared unsaved-changes guard: a clean screen leaves at once; a dirty form
  asks first (`Остани` keeps every value and the form with its safe error, without asking again; `Напусни` leaves). No
  Business-scoped link remains while the dialog is open.
- **Late responses.** The departing screen is unmounted and its requests are aborted; a response of the old Business that
  still arrives is never rendered (tested).

The Customer screens no longer carry their own recovery callbacks; they use the same shared mechanism.

**One lifecycle notice per screen.** The shared banner is the only SUSPENDED lifecycle statement. The page-level repeats
that began "Бизнесът е временно спрян — …" were removed from the Services list and create pages and the Staff list and
create pages (the schedule screens never had one); a create route in a SUSPENDED Business keeps only its way back, and
the list no longer leaves an empty action row (blank space) under the banner. Action-specific explanations that do not
restate the lifecycle (for example "Нови промени не могат да бъдат добавяни." and the inactive-StaffMember note) stay.
Customer screens keep exactly `Бизнесът е временно спрян. Можете да преглеждате клиентите, но не можете да добавяте или
редактирате.`; the other screens keep the generic banner wording.

**Approved naming exception.** Business selection for an ordinary owner is `Бизнеси`; for a Platform Administrator
`Бизнеси` is the platform-wide list; a Platform Administrator who also manages Businesses reaches the owner selection
through `Моите бизнеси`. The three destinations stay distinct (`#/businesses` for owner selection,
`#/platform/businesses` for the platform list); no role or enum value is ever shown.

**Evidence (2026-10-05, executed).** Full frontend suite 61 files, 1,424 tests; lint and build clean.
`App.contextRecovery.test.tsx` (33 tests, the real feature API code with only the network helper mocked) proves recovery
from Services (list, detail, create), Staff (list, detail, create, assignments), Working Hours, Schedule Changes (list,
create, detail), and Customers (list, detail, create); one refresh for simultaneous failures; replace-not-push; no loop
when the refreshed session still carries the Business; missing Service and Customer records, `ACCESS_DENIED`,
SUSPENDED, `401` (and `401` during the refresh), and network failures all keeping the context; the guard dialog with
`Остани` and `Напусни`; late old-Business responses ignored; one notice on every SUSPENDED screen; and the owner and
administrator labels. Rendered review (Playwright Chromium, rebuilt disposable stack) at 1280 and 375: one notice on
the Services, Staff, and Customer lists, create pages, and Customer detail; no horizontal overflow. **Limitation:** a
Membership cannot be revoked through any supported API, so the lost-context scenario was reproduced in the browser by
intercepting the two server answers (`403 ACTIVE_BUSINESS_REQUIRED` and a session without a selection) in the browser
only, with no data changed; it showed `#/businesses`, no extra history entry, one session request, no Business group,
and, for a dirty Customer form, the dialog with the values preserved. That simulation, not a real revocation, is the
browser evidence. Developer review only; human visual approval is pending.

### Third correction (2026-10-06): `Покажи`, selected-card appearance, editing in SUSPENDED, unchanged search

Approved changes. They replace the earlier wording above where they differ; the earlier text stays as history.

**Plan recorded for the Strict backend part (executed as approved).** Inspect `CustomerAdministrationService`: every
mutation calls one `authorizeMutation` that locks the Business lifecycle row, then the Membership row, then rejects a
SUSPENDED Business. Smallest change: `authorizeMutation(context, allowedWhenSuspended)`; `create` passes `false`, `update`
passes `true`. Nothing else moves: the locks and their order, owner-only authorization, the optimistic version check,
validation, uniqueness, the transaction boundary, the HTTP contract, the error taxonomy, the published
`CustomerIdentification`/`CustomerReferenceAccess`, the store, and the shared lifecycle access types are untouched, and no
migration is needed. Verification: unit tests with mocks (locks, order, authorization, version), PostgreSQL API tests, and a
real-lock race test.

**Backend scope of the exception (precisely).** In a SUSPENDED Business the authorized owner of the selected Business may
`PUT /api/business/customers/{id}` an existing Customer (name, phone, email, `expectedVersion`). Everything else is
unchanged: `POST /api/business/customers` still returns `409 BUSINESS_SUSPENDED` before validation and creates no row;
reads and `POST /search` stay allowed; `MANAGER`, `STAFF`, inactive, other-Business, platform-only, and unselected callers get
the same `ACCESS_DENIED` / `ACTIVE_BUSINESS_REQUIRED`; a foreign or unknown ID is the same byte-identical `CUSTOMER_NOT_FOUND`;
a stale or missing version, validation errors, and a duplicate phone or email still apply; DRAFT and ACTIVE behave as before;
Services, StaffMembers, schedules, schedule changes, and Business administration still reject mutations in SUSPENDED.
**This exception applies to no other Business operation.**

**Frontend.** The Customer detail always offers `Редактирай` (the page no longer has a read-only mode); create remains
absent: no `Добави клиент`, and a direct create route renders only its way back. The notice on every Customer screen of a
SUSPENDED Business is exactly `Бизнесът е временно спрян. Можете да преглеждате и редактирате клиентите, но не можете да
добавяте нови.`, shown once through the shared banner (`business/lifecycleNotice.ts`). Saving keeps the stale-version,
validation, and duplicate-contact handling and the guarded reload; the unsaved-changes guard is unchanged. If the suspension
begins during an existing edit the update still succeeds; if it is revealed during creation, creation stays blocked (the
earlier read-only presentation applies to the create route only). Unavailable-Business recovery and authentication handling
are unchanged.

**Business selection.** The button is exactly `Покажи` (visible text and accessible name; no `aria-label`). Each Business is
an `article` named by its own `h2` heading, so tests and assistive technology find the card first and then its `Покажи`
button. The visible `Избран` badge is removed. The selected card has a pale-green background (`--color-success-subtle`) with a
matching border (`--color-success-border`), a small checkmark, `aria-current="true"`, and visually hidden text `Текущо избран
бизнес`; the checkmark means "current selection" only and is independent of the lifecycle badge, so a DRAFT or SUSPENDED
Business can be the selected one. The lifecycle badge stays and, on the green card, keeps its own white chip. Selection,
navigation, pending state, duplicate protection, and the unsaved-changes guard are unchanged.

**Search semantics unchanged.** No algorithm, mode, minimum length, or explanation was added: case-insensitive name
substring over the normalized name, lowercase email substring, canonical phone exact or prefix, 300 ms debounce, request
cancellation, in-memory term, server-side sorting and pagination. New regression evidence: a backend API test finds a
Customer by surname, by a fragment inside a word, by a fragment spanning two words, in other letter case, and with extra
inner whitespace, none of them a prefix of the display name; frontend tests send such terms (and a single character)
verbatim.

**Evidence (2026-10-06, executed).** Full backend `./mvnw --batch-mode verify`: 2,229 tests, 0 failures (BUILD SUCCESS; Phase 4
recorded 2,217). Focused: `BusinessCustomerSuspendedApiIntegrationTests` 8, `CustomerAdministrationServiceTests` 26,
`CustomerAdministrationLockingIntegrationTests` 4, the Customer API suites, and the unchanged `CustomerIdentification*` suites.
Full frontend suite 61 files, 1,440 tests (three consecutive runs); lint, build, and `git diff --check` clean. Migrations,
`pom.xml`, `package.json`, and the lockfile are unchanged. Rendered review (Playwright Chromium, disposable stack, synthetic
data through the supported APIs, 1280 and 375 px): cards with and without selection for DRAFT, ACTIVE, and SUSPENDED
(computed pale-green background `rgb(236, 253, 243)` and border `rgb(166, 224, 197)`, one checkmark, one `aria-current`, the
lifecycle badge unchanged), `Покажи` buttons with a visible keyboard focus ring; in the SUSPENDED Business editing, saving,
persistence after reload, a stale version, and a duplicate email all worked, creation had no control and a direct create route
no form, a forced `POST` was rejected by the real backend with `409 BUSINESS_SUSPENDED`, and the exact notice appeared once;
searching a surname (`Пробен`), a span across two words (`ис Про`), a middle fragment (`естов`), other letter case (`ИВАНОВА`), and
`ова-Пет` found the right Customers; no horizontal overflow anywhere. Developer review only; human visual approval is pending.

### Pre-approval pagination correction and consistency audit (2026-10-04)

- **Pagination correction.** An empty page beyond the data used to be recovered only when `total > 0`. A stale
  route such as `page=2` whose whole result became empty (`total = 0`) stayed on the nonexistent page. Now any
  empty page above 0 is never rendered: the route is replaced (never pushed) with the last valid page, or page 0
  when nothing matches, the list stays in its loading state until that one follow-up request returns, and the
  size, sort, direction, and in-memory search are kept. Page 0 is final, so there is no loop. Covered by
  `CustomerList.test.tsx` (ordinary and search cases, replace-only, two requests, state kept, no loop, `total > 0`
  still recovering to the last page) and `App.customers.test.tsx` (canonical URL, unchanged history length, search
  kept and absent from the URL).
- **Audit scope.** Read in full: `README.md`, `testing-strategy.md`, `security.md`, `architecture.md`, ADR-0019 to
  ADR-0021, the Phase 4 HTTP records and exception handler, the Services, Schedule Changes, and Platform Business
  frontends, and the shared list, sort, pagination, form, feedback, dialog, and guard code they use.
- **Matches an established convention (unchanged):** list page structure (page action row, then the filter form,
  then results; the schedule list is the precedent for keeping its form mounted while results load), left-aligned
  primary action, the shared sortable headers, `ResponsiveSortSelect`, and `ListPagination` (the Services, Staff, and
  Business lists still carry older private pagination markup; the guide mandates the shared one), the card
  breakpoints, `Опитай отново` retry, `feedback-action-layout` states, inline `FieldError` with `aria-describedby`,
  `useFieldValidation`, error alert focus, `useFeedback` lifetimes, the shared unsaved-changes dialog and its sizing
  (no custom dialog), `Зареди актуалните данни` through the guard, `Отказ` and `Запази промените`, and `Редактирай`.
- **Inconsistencies found and corrected** (each is a repeated, reusable convention, and each has a test):
  1. The detail showed the name as a labelled row; Service, Staff, and Business details show the entity name as the
     `h2` header card. The Customer detail now has that header and lists only phone and email.
  2. The form did not reset on a confirmed discard that leaves it mounted (ui guide section 16; the Business and
     schedule forms do). `CustomerForm` now remounts from the loaded values.
  3. The back action stayed enabled while a create or save was in flight (the schedule screens disable it). It is
     now disabled while pending.
  4. List links had the name as their only accessible name; all four sibling lists use `Отвори {name}`. The Customer
     link now has it (the visible text is contained in the name).
- **Intentional differences that remain:** the search form (no precedent); the contact group, its once-only
  group error, and the hint; the success message of a created Customer is shown by the detail after it has loaded
  (the schedule flow shows it immediately, Services and Staff show none); no `h2` and no explanatory paragraph in the
  create section (the heading and the schedule create screens already say it, guide sections 17 and 20, whereas the
  older Service, Staff, and Business create screens repeat it); no duplicated lifecycle sentence under the shared
  SUSPENDED banner (the schedule screens already omit it; Services and Staff still repeat it); the page action row
  is not rendered when empty and its own bottom margin is zeroed inside the list stack so the layout gap is the single
  source of spacing (the sibling lists stack a margin on the gap, a pre-existing quirk outside this phase); the
  mutation-revealed SUSPENDED refresh; and the edit button goes through the guard.

### Deviations, limitations, and remaining work

- **Documented conflict resolved:** the earlier table note kept size, sort, and direction on a Business switch;
  the Phase 5 requirement clears them (above) and this record and the guide are updated.
- The read-only transition after a mid-edit `BUSINESS_SUSPENDED` discards the unsaveable form values; the other
  owner screens show only the message.
- The built-in browser pane was hidden during the session, so the rendered review used the repository's
  Playwright Chromium instead; the human visual checkpoint is still outstanding.
- Not done (Phase 6): the Playwright administration journey, Business A/B isolation through the UI, SUSPENDED
  and privacy checks in the browser, Booking-seam evidence, full gates, roadmap completion, and closing the issue.

## Phase 6 record (implemented and verified; awaiting final review and commit)

Phase 6 added the browser E2E verification and reconciled the documentation. It changed **no** production code, backend
code, migration, dependency, `package.json`, lockfile, or workflow (V1 to V10 are byte-identical to `HEAD`; hashes below).
No production defect was found, so no production fix was made.

### Files

- New: `frontend/e2e/customer-administration-journey.spec.ts` (12 tests), `customer-lifecycle-isolation.spec.ts` (5),
  `customer-mobile-smoke.spec.ts` (3, Pixel 7), `frontend/e2e/support/customers.ts` (fixtures, deterministic expected
  order, privacy and route helpers).
- Changed: `support/publicProfile.ts` (three existing declarations exported for reuse; no behavior change),
  `business-configuration-journey.spec.ts` and `business-onboarding-lifecycle.spec.ts` (test defects below).
- Documentation: this record, README, architecture, product roadmap, implementation plan, testing strategy, security.

### Test defects found in existing specs (not production defects)

Running the unchanged suite against the redesigned navigation gave 47 passes, 1 skipped, and 6 failures. One was the root
cause and five were cascades of it (the first failure left Business A SUSPENDED and skipped its restoring test). The stale
assertions, updated to the approved UI without weakening what they check:

1. The SUSPENDED test expected the removed per-page sentence "нови услуги не могат да бъдат създавани"; it now asserts the single shared
   notice appears once and the create control is absent.
2. The owner-shell test expected the old link list and no "Бизнеси" link; it now expects `Бизнеси, Профил, Услуги, Екип,
   Работно време, Клиенти` and that "Бизнеси" points to `#/businesses` (the owner selection, never the platform list).
3. The Pixel 7 test expected the first menu link to be "Услуги"; the first link is now "Бизнеси".
4. The onboarding test expected an owner to have no "Бизнеси" link; it now asserts the selection link and no "Нов бизнес".

### Browser evidence (real Chromium against the disposable stack; synthetic data created only through supported APIs and UI)

All scenarios use unique Business slugs and owner emails per provisioning attempt and run-time synthetic names. Waiting is by
Playwright polling and web-first assertions; there are no sleeps and no debounce-timing guarantee is claimed (the 300 ms
debounce is covered by Vitest with fake timers, not by the browser tests).

- **Navigation and context** (journey): distinct `Бизнеси` and `Профил`; no selector on the Profile; three cards, `Покажи` on the
  correct card; the Business name above the scoped group; `aria-current`, one checkmark, hidden selected text, no `Избран`
  and no technical value; selection survives reload; switching Businesses changes the data and clears the search, page,
  size, sort, and direction.
- **Creation and editing**: phone only, email only, and both through the interface with raw representative inputs (`0888 123 456`,
  ` Ime.Prezime@Primer.BG `, `+359 (895) 555-777`), canonical values shown and persisted after reload; required name and contact; invalid
  phone (letters, extension, prefix-less) and email inline; edit with persistence; removing both contacts rejected; duplicate
  phone and email (different formatting and case, and on edit) give the safe inline messages, keep the entered values, and leave
  the existing Customers' versions and the total unchanged; equal names are allowed.
- **Live search**: surname, a middle fragment, other letter case, email substring (any case), and phone prefix and full value; typing, a
  deleted character, clearing, and Escape update the list; no `Търси` or `Изчисти` button; a changed term resets to page 0; no
  results versus an empty catalog are distinct; the route holds only `page, size, sort, direction`; no search request carries the
  term in its URL.
- **Sorting and pagination** (54 Customers): default 10 with options 10/25/50; Next, Previous, range, and total; name, phone, and email
  in both directions with the visible order, phone and email cells, `aria-sort`, and the route all asserted against an
  independent expected order (`NULLS LAST`, name tie-break); sort and size changes reset the page; detail and Back preserve page,
  size, sort, direction, and the in-memory search; **stale-page recovery** reproduced through supported `PUT` updates (two matches renamed
  away between page 0 and the request for page 1) recovers to page 0.
- **Unsaved changes**: dirty create and edit prompt on global navigation, Back, `Отказ`, and logout; `Остани` keeps the values, the
  route, and the focus on the invoker; `Напусни` completes the action; a successful create or save shows no prompt. The mobile
  case returns focus to the menu button when the invoking link is in the closed menu. Business switching while a form is open cannot
  be produced in one tab (the selection page replaces the form), so that guard stays proven by Vitest
  (`App.customers.test.tsx`, `BusinessSelection.test.tsx`); the browser covers the global-navigation exit followed by `Покажи`.
- **Lifecycle**: DRAFT and ACTIVE create and edit; SUSPENDED lists, searches, sorts, paginates, shows detail, and edits an existing
  Customer (persisted after reload); no `Добави клиент`; the direct create route shows no form; the exact notice appears once on
  the list, detail, and create route; a forced same-session `POST` returns `409 BUSINESS_SUSPENDED` and the total is unchanged;
  reactivation restores creation. The lifecycle is restored in `finally`.
- **Version conflict**: a second page of the same owner session saves first through the owner API; the stale edit shows the established
  conflict message, keeps the entered values, `Зареди актуалните данни` goes through the shared guard (`Остани`, then `Напусни`),
  and the stored Customer is the first writer's (name and version asserted through the API).
- **Tenant isolation and authorization**: owner B receives `404 CUSTOMER_NOT_FOUND` for Customer A by ID for read and update, with a body
  byte-identical to an unknown ID; A's record is unchanged; A's data never appears in B's list or search (API and interface);
  a Platform Administrator without an owner Membership receives `403 ACTIVE_BUSINESS_REQUIRED` on all five operations; anonymous callers
  receive `401 AUTH_REQUIRED` on all five. The matrix pins the implemented codes (see the Phase 4 note on ADR-0021).
- **Privacy**: after searching by name, email, and phone, and after opening the detail, the sentinel terms and Customer values are absent from the URL,
  `localStorage`, `sessionStorage`, IndexedDB, script-visible cookies, and the document title; no Customer request URL or header contains
  them; the public profile API and the public page contain no Customer value. Failure messages compare booleans, never bodies; fixtures are synthetic
  (`@example.test`, bulk numbers in a fictional `+359 885 550 xxx` range, and numbers from the shared golden vectors).
- **Pixel 7** (a real Playwright device context with touch): Business selection, mobile navigation, cards, sort select, pagination, live search, create
  and edit forms with wrapped validation errors, unbroken 120-character names and 58-character email local parts, the dialog and focus return, SUSPENDED editing
  with creation absent, all with no horizontal overflow.

### Evidence boundaries

- Browser tests do not prove that no `app_user`, Membership, or Appointment row was created. That claim rests on backend evidence:
  `BusinessCustomerMutationApiIntegrationTests.createNeverUsesTheOwnersContactOrCreatesAnyAccountRecord` compares the row counts of `app_user`,
  `membership`, `user_session`, `platform_role`, and `business` before and after two creates (V1 to V10 contain no Appointment table, so none can exist).
- Conservative matching and the Booking seam are proven by backend tests only: `CustomerIdentificationServiceTests`,
  `CustomerIdentificationIntegrationTests`, and `CustomerIdentificationConcurrencyIntegrationTests` (with its test-only consumer and probe table that
  references `customer(business_id, id)`). The browser journey proves **manual** Customer administration; **future Booking consumption of the
  contract (#18) remains unimplemented**.
- No request-interception simulation was added in Phase 6. The only intercepted evidence for the lost-Business-context recovery is the Phase 5
  second-correction developer review (a simulation, because a Membership cannot be revoked through any supported API) and the Vitest suite
  `App.contextRecovery.test.tsx`; it is not real backend evidence.
- The 300 ms debounce, 200% zoom, tablet, and rendered-layout checks are developer review (Phase 5) and human visual approval, not Phase 6 browser assertions.

### Verification (executed 2026-10-06)

- Full backend `./mvnw --batch-mode verify`: 2,229 tests, 0 failures, 0 errors, 0 skipped (BUILD SUCCESS); no Java file changed, so no Java formatting check applies.
- Frontend: `npm ci`, `npm run test` 61 files and 1,440 tests, `npm run lint`, and `npm run build` pass.
- Browser E2E: two consecutive complete `./scripts/run-e2e.sh` runs, each 74 tests passed (54 before Phase 6, 20 new: 12 + 5 + 3), 0 failed, 0 skipped. After each
  run no `spotyourslot-e2e` container, volume, or network remained; the development database and unrelated stacks were not touched.
- `git diff --check` clean; nothing staged; V1 to V10 SHA-256 identical to `HEAD`:

| Migration | SHA-256 |
|---|---|
| V1 | `68cb25d6ccfd4e5aca12ec0b0199f13f3d0dd35418e45e1d7d2ef74a6832dc49` |
| V2 | `c1b62d1fed08138a937f281d4e3952942cc0a5d5cbaf7ed4803dc030a60527f8` |
| V3 | `655d22a52c06eb41996a75c100c1bfab853c907b9e0e076f128ba2ab8be674a2` |
| V4 | `aa48255701e3ce6999073801ca0ed37e292b9ada545e3fe4a50221ada596cb98` |
| V5 | `e2221627ed52ceb951881648d9738d213544ad4c8d79aa35e1e79c0896e958d3` |
| V6 | `73e89120c0163d6ea79ee28f0d59ae02c055df85066234a3688f525ada16ff1b` |
| V7 | `d9a8184b7c7c856064426d79dd375e7b4fb7f55a1fec389b7c391512286339b2` |
| V8 | `2f9fb21a06d4f42e5a3f9b4f479a2bbae2470e4fad0800247aed816d624668e8` |
| V9 | `9f0560e4daeb139eafe611b4e890a8505ca02ccffa30a293fef24a2a2fd182dd` |
| V10 | `a80c976b011bdfffc0948d252cf158ce9b75d8c5341b5dc31c36c94a94c3cfaa` |

### Final acceptance matrix

Legend: **BE** backend unit/integration, **FE** frontend unit (Vitest), **E2E** real browser journey (Phase 6), **SIM** intercepted browser simulation,
**DEV** developer visual review, **HUM** human visual approval (Phase 5, 2026-10-06).

| Requirement | Evidence |
|---|---|
| Business-scoped Customer, no account, Membership, or Appointment (D-record, record vs account) | BE `CustomerSchemaIntegrationTests`, `BusinessCustomerMutationApiIntegrationTests.createNeverUsesTheOwnersContactOrCreatesAnyAccountRecord`; E2E creation journey (visible behavior only) |
| Name required, at least one contact (D1, D2) | BE `CustomerProfileTests`, `BusinessCustomerMutationApiIntegrationTests`; FE `validation.test.ts`, `CustomerCreate.test.tsx`; E2E `requires a name and a contact…` |
| Canonical phone and email (D3, D4) | BE `ContactPolicyVectorTests` and the API vectors; FE `contactPolicy.test.ts`, `validation.test.ts`; E2E creation with raw inputs |
| Unique phone and email per Business, safe conflict (D5, D7) | BE `CustomerStoreIntegrationTests`, `CustomerSchemaIntegrationTests`, mutation API suite; FE `CustomerCreate.test.tsx`, `CustomerDetail.test.tsx`; E2E `duplicate phone and email…` |
| Names are not identity keys | BE `CustomerStoreIntegrationTests`; E2E same-name creation |
| Conservative matching, truth table, concurrency (D6, D9) | BE `CustomerIdentificationServiceTests`, `…IntegrationTests`, `…ConcurrencyIntegrationTests` (**backend only**) |
| Booking consumption of `findOrCreate` and `find` | Contract and test-only consumer proven in BE; **real consumption is #18 and unimplemented** |
| Owner-only private API, tenant isolation, identical 404 (D11) | BE `BusinessCustomerAuthorizationApiIntegrationTests`, `BusinessCustomerReadApiIntegrationTests`, mutation API suite; E2E `owner B cannot read…`, `a Platform Administrator…` |
| Search semantics (D12) | BE `BusinessCustomerReadApiIntegrationTests`, `CustomerSearchCriteriaTests`; FE `CustomerList.test.tsx`; E2E `search is live…` |
| Pagination, sorting, table standard (D13) | BE `CustomerStoreListIntegrationTests`; FE `CustomerList.test.tsx`, `navigation.test.ts`; E2E sorting and pagination tests; HUM |
| Bulgarian form, validation, guard (D14) | FE `CustomerCreate.test.tsx`, `CustomerDetail.test.tsx`, `UnsavedChangesGuard.test.tsx`; E2E validation and unsaved-changes tests; DEV; HUM |
| Privacy: no Customer data in URLs, storage, errors, logs (D15) | BE `BusinessCustomerPrivacyApiIntegrationTests`, `CustomerModuleBoundaryTests`; FE `CustomerList.test.tsx`; E2E privacy test |
| Optimistic versioning (D16) | BE mutation API suite, `CustomerAdministrationLockingIntegrationTests`; FE `CustomerDetail.test.tsx`; E2E `a stale edit is rejected…` |
| Lifecycle: DRAFT/ACTIVE mutable; SUSPENDED read plus edit, create blocked | BE `BusinessCustomerSuspendedApiIntegrationTests`, `CustomerAdministrationServiceTests`; FE `App.customers.test.tsx`; E2E lifecycle test and mobile SUSPENDED test; DEV; HUM |
| Business selection (`Бизнеси`, `Покажи`, selected card) and scoped navigation | FE `BusinessSelection.test.tsx`, `App.businessSelection.test.tsx`, shell tests; E2E navigation tests; DEV; HUM |
| Lost-context recovery | FE `App.contextRecovery.test.tsx`; **SIM** (Phase 5 developer review only, no supported revocation API) |
| Live search UI (no buttons, in-memory term) | FE `CustomerList.test.tsx` (fake-timer debounce); E2E live search; DEV; HUM |
| Mobile and responsive layout | FE `layoutRules.test.ts`; E2E Pixel 7; DEV (1280 to 375, zoom); HUM |
| Customer history and Appointment-based criteria | **Deferred to #18 and #21** (see the table "Acceptance criteria that cannot close in issue #20") |

Unsupported or deferred, deliberately not claimed: Appointments, history, notes, merge, deletion, archive, lifecycle, anonymization, export, MANAGER and STAFF access,
Customer accounts, trigram or full-text search, internationalized email, phone extensions, and the Appointment composite foreign key.

All acceptance criteria that issue #20 can satisfy are met. Implementation and verification are complete, pending final user review and commit; the GitHub
issue and Project board are untouched.
