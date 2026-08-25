# ADR-0001: Use a modular monolith

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-08-12
- **Recorded date:** 2026-08-25
- **Related issues:** #1, #2, #3, #4, #5, #8
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

SpotYourSlot needed an architecture for one generic appointment-booking product
whose identity, tenancy, Business administration, workforce, scheduling,
booking, customer, notification, and audit concerns would evolve in phases. The
architecture needed to keep those concerns understandable without requiring a
separate deployable system for each concern or profession.

The product foundation chose one Spring Boot API, one React SPA, and one shared
PostgreSQL database while defining named backend modules and rules for their
interaction. This record explains that choice retrospectively; it does not claim
that it was the only viable architecture.

## Constraints

Direct repository evidence establishes these constraints at the decision date:

- the MVP was one generic product for several appointment-based industries;
- delivery was divided into small phases, beginning with a single repository
  bootstrap and adding identity, tenancy, and platform onboarding incrementally;
- module responsibilities and encapsulation rules were defined before backend
  implementation;
- production hosting and distributed operational infrastructure were not yet
  configured; and
- microservices, Kubernetes, and Kafka were outside the approved MVP scope.

The repository does not establish a team size, so team-size assumptions are not
part of this decision record.

## Options considered

### Convention-only layered monolith

One deployable application organized mainly by technical layers would have been
the simplest starting structure. It would require little boundary tooling, keep
local debugging straightforward, and allow rapid movement across layers. Its
main risk was that domain ownership and permitted dependencies would remain
informal, making coupling and cross-feature changes harder to control as the
product grew.

### Modular monolith with explicitly verified boundaries

One deployable application divided into domain-oriented modules retains simple
in-process execution while making ownership and allowed dependencies explicit.
Published contracts, internal packages, and structural verification can expose
cycles or forbidden dependencies during development. This approach requires
ongoing design discipline and boundary tests; modules still share one process,
deployment, and failure domain.

### Independently deployed microservices

Separate services can provide independent deployment, scaling, failure
containment, and technology or ownership autonomy where those benefits are
needed. They also introduce network failure modes, distributed observability,
deployment coordination, data-ownership decisions, and cross-service
consistency work. Those costs were not justified by the documented MVP scope
and delivery stage, but microservices are not inherently inferior and may become
appropriate under different constraints.

## Decision

Use a modular monolith for the current product stage: one Spring Boot deployable
with domain-oriented modules below `bg.spotyourslot`, one React application, and
one shared PostgreSQL database.

The backend is modular rather than merely monolithic because modules have named
responsibilities, expose intended cross-module contracts from their base
packages, keep application/domain/infrastructure details internal, and prohibit
cycles and unapproved dependencies. Platform orchestration, for example, uses
published Business and identity contracts instead of importing their
infrastructure.

Spring Modulith verifies structural module rules in tests. It does not create
separate processes, networks, deployments, data stores, or failure isolation,
and it cannot replace careful API design and code review.

## Rationale

This choice fits the evidenced foundation: one generic MVP, phased delivery,
predefined domain boundaries, and no approved distributed operating platform.
It preserves a path to reason about modules independently without introducing
distributed-system concerns before the product demonstrates a need for them.

Shared-process calls are direct and avoid network serialization and partial
network failure between current modules. Shared PostgreSQL transactions can
preserve atomic consistency across module orchestration where required. These
are present-day benefits and consequences; repository evidence does not prove
that they were the original motivation for the 2026-08-12 decision.

## Tradeoffs and disadvantages

- All modules are released and deployed together.
- A process failure or resource exhaustion can affect the complete backend.
- A shared database limits independent data-store ownership and scaling.
- Structural boundaries depend on package design, published contracts, tests,
  review, and continued discipline.
- Independent scaling of one module is limited to scaling the whole backend or
  redesigning that boundary.
- In-process access can make boundary violations tempting because they are
  technically easier than a network call.

## Risks and mitigations

The primary risk is erosion into a tightly coupled monolith. Mitigate it with
domain-oriented packages, narrow published interfaces, module-internal
infrastructure, application-owned transaction boundaries, Spring Modulith
verification, and explicit boundary review during substantial phases.

Another risk is treating the current topology as permanent. Keep module
contracts focused, measure operational behavior, and revisit the decision when
product or scaling evidence changes. These practices can reduce extraction
risk, but they do not make future service extraction automatic or effortless.

## Consequences

New capabilities should enter the appropriate domain module rather than a
generic shared layer. Cross-module work should use published interfaces or
events and an explicit orchestration owner; modules must not reach into another
module's infrastructure. Transactions may span current modules when one atomic
business operation requires it, with the coupling documented and reviewed.

The application remains one backend deployment and one shared operational unit.
Independent deployment or failure isolation requires a later architectural
change rather than being supplied by the module framework.

## Evidence

### Direct historical evidence

- The original [architecture](../architecture.md) defines the deployable modular
  monolith, module responsibilities, shared PostgreSQL database, encapsulation,
  and published-interface approach. Git commit `9cf10e0` first recorded it on
  2026-08-12 for issue #1.
- The [product foundation task](../tasks/00-product-foundation.md) calls the
  planned system a modular monolith and excludes microservices from the MVP.
- The [implementation plan](../implementation-plan.md) defines phased delivery
  and module-boundary verification.
- The [repository bootstrap task](../tasks/01-repository-bootstrap.md) requires
  a modular-monolith-friendly package structure; issue #2 created one backend
  application and build.
- The [identity and tenancy task](../tasks/02-identity-and-tenancy.md) requires
  clear modules, encapsulation, no cyclic dependencies, and compatible Spring
  Modulith verification. Issue #3 added the module roots and verification.
- The [platform Business backend task](../tasks/03a-platform-business-backend.md)
  preserves module boundaries and issue #5 implemented platform orchestration
  through published Business and identity interfaces under parent issue #4.
- The [Maven configuration](../../backend/pom.xml) identifies the backend as a
  modular-monolith application shell and includes Spring Modulith as a test
  dependency.
- The [module-boundary test](../../backend/src/test/java/bg/spotyourslot/architecture/ModuleBoundaryTests.java)
  runs Spring Modulith structural verification. It proves the current checked
  structure, not the historical motivation for choosing it.
- Current module descriptions include the
  [Business](../../backend/src/main/java/bg/spotyourslot/business/package-info.java),
  [identity](../../backend/src/main/java/bg/spotyourslot/identity/package-info.java),
  and [shared](../../backend/src/main/java/bg/spotyourslot/shared/package-info.java)
  roots.

### Retrospective inference

Given the documented MVP scope, phased delivery, single application, and
unconfigured production hosting, avoiding distributed operational complexity
was consistent with the project stage. That interpretation is plausible but is
not stated as the original reasoning in the 2026-08-12 evidence.

The current published interfaces and shared transactions demonstrate how the
decision has been implemented. Their success today does not prove why the
original choice was made or guarantee that the same topology will remain best.

## Conditions for revisiting

Reconsider this decision when evidence shows that a module needs materially
independent scaling, deployment cadence, availability isolation, security or
regulatory isolation, data ownership, or technology constraints that the shared
application cannot meet acceptably. Also revisit it when organizational
ownership or operational measurements show that coordinated monolith releases
are a persistent bottleneck.

Any extraction proposal must define the new data owner, consistency model,
failure handling, observability, deployment operation, and migration path. This
is forward-looking guidance, not a claim that extraction will be simple.
