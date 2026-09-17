# SpotYourSlot — Business Services Backend

Status: Completed — 2026-09-17
GitHub issue: #11 — Build Business services backend
Parent issue: #10 — Add business services, staff, and working schedules

## Task purpose

Issue #11 adds the completed Business-owned Service backend while preserving
session-derived tenancy, the existing Business lifecycle, and modular-monolith
boundaries. Completion applies only to this backend sub-issue. Parent issue #10,
the complete Services business capability, the Business-owner UI, and its
end-to-end configuration journey remain open.

## Required reading

Before each implementation phase, read `AGENTS.md`, issues #11 and #10, this
task, the permanent product, architecture, data-model, security, API, testing,
and implementation-plan documentation, applicable ADRs, current code and tests,
Git status, and relevant history. Permanent documentation remains authoritative
unless an approved issue decision explicitly resolves a conflict.

## Scope

Issue #11 provides Business-scoped Service listing, detail, creation, update,
deactivation, and reactivation. It does not provide StaffMembers, Service
assignments, schedules, availability, Appointments, public booking, frontend
behavior, hard deletion, Business transfer, or changes to authentication,
onboarding, Business lifecycle, or invitations.

`MANAGER` remains part of the long-term product model but Service management by
managers is deferred until its authorization workflow is designed. Service
buffer duration remains a long-term concept but is deferred until availability
and slot-calculation semantics are approved. Neither deferral creates a V5
column or issue #11 API field.

## Approved Service model

A Service has:

- application-generated UUID `id`;
- immutable UUID `businessId` ownership;
- required canonical display `name`, at most 200 Unicode code points;
- database-generated `normalizedName` used only for Business-scoped identity;
- nullable canonical `description`, at most 2000 Unicode code points;
- integer `durationMinutes` in `1..480` with no step requirement;
- nonnegative EUR `BigDecimal price`, persisted as `numeric(12,2)`;
- `active`, defaulting to `true`;
- nonnegative optimistic-concurrency `version`, starting at `0`;
- UTC `createdAt` and `updatedAt` supplied from the injected application Clock.

Creation produces an active Service at version `0`. Every successful update,
deactivation, or reactivation increments the version exactly once and updates
only `updatedAt`. Business ownership, ID, and `createdAt` never change.

## Canonical names and uniqueness

The application canonicalizes a display name by applying Unicode NFKC,
replacing every sequence from this explicit Unicode White_Space set with one
ordinary U+0020 space, and removing resulting leading and trailing spaces:

```text
U+0009..U+000D, U+0020, U+0085, U+00A0, U+1680,
U+2000..U+200A, U+2028, U+2029, U+202F, U+205F, U+3000
```

Meaningful letter case is preserved in `name`. The uniqueness key applies
Unicode full case folding under PostgreSQL `pg_unicode_fast` and NFKC again.
It is unique within one Business and may repeat in separate Businesses.

These pairs conflict within one Business:

```text
Подстригване / подстригване / ПОДСТРИГВАНЕ
Мъжко подстригване / Мъжко   подстригване
Straße / STRASSE
```

Inactive Services retain and reserve their normalized names. Creating or
renaming another Service to that key produces `SERVICE_NAME_CONFLICT`. An
inactive Service may be renamed by the normal versioned update, releasing its
previous key. Reactivation therefore normally cannot encounter a name conflict.

Descriptions use NFKC, remove the same approved boundary whitespace, preserve
internal whitespace, and convert blank canonical values to null.

Application canonicalization and validation happen before persistence.
The database rejects noncanonical stored names and descriptions, while its
generated key and unique constraint remain the final race arbiter. Tests
compare Java canonicalization with the database expression over the full
whitespace set, NFKC expansion and contraction cases, case-folding cases, and
supplementary characters.

## V5 schema contract

V5 creates singular table `service`, consistent with existing singular table
names. PostgreSQL 18.4 accepts the name without quoting. The table contains only
the approved fields and constraints:

