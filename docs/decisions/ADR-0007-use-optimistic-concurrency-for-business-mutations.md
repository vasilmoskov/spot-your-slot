# ADR-0007: Use optimistic concurrency for Business mutations

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-08-14
- **Recorded date:** 2026-08-25
- **Related issues:** #3, #4, #5, #6, #8
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

Two platform administrators can read the same Business and then submit
different changes. If both updates are unconditional, the later write can
silently replace the earlier write even though it was based on stale data. The
same problem applies when concurrent lifecycle requests both assume the same
status.

The Phase 2 schema introduced a Business version field. Phase 3A then selected
explicit expected-version checks for platform Business profile and lifecycle
mutations so a stale write becomes a visible conflict rather than an unnoticed
lost update.

## Evidenced constraints

Repository evidence establishes that:

- a platform-created Business begins in `DRAFT` at version `0`;
- profile edits may change only the approved profile fields;
- lifecycle changes are limited to `DRAFT → ACTIVE`, `ACTIVE → SUSPENDED`, and
  `SUSPENDED → ACTIVE`;
- update and lifecycle requests carry the version on which the caller acted;
- PostgreSQL remains the final mutation guard; and
- conflicts must use a stable safe response without SQL or internal details.

ADR-0002 owns PostgreSQL selection, ADR-0003 owns tenant and platform
authorization boundaries, ADR-0006 owns security-token concurrency, and
ADR-0009 will own the real-PostgreSQL testing strategy. Concurrency policies for
future Services, StaffMembers, Appointments, and other domains are outside this
record.

## Options considered

Optimistic version checking is the only strategy directly documented as
selected for Business mutations. The other options below are a retrospective
assessment, not evidence that they were debated on 2026-08-14.

### Last-write-wins without version checking — retrospective assessment

Unconditional writes are simple for clients and avoid explicit conflict
handling. They can be suitable when state is replaceable or writers do not need
to preserve one another's work. For Business administration they can silently
discard an accepted edit or apply a lifecycle action based on stale state.

### Pessimistic row locking — retrospective assessment

Reading a row under a database lock can serialize a multi-step operation and is
useful when contention is expected or the decision must protect state throughout
one transaction. It holds locks while work is performed, can reduce throughput,
and introduces waiting and deadlock considerations. A browser cannot hold that
database lock safely across the user's read-and-edit interval.

### Optimistic concurrency with explicit version checking

An explicit version lets readers work without a long-held lock and makes the
database reject a write based on stale state. It suits relatively short,
infrequently conflicting administrative mutations, but requires version
exposure, atomic conditional SQL, conflict UX, and a reload-and-reconsider path.

### Serializable transaction isolation — retrospective assessment

Serializable isolation can prevent broad classes of transaction anomalies and
is valuable for complex invariants spanning several reads and writes. It can
abort transactions under contention, requires disciplined retry handling, and
does not by itself express the user-facing fact that a form was based on a
specific older Business representation.

### Distributed or application-level locking — retrospective assessment

An explicit lock service can coordinate work across processes or multiple
resources and may fit long-running workflows. It adds ownership, lease,
availability, expiry, and failure-recovery problems. A process-local lock would
not protect multiple application instances, while an external lock service is
not justified for this bounded database-row problem.

## Decision

Use the Business `version` as an explicit optimistic concurrency token for
approved profile updates and lifecycle transitions. Return the current version
in Business application and HTTP response records. Require clients to send
`expectedVersion` in the JSON request body for `PUT` profile updates and each
lifecycle `POST`.

Perform the final mutation as one conditional PostgreSQL statement. Treat no
returned row as a concurrent conflict and return HTTP 409 with stable code
`BUSINESS_CONCURRENT_UPDATE` and safe Bulgarian detail “Бизнесът е променен.
Обновете данните и опитайте отново.”

The current API does not use HTTP ETags or `If-Match`, and no Business delete
operation exists.

## Version and mutation semantics

### Creation

`BusinessStore.create` always inserts `DRAFT` and version `0`. Creation does not
carry an expected version because no Business row exists yet. Globally unique
slug enforcement is a separate PostgreSQL constraint and conflict path.

### Profile update

The client receives a Business representation containing `version` and returns
that value as `expectedVersion`. The application validates the Business ID,
command, and non-negative expected version, retrieves the current Business, and
rejects an already-stale value before preparing the mutation.

The decisive SQL predicate is:

```sql
WHERE id = :businessId
  AND version = :expectedVersion
```

