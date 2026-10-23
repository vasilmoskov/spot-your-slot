# Documentation index

The [project README](../README.md) is the best starting point: what SpotYourSlot does, how it is built and what it does
not do. These documents go deeper. Current-reference documents describe the system as it is; records and ADRs are
dated history and are kept as written.

## Getting started and development

- [Development guide](development.md): local setup, configuration, first administrator, checks, browser E2E operation,
  cleanup and troubleshooting.
- [API route reference](api.md): every HTTP route with its purpose and conventions.

## Architecture, data, security and testing (current reference)

- [Architecture](architecture.md): modular monolith, modules, time and availability, conflict-safe booking, frontend,
  planned and not implemented.
- [Data model](data-model.md): the implemented schema by migration, constraints, and planned tables.
- [Security and privacy](security.md): authentication, browser controls, authorization and tenant isolation per area,
  public endpoints, abuse protection, privacy.
- [Testing strategy](testing-strategy.md): principles, layers, mandatory high-risk tests, current suites, real versus
  proxy evidence.
- [UI design guidelines](ui-design-guidelines.md): interaction, layout, accessibility and table rules for the Bulgarian
  interface.

## Product and business requirements

- [Product specification](product-spec.md): the approved MVP scope and requirements.
- [Product roadmap](product-roadmap.md): the business capabilities and their status.
- [Implementation plan](implementation-plan.md): the technical sequence of work and its amendments.

## Decisions and historical records

- [Architecture decision records](decisions/README.md): ADR-0001 to ADR-0029, the reasoning and alternatives behind
  significant decisions.
- [Task records](tasks/): one dated record per delivered capability, with decisions, phases, evidence and deviations
  (for example [Business calendar and Appointment management](tasks/09a-business-calendar-and-appointment-management.md)).
- [Verification records](testing-records.md): dated verification evidence by capability.

## Working agreements

[`AGENTS.md`](../AGENTS.md) and [`CLAUDE.md`](../CLAUDE.md) in the repository root are the working instructions used
while developing this repository with an AI coding assistant, including the delivery workflow and risk-based approval
rules.