```sql
CREATE TABLE service (
    id uuid PRIMARY KEY,
    business_id uuid NOT NULL,
    name varchar(200) NOT NULL,
    normalized_name text COLLATE pg_catalog.pg_unicode_fast
        GENERATED ALWAYS AS (
            pg_catalog.normalize(
                pg_catalog.casefold(
                    pg_catalog.btrim(
                        pg_catalog.regexp_replace(
                            pg_catalog.normalize(name, 'NFKC'),
                            U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+',
                            ' ',
                            'g'
                        )
                    ) COLLATE pg_catalog.pg_unicode_fast
                ),
                'NFKC'
            )
        ) STORED,
    description varchar(2000),
    duration_minutes integer NOT NULL,
    price numeric(12,2) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT service_business_fk
        FOREIGN KEY (business_id) REFERENCES business(id) ON DELETE RESTRICT,
    CONSTRAINT service_name_canonical CHECK (...),
    CONSTRAINT service_name_not_blank CHECK (char_length(name) > 0),
    CONSTRAINT service_description_canonical CHECK (...),
    CONSTRAINT service_duration_minutes_range
        CHECK (duration_minutes BETWEEN 1 AND 480),
    CONSTRAINT service_price_nonnegative CHECK (price >= 0),
    CONSTRAINT service_version_nonnegative CHECK (version >= 0),
    CONSTRAINT service_business_id_id_unique UNIQUE (business_id, id),
    CONSTRAINT service_business_normalized_name_unique
        UNIQUE (business_id, normalized_name)
);
```

The source migration contains the full canonical checks. The two unique
constraints create the only indexes beyond the primary key; no speculative
index is added. The `(business_id, id)` constraint supports tenant-safe identity
and future composite references. The normalized-name unique constraint covers
both active and inactive rows.

PostgreSQL `numeric(12,2)` rounds direct SQL values with excess fractional
digits before storage; for example, `12.345` becomes `12.35`. The application
validator explicitly rejects more than two fractional digits before persistence.
The database still rejects negative stored values and numeric overflow.

## Lifecycle and Business state

Allowed operations are:

- list and retrieve active or inactive Services;
- create an active Service;
- update an active or inactive Service;
- deactivate an active Service;
- reactivate an inactive Service.

There is no hard delete or Business transfer. Repeating deactivate on inactive
or reactivate on active returns `SERVICE_INVALID_LIFECYCLE` rather than silently
succeeding.

Businesses in `DRAFT` or `ACTIVE` permit all approved operations. A
`SUSPENDED` Business permits reads and rejects every Service mutation with
`BUSINESS_SUSPENDED`. Suspending or reactivating a Business does not change any
Service state, version, or timestamp.

## Authorization and locking

Every operation derives its Business solely from the authenticated session's
currently selected Business context. No request business ID may select
or redirect tenancy. Every query uses `(business_id, service_id)` where a
Service ID is present, and a missing or cross-tenant Service returns the same
tenant-safe 404.

Only an active `BUSINESS_OWNER` Membership in that selected Business is
authorized. `PLATFORM_ADMIN` has no Service authority solely from the platform
role; a platform administrator who also has the qualifying Membership may act
through it. `MANAGER`, `STAFF`, inactive Memberships, missing context,
unauthenticated users, and Members of another Business are unauthorized.

A genuinely absent selection produces `ACTIVE_BUSINESS_REQUIRED`. The session
filter also clears a selection unsupported by a current active Membership before
the controller runs. A retained selection with a non-owner Membership reaches
the application boundary and produces `ACCESS_DENIED`; a selected Business that
no longer exists is denied through the same safe access outcome.

Mutations run in one transaction. Authorization first locks the selected
Business lifecycle row and then the exact user's qualifying Membership row with
the established shared-lock pattern before the optimistic Service mutation.
This prevents Membership revocation or Business suspension from racing past the
authorization decision. Reads validate the same Business and Membership without
mutation locks.

## Optimistic concurrency