The statement changes only the approved profile columns, sets `updated_at`, and
sets `version = version + 1`. `UPDATE ... RETURNING` yields the new Business
when one row matched and no result when a concurrent writer changed the version
after the application read. The empty result becomes `ConcurrentUpdate`.

### Lifecycle transition

The application validates input, retrieves the Business, checks the expected
version before lifecycle validation, and verifies the named transition. Its
final SQL predicate is stricter:

```sql
WHERE id = :businessId
  AND status = :expectedStatus
  AND version = :expectedVersion
```

A successful transition changes only status, update time, and version, again
incrementing version exactly once. No returned row covers either a concurrent
version change or a current-status mismatch at the final write and is translated
to a concurrent conflict. Disallowed lifecycle pairs detected before mutation
remain the distinct `BUSINESS_INVALID_LIFECYCLE` response.

The preliminary Java comparison is useful for deterministic validation and
orchestration order, but it cannot prevent a change between the read and write.
Only the conditional mutation closes that window. PostgreSQL takes ordinary
row locks while executing an update, so this strategy is not a claim that every
mutation is lock-free.

### Platform activation orchestration

Initial activation checks the version and `DRAFT` status before owner readiness,
then calls the same atomic Business transition. The identity query holds one
qualifying active `BUSINESS_OWNER` Membership row with `FOR SHARE` through the
outer transaction. That is a separate pessimistic lock protecting owner
readiness; it does not lock the Business row or replace its expected-version
predicate. Suspension and reactivation do not take that Membership lock.

Platform authorization is enforced before the application use case. The
Business SQL predicates contain Business identity, expected state, and expected
version; they do not encode `PLATFORM_ADMIN` authority or tenant Membership.
Optimistic concurrency therefore complements rather than replaces authorization,
transactions, validation, lifecycle rules, and database constraints.

## Rationale

An explicit version represents the state the caller actually reviewed. Coupling
it to the `UPDATE` predicate makes success conditional on that state still being
current, rather than relying on PostgreSQL isolation or a non-atomic Java check
to infer freshness.

Returning a conflict lets the user reload and reconsider both versions instead
of automatically overwriting another administrator's accepted work. A single
monotonic value also covers profile and lifecycle mutations consistently while
allowing their final predicates to include different domain conditions.

This fit is contextual. The evidence does not establish that contention will
always remain low or that the same strategy should be copied into every future
domain.

## Tradeoffs and disadvantages

- Every mutating client must retain and return the current version.
- Conflicts require user-facing recovery and can cause work to be re-entered.
- The version identifies that something changed but not which fields changed or
  whether two edits could be merged safely.
- A single Business-wide version creates conflicts even when concurrent edits
  affect disjoint profile fields.
- Repeated requests are not automatically idempotent; retrying with an old
  version conflicts, while retrying with a newly loaded version may apply the
  operation again unless the client reconsiders intent.
- The application performs a preliminary read before the conditional update,
  adding a database operation while still requiring the final SQL guard.
- High contention could produce frequent conflicts and poor administrative UX.

## Risks and mitigations

The main implementation risk is checking the version in Java and then issuing
an unconditional update. Keep identity, lifecycle state where applicable, and
expected version in the atomic SQL predicate, and translate an empty
`UPDATE ... RETURNING` result to a conflict.

Another risk is hiding or automatically retrying conflicts so one writer still
overwrites another's intent. Return the safe 409 response, preserve the user's
context where practical, and require reload and reconsideration before a new
mutation. Do not advise blind overwrite.

Authorization and lifecycle bugs are outside the protection offered by a
version counter. Continue enforcing platform authority, input validation,
allowed transitions, active-owner readiness for initial activation, and unique
slug constraints independently.

Real-PostgreSQL tests coordinate separate transactions and connections using
the same expected version and assert one success and one conflict. They verify
the implemented race under controlled conditions, not every production
workload, isolation interaction, or failure mode.

## Consequences

Business detail and summary contracts retain version as authoritative
application data even when a particular overview does not display it. Mutation
forms and lifecycle actions must use the version from the representation the
administrator reviewed. After success, clients use the returned incremented
version; after conflict, they reload instead of silently overwriting.

New Business mutation paths must either participate in this version or obtain a
separately reviewed concurrency policy. Future domain records do not inherit
this decision automatically. Changing to ETags, field-level versions, merge
semantics, pessimistic locking, or another isolation strategy requires explicit
API and persistence analysis.

## Direct historical evidence

