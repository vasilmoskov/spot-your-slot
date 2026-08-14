# SpotYourSlot — Identity, Roles and Business Isolation

Status: In Progress  
GitHub issue: #3 — Set up users, roles, and business data isolation

## Task purpose

Implement the secure identity, authentication, authorization and multi-tenant foundation for SpotYourSlot.

This phase must establish:

- the initial Flyway-managed database schema;
- users and password credentials;
- platform roles and Business Memberships;
- secure server-managed browser sessions;
- login and logout;
- owner invitations;
- forgotten-password and password-reset flows;
- Business selection for users with multiple Memberships;
- strict Business data isolation;
- deterministic security and integration tests;
- minimal Bulgarian authentication UI.

Do not implement Business onboarding screens, Services, StaffMembers, schedules, availability, Customers, Appointments or booking.

## Required context

Before making changes, read completely:

- `AGENTS.md`;
- `README.md`;
- `docs/product-spec.md`;
- `docs/architecture.md`;
- `docs/data-model.md`;
- `docs/security.md`;
- `docs/testing-strategy.md`;
- `docs/implementation-plan.md`;
- `docs/tasks/00-product-foundation.md`;
- `docs/tasks/01-repository-bootstrap.md`;
- this task file.

Inspect all existing backend, frontend, Compose and CI files before proposing changes.

Treat the permanent documentation and the approved decisions in this task as authoritative.

If this task conflicts with permanent documentation or requires an unresolved product decision, stop and report the conflict before editing.

## Approved product decisions

### Shared login

Use one login flow for:

- `PLATFORM_ADMIN`;
- `BUSINESS_OWNER`;
- `MANAGER`;
- `STAFF`.

After authentication, determine the available destination from the user’s platform role and active Business Memberships.

If a user belongs to more than one Business, allow them to select only from Businesses for which they have an active authorized Membership.

### Session policy

- Maximum session lifetime: 12 hours.
- Idle timeout: 2 hours.
- Closing the browser must not necessarily terminate the session.
- Logout invalidates the current session.
- Password change invalidates all other sessions.
- Successful password reset invalidates all existing sessions.
- Disabled or locked users cannot create new sessions.
- Authorization must revalidate relevant user, Membership and Business state instead of trusting stale browser data.

### Owner invitation

- `PLATFORM_ADMIN` initiates an owner invitation for an existing Business.
- Invitation validity: 48 hours.
- Invitation tokens are single-use.
- The invited owner supplies their name and password.
- Successful acceptance creates or links the user and grants an active `BUSINESS_OWNER` Membership for that Business.
- Replacing an invitation invalidates previous active invitations for the same intended owner and Business.
- Expired invitations cannot be accepted.
- Raw invitation tokens must never be stored in the database or normal logs.

### Password reset

- The forgot-password response is identical whether the account exists or not.
- Reset validity: 30 minutes.
- Reset tokens are single-use.
- Successful reset invalidates all existing sessions and all other active reset tokens for the user.
- Raw reset tokens must never be stored in the database or normal logs.
- Real email delivery is not part of this phase.

### Platform administration boundary

`PLATFORM_ADMIN` may:

- operate only the narrowly required identity APIs;
- initiate or replace an owner invitation for an existing Business;
- inspect basic Business identity/status information when required for that operation.

`PLATFORM_ADMIN` must not:

- impersonate a Business user;
- obtain another user’s session;
- access Customer data, Appointment content or private notes;
- silently bypass tenant authorization.

Future support access is excluded.

## Architecture constraints

Preserve the modular-monolith design.

Use clear modules below `bg.spotyourslot`, including suitable boundaries for:

- `identity`;
- `business`;
- `shared`.

Do not place the complete implementation in controllers or giant service classes.

Controllers must delegate to application use cases. Persistence and domain details must remain appropriately encapsulated.

Avoid cyclic module dependencies.

Use Spring Modulith verification if it is compatible with the selected Spring Boot stack and justified by the permanent architecture. If introducing it would require an incompatible or pre-release dependency, stop and report instead of forcing it.