Create has no expected version. Update, deactivate, and reactivate require a
nonnegative `expectedVersion`. The final PostgreSQL statement contains
`business_id`, `id`, and `version = expectedVersion`; lifecycle statements also
contain the expected `active` state. A successful `UPDATE ... RETURNING`
increments version once and returns the authoritative representation. No
returned row after a previously authorized tenant-safe read becomes
`SERVICE_CONCURRENT_UPDATE`.

The normalized-name unique constraint decides concurrent create and rename
races. SQL state and constraint inspection may classify internal failures, but
HTTP responses never expose SQL, driver text, or constraint names. ADR-0010
records this Service-specific concurrency decision because ADR-0007 is limited
to Business mutations.

## Implemented HTTP contract

Base path: `/api/business/services`.

| Method and path | Request | Success |
|---|---|---|
| `GET /api/business/services?page=0&size=50` | page defaults 0; size defaults 50, maximum 100 | `200` paginated active and inactive Services, ordered by normalized name then ID |
| `GET /api/business/services/{serviceId}` | path UUID | `200` Service or tenant-safe `404` |
| `POST /api/business/services` | create record | `201` authoritative Service and `Location` |
| `PUT /api/business/services/{serviceId}` | update record with expected version | `200` authoritative Service |
| `POST /api/business/services/{serviceId}/deactivate` | expected-version record | `200` authoritative inactive Service |
| `POST /api/business/services/{serviceId}/reactivate` | expected-version record | `200` authoritative active Service |

The implemented request records are:

```java
record CreateServiceRequest(
        String name,
        String description,
        Integer durationMinutes,
        BigDecimal price) {
}

record UpdateServiceRequest(
        String name,
        String description,
        Integer durationMinutes,
        BigDecimal price,
        Long expectedVersion) {
}

record ServiceLifecycleRequest(Long expectedVersion) {
}
```

Raw `@NotBlank` and `@Size` annotations do not compete with name or description
canonicalization. The HTTP layer passes raw values to the application validator,
which canonicalizes first and then validates blankness, Unicode code-point
length, duration, exact price representation, expected version, identifiers,
and pagination. Malformed JSON, UUIDs, and query types use the same safe public
validation response.

The response omits `businessId` because tenancy is already selected by the
session and the ID could encourage client-supplied tenant routing:

```java
record ServiceResponse(
        UUID id,
        String name,
        String description,
        int durationMinutes,
        BigDecimal price,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}

record ServicePageResponse(
        List<ServiceResponse> services,
        int page,
        int size,
        long totalElements) {
}
```

Validation tests cover leading and trailing approved Unicode
whitespace, repeated internal whitespace, blank-after-canonicalization values,
NFKC expansion and contraction, supplementary characters represented by Java
surrogate pairs, and canonical name limits 200/201 and description limits
2000/2001 Unicode code points. HTTP, application, and database results must be
compared directly.

## Error contract

The HTTP API uses the current RFC 7807 `ProblemDetail` shape and stable `code`
property without expanding the global format. It returns these exact safe
Bulgarian public values:

| Condition | Status | Code | Safe Bulgarian public wording |
|---|---:|---|---|
| malformed JSON, invalid UUID/query type, canonical/domain validation, or bad page bounds | 400 | `VALIDATION_ERROR` | `Проверете въведените данни.` |
| no authenticated session | 401 | `AUTH_REQUIRED` | `Необходим е вход.` |
| absent selected Business context | 403 | `ACTIVE_BUSINESS_REQUIRED` | `Изберете бизнес, за да продължите.` |
| authenticated but no qualifying active owner Membership | 403 | `ACCESS_DENIED` | `Нямате достъп до тази операция.` |
| Service missing or belongs to another Business | 404 | `SERVICE_NOT_FOUND` | `Услугата не е намерена.` |
| normalized name already reserved | 409 | `SERVICE_NAME_CONFLICT` | `Вече съществува услуга с това име.` |
| repeated or otherwise invalid Service transition | 409 | `SERVICE_INVALID_LIFECYCLE` | `Промяната на състоянието на услугата не е разрешена.` |
| mutation while Business is suspended | 409 | `BUSINESS_SUSPENDED` | `Спрян бизнес може само да преглежда данните си.` |
| stale same-Service mutation | 409 | `SERVICE_CONCURRENT_UPDATE` | `Услугата е променена. Обновете данните и опитайте отново.` |
| unexpected failure | 500 | `INTERNAL_ERROR` | `Възникна неочаквана грешка.` |

