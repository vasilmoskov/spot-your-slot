# SpotYourSlot

SpotYourSlot is a planned mobile-first, multi-tenant SaaS appointment-booking
platform for small appointment-based service businesses. It supports hair and
beauty businesses, massage practitioners, makeup artists, and other small
businesses that provide individual services with known durations through one
shared generic booking model.

Each Business has isolated data and one public page at
`https://spotyourslot.bg/{businessSlug}`; locally the equivalent example is
`http://localhost:5173/{businessSlug}`. The production domain is illustrative:
it is not registered or configured by this task, and neither domain nor
trademark availability has been legally verified.

This repository is in the **documentation-only product-foundation phase**. No
Spring Boot or React application, dependency manifest, Docker/CI/hosting
configuration, or external resource has been created.

## Product identity

| Item | Value |
|---|---|
| Product | SpotYourSlot |
| Human-readable brand form | Spot Your Slot |
| Preferred meaning | “Find the available time that works for you.” |
| Repository/local directory | `spot-your-slot` |
| Java base package | `bg.spotyourslot` |
| Development database | `spotyourslot` |
| Example production origin | `https://spotyourslot.bg` |
| Public Business URL | `https://spotyourslot.bg/{businessSlug}` |
| Local public URL | `http://localhost:5173/{businessSlug}` |
| Default timezone | `Europe/Sofia` |
| MVP currency | `EUR` |
| User-facing MVP language | Bulgarian |
| Source identifiers and technical documentation | English |

Non-binding future marketing possibilities are “Find your time. Book your
slot.”, “See it. Spot it. Book it.”, and “Your time. Your slot.” They do not
expand the MVP.

## Documentation

- [Product specification](docs/product-spec.md)
- [Architecture](docs/architecture.md)
- [Data model](docs/data-model.md)
- [Security](docs/security.md)
- [Testing strategy](docs/testing-strategy.md)
- [Implementation plan](docs/implementation-plan.md)
- [Foundation task](docs/tasks/00-product-foundation.md)

## Planned technology and version policy

The planned monorepo uses Java 25 LTS, a Spring Boot/Maven modular-monolith REST
API, React/TypeScript/Vite, and PostgreSQL. Bootstrap must verify official
primary documentation and select the latest stable GA Spring Boot release that
officially supports Java 25, a supported stable Maven release, the latest
suitable Node.js LTS, compatible stable React/TypeScript/Vite releases, and the
latest stable PostgreSQL major supported by both local Docker and the approved
host. Other libraries must be stable and mutually compatible—never alpha, beta,
milestone, release-candidate, snapshot, preview, or experimental without
separate approval.

Bootstrap records exact versions and compatibility reasoning, pins direct
dependencies/tools and Docker image versions, commits generated lockfiles, and
keeps upgrades controlled. Exact versions are intentionally not selected or
installed during this documentation-only task.

Production hosting remains an unconfigured proposal: Cloudflare Pages, a paid
Render service and PostgreSQL, and Resend.

## Planned repository layout

```text
spot-your-slot/
├── backend/                 # future phase
├── frontend/                # future phase
├── docs/
│   ├── tasks/
│   ├── product-spec.md
│   ├── architecture.md
│   ├── data-model.md
│   ├── security.md
│   ├── testing-strategy.md
│   └── implementation-plan.md
├── .github/workflows/       # future phase
├── compose.yaml             # future phase
├── AGENTS.md
├── README.md
└── .gitignore               # future phase
```

## Current scope boundary

The MVP uses one booking engine for `HAIR_SALON`, `BARBERSHOP`, `NAIL_STUDIO`,
`MASSAGE_STUDIO`, `MAKEUP_STUDIO`, `BEAUTY_STUDIO`, and `OTHER`. BusinessType is
descriptive only. Medical/health workflows, groups, shared-resource scheduling,
recurrence, travel-time calculation, multiple locations, industry-specific
forms/workflows, and marketplace discovery are explicitly excluded alongside
the broader exclusions in the product specification.
