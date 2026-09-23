# SpotYourSlot — Staff Management and Service Assignments Backend

Status: Completed — 2026-09-23
GitHub issue: #12 — Build staff management and service assignments backend
Parent issue: #10 — Add business services, staff, and working schedules
Depends on: #11 — Build Business services backend

## Task purpose

Issue #12 adds the completed backend for Business-owned StaffMembers and their
supported-Service assignments. This completion covers the backend only. Parent
issue #10 remains open; issue #14's Business-owner interface and issue #15's
browser verification remain deferred, as do working schedules, availability,
and booking.

A StaffMember is an operational Business record. It does not require an
application user, credentials, Membership, or invitation. The approved future
optional one-to-one link between a StaffMember and a same-Business Membership is
outside this issue and is not represented by the V6 schema.

## Required reading

Before each implementation phase, read `AGENTS.md`, issues #10–#15, this task,
the permanent product, architecture, data-model, security, testing, roadmap,
and implementation-plan documentation, applicable ADRs, and the completed
Business Service implementation. Permanent documentation remains authoritative
unless an approved issue decision resolves a conflict.

## Scope

An authorized owner may:

- list active and inactive StaffMembers for the selected Business;
- retrieve one StaffMember;
- create and edit a StaffMember;
- deactivate and reactivate a StaffMember;
- list the StaffMember's Service assignments;
- atomically replace the complete assigned-Service set;
- add active Services and remove existing assignments; and
- retain an already assigned inactive Service or remove that relationship.

Issue #12 does not include frontend behavior, StaffMember accounts,
Membership management, schedules, availability, Appointments, booking,
Customers, public pages, notifications, or production deployment.

## Approved StaffMember model

A StaffMember has:

- application-generated UUID `id`;
- immutable UUID `businessId` ownership;
- required canonical `displayName`, from 1 through 200 Unicode code points;
- nullable canonical `contactEmail`, at most 320 Unicode code points;
- nullable canonical `contactPhone`, at most 50 Unicode code points;
- `active`, defaulting to `true`;
- nonnegative aggregate `version`, starting at `0`;
- UTC `createdAt` and `updatedAt` supplied by the application Clock.

Business ownership, ID, and creation time never change. Creation produces an
active StaffMember at version `0`. Profile update, deactivation, reactivation,
and complete assignment replacement share one version and increment it exactly
once per successful mutation.

Different StaffMembers may have the same display name, including the same
normalized display name. Contact emails and phones are not identity keys and
may also repeat.

### Text canonicalization and validation

The workforce module owns its text canonicalization. It must not import
Catalog's internal Service canonicalizer.

Display names use Unicode NFKC, replace every sequence from this explicit
Unicode White_Space set with one U+0020 space, and remove resulting boundary
spaces:

```text
U+0009..U+000D, U+0020, U+0085, U+00A0, U+1680,
U+2000..U+200A, U+2028, U+2029, U+202F, U+205F, U+3000
```

The meaningful case of the display name is preserved. An internal generated
normalized display name applies Unicode full case folding and NFKC again only
for deterministic ordering; it is not unique.

Contact email uses NFKC, removes approved boundary whitespace, lowercases with
`Locale.ROOT`, converts blank input to null, and uses the established Jakarta
email validation. The database enforces canonical lowercase storage and bounds;
application validation owns complete email syntax.

Contact phone uses NFKC, removes approved boundary whitespace, converts blank
input to null, and preserves accepted formatting. It permits ASCII digits, an
optional leading `+`, spaces, parentheses, `.`, `/`, and `-`, requires 3 through
20 digits, and is limited to 50 Unicode code points. Wider phone normalization
and identity matching remain future product decisions.

## StaffMember lifecycle

- StaffMembers may be created, read, updated, deactivated, and reactivated.
- Repeated deactivation or reactivation is an invalid lifecycle transition.
- There is no hard-delete application operation or Business transfer.
- Deactivation preserves profile data and every Service assignment.
- Active and inactive StaffMembers remain fully administratively configurable.
- Profile fields may be edited while inactive.
- Assignments may be added, retained, or removed while inactive.
- Activity does not participate in assignment authorization or concurrency.

StaffMember activity will later determine availability participation and
eligibility for new booking. That is deferred intent only. This issue does not
implement or assume any behavior for existing or future Appointments.

## Service assignments

A StaffMember may perform zero or more Services, and a Service may be assigned
to zero or more StaffMembers. `staff_member_service` is a Business-owned
relationship rather than an independently versioned aggregate.

Rules:

- both records must belong to the selected Business;
- one StaffMember/Service pair may appear only once;
- assignment replacement is a complete desired-set replacement;
- assignment replacement is atomic and uses the StaffMember `expectedVersion`;
- a successful replacement increments the StaffMember version exactly once,
  including a same-set replacement;
- only a Service addition is subject to the active-Service rule;
- an already assigned inactive Service may remain assigned or be removed;
- a removed inactive Service cannot be restored until the Service is active;
- StaffMember activity does not restrict assignment replacement;
- StaffMember or Service deactivation never removes assignments; and
- removing an assignment deletes only the relationship, not either endpoint.

