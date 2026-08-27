# ADR-0003: Use Business-scoped Memberships for multi-tenancy

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-08-12
- **Recorded date:** 2026-08-25
- **Related issues:** #1, #3, #4, #5, #8
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

SpotYourSlot needed one authentication identity to participate in one or more
Business tenants without making a Business role global. Authorization also had
to distinguish platform administration from work performed inside a Business
and prevent a role held in one Business from granting access to another.

The product foundation selected a global User, a Business tenant root, and an
explicit Membership relating the two. This record explains that decision
retrospectively without claiming that the repository documents a historical
comparison of every tenancy model.

## Evidenced constraints

Repository evidence at the decision date establishes that:

- one User could belong to multiple Businesses;
- tenant roles had to be granted only through explicit Business Memberships;
- authorization had to use server-resolved Business context rather than trust a
  client-supplied Business ID;
- `PLATFORM_ADMIN` authority was separate from Business roles;
- tenant-owned data would use `business_id`, with application authorization as
  the primary control and database constraints as defense in depth; and
- the MVP used one shared application and database rather than a schema or
  deployment per Business.

ADR-0001 owns the modular-monolith topology, ADR-0002 owns PostgreSQL selection,
ADR-0005 will own cookie and session authentication, and ADR-0006 will own
security-token persistence and lifecycle guarantees.

## Options considered

Business-scoped Memberships are the only model directly documented as selected
in the historical repository. The other options below are a retrospective
assessment, not evidence that they were debated on 2026-08-12.

### Store one Business ID and role directly on User — retrospective assessment

This model is simple for users who can belong to exactly one Business: tenant
lookup is direct and fewer tables and joins are required. It couples a global
identity to one tenant, makes different roles across Businesses impossible, and
requires redesign when a User needs access to another Business.

### Create a separate User identity for every Business — retrospective assessment

Tenant-local identities make the role relationship straightforward and can
reduce accidental cross-tenant identity reuse. They duplicate credentials,
recovery and account state, fragment one person's identity, and create a poor
experience for users who work across Businesses.

### Store Business roles globally on User — retrospective assessment

Global roles map simply to framework authorities and can suit products where a
role truly applies everywhere. `BUSINESS_OWNER`, `MANAGER`, and `STAFF` need a
Business dimension in SpotYourSlot; without one, the role cannot identify which
tenant it authorizes and risks over-broad access.

### Use Business-scoped Memberships in one shared schema

Memberships let one global User hold an independent role and active state in
each Business while reusing one credential identity. The relationship is
explicit and queryable, but every tenant operation must still enforce the
correct Membership, role, Business state, and resource ownership. A shared
schema magnifies the impact of a missing predicate.

### Use schema-per-tenant or database-per-tenant isolation — retrospective assessment

Separate schemas or databases can provide stronger storage and operational
separation and support tenant-specific backup or placement. They increase
provisioning, migration, connection management, monitoring, cross-tenant
platform operations, and recovery complexity. The repository does not show
that either option was evaluated at the original decision date.

## Decision

Model User as the global authentication identity and Business as the tenant
root. Model Membership as the Business-scoped relationship between them, with
the Business role and active state stored on Membership.

`BUSINESS_OWNER`, `MANAGER`, and `STAFF` are Membership roles, not global User
authorities. `PLATFORM_ADMIN` is a separate platform-level relationship. A User
may hold both platform authority and Business Memberships; the models are
separate, not mutually exclusive, and neither automatically grants the other.

Tenant access requires an authenticated identity and an appropriate active
Membership for the requested Business, plus operation-specific role, Business-
state, and resource-ownership rules. Membership modelling does not make every
query tenant-safe.

## Membership and authorization semantics

The implemented `app_user` row has global credential and account state but no
Business role. `membership` references exactly one User and one Business,
allows only the three approved roles, stores an independent active flag, and
permits at most one row for a given `(user_id, business_id)` pair. One User can
therefore hold different Memberships and roles across Businesses without
duplicating credentials.

User account state and Membership state answer different questions. User
`active` and `locked` determine whether the global identity can authenticate or
retain a valid session. Membership `active` determines whether that User's
relationship with one Business currently qualifies for access. An active
Membership does not imply an active, unlocked User; an active User does not
imply access to any Business.

