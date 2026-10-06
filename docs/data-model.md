# SpotYourSlot data model

## Conventions

- PostgreSQL is authoritative and Flyway owns every schema change.
- Primary keys are UUIDs. Tenant-owned rows contain non-null `business_id`, and
  composite constraints prevent cross-Business references.
- Mutable records use audit fields; security/business events are append-only.
- Appointment instants use UTC `timestamptz`; recurring schedules use local
  date/time interpreted in the Business IANA timezone.
- EUR money uses Java `BigDecimal` and PostgreSQL `numeric(12,2)`.
- Appointments are retained and status-transitioned, not physically deleted.

## Identity and tenancy

- **app_user:** normalized unique email, password hash, active/locked state,
  password-change time, audit fields; no tenant role directly.
- **business:** globally unique slug, `BusinessType`, generic profile/contact
  fields, one optional structured Bulgarian address, timezone,
  booking/notice/cancellation settings, status, currency, audit fields.
- **membership:** `user_id`, `business_id`, role (`BUSINESS_OWNER`, `MANAGER`,
  `STAFF`), active state; unique per user/Business.
- **platform_role:** platform-level `PLATFORM_ADMIN`, avoiding a fake tenant.
- **invitation:** `business_id`, email, intended role, token hash, expiry,
  consumed/revoked timestamps, inviter, audit fields.
- **password_reset** and **user_session:** hash/reference and lifecycle metadata;
  raw secrets are never stored or logged.

Flyway migration `V1__identity_and_tenancy.sql` creates these seven Phase 2
tables. Sessions store a SHA-256 token hash, credential version, selected
authorized Business, activity/absolute expiry, and revocation time. Invitation
and reset tokens also persist only SHA-256 hashes. The database restricts
Membership roles to `BUSINESS_OWNER`, `MANAGER`, and `STAFF`.

Flyway `V2__enforce_single_active_identity_tokens.sql` enforces single active
invitation/reset issuance. Flyway `V3__add_business_profile_fields.sql` adds
nullable `description` (2,000 characters), `address` (500), `phone` (50), and
`contact_email` (320). PostgreSQL rejects non-null whitespace-only values and
requires persisted contact email to be lowercase; application validation also
normalizes and validates supplied contact email.

Flyway `V4__structure_business_address.sql` renames the existing `address`
column to `address_details`, preserving every prior value, and adds nullable
`city` (100 characters), `postal_code` (20), `street` (200), and
`street_number` (50). The renamed details column retains its 500-character and
nonblank constraints; each new field also rejects non-null whitespace-only
values. Country is currently fixed to Bulgaria in the product/UI and is not a
stored selectable field. V1, V2, and V3 were not rewritten.

Platform-created Businesses always persist as `DRAFT` with version 0. Profile
and lifecycle mutations compare the supplied expected version atomically and
increment `version` exactly once. Lists are bounded to 100 rows and ordered by
`created_at DESC, id DESC` with a separate total count.

Business status is `DRAFT`, `ACTIVE`, or `SUSPENDED`. BusinessType is
`HAIR_SALON`, `BARBERSHOP`, `NAIL_STUDIO`, `MASSAGE_STUDIO`, `MAKEUP_STUDIO`,
`BEAUTY_STUDIO`, or `OTHER` and has no behavioral branching.

## Catalog and workforce

Flyway `V5__add_business_services.sql` creates the implemented **service** table.
Each row has an application-generated UUID, immutable `business_id`, canonical
display name, database-generated normalized name, optional canonical
description, duration from 1 through 480 minutes, nonnegative EUR
`numeric(12,2)` price, active state, nonnegative optimistic version, and UTC
creation/update timestamps. Application validation permits at most ten integer
and two fractional price digits and rejects excess fractional digits instead of
allowing PostgreSQL to round them.