## Completed implementation phases

### Phase 1 — schema and decision record

- added this task record and ADR-0010;
- added immutable V5;
- added focused PostgreSQL 18.4 schema, expression, constraint, uniqueness, and
  catalog tests;
- updated the existing schema test only to expect V5, permit `service`, and
  preserve the V4 hash.

### Phase 2 — domain and persistence

- added Service domain types, canonicalization, validation, commands, records,
  exceptions, and store;
- implemented tenant-safe reads, deterministic pagination, atomic versioned
  mutations, and unique/SQL exception classification;
- added domain and real-PostgreSQL persistence, isolation, race, and parity tests.

### Phase 3 — authorization and application

- added active-owner selected-Business authorization and shared locking;
- implemented DRAFT/ACTIVE/SUSPENDED behavior and Service lifecycle orchestration;
- added authorization, tenant-isolation, lifecycle, clock, and concurrent
  application integration tests.

### Phase 4 — HTTP contract

- added the approved controller, request/response records, and safe exception
  mapping under `/api/business/services`;
- added API integration tests, including canonical validation parity;
- ran focused then full backend verification and performed a scope review.

### Final phase — documentation and acceptance

- synchronize the permanent Service/backend contracts with the committed code;
- run the complete backend verification and final scope and migration-integrity
  review;
- leave issue #10, the complete Services capability, frontend work, and the
  end-to-end configuration journey open.

## Phase 1 verification evidence

`ServiceSchemaIntegrationTests` executes V1 through V5 through Flyway against
the pinned `postgres:18.4-bookworm` Testcontainer. It verifies generated-column
acceptance, regular schema-qualified `normalize(text, text)` calls,
`pg_unicode_fast`, catalog immutability of every generated-expression function,
the explicit whitespace set, generated-column write protection, uniqueness,
inactive reservation, constraints, defaults, types, timestamps, and the exact
constraint/index inventory. `BusinessSchemaIntegrationTests` proves all five
migration versions and byte-for-byte V1–V4 preservation.

The approved Phase 1 scope extension updates the older Phase 2 table-inventory
assertion in `IdentitySchemaIntegrationTests` to preserve the Phase 2 tables
while excluding only domain tables that remain unimplemented. The complete
backend verification passes with V5 present.

## Phase 2–4 verification evidence

`ServiceTextCanonicalizerTests`, `ServiceInputValidatorTests`,
`ServiceStoreIntegrationTests`, and `ServiceSchemaIntegrationTests` cover the
canonical, validation, V5, persistence, isolation, uniqueness, and concurrency
contracts. `ServiceAdministrationServiceTests`,
`ServiceAdministrationServiceIntegrationTests`, and
`ServiceAuthorizationLockingIntegrationTests` cover selected-Business owner
authorization, lifecycle behavior, mapping, safe outcome classification, and
the Business-then-Membership lock order with coordinated PostgreSQL races.

`BusinessServiceControllerTests`, `BusinessServiceExceptionHandlerTests`, and
`BusinessServiceApiIntegrationTests` cover all six HTTP operations, the exact
records and safe error contract, real authenticated-session selection, role and
tenant isolation, CSRF, canonical-first validation, lifecycle and version
conflicts, and diagnostic redaction. No Services frontend or browser E2E flow is
part of issue #11.

Final issue #11 backend verification evidence on 2026-09-17: `./mvnw verify`
ran 602 tests with zero failures, errors, or skips. The included Spring Modulith
module-boundary test passed. This is a dated completion result, not a permanent
expected test count for future builds.