## Database schema

Create versioned Flyway migrations under the backend resources.

Do not use Hibernate schema generation for application schema changes. Retain schema validation.

Implement the smallest schema needed for this phase.

### Business

Create the minimal `business` persistence required for tenant ownership and Membership validation.

It must include at least:

- stable primary key;
- unique slug;
- display name;
- Business status compatible with the approved lifecycle;
- Business type if required by the approved model;
- timezone;
- audit timestamps;
- optimistic locking or another documented concurrent-update strategy where appropriate.

Do not implement the later Business onboarding workflow or configuration UI.

### User

Create an application-user model containing at least:

- stable primary key;
- normalized unique email;
- display name;
- password hash;
- active/locked state;
- password-change timestamp or credential version;
- audit timestamps.

Never store raw passwords.

Document and implement deterministic email normalization.

Do not create Customer accounts.

### Platform role

Model `PLATFORM_ADMIN` explicitly.

Do not represent Business roles as global user roles.

A normal Business user must not gain platform access merely through a Membership.

### Membership

Create explicit Business Memberships with:

- `business_id`;
- `user_id`;
- role: BUSINESS_OWNER, MANAGER or STAFF;
- active/inactive state;
- audit timestamps;
- constraints preventing invalid duplicates.

A user may belong to multiple Businesses.

Every Membership belongs to exactly one Business and one user.

Do not create the optional `StaffMember` link yet. That belongs to the workforce phase.

### Session

Persist server-managed sessions with at least:

- an opaque session identifier or secure hash/reference;
- user ownership;
- creation time;
- last activity time;
- absolute expiration;
- invalidation/revocation state or time;
- security-relevant metadata only when justified and privacy-safe.

Do not store raw bearer credentials unnecessarily.

Support efficient lookup, expiration and revocation.

### Invitation

Persist owner invitations with at least:

- Business ownership;
- normalized invited email;
- hashed token;
- creation and expiration times;
- consumed/invalidation state;
- creator/audit reference where appropriate.

Enforce single-use behavior transactionally.

### Password reset

Persist password-reset requests with at least:

- user ownership;
- hashed token;
- creation and expiration times;
- consumed/invalidation state.

Enforce single-use behavior transactionally.

### Database requirements

Add:

- primary keys;
- foreign keys;
- Business-scoped unique constraints where appropriate;
- indexes supporting authentication and token lookup;
- audit fields;
- length and state constraints;
- case-insensitive normalized email uniqueness;
- migration tests against real PostgreSQL.

Do not introduce tables for Services, StaffMembers, schedules, Customers, Appointments, notifications or later phases.

## Password security

Use a secure adaptive password hashing algorithm supported by Spring Security.

Prefer Argon2id if it can be used with a stable, well-supported dependency and operationally safe configuration. Otherwise use bcrypt with a documented cost.

Do not implement a custom password algorithm.

Define and validate an MVP password policy that favors length over arbitrary composition rules.

Recommended baseline:

- minimum 12 characters;
- maximum reasonable length to prevent abuse;
- allow password managers and passphrases;
- do not silently truncate passwords;
- provide Bulgarian validation messages.

Do not log password values or hashes.

Document the selected algorithm, parameters and upgrade strategy.

## Session authentication

Use secure server-managed HTTP-only cookie authentication.

The session cookie must be:

- `HttpOnly`;
- `Secure` in production;
- appropriately `SameSite`;
- scoped narrowly;
- persistent for the approved maximum session lifetime;
- configurable by environment;
- free of sensitive user data.

Do not use browser local storage or session storage for authentication tokens.

Regenerate or rotate the session identifier after successful authentication where appropriate.

Return only the minimum authenticated-user information needed by the frontend.

Implement:

- login;
- logout;
- current-session endpoint;
- available authorized Businesses;
- active Business selection or switching;
- password change.

Business selection must never grant access. It may select only among already authorized active Memberships.

## Authorization and tenant isolation

Create a server-side authenticated Business context.