The normalized name applies Unicode NFKC, the approved whitespace collapse and
trim, Unicode full case folding under `pg_unicode_fast`, and NFKC again. It is
unique within a Business across active and inactive Services, so deactivation
does not release a name. Service identity is also unique with its Business;
the Business foreign key restricts deletion while Services exist. Services are
versioned for update, deactivation, and reactivation and cannot be hard-deleted
or transferred by the implemented application contract. V5 has no Service
buffer or Staff-assignment column.

Flyway `V6__add_staff_members_and_service_assignments.sql` creates the
implemented **staff_member** and **staff_member_service** tables.

Each StaffMember has an application-generated UUID, immutable `business_id`,
canonical display name, generated normalized display name for ordering,
optional canonical contact email and phone, active state, nonnegative aggregate
version, and UTC creation/update timestamps. New rows are active at version 0.
Display names and contact values need not be unique. StaffMembers are ordered
within a Business by normalized display name and ID.

`staff_member_service` contains only `business_id`, `staff_member_id`, and
`service_id`. Its complete triple is the primary key. Composite restrictive
foreign keys require the StaffMember and Service to belong to that Business,
prevent duplicate relationships, and preserve assignments when either endpoint
is inactive. There is deliberately no database constraint coupling an
assignment to either endpoint's active state.

Profile update, lifecycle transition, and complete desired-set assignment
replacement share the StaffMember aggregate version. Each successful operation
increments it exactly once, including a same-set assignment replacement.
Assignment replacement validates active state only for Service additions;
already assigned inactive Services may be retained or removed. Removed
assignment history is not stored.

V6 has no StaffMember-to-user or Membership link. A future approved capability
may add an optional same-Business one-to-one link.

Flyway `V7__add_recurring_staff_working_schedules.sql` creates the implemented
**staff_working_schedule** and **staff_working_period** tables. `V7` installs
PostgreSQL's supplied trusted `btree_gist` extension.

`staff_working_schedule` uses `(business_id, staff_member_id)` as its primary
key with a restrictive composite foreign key to `staff_member`. It stores a
nonnegative `bigint version` starting at 0 and UTC `created_at`/`updated_at`.
`V7` backfills one row per existing StaffMember at version 0, using the
StaffMember's creation instant for both timestamps; StaffMember creation
inserts the new empty schedule in the same transaction.

