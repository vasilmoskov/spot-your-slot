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
- **MANAGER:** manages operations, Services, Appointments, and StaffMember
  schedules, but not platform-level settings. Customer administration is
  owner-only until a separate approved MANAGER design exists (ADR-0021).
- **STAFF:** sees and manages Appointments and schedule belonging to the linked
  StaffMember, creates manual Appointments, and marks them complete/no-show.
- **Customer:** books as an unauthenticated guest; no Customer account exists. A Customer *record* is
  Business-owned data about a guest, not a login: public booking will find or create it automatically from the
  supplied phone or email, and owners also create records manually for telephone, walk-in, and other externally
  received bookings. A future authenticated Customer account is a separate, explicitly approved capability.

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
  PLATFORM_ADMIN may reactivate it. One approved exception: the owner may still correct
  (update) an existing Customer record; creating Customers and every other change stay blocked.

## Public page and booking flow

An ACTIVE Business page shows its generic profile, contact/working information,
active Services with price/duration, and available qualified team members. The
Bulgarian mobile-first flow is:

1. «Избор на услуга»: select a Service;
2. «Избор на служител»: select a person or “Без предпочитание”;
3. «Дата и час»: select an available date and time;
4. «Вашите данни»: provide a name, a phone and/or an email, and an optional booking
   note of at most 500 characters;
5. «Преглед и потвърждение»: review the complete appointment and submit;
6. receive a confirmation page. The email and the secure cancellation link are later
   work; until then the page tells the guest to contact the Business to change or cancel.

Issue #18 decisions (ADR-0022 to ADR-0026, [task 08a](tasks/08a-appointment-core-and-guest-booking.md);
decided and documented, not yet implemented): the journey stays on the single `/{businessSlug}` URL with
in-memory steps; the public wording is formal; contact needs a name and at least one of phone or email under
the Customer policies, a short privacy notice is shown, and there is no verification, Customer account, or
marketing consent; a displayed slot is never a reservation; the assigned person for “Без предпочитание” is shown
after confirmation. Production public booking waits for the Business calendar (issue #21).

Multi-Service visit (decided in [ADR-0027](decisions/ADR-0027-book-several-services-as-one-atomic-visit-with-service-lines-a-versioned-set-fingerprint-and-an-explicit-review-consistency-check.md) and [task 08b](tasks/08b-multi-service-guest-booking-plan.md); **not implemented: today a booking is exactly one Service**): the guest may choose one to five distinct Services with
checkboxes (at most 480 minutes in total), sees the selected Services with the total duration and the total EUR price before continuing, and books them as one visit with one StaffMember who supports every selected Service, in one
continuous interval, reserved by one atomic submission. The Services are performed in the public-profile order, whatever order they were ticked. If a reviewed Service's duration or price changes before the guest confirms, nothing is
booked and the guest is asked to review and confirm again. Confirmation and replay describe the whole visit.

Issue #17 delivers only the profile part of this page (decisions in
[task 06a](tasks/06a-public-business-profile.md); the read contract `GET /api/public/businesses/{slug}` and the page are implemented and browser-verified): display
name, Business type, optional description, optional telephone and structured
address (both public when entered), and active Services with duration and EUR
price, at the top-level `/{businessSlug}`. It shows no StaffMember, no
availability, and no booking action; issue #18 owns the flow below. Business
contact email and timezone are not public. After first activation the slug is
immutable, and reserved paths cannot be slugs.

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

Current MVP implementation: booking window and minimum notice are not yet
Business settings. Availability uses the fixed values of ADR-0013 and ADR-0016:
Business-local dates from today through today + 29 (30 dates), an inclusive
two-hour minimum notice, a 15-minute grid, zero buffers, and no breaks. Making
them configurable is a recorded follow-up that needs separate approval.

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
increments the shared StaffMember version once.

Each StaffMember also owns one independent recurring weekly working-schedule
aggregate: zero or more weekday periods with local `HH:mm` start/end times at
one-minute precision, an independent optimistic version, and atomic complete
replacement of the desired week. An authorized Business owner retrieves and
replaces this schedule through its own authenticated endpoint; the schedule
version is separate from the StaffMember version, and inactive StaffMembers
keep a readable but non-mutable schedule. Breaks remain a future capability.