For authenticated Business operations:

- derive the user from the server-managed session;
- verify an active Membership;
- verify the required Membership role;
- verify the current Business state;
- scope every Business-owned query and mutation by `business_id`;
- never trust a frontend-supplied Business ID without validation.

Prevent horizontal privilege escalation.

A user from Business A must not read or modify data belonging to Business B, even by changing:

- path IDs;
- query parameters;
- request bodies;
- cookies;
- selected Business state.

Use safe not-found/forbidden behavior that does not unnecessarily disclose cross-tenant resource existence.

Add reusable authorization components without hiding tenant checks in fragile conventions.

## CSRF, CORS and API security

Because authentication uses cookies:

- enable CSRF protection;
- provide a secure frontend-compatible CSRF token mechanism;
- require CSRF protection for state-changing browser requests;
- configure exact allowed origins per environment;
- do not use wildcard credentialed CORS;
- keep production origins environment-based;
- return structured safe errors;
- avoid stack traces and internal details in API responses.

Use RFC 7807-compatible problem responses where consistent with the approved architecture.

Bulgarian user-facing errors must have stable machine-readable error codes.

## Authentication endpoints

Design focused REST endpoints for the required flows.

The exact paths may follow established Spring conventions, but the API must clearly support:

- login;
- logout;
- current authenticated session;
- available Businesses;
- selecting/switching the active Business;
- changing password;
- accepting an owner invitation;
- requesting password reset;
- completing password reset;
- creating/replacing an owner invitation through narrowly authorized platform administration.

Document the endpoint contract.

Do not create unrelated CRUD APIs.

## Safe local development delivery

Real email is excluded.

Create a small delivery abstraction for invitation and password-reset links so that the future notification phase can replace the implementation.

For local/test environments, provide a safe development mechanism that allows the developer and automated tests to retrieve generated links.

The development mechanism must:

- be disabled in production;
- avoid raw tokens in normal application logs;
- avoid storing raw tokens in PostgreSQL;
- avoid writing raw tokens to tracked files;
- be inaccessible to ordinary unauthenticated or Business users;
- have bounded in-memory retention;
- clearly identify that it is development-only.

A development-only in-memory mailbox exposed through a protected development endpoint is acceptable if it satisfies these rules.

Do not integrate Resend or another external provider.

## Initial platform administrator

Provide a safe, documented method to create the first `PLATFORM_ADMIN` in local development and future deployments.

Requirements:

- no default production password;
- no committed credential;
- idempotent behavior;
- environment-based input or an explicit controlled command;
- password must be hashed before persistence;
- secrets must not be logged;
- production startup must not silently create an administrator from unsafe defaults.

Do not seed real personal information.

Document the local setup procedure using placeholders.

## Rate limiting

Add a focused rate-limiting strategy for public authentication endpoints, especially:

- login;
- forgot password;
- invitation acceptance;
- password-reset completion.

Prefer a simple in-process implementation suitable for the initial single-instance MVP if it is clearly documented as an initial protection.

Do not add Redis.

The implementation must:

- use conservative keys such as normalized email plus client address where appropriate;
- avoid exposing account existence;
- produce safe `429` responses;
- be deterministic and testable;
- document the limitations for future multi-instance deployment.

Do not trust forwarding headers unless the application is behind an explicitly configured trusted proxy.

## Minimal frontend scope

Implement only the frontend required to exercise the identity flows.

Use Bulgarian customer-facing text and accessible forms.

Include:

- shared login page;
- forgotten-password request page;
- password-reset completion page;
- owner-invitation acceptance page;
- minimal authenticated session state;
- Business selection when a user has multiple authorized Memberships;
- logout;
- password-change form if needed to complete the approved flow.

Do not implement:

- platform onboarding screens;
- Business administration dashboard;
- Services or team management;
- calendars;
- booking UI;
- product styling system;
- complex routing abstractions not justified by this phase.

Forms must have:

- visible labels;
- keyboard accessibility;
- clear Bulgarian validation messages;
- safe generic authentication errors;
- loading and disabled states;
- no secrets written to browser storage.

Choose the smallest stable routing/data-fetching approach consistent with the existing stack. Do not add a large state-management framework.

## Testing requirements

### Backend unit tests

Cover at least:

- email normalization;
- password validation and hashing behavior;
- session timeout calculations;
- token hashing and verification;
- invitation lifecycle;
- password-reset lifecycle;
- role decisions;
- rate-limit decisions.

Use an injected clock and deterministic randomness boundaries where needed.

### PostgreSQL integration tests

Use Testcontainers with real PostgreSQL.

Cover at least:

- Flyway migration success from an empty database;
- unique normalized email;
- Membership constraints;
- foreign-key integrity;
- token persistence as hashes;
- session persistence and revocation;
- invitation single use;
- password-reset single use;
- concurrent or repeated token consumption;
- Business A/B isolation.

Do not replace PostgreSQL integration behavior with H2.

### API/security integration tests

Cover at least:

- successful login;
- invalid credentials;
- enumeration-safe login and forgot-password responses;
- logout invalidation;
- unauthenticated rejection;
- CSRF rejection and success;
- exact-origin CORS behavior;
- 12-hour absolute session expiration;
- 2-hour inactivity expiration;
- session invalidation after password change;
- all-session invalidation after password reset;
- expired, invalid and replayed invitation tokens;
- expired, invalid and replayed reset tokens;
- user with one Membership;
- user with multiple Memberships;
- unauthorized Business selection;
- `BUSINESS_OWNER`, `MANAGER` and `STAFF` authorization boundaries;
- `PLATFORM_ADMIN` inability to impersonate or enter a Business as a member;
- safe development-link access;
- production absence of development delivery endpoints;
- rate limiting and safe `429` responses.

### Frontend tests

Cover the critical states of:

- login form;
- generic login failure;
- forgotten-password generic success response;
- invitation acceptance validation;
- password-reset validation;
- Business selection;
- logout;
- protected authenticated state;
- accessible labels and keyboard submission.

### Module-boundary tests

Verify the approved modular-monolith package boundaries.

## Test fixtures

Create deterministic test fixtures/builders without production default accounts.

Tests may create:

- one `PLATFORM_ADMIN`;
- users belonging to Business A;
- users belonging to Business B;
- one user belonging to both Businesses;
- owner, manager and staff Memberships;
- active, expired, consumed and invalid tokens;
- active, idle-expired, absolute-expired and revoked sessions.

Fixture credentials must be clearly test-only.

## Documentation

Update permanent documentation only where implementation decisions become concrete.

Update `README.md` with exact local instructions for:

- starting PostgreSQL;
- running migrations;
- creating the local initial `PLATFORM_ADMIN`;
- obtaining local development invitation/reset links;
- starting backend and frontend;
- exercising login/logout;
- running unit and Testcontainers checks;
- troubleshooting cookies, CSRF, CORS and expired sessions.

Document:

- endpoint summary;
- selected password hashing algorithm and parameters;
- cookie attributes;
- session lifetime and idle timeout;
- token validity periods;
- local development delivery behavior;
- initial rate-limit behavior and limitations;
- tenant-context enforcement;
- any temporary decisions.

Do not copy this complete task into permanent documentation.

## Security review checklist

Before completion, verify:

- no raw passwords or tokens are stored;
- no passwords, hashes, session IDs or raw tokens appear in normal logs;
- no authentication token is stored in frontend storage;
- cookies have the intended attributes;
- CSRF is active for state-changing requests;
- credentialed CORS is exact-origin;
- production does not expose development token delivery;
- production cannot create an administrator from default credentials;
- Business access requires active Membership;
- platform role does not imply Business Membership;
- Business A/B isolation tests pass;
- errors do not expose account existence or internal details;
- rate limiting covers the approved public endpoints;
- no later-phase personal or Business data was introduced.

## Quality requirements