No removed-assignment history is introduced. Preserving relationship rows when
an endpoint becomes inactive provides the lifecycle continuity required by this
issue; it does not constitute an audit trail.
A future immutable business audit trail requires separate scope.

## V6 schema contract

V6 creates singular tables `staff_member` and `staff_member_service`.

`staff_member` contains the approved fields plus generated
`normalized_display_name`. It has a restrictive Business foreign key, canonical
text checks, valid phone checks, nonnegative version, unique `(business_id, id)`
ownership identity, and an index on
`(business_id, normalized_display_name, id)`.

`staff_member_service` contains only `business_id`, `staff_member_id`, and
`service_id`. Its primary key is the complete triple. Composite restrictive
foreign keys target `(business_id, id)` on StaffMember and Service, and a reverse
index supports `(business_id, service_id, staff_member_id)` lookup.

The database deliberately has no constraint coupling either endpoint's active
state to an assignment row. Inactive StaffMembers and inactive Services may
retain assignments. The application layer validates only Service additions and
does so transactionally.

No `membership_id`, schedule, availability, Appointment, or booking column or
table is part of V6.

## Authorization, tenancy, and locking

Every operation derives the user and selected Business from the authenticated
server-side context. Requests and responses omit `businessId`. Every persistence
operation includes `business_id`; missing and cross-Business resource IDs use
the same safe not-found behavior.

Only an active `BUSINESS_OWNER` Membership in the selected Business authorizes
issue #12. `PLATFORM_ADMIN` authority alone, `MANAGER`, `STAFF`, inactive or
missing Memberships, and Memberships from another Business do not grant access.
Future `MANAGER` access remains deferred.

DRAFT and ACTIVE Businesses allow reads and mutations. SUSPENDED Businesses
allow reads and reject every mutation. Mutation transactions lock the Business
lifecycle row and exact qualifying Membership row with the established shared
lock order before changing workforce data.

Assignment replacement conditionally updates the StaffMember using
`business_id`, ID, and `expectedVersion`. It does not include `active = true`.
After that guard, Service additions are resolved and locked through a narrow
published Catalog contract in deterministic UUID order. The Catalog contract
exposes immutable Service references and never exposes repositories or
persistence records.

## Optimistic concurrency

Create has no expected version. Profile update, deactivate, reactivate, and
assignment replacement require a nonnegative `expectedVersion`.

The final StaffMember mutation uses one conditional PostgreSQL
`UPDATE ... RETURNING`. Lifecycle mutations also include the expected active
state. Assignment replacement uses this predicate:

```sql
WHERE business_id = :businessId
  AND id = :staffMemberId
  AND version = :expectedVersion
```

No activity predicate is allowed. A successful assignment replacement increments
the version and update time once before relationship reconciliation in the same
transaction. Any validation or persistence failure rolls back the version and
relationship changes together.

Two profile, lifecycle, or assignment operations using the same StaffMember
version produce exactly one success. The other returns a safe concurrent-update
conflict. Database relationship uniqueness remains a separate final safeguard.
ADR-0011 records this aggregate concurrency decision.

## Implemented HTTP contract

Base path: `/api/business/staff-members`.

| Method and path | Success |
|---|---|
| `GET /api/business/staff-members?page=0&size=50` | `200`, deterministic paginated active and inactive StaffMembers |
| `GET /api/business/staff-members/{staffMemberId}` | `200` or tenant-safe `404` |
| `POST /api/business/staff-members` | `201`, authoritative StaffMember and `Location` |
| `PUT /api/business/staff-members/{staffMemberId}` | `200`, versioned profile update |
| `POST /api/business/staff-members/{staffMemberId}/deactivate` | `200`, authoritative inactive StaffMember |
| `POST /api/business/staff-members/{staffMemberId}/reactivate` | `200`, authoritative active StaffMember |
| `GET /api/business/staff-members/{staffMemberId}/service-assignments` | `200`, safe assigned-Service summaries |
| `PUT /api/business/staff-members/{staffMemberId}/service-assignments` | `200`, atomic versioned full-set replacement |

The default page size is 50 and maximum is 100. Staff ordering uses normalized
display name then ID. Assignment responses use safe `{id, name, active}` Service
summaries in Catalog normalized-name and ID order. HTTP records expose no
Business, account, credential, Membership, or internal persistence information.
All POST and PUT requests remain CSRF-protected.

## Implemented error contract

