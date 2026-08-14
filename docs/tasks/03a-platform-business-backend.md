# SpotYourSlot — Platform Business Backend

Status: Complete
GitHub issue: #5 — Build platform Business management backend
Parent issue: #4 — Create platform admin tools and onboard businesses

## Task purpose

Implement the backend foundation that allows a PLATFORM_ADMIN to create, inspect,
edit, activate, suspend, and reactivate Businesses.

This is the first bounded part of Phase 3.

Do not implement the platform-admin React interface during this task.

## Required reading

Before proposing or making changes, read completely:

- `AGENTS.md`;
- `README.md`;
- `docs/product-spec.md`;
- `docs/architecture.md`;
- `docs/data-model.md`;
- `docs/security.md`;
- `docs/testing-strategy.md`;
- `docs/implementation-plan.md`;
- `docs/tasks/02-identity-and-tenancy.md`;
- the existing Phase 2 implementation and tests.

Permanent project documentation remains authoritative.

If this task conflicts with an authoritative document or the existing
implementation, stop and report the conflict before editing.

## Existing foundation

Phase 2 already provides:

- database-backed authentication and sessions;
- `PLATFORM_ADMIN`;
- `BUSINESS_OWNER`, `MANAGER`, and `STAFF` Membership roles;
- strict Business Membership checks;
- the `business` table;
- owner invitation creation and acceptance;
- safe API error handling;
- PostgreSQL integration tests;
- Spring Modulith module-boundary verification.

Reuse these foundations. Do not create a second authentication, authorization,
invitation, or Business model.

## Approved lifecycle decisions

A new Business always starts in `DRAFT`.

The only allowed lifecycle transitions are:

- `DRAFT → ACTIVE`;
- `ACTIVE → SUSPENDED`;
- `SUSPENDED → ACTIVE`.

Do not allow:

- `ACTIVE → DRAFT`;
- `SUSPENDED → DRAFT`;
- `DRAFT → SUSPENDED`;
- transitions to the current status;
- arbitrary client-selected status changes.

Initial activation requires at least one active `BUSINESS_OWNER` Membership for
the Business.

Do not require Services, StaffMembers, schedules, or availability for activation
during this task because those domains do not exist yet. Their future readiness
requirements belong to later phases.

## Business data

Preserve the existing Business identity and lifecycle fields:

- `id`;
- `slug`;
- `displayName`;
- `businessType`;
- `status`;
- `timezone`;
- `version`;
- `createdAt`;
- `updatedAt`.

Add the approved basic profile/contact fields if they are not already persisted:

- optional description;
- optional single address;
- optional phone;
- optional email.

Use a new Flyway migration. Never edit V1 or V2.

Do not add Services, StaffMembers, schedules, Customers, Appointments, booking
settings, payments, subscriptions, locations, custom domains, or
industry-specific fields.

## Validation rules

### Slug

The slug must:

- be globally unique;
- be normalized to lowercase;
- contain only lowercase Latin letters, digits, and single hyphens;
- not start or end with a hyphen;
- not contain consecutive hyphens;
- satisfy the existing database constraint;
- produce a stable safe conflict response when already used.

Do not silently change an invalid slug into a materially different value.

### Display name

The display name is required after trimming and must use a documented bounded
length consistent with the database.

### BusinessType

Accept only the existing values:

- `HAIR_SALON`;
- `BARBERSHOP`;
- `NAIL_STUDIO`;
- `MASSAGE_STUDIO`;
- `MAKEUP_STUDIO`;
- `BEAUTY_STUDIO`;
- `OTHER`.

BusinessType is descriptive only. It must not select different behavior,
schemas, authorization, or application flows.

### Timezone

Require a valid IANA timezone.

Default to `Europe/Sofia` when omitted during creation.

Reject invalid timezone identifiers with a safe Bulgarian validation response.

### Contact information

Validate bounded lengths.

If an email is supplied, validate and normalize it consistently.

Optional blank contact values should be stored consistently as null or according
to one clearly documented rule.

Do not treat a Business contact email as a login identity.

## Required backend operations

Provide PLATFORM_ADMIN-only backend operations for:

1. listing Businesses;
2. retrieving one Business;
3. creating a DRAFT Business;
4. editing approved basic Business fields;
5. activating a DRAFT Business;
6. suspending an ACTIVE Business;
7. reactivating a SUSPENDED Business.