- Keep classes and modules focused.
- Use constructor injection.
- Validate all request DTOs.
- Use transactions at application-use-case boundaries.
- Use database constraints as defense in depth.
- Use an injected clock for time-sensitive behavior.
- Use secure random token generation.
- Compare security-sensitive values safely.
- Do not weaken existing bootstrap security or tests.
- Do not suppress security warnings without documented justification.
- Preserve the existing Java, Spring Boot, Node, React, TypeScript, Vite and PostgreSQL versions unless a verified compatibility issue requires human approval.
- Do not add speculative infrastructure.
- Do not commit generated build output or local credentials.

## Explicit exclusions

Do not implement:

- creation/editing/suspension UI for Businesses;
- full platform onboarding;
- StaffMember entities or account linkage;
- Services;
- qualifications;
- working schedules;
- availability;
- Customers;
- Appointments;
- public booking;
- cancellation;
- real notification delivery;
- reminders;
- social login;
- Customer accounts;
- payments;
- SMS;
- impersonation;
- support access;
- production deployment;
- hosting or DNS changes;
- Redis, Kafka, Kubernetes or microservices.

## Required verification

Run the narrowest checks first, followed by the complete relevant suite.

At minimum, run the following backend checks from `backend/`:

* `./mvnw test`
* `./mvnw verify`

Run the following frontend checks from `frontend/`:

* `npm ci`
* `npm run lint`
* `npm run test`
* `npm run build`

Run the following repository and infrastructure checks from the repository root:

* `docker compose config --quiet`
* `git diff --check`
* `git status --short --untracked-files=all`

Also:

* start PostgreSQL and confirm that it becomes healthy;
* run the backend against PostgreSQL;
* confirm that the health endpoint returns `UP`;
* execute the critical authentication flows locally or through integration tests;
* confirm that the production profile does not expose development-only endpoints;
* inspect the final diff for secrets and generated output;
* confirm that no Phase 3 or later functionality was implemented;
* stop Compose normally and preserve the named development volume.

## Acceptance criteria

This task is complete only when:

1. Flyway creates the approved initial identity and tenancy schema.
2. Hibernate validates rather than creates the schema.
3. Users, platform roles and Business Memberships are persisted correctly.
4. Secure cookie login, logout and current-session flows work.
5. Session absolute and inactivity timeouts are enforced.
6. Password changes and resets invalidate sessions as approved.
7. Owner invitations are hashed, expiring, single-use and operational.
8. Password reset is enumeration-safe, hashed, expiring and single-use.
9. Local/test link delivery works without logs, files or database storage of raw tokens.
10. Production does not expose the development delivery mechanism.
11. Users can select only Businesses authorized through active Memberships.
12. Business A/B isolation is enforced and tested.
13. `PLATFORM_ADMIN` cannot impersonate or inherit Business access.
14. CSRF and exact-origin CORS are configured and tested.
15. Initial authentication rate limiting is implemented and documented.
16. Minimal accessible Bulgarian frontend identity flows work.
17. PostgreSQL and Testcontainers integration tests pass.
18. Existing bootstrap checks remain green.
19. Documentation matches the implemented behavior.
20. No later-phase functionality is implemented.
21. No credentials, build outputs or dependency directories are tracked.
22. `git diff --check` passes.
23. No commit or push has been performed.

## Completion procedure

After implementation:

1. Review every created and modified file.
2. Run all required backend and frontend checks.
3. Run the PostgreSQL and security integration tests.
4. Review migrations and database constraints.
5. Review cookie, CSRF, CORS and rate-limit behavior.
6. Search for accidentally stored or logged secrets and raw tokens.
7. Search for unapproved Phase 3 or later functionality.
8. Run `git diff --check`.
9. Run `git status --short --untracked-files=all`.
10. Report every created and modified file.
11. Report schema objects and migration versions.
12. Report endpoint contracts.
13. Report every verification command and result.
14. Report security assumptions and temporary decisions.
15. Report unresolved questions requiring human approval.
16. Confirm that no commit or push was performed.
17. Stop and wait for human review.