An authorized Business owner can also administer, through an authenticated
backend API and the "Промени в графика" interface, four kinds of schedule exception: a
Business closure, StaffMember time off, a working-day override (which may have no
periods, removing that day's recurring periods), and additional working periods.
Each is a versioned exception with a full-day date range for closures and time
off or local `HH:mm` periods on one date, and is created, atomically replaced,
or hard-deleted with the expected version. Exceptions of an inactive StaffMember
stay readable but cannot be changed, and a suspended Business is read-only. They
are applied by the internal Scheduling availability calculation described below;
no public availability endpoint, booking, or Appointment exists yet.

## Availability and assignment

Availability considers Business and StaffMember state, qualification, Service
duration plus buffer, weekly intervals, breaks, overrides, absences, blocking
Appointments, notice, booking window, and Business timezone. The complete
duration and buffer must fit one available interval. Implemented so far: the
internal calculation over an ACTIVE Business, an active Service, and active
assigned StaffMembers with the fixed values above; Appointments and breaks do not
exist yet, so no busy time is subtracted. Availability is a current view that
reserves nothing.

For “Без предпочитание”, select among qualified available StaffMembers using
the fewest non-cancelled Appointments on the local date, then creation time and
ID. Recalculate during booking. Store actual instants in UTC; interpret recurring
schedules as local wall time. DST gaps produce no slot; repeated times map to
distinct UTC instants and are visibly disambiguated.

## Appointment and Customer lifecycle

Online and authorized staff-created Appointments support editing, rescheduling,
Customer/staff cancellation, separate Customer booking and private staff notes,
source, status, and audit history. Every successful Appointment is automatically
`CONFIRMED`. Amended by ADR-0022 (issue #18): the Appointment statuses are
`CONFIRMED` and `CANCELLED` (only `CONFIRMED` blocks time) and the source is `ONLINE` or
`MANUAL`. Cancellation attribution (Customer or Business), the late-cancellation
flag, and `COMPLETED`/`NO_SHOW` (allowed only at or after start) are deferred to the
issues that implement them. An Appointment keeps snapshots of the Service name,
duration, EUR price, Business timezone, and assigned StaffMember name, and no copy of
Customer contact data. Normal operations never physically delete an Appointment.

Customer cancellation uses a secure random link while only its hash is stored.
A future Appointment may be cancelled after the deadline but is marked late;
past Appointments cannot be Customer-cancelled. Staff cancellation may include
a reason.

A Business-owned Customer stores one display name and a canonical phone and/or
email (at least one), with no account, note, or status (ADR-0019). A normalized
phone or email is unique within one Business and never shared across Businesses.
Matching is conservative and deterministic (ADR-0020): when both identifiers are
supplied, a Customer is found only if both match the same Customer, and a new
Customer is created only if neither matches; any partial match or conflict is
rejected for explicit owner correction, and nothing is merged, moved, or added
automatically. A guest sees a generic message that does not reveal which
identifier matched. Appointment history belongs to Appointment issues #18 and #21,
not to the Customer record, and the Appointment keeps no copy of the submitted contact
data (ADR-0022). Do not solicit health or other special-category data.

## Administration and notifications

The responsive Business administration UI includes authentication, daily and
weekly calendar views, manual Appointment operations, Service and StaffMember
management, schedules/breaks/time off, Customers/history, and Business settings.
Generic Bulgarian administration uses “Екип” and “Член на екипа”. Public
booking uses formal wording, the neutral step headings listed under the public
flow, and the option “Без предпочитание” (ADR-0026); the earlier informal question
“При кого искаш да запазиш час?” is withdrawn.
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
budget/backups/recovery, email domain/templates/retry window, slug redirects
(reserved words and post-activation slug immutability are decided for
issue #17 in ADR-0018), and phone-normalization handling. Exact dependency/tool versions
are selected during bootstrap under the documented compatibility policy.