`user_session.active_business_id` is a nullable foreign key to Business. The
database does not prove that the session User has a Membership for that value.
Application persistence validates an active Membership when listing available
Businesses and selecting one. The session filter revalidates a selected
Business and clears an invalid selection while preserving otherwise valid
authentication.

`BusinessAuthorizer` can require an active Membership and an allowed Business
role. Phase 4 tenant operational APIs do not yet exist, so it is not evidence
that all future Business-owned operations are protected. Business status and
resource ownership remain separate authorization inputs rather than properties
implicitly enforced by Membership lookup.

Initial Business activation checks for an active `BUSINESS_OWNER` Membership
belonging to the same Business. That readiness query intentionally does not join
`app_user` or validate User active or locked state; it proves the approved
Membership condition, not that the owner can currently authenticate.

## Rationale

Putting role and active state on Membership gives them the tenant dimension
they require. It prevents a Business role on a global User from inherently
applying to every Business and supports future multi-Business users with one
credential lifecycle.

Separating `PLATFORM_ADMIN` preserves a distinct platform authorization plane.
Platform authority can secure platform routes without fabricating a Membership,
while Business roles cannot become platform authority through framework role
mapping alone.

The shared-schema model fits the selected application and database architecture
while keeping tenant ownership explicit through `business_id`. This is logical
separation enforced by application predicates and selected database
constraints; it is not process, schema, database, or physical isolation.

## Tradeoffs and disadvantages

- Tenant authorization requires joins or lookups involving User, Membership,
  Business, and often the target resource.
- Every Business-owned query and mutation must carry the correct Business
  predicate; omission can expose data across tenants.
- Account, Membership, Business, and resource states can change independently,
  increasing authorization-state complexity.
- A shared schema does not contain a tenant failure, noisy workload, or
  privileged database access to one Business.
- Cross-Business platform operations need separate, narrowly scoped authority.
- Supporting multiple roles for the same User in one Business would require a
  future model change because the current schema stores one role per Membership.

## Security risks and mitigations

The primary risk is horizontal privilege escalation through a missing or
incorrect `business_id` or Membership predicate. Mitigate it with server-derived
identity and Business context, explicit authorization components, tenant-
prefixed persistence operations, same-Business foreign-key constraints where
implemented, and Business A/Business B tests for each tenant-owned API.

Never trust browser-selected Business state as an authorization grant. Recheck
the current User, active Membership, required role, Business state, and target
resource ownership at the use-case boundary. Return safe denial behavior that
does not unnecessarily disclose another tenant's data.

PostgreSQL Row-Level Security is deferred and is not part of this decision or
the current implementation. Database constraints provide defense in depth but
do not replace application authorization, and Membership existence alone does
not satisfy every operation-specific rule.

## Consequences

New tenant-owned tables must include non-null `business_id` and use ownership
constraints and tenant-scoped indexes appropriate to their relationships.
Application use cases must derive or validate the Business from authenticated
Membership and scope persistence by it. Platform operations must remain
separate and must not imply Business Membership access.

Services, StaffMembers, Customers, Appointments, and other operational tenant
domains remain future work. Their complete `business_id` enforcement, same-
Business relationships, authorization predicates, and isolation tests are
requirements, not current implementation facts. Current tests do not prove
isolation for repositories or APIs that do not yet exist.

## Direct historical evidence

- The original [product specification](../product-spec.md) says Users receive
  tenant roles only through explicit Business Membership, may belong to
  multiple Businesses, and use server-resolved Business context. Git commit
  `9cf10e0` first recorded this on 2026-08-12 for issue #1.
- The original [architecture](../architecture.md) identifies Business as the
  tenant boundary, requires `business_id` on tenant-owned data, makes
  application authorization primary, and explicitly defers RLS.
- The original [data model](../data-model.md) defines a global `app_user`, a
  Business-scoped `membership` with role and active state, and a separate
  `platform_role`.
- The original [security design](../security.md) requires authentication, an
  active Membership, the required Business role, and repository ownership
  predicates while separating platform routes.