Use focused application services, repositories, DTOs, controllers, and domain
logic under the documented module boundaries.

Do not expose unrestricted generic status updates.

Do not expose persistence entities or internal records directly as API
contracts.

The Business list must return only minimum platform-support metadata and must
not expose:

- password hashes;
- session or security tokens;
- invitation/reset token hashes;
- Customer information;
- Appointment information;
- private operational notes.

A simple bounded list is sufficient for the pilot. Do not add speculative
search infrastructure or a complex pagination framework. If pagination is
introduced, keep it minimal and deterministic.

## Authorization and isolation

Every operation in this task requires authenticated `PLATFORM_ADMIN` authority.

A Business Membership role alone must not grant access to these endpoints.

PLATFORM_ADMIN authority must not grant access to Customer or Appointment
content.

Do not implement impersonation.

Do not trust a role, status, or platform flag supplied by the frontend.

Preserve the Phase 2 separation between platform roles and Business
Memberships.

## Concurrency and consistency

Use PostgreSQL as the final source of truth.

Prevent duplicate slugs with a database constraint and transactional handling.

Use the existing Business `version` field or another clearly justified
optimistic-concurrency strategy to prevent silent lost updates.

Concurrent lifecycle transitions must not produce an invalid state.

Map uniqueness and optimistic-concurrency conflicts to stable safe API errors.

Do not leak SQL messages, constraint names, stack traces, or internal exception
messages.

## API behavior

Use structured problem responses with:

- appropriate HTTP status;
- stable public error code;
- safe Bulgarian message.

At minimum distinguish:

- validation failure;
- authentication required;
- platform access denied;
- Business not found;
- slug conflict;
- invalid lifecycle transition;
- missing active owner during activation;
- concurrent update conflict.

Do not return arbitrary `IllegalArgumentException` messages.

## Testing requirements

Use deterministic unit tests for:

- slug validation;
- timezone validation;
- allowed lifecycle transitions;
- rejected lifecycle transitions.

Use real PostgreSQL through Testcontainers for:

- Flyway migration from an empty database;
- preservation of V1 and V2;
- basic profile fields;
- unique slug enforcement;
- create and update persistence;
- optimistic-concurrency behavior;
- activation requiring an active BUSINESS_OWNER;
- valid lifecycle transitions;
- rejected lifecycle transitions;
- concurrent conflicting updates or transitions.

Use API/security integration tests proving:

- unauthenticated requests are rejected;
- BUSINESS_OWNER, MANAGER, and STAFF cannot use platform endpoints;
- PLATFORM_ADMIN can perform the approved operations;
- returned data contains only approved platform metadata;
- validation and conflict responses use stable codes and safe Bulgarian text;
- no internal exception or database detail is exposed.

Keep all existing Phase 2 tests passing.

Do not use arbitrary sleeps in concurrency tests.

Map every acceptance criterion to a concrete test in the completion report.

## Documentation updates

Update permanent documentation only where required to describe concrete
implemented Phase 3 backend behavior, API decisions, migration, concurrency
strategy, or temporary limitations.

Do not rewrite unrelated documentation.

Do not mark the complete Phase 3 issue as finished because the platform-admin
frontend and final integration tasks remain separate.

## Explicit exclusions

Do not implement:

- platform-admin React UI;
- public Business pages;
- public booking;
- Services;
- StaffMembers;
- schedules or availability;
- Customers;
- Appointments;
- Business-user configuration screens;
- real email delivery;
- payments or subscriptions;
- billing;
- deployment or hosting changes;
- Phase 4+ functionality.

Do not add a dependency unless it is demonstrably necessary and separately
approved.

Do not modify CI or Compose unless an unexpected necessity is reported and
separately approved.

## Acceptance criteria

This task is complete only when:

1. A PLATFORM_ADMIN can create a valid DRAFT Business.
2. Business slugs are validated and globally unique.
3. PLATFORM_ADMIN can list and retrieve minimum Business metadata.
4. PLATFORM_ADMIN can edit only approved basic Business fields.
5. Only the approved lifecycle transitions are possible.
6. Activation requires an active BUSINESS_OWNER.
7. Optimistic concurrency prevents silent lost updates.
8. Non-platform users cannot access the operations.
9. Safe stable errors cover validation, authorization, uniqueness, lifecycle,
   missing owner, not found, and concurrency.
