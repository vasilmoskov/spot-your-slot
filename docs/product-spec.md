# SpotYourSlot MVP product specification

## Purpose and success criteria

SpotYourSlot reduces booking calls and messages, exposes genuine availability,
prevents double booking, and gives Business teams mobile-friendly daily and
weekly administrative calendar views. It is one multi-tenant SaaS for small
businesses providing individual appointment-based services with known durations.

The shared MVP supports hair salons, barbershops, nail studios/manicurists,
massage studios/therapists, makeup artists, beauty/cosmetic studios, and similar
small businesses. A first pilot may be a hair salon or barbershop, but the domain
model, database, API, security, URLs, and core UI remain generic.

Success means a Business can configure StaffMembers, Services, and schedules; a
guest can book and cancel safely; authorized users can manage appointments;
notifications are reliable; tenant data is isolated; and PLATFORM_ADMIN can
activate or suspend Businesses.

## Identity, tenancy, and branding

SpotYourSlot is one shared application and database, not a deployment or schema
per Business. Each Business has a globally unique normalized slug and public URL
`https://spotyourslot.bg/{businessSlug}`; local example:
`http://localhost:5173/{businessSlug}`. The example domain is not registered or
configured by this work, and domain/trademark availability is not legally
verified.

The default timezone is `Europe/Sofia`, MVP currency is `EUR`, user-facing text
is Bulgarian, and identifiers/technical documentation are English. The
human-readable brand form “Spot Your Slot” may be used where appropriate; the
preferred meaning is “Find the available time that works for you.” Marketing
phrases are non-binding and cannot expand scope.

## Business classification

`BusinessType` is one of `HAIR_SALON`, `BARBERSHOP`, `NAIL_STUDIO`,
`MASSAGE_STUDIO`, `MAKEUP_STUDIO`, `BEAUTY_STUDIO`, or `OTHER`. It is descriptive
configuration for platform administration, suitable Bulgarian presentation,
future analysis, or future controlled customization. It does not select a
booking engine, schema, deployment, industry branch, or speculative abstraction.

Every type uses the same Services, StaffMembers, schedules, availability,
Customers, Appointments, cancellations, notifications, roles, and tenant model.

## Personas and permissions

- **PLATFORM_ADMIN:** creates/lists Businesses, edits basic details and slugs,
  invites the first owner, activates/suspends Businesses, and sees minimal
  support metadata—not passwords, raw tokens, or routine Customer content.
- **BUSINESS_OWNER:** manages settings, Services, StaffMembers, schedules,
  Appointments, Customers, and Memberships within an authorized Business.
- **MANAGER:** manages operations, Services, Customers, Appointments, and
  StaffMember schedules, but not platform-level settings.
- **STAFF:** sees and manages Appointments and schedule belonging to the linked
  StaffMember, creates manual Appointments, and marks them complete/no-show.
- **Customer:** books as an unauthenticated guest; no Customer account exists.

Users receive tenant roles only through explicit Business Membership. A user
may hold Memberships in multiple Businesses; authorization is evaluated in the
server-resolved Business context, never inferred from a client Business ID.

The currently implemented Business Services and StaffMember backends are
intentionally narrower: only an active `BUSINESS_OWNER` Membership for the
selected Business grants Service, StaffMember, and Service-assignment
administration. `MANAGER` authority remains a future product direction and is
not currently implemented. A platform administrator who also has the qualifying
owner Membership acts through that Membership; the platform role alone grants
no private Business configuration access.

A StaffMember exists independently of an application account. The implemented
schema has no StaffMember-to-user or Membership link, credentials, invitation,
role, or session state. A future approved capability may add an optional
same-Business one-to-one StaffMember/Membership link without changing the
operational StaffMember identity.

## Onboarding and lifecycle

There is no public Business self-registration. PLATFORM_ADMIN creates the
Business and unique slug, enters the first owner email, sends a secure expiring
single-use invitation, and activates the BUSINESS_OWNER Membership after
password setup. The owner configures the Business before PLATFORM_ADMIN
activates it. Normal users cannot elevate themselves.

- **DRAFT:** administrative configuration is allowed; public booking is not.
- **ACTIVE:** public booking and authorized administration are allowed.
- **SUSPENDED:** data is preserved and public booking rejected; Business
  administration is read-only except logout and account-security actions.
  PLATFORM_ADMIN may reactivate it.

## Public page and booking flow

An ACTIVE Business page shows its generic profile, contact/working information,
active Services with price/duration, and available qualified team members. The
Bulgarian mobile-first flow is:

1. select a Service;
2. answer “При кого искаш да запазиш час?” by selecting a person or “Без
   предпочитание”;
3. select an available date and time;
4. provide name, phone, email, and optional booking note;
5. accept privacy information and cancellation rules;
6. submit and receive a confirmation page, email, and secure cancellation link.

Each Business has one unique slug/URL. The backend revalidates availability in
the booking transaction. A lost race returns HTTP 409 with a safe Bulgarian
message asking the Customer to choose another time.

## Business settings and Services

