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
  before implementation.
- Work in small, reviewable phases from `docs/implementation-plan.md`.
- Obtain explicit approval before persistent changes, dependency changes,
  generated files, external writes, Git mutations, or state-changing checks.
- Inspect existing code and tests before proposing a change. Keep changes
  focused and preserve unrelated user work.
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

## Verification and handoff

- Run the narrowest relevant checks first, then justified broader checks.
- Use real PostgreSQL through Testcontainers for persistence, concurrency, and
  tenant-isolation behavior.
- Review the final diff for naming, scope, security, and accidental files.
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