10. V1 and V2 remain unchanged and a new migration applies successfully.
11. All new unit, PostgreSQL, API, security, and concurrency tests pass.
12. All existing Phase 2 tests continue to pass.
13. No frontend or Phase 4+ functionality is implemented.
14. Code is conventionally formatted and readable.
15. `git diff --check` passes.
16. No credentials, generated output, or unrelated files are tracked.
17. No commit or push is performed.

## Completion procedure

After implementation:

1. Review every changed file.
2. Run the narrowest tests first.
3. Run the complete relevant backend test and verification suite.
4. Validate Flyway against PostgreSQL.
5. Run `git diff --check`.
6. Run `git status --short --untracked-files=all`.
7. Map every acceptance criterion to concrete tests or verification.
8. Report every created, modified, and deleted file.
9. Report exact executed test counts.
10. Report assumptions, limitations, and unresolved decisions.
11. Confirm that the platform-admin frontend remains unimplemented.
12. Do not commit or push.
13. Stop and wait for human review.

## Completion evidence

Completed on 2026-08-14. The implementation provides the seven approved
PLATFORM_ADMIN-only endpoints, Flyway V3 profile fields, deterministic bounded
pagination, expected-version optimistic concurrency, the three approved
lifecycle transitions, and same-Business active-owner readiness for initial
activation. The owner query uses `FOR SHARE` inside the platform activation
transaction. V1 and V2 remain unchanged, and no Phase 4+ domain or
platform-admin frontend was added.

Acceptance evidence:

1. `PlatformBusinessApiIntegrationTests` proves creation returns DRAFT/version 0.
2. `BusinessDomainTests`, `BusinessSchemaIntegrationTests`, and
   `BusinessAdministrationServiceIntegrationTests` prove slug validation and
   PostgreSQL uniqueness handling.
3. `PlatformBusinessApiIntegrationTests` proves bounded listing and retrieval
   expose only approved metadata.
4. `BusinessInputValidatorTests`, `BusinessAdministrationServiceTests`, and the
   API integration suite prove only approved profile fields are mutable.
5. `BusinessDomainTests`, Business service/store integration tests, and the API
   suite prove all allowed and rejected lifecycle transitions.
6. `ActiveBusinessOwnerQueryIntegrationTests`,
   `PlatformBusinessServiceTests`, `PlatformBusinessServiceIntegrationTests`,
   and the API suite prove initial activation requires an active owner for the
   same Business and holds the qualifying row lock through activation.
7. `BusinessStoreIntegrationTests` and
   `BusinessAdministrationServiceIntegrationTests` use coordinated PostgreSQL
   writers to prove exactly one same-version mutation succeeds.
8. `PlatformBusinessApiIntegrationTests` proves unauthenticated and non-platform
   role rejection and PLATFORM_ADMIN access.
9. Controller/advice tests and the API integration suite prove the stable safe
   validation, authentication, authorization, not-found, uniqueness, lifecycle,
   missing-owner, concurrency, and unexpected-error mappings.
10. `BusinessSchemaIntegrationTests` migrates an empty PostgreSQL database
    through V1, V2, and V3 and verifies the schema; repository diff inspection
    confirms V1 and V2 were not edited.
11. Targeted unit/PostgreSQL/API/security/concurrency suites and the complete
    Maven verification pass.
12. Complete Maven verification includes and passes the Phase 2 regression
    suites, including `AuthenticationApiIntegrationTests` and
    `ProductionProfileIntegrationTests`.
13. Repository diff/status inspection confirms no frontend or Phase 4+ work.
14. Changed Java files were reviewed and the focused compressed-body search has
    no prohibited matches.
15. `git diff --check` passes.
16. Tracked-file and secret-pattern inspection finds no credentials, generated
    output, or unrelated files.
17. No commit or push was performed during finalization; handoff stops for human
    review.

Executed final verification:

```text
cd backend
./mvnw -Dtest=BusinessInputValidatorTests,BusinessAdministrationServiceIntegrationTests,BusinessStoreIntegrationTests,ActiveBusinessOwnerQueryIntegrationTests,BusinessSchemaIntegrationTests,ModuleBoundaryTests test
./mvnw verify
cd ..
git diff --check
git status --short --untracked-files=all
```

The focused suite passed 155 tests and complete verification passed 306 tests,
with zero failures, errors, or skips. Generated Maven output remained ignored.