A Business stores `BusinessType`, slug, name, description, optional structured
address fields (city, postal code, street, street number, and additional
details), phone, email, timezone (default `Europe/Sofia`), booking window
(default 30 days), minimum notice (default two hours), cancellation window
(default 24 hours), currency (`EUR`), and status. The current MVP address
context is fixed to Bulgaria; multiple locations and country selection remain
outside scope.

The booking window is the Business-configurable number of days in advance that
Customers may book; its default remains 30 days. It is independent of the daily
and weekly administrative calendar views. Those views only change how Business
users inspect and manage Appointments and never cap the booking horizon.

The implemented Service backend stores a canonical display name, optional
canonical description, `BigDecimal` EUR price, duration, active state,
optimistic version, and creation/update timestamps under immutable Business
ownership. Service buffers remain future availability behavior and are not
fields of the current Service record.

The implemented Workforce backend stores Business-scoped StaffMembers with a
canonical display name, optional canonical contact email and phone, active
state, one optimistic aggregate version, and creation/update timestamps. New
StaffMembers are active. Active and inactive StaffMembers remain visible and
administratively configurable: their profile may be edited and their complete
desired Service-assignment set may be replaced. Deactivation preserves profile
data and assignments, and there is no hard-delete operation.

Only active Services may be added to an assignment set. An already assigned
inactive Service may be retained or removed; after removal it cannot be restored
until reactivated. Service deactivation preserves existing assignment rows.
Each successful complete-set replacement, including a same-set replacement,
increments the shared StaffMember version once. Weekly working intervals,
breaks, time off, and working overrides remain future capabilities.

## Availability and assignment

Availability considers Business and StaffMember state, qualification, Service
duration plus buffer, weekly intervals, breaks, overrides, absences, blocking
Appointments, notice, booking window, and Business timezone. The complete
duration and buffer must fit one available interval.

For “Без предпочитание”, select among qualified available StaffMembers using
the fewest non-cancelled Appointments on the local date, then creation time and
ID. Recalculate during booking. Store actual instants in UTC; interpret recurring
schedules as local wall time. DST gaps produce no slot; repeated times map to
distinct UTC instants and are visibly disambiguated.

## Appointment and Customer lifecycle

Online and authorized staff-created Appointments support editing, rescheduling,
Customer/staff cancellation, separate Customer booking and private staff notes,
source, status, and audit history. Every successful Appointment is automatically
`CONFIRMED`. MVP statuses are `CONFIRMED`, `CANCELLED_BY_CUSTOMER`,
`CANCELLED_BY_BUSINESS`, `COMPLETED`, and `NO_SHOW`. `COMPLETED`/`NO_SHOW` are
allowed only at or after start. Normal operations never physically delete an
Appointment.

Customer cancellation uses a secure random link while only its hash is stored.
A future Appointment may be cancelled after the deadline but is marked late;
past Appointments cannot be Customer-cancelled. Staff cancellation may include
a reason.

A Business-owned Customer stores name, phone, email, private staff note,
active/blocked state, and Appointment-derived history. Match conservatively
within one Business by normalized phone, then normalized email; ambiguous cases
remain separate. Never merge across Businesses. Do not solicit health or other
special-category data.

## Administration and notifications

The responsive Business administration UI includes authentication, daily and
weekly calendar views, manual Appointment operations, Service and StaffMember
management, schedules/breaks/time off, Customers/history, and Business settings.
Generic Bulgarian administration uses “Екип” and “Член на екипа”. Public
booking uses natural contextual wording such as “При кого искаш да запазиш
час?” and “Без предпочитание”; it does not require one generic performer noun.
`StaffMember` remains the internal English domain and technical term. Daily and
weekly views do not limit the configured booking window.

The future `EmailService` uses a persistent outbox. Notifications include
invitation/setup/reset, booking/cancellation/change confirmation, reminder
around 24 hours before, and new-online-booking notification. Idempotency prevents
duplicate reminders; provider failure never rolls back an Appointment. Local
and test adapters do not send. Resend remains a separately approved proposal.

## Explicit MVP exclusions

Exclude medical records/workflows, healthcare questionnaires, group classes or
capacity, rooms/chairs/equipment/resources, recurring Customer Appointments,
home-visit travel-time calculations, multiple locations, industry-specific
forms/fields/workflows, marketplace discovery, custom workflows by profession,
Customer accounts, social login, SMS, payments/deposits, reviews, promotions,
loyalty, Customer subscriptions, inventory, accounting, native apps, custom
domains, product AI, complex analytics, microservices, Kubernetes, Kafka, and
Redis without measured need and approval.

## Assumptions and decisions requiring approval

Approved foundation assumptions include automatic confirmation, the lifecycle
behavior above, generic Business/StaffMember terminology, optional one-to-one
StaffMember/Membership linkage, EUR, a 15-minute slot grid with minute-precise
durations, and deterministic no-preference assignment.

Approved Phase 2 decisions set server-managed session storage, a 12-hour maximum
lifetime, and a two-hour idle timeout. Before launch, humans still decide
retention periods, privacy/legal wording and roles, any audited PLATFORM_ADMIN
support access, frontend/calendar libraries and styling, hosting vendors/region/
budget/backups/recovery, email domain/templates/retry window, slug redirects and
reserved words, and phone-normalization handling. Exact dependency/tool versions
are selected during bootstrap under the documented compatibility policy.