- The [identity and tenancy task](../tasks/02-identity-and-tenancy.md) requires
  an optimistic lock or another documented strategy where appropriate, and
  issue #3 introduced the Business `version` column in
  [V1](../../backend/src/main/resources/db/migration/V1__identity_and_tenancy.sql)
  on 2026-08-14. That evidence establishes the schema capability, not the final
  Phase 3A protocol.
- The [platform Business backend task](../tasks/03a-platform-business-backend.md)
  first explicitly selects optimistic concurrency for Business mutations,
  requires safe conflict handling and concurrent-write tests, and records issue
  #5 under parent issue #4. Git commit `92aa702` added that task on 2026-08-14.
- [BusinessStore](../../backend/src/main/java/bg/spotyourslot/business/infrastructure/BusinessStore.java)
  implements version-zero creation and the current conditional profile and
  lifecycle SQL. Commit `1a053b8` added the persistence and concurrency control
  for issue #5.
- [BusinessAdministrationService](../../backend/src/main/java/bg/spotyourslot/business/application/BusinessAdministrationService.java)
  implements preliminary version checks, lifecycle decision order, affected-row
  interpretation through `UPDATE ... RETURNING`, and safe application conflicts.
  [BusinessAdministration](../../backend/src/main/java/bg/spotyourslot/business/BusinessAdministration.java)
  and [BusinessRecords](../../backend/src/main/java/bg/spotyourslot/business/BusinessRecords.java)
  expose the current version-bearing application contract.
- [PlatformBusinessController](../../backend/src/main/java/bg/spotyourslot/platform/web/PlatformBusinessController.java)
  and [HTTP records](../../backend/src/main/java/bg/spotyourslot/platform/web/PlatformBusinessHttpRecords.java)
  show that expected versions use request-body JSON rather than ETags or
  `If-Match`. The
  [platform exception handler](../../backend/src/main/java/bg/spotyourslot/platform/web/PlatformBusinessExceptionHandler.java)
  defines the safe 409 conflict response.
- [PlatformBusinessService](../../backend/src/main/java/bg/spotyourslot/platform/application/PlatformBusinessService.java)
  shows the initial activation order and its separate active-owner check. The
  [active-owner query](../../backend/src/main/java/bg/spotyourslot/identity/application/ActiveBusinessOwnerQueryService.java)
  owns the Membership `FOR SHARE` lock.
- [BusinessStore integration tests](../../backend/src/test/java/bg/spotyourslot/business/infrastructure/BusinessStoreIntegrationTests.java)
  verify conditional persistence, exact version increments, status predicates,
  and same-version races on separate PostgreSQL connections.
  [Business administration integration tests](../../backend/src/test/java/bg/spotyourslot/business/application/BusinessAdministrationServiceIntegrationTests.java)
  verify one application-level success and one conflict for concurrent profile
  and lifecycle writes. [Platform Business API tests](../../backend/src/test/java/bg/spotyourslot/integration/PlatformBusinessApiIntegrationTests.java)
  verify the public 409 contract. These tests prove current bounded behavior,
  not every production workload.
- The current [data model](../data-model.md), [architecture](../architecture.md),
  [security design](../security.md), and [testing strategy](../testing-strategy.md)
  describe the implemented version, transaction, owner-lock, error, and test
  boundaries.
- The [platform onboarding frontend task](../tasks/03b-platform-admin-onboarding-frontend.md)
  requires issue #6 mutation flows to send the displayed `expectedVersion`, use
  successful response versions, and offer reload rather than silent overwrite.
  Those later mutation screens remain pending.

## Retrospective inference

The comparison with last-write-wins, pessimistic Business-row locking,
serializable isolation, and distributed locks is later analysis. The repository
does not show that those alternatives were historically evaluated before the
Phase 3A choice.

Optimistic concurrency is a reasonable fit when Business administration has
limited contention and human edit intervals make long-held database locks
impractical. That assessment is contextual and forward-looking; current tests
do not prove the expected production contention level or that optimistic
concurrency will remain preferable at every scale.

## Conditions for revisiting

Revisit this decision if measured conflicts are frequent, operations span
several aggregates, safe automatic merging becomes possible, clients need
standard HTTP cache preconditions, or correctness requires state to remain
locked across a transaction rather than merely detecting change at mutation.

Any revision must define the client precondition contract, atomic database
guard, authorization and lifecycle interaction, retry/idempotency behavior,
conflict UX, migration of existing versions, and deterministic concurrent
verification. It must not restore silent lost updates as an accidental default.
