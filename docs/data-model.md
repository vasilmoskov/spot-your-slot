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
  fields, single address, timezone, booking/notice/cancellation settings,
  status, currency, audit fields.
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

Business status is `DRAFT`, `ACTIVE`, or `SUSPENDED`. BusinessType is
`HAIR_SALON`, `BARBERSHOP`, `NAIL_STUDIO`, `MASSAGE_STUDIO`, `MAKEUP_STUDIO`,
`BEAUTY_STUDIO`, or `OTHER` and has no behavioral branching.

## Catalog and workforce

- **service:** `business_id`, name, description, price, duration minutes,
  buffer minutes, active state, audit fields.
- **staff_member:** `business_id`, display name, active state, optional
  `membership_id`, stable creation time, audit fields.
- **staff_member_service:** `business_id`, `staff_member_id`, `service_id`;
  unique staff-member/service qualification.
- **weekly_work_interval**, **schedule_break**, **time_off**, and
  **working_override:** all use `business_id` and `staff_member_id` plus their
  weekday/local-time/date/type fields.

The optional StaffMember/Membership link is one-to-one in both directions. A
unique constraint covers `staff_member.membership_id`; composite ownership
constraints require both rows to share `business_id`. A StaffMember without a
Membership/application user is valid.

## Customers and Appointments

- **customer:** `business_id`, name, original/normalized phone and email,
  private staff note, active/blocked state, audit fields. History metrics derive
  from Appointments.
- **appointment:** `business_id`, `staff_member_id`, `service_id`, `customer_id`,
  UTC `start_at`/`occupied_until`, captured Service facts, status, source
  (`ONLINE`/`STAFF`), Customer booking note, private staff note, cancellation
  metadata, `late_cancellation`, audit fields.
- **appointment_event:** `business_id`, Appointment, type, actor, timestamp,
  non-sensitive summary/correlation ID.
- **cancellation_token:** `business_id`, Appointment, token hash and lifecycle.

All referenced rows must share the Business. Statuses are `CONFIRMED`,
`CANCELLED_BY_CUSTOMER`, `CANCELLED_BY_BUSINESS`, `COMPLETED`, and `NO_SHOW`.
Every successful online/staff-created Appointment is `CONFIRMED`; no MVP
confirmation-mode field or `PENDING` status exists.

Only `CONFIRMED` blocks time:

```sql
EXCLUDE USING gist (
  staff_member_id WITH =,
  tstzrange(start_at, occupied_until, '[)') WITH &&
)
WHERE (status = 'CONFIRMED')
```

Flyway enables `btree_gist`. The specific violation becomes HTTP 409. Completed,
no-show, and cancelled rows remain in history. Application rules permit
`COMPLETED`/`NO_SHOW` only at or after start.

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
and restrictive delete behavior.

No health or special-category data is solicited. Export and reviewed
deletion/anonymization remain future work. Retention and lawful bases require
human/legal approval; technical design does not claim complete GDPR compliance.