- The [testing strategy](../testing-strategy.md) requires Business A/Business B
  isolation, role boundaries, inactive-Membership cases, and cross-Business
  reference tests for implemented tenant domains.
- The [implementation plan](../implementation-plan.md) assigns the identity and
  tenancy foundation to Phase 2 and future tenant-owned operational domains to
  later phases.
- The [identity and tenancy task](../tasks/02-identity-and-tenancy.md) explicitly
  rejects global Business roles, requires multi-Business Memberships and active
  Membership validation, and was implemented under issue #3.
- The [V1 migration](../../backend/src/main/resources/db/migration/V1__identity_and_tenancy.sql)
  implements the current User, Business, Membership, platform-role, and selected-
  Business schema and constraints.
- Current Membership behavior is implemented by
  [MembershipRole](../../backend/src/main/java/bg/spotyourslot/identity/domain/MembershipRole.java),
  [IdentityStore](../../backend/src/main/java/bg/spotyourslot/identity/infrastructure/IdentityStore.java),
  [AuthenticationService](../../backend/src/main/java/bg/spotyourslot/identity/application/AuthenticationService.java),
  [DatabaseSessionFilter](../../backend/src/main/java/bg/spotyourslot/identity/infrastructure/DatabaseSessionFilter.java),
  and [BusinessAuthorizer](../../backend/src/main/java/bg/spotyourslot/identity/application/BusinessAuthorizer.java).
- Platform authority is derived separately by
  [AuthenticatedUser](../../backend/src/main/java/bg/spotyourslot/identity/application/AuthenticatedUser.java)
  and [SecurityConfiguration](../../backend/src/main/java/bg/spotyourslot/identity/configuration/SecurityConfiguration.java).
- The [platform Business backend task](../tasks/03a-platform-business-backend.md)
  preserves platform/Membership separation. Under parent issue #4, issue #5
  added the published
  [active-owner query](../../backend/src/main/java/bg/spotyourslot/identity/ActiveBusinessOwnerQuery.java),
  its [implementation](../../backend/src/main/java/bg/spotyourslot/identity/application/ActiveBusinessOwnerQueryService.java),
  and [platform orchestration](../../backend/src/main/java/bg/spotyourslot/platform/application/PlatformBusinessService.java).
- [Schema tests](../../backend/src/test/java/bg/spotyourslot/integration/IdentitySchemaIntegrationTests.java)
  verify current Membership roles and duplicate prevention.
  [Authentication API tests](../../backend/src/test/java/bg/spotyourslot/integration/AuthenticationApiIntegrationTests.java)
  verify multi-Business selection, inactive-Membership clearing, Business
  selection isolation, and separation of platform authority.
  [Active-owner tests](../../backend/src/test/java/bg/spotyourslot/identity/application/ActiveBusinessOwnerQueryIntegrationTests.java)
  and [platform API tests](../../backend/src/test/java/bg/spotyourslot/integration/PlatformBusinessApiIntegrationTests.java)
  verify the current same-Business owner predicate and role-plane separation.
  These tests establish current behavior, not the original motivation or future
  tenant-domain coverage.

## Retrospective inference

Memberships balance one global credential identity with per-Business roles and
revocation. Compared with placing one role on User, they reduce pressure to
duplicate accounts or broaden tenant authority as multi-Business use appears.
This comparative rationale is consistent with the model but is not documented
as the original deliberation on 2026-08-12.

Shared-schema tenancy reduces per-tenant operational provisioning but relies on
consistent authorization and ownership predicates. Comparing it with schema-
per-tenant or database-per-tenant isolation is later analysis; the repository
does not document that those options were historically considered.

## Conditions for revisiting

Revisit this decision if requirements demand multiple simultaneous roles per
Membership, delegated or temporary access, tenant hierarchies, materially
stronger storage isolation, tenant-specific data residency, or independent
tenant backup and recovery that the shared model cannot meet acceptably.

Any change must define identity reuse, role and Membership migration, tenant
context derivation, data ownership, authorization behavior, operational impact,
and a tested transition that does not broaden access. This is forward-looking
guidance, not a claim that changing tenancy models will be easy.
