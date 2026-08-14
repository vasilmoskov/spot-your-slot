# SpotYourSlot working agreements

## Product constraints

- The product is **SpotYourSlot** and the repository and local project directory
  are `spot-your-slot`.
- Use Java base package `bg.spotyourslot` and development database `spotyourslot`.
- The example production origin is `https://spotyourslot.bg`; public business
  pages use `https://spotyourslot.bg/{businessSlug}`. The domain is an example
  only and is not registered or configured by the foundation work; domain and
  trademark availability have not been legally verified.
- User-facing MVP text is Bulgarian. Source code, identifiers, technical
  documentation, and the PascalCase product name `SpotYourSlot` are English.
- SpotYourSlot is one multi-tenant modular monolith for small individual
  appointment-based service businesses. Preserve strict business isolation.
- Java 25 LTS is required. Exact Spring Boot, Maven, Node.js LTS, React,
  TypeScript, Vite, PostgreSQL, and library versions are selected and pinned
  during bootstrap after official compatibility verification.

## Delivery rules

- Read the applicable task in `docs/tasks/` and the permanent documentation
  before implementation. Permanent documentation remains authoritative even
  when a task file or prompt is intentionally brief.
- Work in small, independently reviewable phases from
  `docs/implementation-plan.md`. When an approved task contains multiple
  substantial concerns, propose a short ordered sequence, complete and verify
  one step, and only then begin the next.
- Obtain explicit approval before persistent changes, dependency changes,
  generated files, external writes, Git mutations, or state-changing checks.
- Inspect existing code and tests before proposing a change. Keep changes
  focused and preserve unrelated user work.
- Do not expand into a later phase or adjacent concern for convenience. If work
  reveals a conflict, missing decision, unexpected dependency, or necessary
  scope expansion, stop and request approval.
- Never commit credentials, production tokens, or personal data. Do not log
  passwords, raw security tokens, session identifiers, or customer notes.
- Do not weaken authentication, authorization, tenant checks, validation, or
  tests to make work pass.
- Use Flyway migrations from the first database change. Never edit an applied
  migration.
- Use `BigDecimal` for EUR amounts and UTC instants for appointments; interpret
  and display schedules in the Business timezone, default `Europe/Sofia`.
- Treat the backend and PostgreSQL as the source of truth for availability.
- Every business-owned query and mutation must derive or validate the Business
  from authenticated Membership; never trust a client-supplied business ID.
- Preserve `StaffMember` as the internal English domain/technical term. In
  generic Bulgarian administration use “Екип” and “Член на екипа”; in public
  booking prefer contextual wording such as “При кого искаш да запазиш час?”
  and the option “Без предпочитание”, rather than a mandatory performer noun.
- A Business configures how many days in advance Customers may book; the default
  booking window is 30 days. Daily and weekly administrative calendar views are
  display modes only and never limit how far ahead booking is possible.
- Normal business operations never physically delete appointments.

## Code quality and formatting

- Write conventional, readable Java and TypeScript with consistent indentation,
  line wrapping, and organized imports. Avoid wildcard imports in new or
  modified code.
- Do not compress classes, methods, constructors, records, annotations, field
  declarations, or tests onto single lines. Keep one field declaration per
  readable statement.
- Prefer focused classes and methods with clear responsibilities over large
  services or controllers.
- Review formatting and readability before completion. Formatting-only changes
  must preserve behavior and remain separate from unrelated refactoring.

## Security and state management

- Never return arbitrary internal exception messages, SQL details, stack traces,
  or secret values to API clients. Use stable public error codes and safe
  Bulgarian client messages.
- In-memory state reachable from public requests requires an explicit capacity
  limit, expiration cleanup, and documented behavior at saturation. Document
  when an in-process mechanism is unsuitable for multiple instances.
- Never retain raw passwords, session tokens, invitation tokens, or reset tokens
  in rate-limiter keys, logs, or diagnostic state.
- Protect security-sensitive replacement and single-use flows with both database
  constraints and transactional application logic where applicable.

## Verification and handoff

- Run the narrowest relevant checks first, then justified broader checks.
- Use real PostgreSQL through Testcontainers for persistence, concurrency, and
  tenant-isolation behavior.
- Map every acceptance criterion to a concrete test or explicitly documented
  manual verification. A successful build or broad suite alone is not evidence
  for every behavior; name the tests or commands proving important security,
  tenant, lifecycle, and concurrency claims.
- Use real PostgreSQL integration tests when persistence constraints, tenant
  isolation, or concurrency depend on database behavior. Coordinate concurrent
  operations deterministically without arbitrary sleeps, and use controllable
  clocks for expiration and lifecycle tests where practical.
- Never weaken, delete, or generalize a valid assertion merely to make a test
  pass. Distinguish checks actually executed from recommendations or deferred
  checks.
- Before completion, compare the implementation with the task scope and every
  acceptance criterion. Review the final diff for naming, scope, security,
  accidental files, generated output, secrets, compressed formatting, and
  later-phase functionality.
- Report exact test counts only when supported by executed output. Do not call
  partially implemented or indirectly tested behavior complete; state remaining
  limitations and operational tradeoffs explicitly.
- Report files changed, checks run, limitations, assumptions, and decisions
  still requiring approval. Do not commit or push unless explicitly approved.

## MVP boundaries

All approved business types share one generic booking model. Do not introduce
industry-specific workflows. Exclude customer accounts, social login, SMS,
payments, deposits, discovery, reviews, promotions, loyalty, customer
subscriptions, inventory, accounting, medical records or workflows, healthcare
questionnaires, group classes/capacity, resource scheduling, recurring customer
appointments, home-visit travel-time calculations, native apps, multi-location
businesses, custom domains, custom professional workflows/forms/fields, product
AI, complex analytics, microservices, Kubernetes, and Kafka. Redis requires a
demonstrated need and separate approval.
