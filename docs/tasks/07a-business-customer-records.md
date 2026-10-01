# SpotYourSlot — Business-scoped Customer Records

Status: Phase 1 (decisions, ADRs, and plan) is documented and awaits review. Issue #20
is in progress; no implementation exists. Phase 2 and later phases need separate
explicit approval.
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

Resolution (ADR-0019): a small `bg.spotyourslot.shared.contact` package holds the text
canonicalizer, phone policy, and email policy. In Phase 2, StaffMember delegates to it
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
| 2 | **First** extract the shared contact policy (backend and frontend module), add golden vectors, make StaffMember delegate, and verify existing StaffMember behavior. **Then** the `customer` domain, `V10__add_customers.sql`, repository/store, uniqueness and concurrency tests, and the boundary test. No controller or frontend Customer UI. Internal checkpoints are allowed within this one phase. | Strict | Own approval and commit; Phase 1 accepted |
| 3 | `CustomerIdentification` and `CustomerReferenceAccess`, normalization, typed outcomes, transaction and race tests, test-only consumer. No Appointment. | Strict (cross-module contract) | Own approval and commit |
| 4 | Private administration backend: authorization, list, search, detail, create, update, lifecycle behavior, tenant-isolation and privacy tests, `EXPLAIN` evidence. | Strict | Own approval and commit |
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