| Status | Code | Safe Bulgarian public wording |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | `Проверете въведените данни.` |
| 401 | `AUTH_REQUIRED` | `Необходим е вход.` |
| 403 | `ACTIVE_BUSINESS_REQUIRED` | `Изберете бизнес, за да продължите.` |
| 403 | `ACCESS_DENIED` | `Нямате достъп до тази операция.` |
| 404 | `STAFF_MEMBER_NOT_FOUND` | `Членът на екипа не е намерен.` |
| 404 | `SERVICE_NOT_FOUND` | `Услугата не е намерена.` |
| 409 | `STAFF_MEMBER_INVALID_LIFECYCLE` | `Промяната на състоянието на члена на екипа не е разрешена.` |
| 409 | `STAFF_MEMBER_CONCURRENT_UPDATE` | `Данните за члена на екипа са променени. Обновете данните и опитайте отново.` |
| 409 | `SERVICE_INACTIVE` | `Неактивна услуга не може да бъде добавена към член на екипа.` |
| 409 | `BUSINESS_SUSPENDED` | `Спрян бизнес може само да преглежда данните си.` |
| 500 | `INTERNAL_ERROR` | `Възникна неочаквана грешка.` |

Duplicate Service IDs in an assignment request are validation errors. There is
no inactive-StaffMember error because inactive StaffMembers remain
administratively configurable. Responses never expose SQL, constraints, stack
traces, internal identifiers, Membership details, or cross-Business existence.

## Testing responsibilities

Tests cover:

- workforce-owned Unicode canonicalization and code-point bounds;
- email and permissive formatted-phone validation;
- duplicate display names and contacts;
- default active state and deterministic listing;
- profile, lifecycle, and assignment versioning;
- zero, one, and multiple assignments;
- same-set replacement and exactly one version increment;
- duplicate and cross-Business assignment rejection;
- retained and removed inactive-Service assignments;
- rejection of newly added or restored inactive Services;
- assignment mutation for active and inactive StaffMembers;
- preservation across StaffMember, Service, and Business lifecycle changes;
- PostgreSQL schema, constraints, index inventory, and restrictive deletion;
- coordinated StaffMember and assignment races without sleeps;
- Service deactivation versus addition locking;
- DRAFT, ACTIVE, and SUSPENDED behavior;
- owner-only role, authentication, CSRF, and tenant isolation;
- safe HTTP errors and diagnostic redaction;
- Catalog and Workforce module boundaries; and
- unchanged authentication, onboarding, Business, and Service behavior.

PostgreSQL-specific behavior and every concurrency claim use the pinned real
PostgreSQL Testcontainer.

## Completed implementation phases

### Phase 1 — schema, task contract, and ADR

- add this task record and ADR-0011;
- add immutable V6;
- add focused PostgreSQL schema, ownership, relationship, and index tests;
- update older schema inventory assertions only for the newly implemented tables;
- preserve V1–V5 byte-for-byte.

### Phase 2 — StaffMember domain and persistence

- implement workforce-owned canonicalization and validation;
- add StaffMember records and tenant-scoped persistence;
- implement deterministic listing, profile changes, lifecycle, and versioning;
- add focused unit and PostgreSQL persistence tests.

### Phase 3 — authorization and application orchestration

- reuse the published Business lifecycle and selected-owner contracts;
- implement DRAFT, ACTIVE, and SUSPENDED behavior;
- preserve Business-then-Membership lock order;
- add authorization, lifecycle, and coordinated concurrency tests.

### Phase 4 — Catalog boundary and Service assignments

- add a narrow Catalog contract for tenant-scoped Service references and locks;
- implement atomic full-set assignment replacement;
- validate active state only for additions;
- add cross-module, inactive-Service, rollback, and concurrency coverage.

### Phase 5 — HTTP API

- implement the approved controller, records, and safe exception mapping;
- add focused controller and PostgreSQL-backed MockMvc tests;
- verify session context, CSRF, privacy, role, lifecycle, and tenant behavior.

### Phase 6 — documentation and acceptance

- synchronize only the necessary permanent documents and roadmap;
- run complete backend and unaffected frontend checks;
- review scope, migrations, module boundaries, errors, and final diff.

Each phase requires separate review and approval before persistent changes.

## Phase 1 verification evidence

Phase 1 must prove against PostgreSQL 18.4 that:

- V1 through V6 apply from empty;
- V1 through V5 remain byte-for-byte unchanged;
- the exact V6 columns, generated expression, constraints, and indexes exist;
- duplicate normalized display names are allowed;
- composite foreign keys reject cross-Business assignments;
- duplicate relationships are rejected;
- StaffMember and Service deletion are restrictive while assigned;
- inactive StaffMembers and inactive Services retain assignment rows;
- no database constraint applies application-owned activity rules; and
- schedules, availability, Appointments, booking, and Membership linkage remain absent.

## Final verification evidence

Final issue #12 backend verification on 2026-09-23 used PostgreSQL 18.4:
`./mvnw --batch-mode verify` ran 802 tests with zero failures, errors, or
skips. The complete build applied and validated all six Flyway migrations and
included the passing Spring Modulith `ModuleBoundaryTests`, so no separate
boundary-test invocation was necessary.

The unaffected frontend verification also passed on 2026-09-23. `npm ci`
installed the committed lockfile, `npm run lint` completed successfully,
`npm run test` ran 126 tests across 12 files with no failures, and
`npm run build` produced the production bundle. Browser E2E was intentionally
not run for this documentation-only phase; issue #15 remains responsible for
the Staff configuration browser journey.
