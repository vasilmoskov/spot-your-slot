# ADR-0022: Model Appointments with snapshots, two statuses, and a database overlap exclusion

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-10-06
- **Recorded date:** 2026-10-06
- **Related issues:** #18 (reconciles part of #19; consumer: #21)
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

Issue #18 introduces the first Appointment record. The permanent documentation
already described one: `appointment` with `start_at`/`occupied_until`, five
statuses, sources `ONLINE`/`STAFF`, and a GiST exclusion for `CONFIRMED` rows
(`docs/data-model.md`). Issue #18 itself specifies two statuses
(`CONFIRMED`, `CANCELLED`) and sources `ONLINE`/`MANUAL`. ADR-0020 deferred two
questions to this issue: whether an Appointment snapshots the submitted Customer
contact data, and how the same-Business Customer relationship is persisted. Issue
#19 separately requires that overlapping blocking appointments cannot exist, but
depends on an Appointment model that does not yet exist.

## Constraints

- PostgreSQL is the final arbiter of overlap (`architecture.md`, ADR-0002).
- Tenant-owned rows carry `business_id` and composite foreign keys prevent
  cross-Business references (ADR-0003).
- Migrations are immutable and forward-only (ADR-0004).
- Appointments are never physically deleted in normal operation.
- Historical appointment facts must not change when a Service, StaffMember, price,
  schedule, or Customer contact later changes (issue #18).
- Customer contact data lives only in the Customer record (ADR-0019, ADR-0020).
- No unprotected intermediate schema may exist (approved direction for #18).
- No private staff note, cancellation metadata, or event table is needed yet.

## Options considered

### Statuses

1. **The five documented values** (`CONFIRMED`, `CANCELLED_BY_CUSTOMER`,
   `CANCELLED_BY_BUSINESS`, `COMPLETED`, `NO_SHOW`). Complete for the later
   lifecycle, but it creates values and before-start rules (`COMPLETED`/`NO_SHOW`)
   that nothing in issue #18 can create or exercise.
2. **`CONFIRMED` and `CANCELLED` only. Selected.** It matches issue #18, is the
   smallest model that satisfies "a cancelled appointment does not block", and
   defers attribution to a dedicated field when cancellation exists. Later values
   are added by a forward migration that widens the CHECK.

### Customer contact snapshot

1. A snapshot of name, phone, and email on every Appointment gives immutable
   history but duplicates personal data and complicates retention and
   anonymization.
2. **No snapshot. Selected.** The Customer record stays the single home of contact
   data. A notification recipient belongs to a later outbox payload. Idempotency
   uses a request fingerprint instead (ADR-0024).

### Overlap protection timing

1. Create the table first and the exclusion later (two migrations). Matches the
   literal issue order but leaves a schema that permits overlapping `CONFIRMED`
   rows.
2. **Create the table and the exclusion constraint in the same migration.
   Selected.** No intermediate schema without protection and one migration.

### Time columns

Store `start_at` and `end_at` (the Service end) plus the documented
`occupied_until` (the blocking end). With zero buffers the two ends are equal; a
later approved buffer changes only `occupied_until`. Dropping `end_at` would make
the customer-visible end a derived value. The redundancy is deliberate and is
protected by CHECK constraints.

## Decision

**Table.** `V11__add_appointments.sql` (Phase 2 of issue #18) creates `appointment`
together with its constraints. Column and constraint names below are normative for
meaning; stable constraint names are fixed when the migration is written.

| Group | Columns and rules |
|---|---|
| Identity and ownership | `id uuid` (application-generated); `business_id` NOT NULL, restrictive foreign key to `business`. |
| References | `customer_id`, `service_id`, `staff_member_id`, each with a composite restrictive foreign key `(business_id, …)` to the same-Business `customer`, `service`, and `staff_member` rows. There is **no** foreign key to `staff_member_service`: an assignment may be removed later and its history is not stored (ADR-0011), so eligibility is enforced transactionally (ADR-0023), not by the schema. |
| Source and status | `source` CHECK `ONLINE`/`MANUAL`; `status` CHECK `CONFIRMED`/`CANCELLED`. A client never supplies either (ADR-0026). |
| Instants | `start_at`, `end_at`, `occupied_until` `timestamptz`. CHECK `end_at = start_at + duration_minutes * interval '1 minute'` (elapsed time, so DST-correct) and `occupied_until >= end_at`. MVP buffers are zero, so `occupied_until = end_at`. |
| Snapshots | `timezone` (the Business IANA zone), `duration_minutes` (1–480), `price_eur numeric(12,2)` (≥ 0), `service_name` (canonical), and `staff_display_name` (canonical). They are written once from the locked Service and StaffMember rows and never recomputed. No Customer name, phone, or email is copied. |
| Customer note | Optional `customer_note`, plain text, at most 500 code points; blank is stored as NULL; never rendered as markup, never logged. Exact normalization is a Proposed detail (task record). |
| Public reference | `public_reference`, a short random, unambiguous, uppercase code, `UNIQUE (business_id, public_reference)`. It is informational only: it grants no access and no endpoint reads by it. |
| Idempotency | Nullable `booking_attempt_hash` (SHA-256 of the attempt ID), `request_fingerprint`, `fingerprint_encoding_version`, `fingerprint_key_version`, with a partial unique index on `(business_id, booking_attempt_hash)`. A CHECK requires them for `ONLINE` rows. Semantics are in ADR-0024. |
| Concurrency and audit | `version bigint NOT NULL DEFAULT 0` (for later mutations); finite, ordered `created_at`/`updated_at`. |

**Overlap exclusion (same migration).**

```sql
EXCLUDE USING gist (
  staff_member_id WITH =,
  tstzrange(start_at, occupied_until, '[)') WITH &&
) WHERE (status = 'CONFIRMED')
```

`btree_gist` is already installed by V7. A StaffMember is unique to one Business
through the composite foreign key, so keying on `staff_member_id` is
tenant-safe. Half-open bounds allow adjacency. The same constraint guards `UPDATE`,
so a later move, reassignment, or return to `CONFIRMED` inherits it. A `23P01`
violation is translated by the persistence layer into a typed, sanitized overlap
outcome (no constraint name, SQL, or value), reused by every creation path.

**Lifecycle.** Online bookings are created `CONFIRMED` with source `ONLINE`.
Source `MANUAL` is reserved for #21. Only `CONFIRMED` blocks time. `CANCELLED`
rows are retained, appear in authorized history, and do not block. Cancellation
attribution (customer or Business), the cancellation time, the late-cancellation
flag, and `COMPLETED`/`NO_SHOW` with their before-start rule are **deferred** to
the issues that implement them and arrive through forward migrations.

**Historical preservation.** All foreign keys are restrictive and nothing is
hard-deleted. A later Service rename or price change, StaffMember rename or
deactivation, schedule change, or Customer contact correction never rewrites an
Appointment's snapshots. Deactivating a Service or StaffMember does not cancel or
alter existing future `CONFIRMED` appointments; they continue to occupy time. An
owner-facing impact warning is a recorded follow-up, not part of this decision.

**Amendment of permanent documents.** This decision explicitly amends the
five-status vocabulary and `ONLINE`/`STAFF` source in `docs/product-spec.md`,
`docs/data-model.md`, `docs/implementation-plan.md`, `docs/architecture.md`,
`docs/security.md`, and `docs/testing-strategy.md`. The original text is quoted in
the task record
([08a](../tasks/08a-appointment-core-and-guest-booking.md)). No earlier ADR defined
the statuses.

**#18 / #19 reconciliation.** This decision and ADR-0023 satisfy the #19
requirements for creation: overlap prevention, different-StaffMember and
different-Business independence, half-open adjacency, inclusion of the full
duration, cancelled rows not blocking, at most one winner under concurrent
identical or partially overlapping creation, no partial data for the loser,
revalidation at the final mutation, and refreshed availability excluding the
booked time (through the real busy-interval source replacing the placeholder, ADR-0016).
#19 remains incomplete: Business-created (manual) creation, move, reassignment,
Service change, "a failed update leaves the original unchanged", authenticated
conflict feedback, Business-user versus Customer races, buffers, and the override
decision are implemented with #21. The detailed mapping is in the task record.

## Rationale

The two-status model is the smallest lifecycle that makes the exclusion predicate
and the cancelled-row tests meaningful, and it follows the issue. A single
migration cannot be observed in an unprotected state. Keeping contact data in the
Customer record avoids a second copy of personal data. Snapshots of the facts the
guest agreed to (Service name, duration, price, timezone, and the assigned
StaffMember's name) keep the confirmation and the history stable.

## Tradeoffs and disadvantages

- Adding cancellation attribution, `COMPLETED`, and `NO_SHOW` later requires
  forward migrations and an amended CHECK.
- The Customer name shown for an Appointment is the current Customer record, which
  the owner may correct; a matched Customer's stored name can differ from the name a
  guest typed (ADR-0020 matching never rewrites the record).
- `end_at` and `occupied_until` are equal in the MVP.
- Snapshotting the StaffMember display name stores a name that may later change on
  the live record.
- A schema-level eligibility guarantee (Service assigned to StaffMember) is not
  possible; it relies on transactional checks and tests.

## Risks and mitigations

- **Overlap under concurrency:** the exclusion constraint, with deterministic
  two-writer PostgreSQL tests (Phase 2).
- **Cross-Business reference:** composite foreign keys and a test that each
  cross-Business insert is rejected.
- **Redundant time columns drifting:** CHECK constraints.
- **Unprotected window:** none, because the constraint is in V11.

## Consequences

Phase 2 writes V11, the domain model and store, the typed overlap outcome, and the
real `BusyIntervalSource` in a new `booking` module, and deletes
`NoBookingBusyIntervalSource` with its wiring test. For **guest booking**, Customer
matching (`CustomerIdentification.findOrCreate`, ADR-0020) is the only way to obtain a
Customer ID. This does not restrict future manual booking (issue #21), which may select
an existing same-Business Customer through the published reference contract
(`CustomerReferenceAccess`) or use the same matching contract. Documents are reconciled in
Phase 1.

## Evidence

Direct evidence: issues #18, #19, #20, #21; `docs/data-model.md`,
`docs/product-spec.md`, `docs/implementation-plan.md`, `docs/architecture.md`
(documented statuses, `occupied_until`, exclusion); V5, V6, V7, V10 migrations
(composite keys, `btree_gist`); ADR-0011, ADR-0013, ADR-0016, ADR-0019, ADR-0020.
Inference: PostgreSQL permits `tstzrange(start_at, occupied_until, '[)')` in a
partial exclusion constraint; this is proven by the Phase 2 schema tests, not yet
observed in this repository.

## Implementation notes (Phase 2, 2026-10-06)

Implemented in `V11__add_appointments.sql`, `booking.domain`, `booking.infrastructure.AppointmentStore`,
and `BookingBusyIntervalSource`. No decision above changed; these clarifications and deviations are recorded
so the accepted text is not silently rewritten:

- **Tighter equality.** The migration requires `occupied_until = end_at` (the current zero-buffer policy)
  instead of the `occupied_until >= end_at` written in the Decision. A later approved buffer relaxes the one
  constraint `appointment_occupied_until_equals_end` by a forward migration.
- **Idempotency columns.** The four columns are all present or all absent
  (`appointment_idempotency_all_or_none`) and always present for `ONLINE` rows
  (`appointment_online_requires_idempotency`). A `MANUAL` row may carry them, so a later manual-creation form
  can be idempotent too; this is a Phase 2 detail, not an accepted rule for #21.
- **Note.** The database accepts only a trimmed, non-empty note of at most 500 code points with no control
  character other than tab and line feed (a carriage return is rejected, so a caller normalizes line breaks
  first). The exact application-side normalization remains Proposed (task record).
- **Public reference.** Ten characters of the Crockford base32 alphabet (digits and letters except I, L, O, U),
  uppercase, checked by `appointment_public_reference_format`. The alphabet question is therefore finalized.
- **Timezone.** `varchar(100)`, the length of `business.timezone`; the database checks only a non-empty trimmed
  value, and the domain requires a known region identifier.
- **Names.** The Service and StaffMember snapshots must satisfy the same canonical NFKC/whitespace form as the
  source columns. The database enforces it (`appointment_service_name_canonical`,
  `appointment_staff_display_name_canonical`), and the domain enforces the same invariant with the repository's
  existing `shared.contact` canonicalization: a noncanonical name (repeated or non-space whitespace inside,
  edge whitespace, or a string NFKC would change) is rejected rather than silently changed, so a stored snapshot is
  always the exact source fact. Phase 2 therefore also depends on the `shared::contact` named interface.
- **Time range.** The domain rejects an instant outside `0001-01-01` to `9999-12-31` so every value converts to
  a PostgreSQL `timestamptz`; PostgreSQL itself rejects an overflow with SQLState `22008`.
- **Store.** `AppointmentStore` offers `insert`, `find`, `findByAttemptHash`, and `findBlockingWindows`; there is
  no update, cancellation, or manual-creation operation (deferred). Failures are classified from the SQLState
  and the structured constraint, table, or column fields into the sealed, cause-free
  `AppointmentPersistenceException` (overlap, duplicate public reference, duplicate attempt, unknown
  reference, invalid data, concurrent failure for `40001` and `40P01`, and an unexpected failure that keeps only
  the SQLState). The store opens no transaction.
- **PostgreSQL 18 note.** `pg_constraint` also lists `NOT NULL` constraints (type `n`); schema tests exclude
  them when listing declared constraints.

## Conditions for revisiting

Revisit for approved buffers, additional lifecycle states, a pending state,
Customer contact snapshots (for example for notifications or legal retention),
multiple Services or StaffMembers per appointment, or a measured need for
different time columns.
