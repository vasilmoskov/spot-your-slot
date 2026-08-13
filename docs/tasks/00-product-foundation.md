# SpotYourSlot — Product Foundation

Status: Completed
Approved: 2026-08-13

## Task purpose and boundary

Define the complete product and technical foundation for SpotYourSlot before the
first foundation commit. This task is documentation-only. Update the original
foundation rather than creating a second task. Do not create application code,
backend/frontend directories, manifests, dependencies, generated projects,
Docker, CI, hosting configuration, external resources, commits, or pushes.

## Product identity

- Product: SpotYourSlot; human-readable brand: Spot Your Slot.
- Preferred meaning: “Find the available time that works for you.”
- Repository and local directory: `spot-your-slot`.
- Java base package: `bg.spotyourslot`; development database: `spotyourslot`.
- Example origin: `https://spotyourslot.bg`.
- Public URL: `https://spotyourslot.bg/{businessSlug}`.
- Local example: `http://localhost:5173/{businessSlug}`.
- Default timezone: `Europe/Sofia`; MVP currency: `EUR`.
- Bulgarian user-facing MVP language; English source identifiers and technical
  documentation.

The domain is an unregistered/unconfigured example for this task. Domain and
trademark availability have not been legally verified. Non-binding marketing
possibilities—“Find your time. Book your slot.”, “See it. Spot it. Book it.”,
and “Your time. Your slot.”—must not expand the MVP.

## Product goal and generic scope

Build a reliable mobile-first, multi-tenant SaaS appointment platform for small
businesses providing individual services of known duration. Reduce calls and
messages, show real availability, prevent overlaps, provide daily and weekly
administrative calendar views, accept staff-entered phone/in-person
Appointments, allow secure guest cancellation, and notify Customers and
Business users.

The shared MVP supports hair salons, barbershops, nail studios/manicurists,
massage studios/therapists, makeup artists, beauty/cosmetic studios, and other
small individual appointment businesses. A first pilot may be hair/barber, but
the domain, schema, API, authorization, URLs, and core UI stay generic. One
application/database/deployment serves all Businesses with strict isolation.

`BusinessType` is descriptive configuration: `HAIR_SALON`, `BARBERSHOP`,
`NAIL_STUDIO`, `MASSAGE_STUDIO`, `MAKEUP_STUDIO`, `BEAUTY_STUDIO`, `OTHER`. It
may inform platform administration, appropriate Bulgarian labels/presentation,
future analysis, or controlled customization. It never creates separate booking
engines, schemas, deployments, professional branches, or speculative layers.

Every type shares Services, StaffMembers, schedules, availability, Customers,
Appointments, cancellations, notifications, roles, and isolation.

## Tenant, users, and roles

Each Business has one globally unique normalized slug and its own profile,
StaffMembers, Services, schedules, Customers, Appointments, and Memberships.
Every tenant record/query uses `business_id`. Server-resolved Membership or
public slug establishes context; a client Business ID is never trusted alone.

- **PLATFORM_ADMIN:** create/list/edit Business, assign/change slug, invite the
  first BUSINESS_OWNER, activate/suspend/reactivate, and inspect minimal support
  metadata without credentials/raw tokens or routine Customer content.
- **BUSINESS_OWNER:** manage all Business configuration and operational data.
- **MANAGER:** manage Services, StaffMember schedules, Customers, Appointments,
  and calendars, excluding platform settings.
- **STAFF:** manage the linked StaffMember's schedule/Appointments under least
  privilege, manually create Appointments, and mark complete/no-show.
- **Customer:** unauthenticated guest; no Customer account in MVP.

A StaffMember may exist without an application user. Optionally, one StaffMember
links to at most one Membership and one Membership to at most one StaffMember;
both must have the same `business_id`.

## Onboarding, page, and lifecycle

No public Business self-registration exists. PLATFORM_ADMIN creates a DRAFT
Business/slug, sends the first owner a secure expiring single-use invitation,
and later activates it after the owner configures StaffMembers, Services, and
schedules. Raw invitation/reset tokens are not stored.

- DRAFT allows authorized configuration but rejects public booking.
- ACTIVE allows public booking and authorized administration.
- SUSPENDED preserves data, rejects public booking, and permits read-only
  Business administration except logout/account-security; PLATFORM_ADMIN may
  reactivate it.

An ACTIVE public page shows generic Business/contact/working details, active
Services with EUR price/duration, available qualified team members, and “Запази
час”. DRAFT/SUSPENDED show a clear Bulgarian unavailable message.

## Booking and availability

The guest opens the Business URL, selects a Service, answers “При кого искаш да
запазиш час?” by selecting a person or “Без предпочитание”, selects date/time,
provides name/phone/email/optional note, accepts privacy/cancellation
information, and receives a Bulgarian confirmation, email, and secure
cancellation link. Public booking does not require one generic performer noun.
Generic Bulgarian administration uses “Екип” and “Член на екипа”, while
`StaffMember` remains the internal English domain and technical term.

Services have name/description, `BigDecimal` price, duration, buffer, active
state, and qualified StaffMembers. StaffMembers have display name, active state,
Services, weekly intervals, breaks, one-time time off, and working overrides.