`staff_working_period` stores the same `(business_id, staff_member_id)`
ownership, ISO `weekday` (`1`–`7`), local `start_time`/`end_time` as
`time without time zone`, and a generated `int4range` (`minute_range`)
converting each time to minutes after midnight with canonical `[start,end)`
bounds. The primary key is the complete row, rejecting exact duplicates. A
restrictive composite foreign key targets `staff_working_schedule`. Checks
enforce the weekday range, minute precision (rejecting seconds/sub-minute
values), rejection of PostgreSQL's special `24:00:00` value, and `start_time <
end_time`. A GiST exclusion constraint (`business_id WITH =, staff_member_id
WITH =, weekday WITH =, minute_range WITH &&`) rejects overlapping ranges only
within the same Business, StaffMember, and weekday; half-open bounds permit
adjacency. A request may contain at most 100 periods; this is an application
contract enforced alongside the database invariants.

The schedule version is independent of the StaffMember aggregate version.
Every accepted complete replacement — deleting existing periods and inserting
the validated desired set in one transaction — increments the schedule
version and `updated_at` exactly once, including an identical replacement.
Exceptions, holidays, leave, time off, working overrides, and breaks remain
outside `V7`.

Flyway `V9__add_schedule_exceptions.sql` creates the implemented
**schedule_exception** and **schedule_exception_period** tables
([ADR-0014](decisions/ADR-0014-store-schedule-exceptions-as-versioned-aggregates-with-same-kind-date-exclusion.md)).
`schedule_exception` is a versioned aggregate: UUID `id`, `business_id`,
nullable `staff_member_id`, `kind`, inclusive `first_date`/`last_date`,
`all_day`, a generated inclusive `date_range`, nonnegative `bigint version`
starting at 0, and UTC `created_at`/`updated_at`. `kind` is one of
`BUSINESS_CLOSURE`, `STAFF_TIME_OFF`, `WORKING_DAY_OVERRIDE`, and
`ADDITIONAL_WORKING_PERIODS`; the StaffMember is absent exactly for
`BUSINESS_CLOSURE`. A composite foreign key to `staff_member(business_id, id)`
prevents cross-Business references. Full-day ranges (`all_day`) exist only for
closures and time off; partial blocks and both working kinds cover exactly one
date. Only finite dates and `first_date <= last_date` are required; no
calendar-year bounds are imposed. `kind` and StaffMember are immutable.

`schedule_exception_period` stores the local `start_time`/`end_time` of one
aggregate with a generated `int4range`. Periods are whole-minute, `start_time <
end_time`, and `24:00` is rejected. A per-aggregate GiST exclusion constraint
rejects duplicate and overlapping periods; adjacent periods are allowed and
never merged. Its foreign key to the exception is `ON DELETE CASCADE`:
aggregate composition, not an independently owned relationship.

Two GiST exclusion constraints reject overlapping date ranges only for the same
kind and scope (Business closures per Business; each StaffMember kind per
StaffMember), so one date belongs to at most one aggregate of that kind and
scope and disjoint partial periods share one aggregate. Cross-kind overlaps,
such as a closure over an override, are allowed and resolved by the availability
engine. PostgreSQL does not enforce child-row counts: the domain content record
and store require zero periods for full-day aggregates, at least one for partial
closures/time off and additional working periods, and permit zero or more for an
override. Exact indexes: primary key, `(business_id, id)`, the two exclusion
indexes, `(business_id, first_date, last_date, id)`, and GiST `(business_id,
date_range)`.

## Customers and Appointments

- **customer** (ADR-0019, issue #20; implemented by `V10__add_customers.sql`): UUID `id`, immutable `business_id` (restrictive foreign key to
  `business`), required `display_name` (`varchar(200)`, canonical NFKC/whitespace form), a
  generated stored `normalized_display_name` (sorting and search only), optional canonical
  E.164 `phone` (`varchar(16)`), optional canonical lowercase `email` (`varchar(320)`),
  nonnegative `version`, and UTC `created_at`/`updated_at`. One canonical value is stored per
  identifier; no original text, staff note, status, account or Membership link, or
  Appointment-derived column exists. Checks require at least one of phone and email.
  `UNIQUE (business_id, phone)` and `UNIQUE (business_id, email)` (NULLs are distinct) make
  each identifier unique within one Business only; `UNIQUE (business_id, id)` prepares the
  future composite foreign key from Appointments. An index on `(business_id,
  normalized_display_name, id)` serves the default sort. Customers have no lifecycle and are
  never hard-deleted in the MVP; retention, export, anonymization, merge, and legal deletion
  are deferred. Updates are versioned and increment `version` once per accepted update.
  Stable constraint names: `customer_business_fk`, `customer_display_name_canonical`,
  `customer_display_name_not_blank`, `customer_phone_canonical`, `customer_email_canonical`
  (database-level canonical storage only, not address syntax, which the application's shared
  `ContactEmailPolicy` enforces),
  `customer_contact_present`, `customer_version_nonnegative`,
  `customer_timestamps_finite_ordered` (finite timestamps, `updated_at >= created_at`),
  `customer_business_phone_unique`, `customer_business_email_unique`,
  `customer_business_id_id_unique`; index `customer_business_normalized_display_name_id_idx`.
  History metrics derive from Appointments in later issues.
- **appointment** (issue #18, ADR-0022; **decided and planned for `V11`, not yet implemented**).
  Created together with its overlap exclusion constraint. `business_id`; `customer_id`, `service_id`, and
  `staff_member_id` each with a composite same-Business restrictive foreign key (and no foreign key to the
  Service assignment); UTC `start_at`, `end_at`, and `occupied_until` (equal in the MVP, zero buffers); snapshots
  `timezone`, `duration_minutes`, `price_eur`, `service_name`, and `staff_display_name`; `source`
  (`ONLINE`/`MANUAL`); `status` (`CONFIRMED`/`CANCELLED`); optional plain-text `customer_note` (at most 500
  code points); a random informational `public_reference` unique per Business; the idempotency columns
  `booking_attempt_hash`, `request_fingerprint`, `fingerprint_encoding_version`, and `fingerprint_key_version`
  (ADR-0024); `version`; audit timestamps. **No Customer name, phone, or email is copied** (D2). The private
  staff note, cancellation metadata, and `late_cancellation` are deferred to #21 and the cancellation issue and
  arrive by forward migrations.
- **schedule revision** (issue #18 Phase 3, ADR-0025; **decided and planned for `V12`, not yet implemented**): one
  row per Business, bumped by every mutation that changes recurring schedules or schedule exceptions and locked
  `FOR SHARE` by booking. Its exact name is Proposed.
- **appointment_event:** `business_id`, Appointment, type, actor, timestamp,
  non-sensitive summary/correlation ID.
- **cancellation_token:** `business_id`, Appointment, token hash and lifecycle.

All referenced rows must share the Business. Amended by ADR-0022 (the earlier text listed
`CONFIRMED`, `CANCELLED_BY_CUSTOMER`, `CANCELLED_BY_BUSINESS`, `COMPLETED`, and `NO_SHOW`, and source
`ONLINE`/`STAFF`): statuses are `CONFIRMED` and `CANCELLED`, and the source is `ONLINE` or `MANUAL`.
Every successful online/manual Appointment is `CONFIRMED`; no MVP
confirmation-mode field or `PENDING` status exists. Cancellation attribution and `COMPLETED`/`NO_SHOW` are
deferred and arrive by forward migrations.

Only `CONFIRMED` blocks time (created in `V11`, the same migration as the table):

```sql
EXCLUDE USING gist (
  staff_member_id WITH =,
  tstzrange(start_at, occupied_until, '[)') WITH &&
)
WHERE (status = 'CONFIRMED')
```

Flyway enables `btree_gist` (installed by `V7`). The specific violation becomes a typed sanitized outcome
and, publicly, HTTP 409. Cancelled rows remain in history. The deferred `COMPLETED`/`NO_SHOW` rule (only at
or after start) applies when those statuses are introduced. The `appointment` table never has a foreign key
to the Service assignment because assignment history is not stored.

## Notifications, audit, and relationships

- **outbox_event:** aggregate/event, necessary payload, availability/status,
  attempts, unique idempotency key, timestamps.
- **email_delivery:** optional `business_id`, event, template/recipient/provider,
  status/attempts, provider reference, redacted error.
- **reminder:** `business_id`, Appointment, target/type/status/idempotency key.
- **audit_event:** optional `business_id`, actor/action/target/time/outcome,
  correlation ID, minimal metadata—never credentials, tokens, or note contents.

```text
app_user ──< membership >── business ──< service
                  │            ├────< staff_member ──< schedules/time off
                  │            ├────< customer
                  │            └────< appointment >── staff_member/service/customer
                  └──────────── optional StaffMember login link
appointment ──< event / reminder / cancellation_token
business transaction ──< outbox_event ──< email_delivery
```

## Index and privacy plan

Use a global unique normalized Business slug; unique Membership
`(user_id,business_id)`; tenant-prefixed lookup/foreign keys; Appointment indexes
on `(business_id,start_at)`, `(staff_member_id,start_at)`, and
`(customer_id,start_at desc)` plus GiST; schedule indexes by
Business/StaffMember/day; token and outbox indexes; checks for ranges/statuses;
and restrictive delete behavior. Issue #18 creates only the exclusion index and the unique indexes it needs
(public reference and attempt hash); the calendar and Customer-history indexes are added with the issue that
queries them (#21).

No health or special-category data is solicited. Export and reviewed
deletion/anonymization remain future work. Retention and lawful bases require
human/legal approval; technical design does not claim complete GDPR compliance.
