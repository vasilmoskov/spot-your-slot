# SpotYourSlot — Business-scoped Customer Records

Status: Phase 1 (decisions, ADRs, and plan), Phase 2 (shared contact policy, Customer domain,
`V10` schema, and internal persistence), and Phase 3 (conservative matching, the published
`CustomerIdentification` and `CustomerReferenceAccess` contracts, caller-owned transaction semantics,
and PostgreSQL concurrency evidence) are committed. Phase 4 (the private owner-only administration
backend: list, body-based search, detail, explicit create, and version-guarded update) is implemented
and verified and awaits review and commit. Issue #20 is in progress; the Business-owner interface (Phase 5)
and the browser E2E (Phase 6) are not started, and each later phase needs separate explicit approval.
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
`updatedAt`; `businessId` never in a response. An accepted update increments `version`
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
`POST /search` and rejects create and update with `BUSINESS_SUSPENDED`. No delete,
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
  switch resets the page and keeps size, sort, and direction; out-of-range pages recover
  to the last valid page by replacing history.
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
SUSPENDED shows the shared read-only banner, read-only fields, and no create button.
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
| 5 | Business-owner interface: navigation, list and search **first**, then create, detail, and edit; validation, guards, SUSPENDED mode, rendered desktop, tablet, mobile, and zoom review. Two human visual checkpoints are allowed within this one phase. | Standard (human visual approval) | Human visual approval |
| 6 | Playwright administration journey, tenant isolation, lifecycle, privacy evidence, Booking-seam evidence, full gates, documentation completion, roadmap update, review archive. | Standard | Green verification; closure only with explicit approval |

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
- **Phase 5:** Vitest for the shared contact validation and golden vectors, route parsing
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
| `BUSINESS_OWNER` of a SUSPENDED Business | allowed | 409 `BUSINESS_SUSPENDED` (before validation and lookup) |

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
| 409 | `BUSINESS_SUSPENDED` | create or update in a SUSPENDED Business |
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