Availability uses Business/StaffMember state, qualification, schedule, breaks,
time off/overrides, existing Appointments, Service duration/buffer, notice,
window, and Business timezone. Full duration plus buffer must fit. For no
preference, choose the available qualified StaffMember with fewest non-cancelled
Appointments that local day, then creation time and ID. Revalidate in the
booking transaction. Store Appointment instants in UTC and handle Sofia DST
gaps/overlaps explicitly.

Each Business configures how many days in advance Customers may book. The
default booking window remains 30 days. Daily and weekly administrative
calendar views are display/query modes only and must not limit that booking
horizon.

PostgreSQL and backend prevent double booking. A GiST exclusion constraint uses
`staff_member_id` plus half-open UTC occupied range for `CONFIRMED` Appointments.
Concurrent overlap yields exactly one success; the loser receives HTTP 409 with
a Bulgarian retry message.

## Appointments, Customers, and notifications

Support online/staff creation, authorized edit/reschedule, Customer/staff
cancellation, distinct Customer booking/private staff notes, source, status,
audit history, and no physical deletion in normal operations. All successful
Appointments are automatically `CONFIRMED`; statuses are `CONFIRMED`,
`CANCELLED_BY_CUSTOMER`, `CANCELLED_BY_BUSINESS`, `COMPLETED`, `NO_SHOW`.
Complete/no-show is allowed only at or after start.

Customer cancellation uses a secure token stored only as a hash. Future
Appointments may be cancelled after the deadline and marked late; past ones may
not. Customers belong to one Business, with name/phone/email, private staff note,
active/blocked state, and derived history. Match conservatively within the same
Business; never across Businesses. Do not collect health/special-category data.

Future `EmailService` events cover invitations/account flows, confirmation,
cancellation, changes, roughly 24-hour reminders, and new online booking. A
transactional outbox, delivery records, bounded retry, and idempotency prevent
Appointment rollback and duplicate reminders. Development/tests never send;
Resend is only a separately approved production proposal. SMS is excluded.

## Administration

The mobile-friendly Business UI covers authentication, daily and weekly
calendar views, manual Appointment operations,
Service/StaffMember/schedule/break/time-off
management, Customers/history, and Business settings. The platform UI covers
only protected onboarding and lifecycle operations. Forms are accessible,
keyboard-operable, validated, and Bulgarian-facing.

## Technology and exact-version policy

The planned modular monolith uses Java 25 LTS, Spring Boot/Maven, REST, Spring
Web/Data JPA/Security/Validation, Flyway, PostgreSQL, Testcontainers, and a React/
TypeScript/Vite frontend. It remains one monorepo and one generic system.

Exact versions are deliberately deferred to bootstrap. Bootstrap must:

1. verify official primary documentation;
2. choose latest stable GA Spring Boot officially supporting Java 25;
3. choose a stable Maven supported by that toolchain;
4. choose latest suitable Node.js LTS and compatible stable React,
   TypeScript, and Vite production releases;
5. choose latest stable PostgreSQL major supported by local Docker and the
   approved production host;
6. use only stable mutually compatible libraries absent separate approval;
7. record exact versions and compatibility reasoning;
8. pin direct dependencies/tools and Docker tags, commit generated lockfiles,
   and keep upgrades controlled/reviewable.

Do not use alpha, beta, milestone, release-candidate, snapshot, preview, or
experimental dependencies without separate approval. Do not select or install
exact versions in this documentation task.

## Testing and security requirements

Use cookie sessions with CSRF, exact-origin CORS, adaptive password hashing,
hashed expiring single-use tokens, rate limits, safe errors, least privilege,
redacted logs, secret separation, and tenant-scoped repositories/constraints.
Technical measures do not alone guarantee GDPR compliance.

Use JUnit, Spring integration tests, real PostgreSQL Testcontainers, frontend
component tests, and Playwright. Mandatory tests include Business A/B denial,
same-Business StaffMember/Membership constraints, all BusinessTypes using one
engine, DST/time boundaries, lifecycle rules, token/session security, provider
failure, reminder idempotency, and two concurrent overlaps producing one success.

## Explicit MVP exclusions

Exclude medical records/workflows, healthcare questionnaires, group classes or
capacity, room/chair/equipment/resource scheduling, recurring Customer
Appointments, home-visit travel-time calculations, multi-location Businesses,
industry-specific forms/fields/workflows, marketplace discovery, custom
professional workflows, Customer accounts, social login, SMS, payments,
deposits, reviews, promotions, loyalty/subscriptions, inventory, accounting,
native apps, custom domains, product AI, complex analytics, microservices,
Kubernetes, Kafka, and Redis without demonstrated need/approval.

## Deliverables and completion

Update `AGENTS.md`, `README.md`, and the existing product, architecture, data,
security, testing, implementation-plan, and foundation Markdown documents. Keep
them internally consistent; create no second foundation task.

Review every Markdown file and search for obsolete identity/terminology. Report
all modified files, scope/terminology/version policy, remaining decisions, and
any intentional obsolete-term occurrence. Verify no application/configuration/
dependency/hosting/Git state was created. Do not commit or push. Stop for human
review.
