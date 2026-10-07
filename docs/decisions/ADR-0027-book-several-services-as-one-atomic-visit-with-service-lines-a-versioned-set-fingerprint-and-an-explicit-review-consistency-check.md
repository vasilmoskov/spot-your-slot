# ADR-0027: Book several Services as one atomic visit with Service lines, a versioned set fingerprint, and an explicit review-consistency check

## Metadata

- **Status:** Accepted (the product decisions were approved by the product owner on 2026-10-07; this record has not yet had an independent review, and **nothing in it is implemented**)
- **Decision date:** 2026-10-07
- **Recorded date:** 2026-10-07
- **Related issues:** #18 (production public booking still waits for #21; #19 is unchanged)
- **Supersedes:** None
- **Amends in part:** [ADR-0013](ADR-0013-define-availability-interval-precedence-grid-and-dst-semantics.md), [ADR-0016](ADR-0016-orchestrate-availability-through-published-contracts-and-a-scheduling-owned-busy-interval-seam.md), [ADR-0022](ADR-0022-model-appointments-with-snapshots-two-statuses-and-a-database-overlap-exclusion.md), [ADR-0023](ADR-0023-book-appointments-in-one-repeatable-read-transaction-with-ordered-locks-and-bounded-whole-transaction-retry.md), [ADR-0024](ADR-0024-make-booking-attempts-idempotent-with-a-versioned-hmac-request-fingerprint-and-distinguish-uncertain-outcomes.md), and [ADR-0026](ADR-0026-expose-guest-booking-through-narrow-public-contracts-with-session-independent-endpoints-and-bounded-abuse-protection.md). Each of them keeps its historical text and carries a short amendment note that points here. [ADR-0025](ADR-0025-coordinate-schedule-changes-with-booking-through-a-business-level-schedule-revision-guard.md) is not changed.
- **Clarified (2026-10-07, before the first implementation phase):** the capacity of the visit total (section 3, "Money") and the exact UUID orders (sections 2 and 7, "Identifier order"). Both are specification corrections of this record, not new decisions.
- **Superseded by:** None

## Context and problem

Guest booking (ADR-0022 to ADR-0026) books exactly one Service for one StaffMember. Businesses that sell combined work (for example a cut and a colouring in one visit) currently have to publish a combined Service. Booking several Services in one visit is now a requested capability. Doing it as several independent bookings could succeed in part and leave a guest with half a visit, and a checkbox over a single-Service request would only pretend to work. The visit has to be reserved as one thing: one StaffMember, one continuous interval, one idempotency key, one overlap range, one confirmation, and one replay.

A second problem came from the human review. The booking contract today lets the server silently use the current price and duration of a Service even when the guest reviewed different ones. Adding more Services multiplies the chance that a reviewed total is no longer true when the guest confirms, and a booking must not commit with terms the guest did not review.

## Constraints

- Tenant isolation, the repeatable-read transaction owned by the booking use case, ordered locks and bounded whole-transaction retry (ADR-0023, ADR-0025) are preserved. The database overlap exclusion stays the final arbiter (ADR-0022).
- A committed migration is never edited (ADR-0004); existing Appointments and historical idempotent replays must keep working exactly.
- The server alone decides prices, durations, totals, end, status, source, and the Business timezone. A client value may be compared with a server value, but it is **never persisted** and never becomes a price or a duration.
- Nothing is logged, stored, or put in a URL that is personal; the fingerprint stays an HMAC (ADR-0024).
- The public frontend keeps its in-memory attempt, the exact frozen request body, sticky uncertainty, no automatic retry, and the `Retry-After` gate (ADR-0024 notes).
- No cancellation endpoint is introduced here; Customer matching (ADR-0020) is untouched; no buffers, no per-Service StaffMember, no package pricing.

## Options considered

### Appointment representation

1. **One Appointment row per Service, linked by a visit identifier.** The overlap exclusion works per row, but the attempt hash, the replay, the cancellation, the public reference, and the future calendar all need a group, and a group can be half written or half cancelled.
2. **One Appointment per visit with a `appointment_service` line table. Selected.** One row keeps one idempotency key, one exclusion range, one status, and one reference; the lines are immutable per-Service snapshots.
3. **The same, with `service_id` and `service_name` relaxed to nullable.** Cleaner reads, but it needs a later destructive «contract» migration and changes columns that issue #21 will read; rejected for now.

### Price and duration drift

1. **The server is silently authoritative** (the earlier proposal): the confirmation shows the final figures. Rejected after review: the booking could commit with terms the guest did not see.
2. **An explicit review-consistency check. Selected.** The request carries the reviewed per-Service duration and price as claims; the server compares them with the locked rows and refuses the whole booking, writing nothing, when they differ.
3. **Reject any change to a Service after the guest started.** Rejected: it would also reject changes that do not affect the visit (a renamed or described Service).

### Where the reviewed facts live in the request identity

1. **Outside the fingerprint** (claims are advisory). A resent attempt with other claims would replay a success the new claims never agreed to; rejected.
2. **Inside the fingerprint. Selected.** A request that differs in any claimed fact is a different request (`BOOKING_ATTEMPT_MISMATCH`), as any other differing field is.

### Order of the Services

1. **The guest's click order.** Makes the request depend on a hidden order and `[A,B]` differ from `[B,A]` in the fingerprint; availability does not depend on the order anyway.
2. **The server's public-profile order for execution and a stable id order for identity. Selected** (approved).

## Decision

### 1. The visit

A booking is one **visit**: one to five **distinct active Services** of the Business, performed consecutively by **one StaffMember** in **one continuous interval** whose length is the sum of the Service durations (zero buffers). The total is at most **480 minutes** (the existing Appointment and Service maximum), and the count at most **5**. The visit is reserved by one atomic submission; there is no partial booking. Eligible StaffMembers are the **active StaffMembers assigned to every selected Service**. A different StaffMember per Service is out of scope.

### 2. Two orders, for two purposes

- **Execution order** (what the guest sees and what is stored in `position`): the **public-profile order**, `normalized_name ASC, id ASC` (the order `catalog` already uses for the profile). The checkbox click order has no effect.
- **Request identity order** (what the fingerprint encodes): **ascending Service UUID**. It does not depend on any name, so a later rename can never change the fingerprint of a stored attempt.

The client sends a **set**; the server sorts it for each purpose. Duplicate Service identifiers are invalid.

**Identifier order (exact).** A UUID is the 16 bytes of its canonical form in **big-endian** order: the most significant 8 bytes, then the least significant 8 bytes, each byte read as an **unsigned** value 0 to 255. "Ascending UUID" means the **unsigned lexicographic order of those 16 bytes**. It is exactly the order of the canonical lowercase hexadecimal text and exactly PostgreSQL's `uuid` order (`memcmp` of the 16 bytes), and it equals comparing the two 64-bit halves with `Long.compareUnsigned` (the most significant half first). It is **not** Java's `UUID.compareTo`, which compares the halves as **signed** `long` values, so every identifier whose first byte is `0x80` or higher sorts before every identifier below `0x80`, and within one most significant half every least significant half with its top bit set (all version 4 identifiers, whose variant bits are `10`) sorts before one without it. For the four identifiers `00000000-0000-4000-8000-000000000001`, `7fffffff-ffff-4fff-bfff-ffffffffffff`, `80000000-0000-4000-8000-000000000000`, and `ffffffff-ffff-4fff-bfff-ffffffffffff` the order defined here is that same order (A, B, C, D) and Java's is C, D, A, B. Code that must follow this order uses a dedicated unsigned comparator (a small shared helper with that name and contract, added in the phase that first needs it), never `UUID.compareTo`, and never a hand-written signed comparison.

### 3. Schema: `V13__add_appointment_services.sql` (a new migration; V11 and V12 stay byte-identical)

```sql
-- The visit total can exceed one Service price (see "Money" below): widen the precision, keep the scale.
ALTER TABLE appointment ALTER COLUMN price_eur TYPE numeric(13,2);
ALTER TABLE appointment ADD COLUMN service_count smallint NOT NULL DEFAULT 1;
ALTER TABLE appointment ADD CONSTRAINT appointment_service_count_range
    CHECK (service_count BETWEEN 1 AND 5);

CREATE TABLE appointment_service (
    appointment_id uuid NOT NULL,
    position smallint NOT NULL,
    business_id uuid NOT NULL,
    service_id uuid NOT NULL,
    service_name varchar(200) NOT NULL,
    duration_minutes integer NOT NULL,
    price_eur numeric(12,2) NOT NULL,

    CONSTRAINT appointment_service_pkey PRIMARY KEY (appointment_id, position),
    CONSTRAINT appointment_service_appointment_fk
        FOREIGN KEY (business_id, appointment_id)
        REFERENCES appointment(business_id, id) ON DELETE RESTRICT,
    CONSTRAINT appointment_service_service_fk
        FOREIGN KEY (business_id, service_id)
        REFERENCES service(business_id, id) ON DELETE RESTRICT,
    CONSTRAINT appointment_service_position_range CHECK (position BETWEEN 1 AND 5),
    CONSTRAINT appointment_service_distinct UNIQUE (appointment_id, service_id),
    CONSTRAINT appointment_service_duration_range CHECK (duration_minutes BETWEEN 1 AND 480),
    CONSTRAINT appointment_service_price_nonnegative CHECK (price_eur >= 0),
    CONSTRAINT appointment_service_line_name_canonical CHECK (<the same expression as appointment_service_name_canonical of V11>)
);
CREATE INDEX appointment_service_service_idx ON appointment_service (business_id, service_id);
```

- **Money: capacity of the visit total (clarified).** A Service price is a non-negative EUR amount with at most **10 integer digits and 2 fraction digits**: at most `9 999 999 999.99`, that is **999 999 999 999 cents** (the `service.price` column is `numeric(12,2)`; the Service validator and the Appointment domain rules already enforce 10 integer digits). A visit has at most five Services, so a visit total is at most **4 999 999 999 995 cents = 49 999 999 999.95 EUR**, which has **11 integer digits** and does **not** fit `numeric(12,2)` (it holds at most 9 999 999 999.99). V13 therefore **widens `appointment.price_eur` to `numeric(13,2)`** (at most `99 999 999 999.99`, about twice the largest possible visit total), keeps the scale, and **keeps `appointment_service.price_eur` and `service.price` at `numeric(12,2)`**: no price limit is imposed on the guest, and the limits stay the approved five Services and 480 minutes. Widening the precision of a `numeric` at the same scale does not rewrite or change any stored value (PostgreSQL treats it as a metadata change), so every existing Appointment keeps its value bit for bit, and the V11 check `appointment_price_nonnegative` still applies. No extra upper-bound check is added: the visit-consistency trigger below makes the total equal to the sum of at most five lines of at most `9 999 999 999.99` each, so the bound is implied and cannot be exceeded.
- **Overflow-safe arithmetic (clarified).** Every money value is computed exactly and never as a binary floating-point number. In Java a price and a total are `BigDecimal` with scale 2 and are added with `BigDecimal.add`; the integer cents of a claim or a total are obtained with `movePointRight(2).longValueExact()` (it throws when the value is not an exact whole number of cents or does not fit a `long`) and summed with `Math.addExact`; the per-Service limit `999 999 999 999` and the total limit `4 999 999 999 995` are named constants, far below `Long.MAX_VALUE`, and the domain rule `requirePrice` for a visit total uses the total limit, while a line keeps the 10-digit limit. In SQL sums use `numeric` (`sum(price_eur)` is exact and cannot overflow here) and are compared as `numeric`, never cast to an integer or a float. In the browser a Service price from the profile is converted once to integer cents with `Math.round(price * 100)` and every sum is a sum of integers (the largest total, `4 999 999 999 995`, is far below `Number.MAX_SAFE_INTEGER`); a total is formatted from the integer cents, never from a float sum. The wire carries the reviewed price as an integer number of cents (section 6) and the response price as the exact decimal text of the stored `numeric`.
- **Meaning of the `appointment` columns after V13** (the V11 columns and constraints are untouched, except that `price_eur` is wider): `duration_minutes` and `price_eur` are the **visit totals**; `end_at = start_at + duration_minutes` and `occupied_until = end_at` therefore cover the whole visit; `service_id` and `service_name` are the **headline**, a copy of line 1 (the first Service in profile order), kept so every existing reader and `NOT NULL` column stays valid; `service_count` is the number of lines.
- **Backfill** (in V13, after the DDL and before the triggers): `INSERT INTO appointment_service (appointment_id, position, business_id, service_id, service_name, duration_minutes, price_eur) SELECT id, 1, business_id, service_id, service_name, duration_minutes, price_eur FROM appointment`. Every existing Appointment gets exactly one line from its own snapshot, deterministically, and `service_count` defaults to 1, so no existing row changes meaning.
- **Verification at the end of V13:** a `DO` block raises if any Appointment lacks its line or its totals differ from the line, so a wrong backfill fails the migration instead of shipping.
- **Integrity at commit.** A `DEFERRABLE INITIALLY DEFERRED` constraint trigger (function `appointment_visit_is_consistent`, raising `ERRCODE '23514'` with constraint name `appointment_visit_consistent`) runs after every insert or update of `appointment` and of `appointment_service` and checks, per Appointment: the number of lines equals `service_count`; the positions are exactly `1..service_count`; the sum of line durations equals `duration_minutes`; the sum of line prices equals `price_eur`; and `service_id` and `service_name` equal line 1. A `BEFORE UPDATE OR DELETE` trigger on `appointment_service` rejects every change, so lines are immutable snapshots (nothing is ever hard-deleted, ADR-0022). The persistence layer translates the violation by SQLState and constraint name into a typed internal failure, never into a public message.
- **Overlap protection is unchanged.** The existing `appointment_staff_no_overlap` exclusion on `[start_at, occupied_until)` of the visit row is the whole-visit overlap protection; lines have no time range of their own. Intermediate boundaries of the visit are derived (consecutive durations in `position` order), not stored.
- **Tenant isolation.** Every line carries `business_id` and two composite same-Business foreign keys, so a line cannot reference another Business's Appointment or Service.

### 4. Availability and eligibility (amends ADR-0013, ADR-0016)

`AvailabilityQuery` gains `calculate(businessId, serviceIds, staffMemberIdOrNull)`; the one-Service method stays and delegates. The occupied duration is the sum of the locked Service durations as elapsed time in whole minutes (so a clock change inside the visit is handled by the existing instant arithmetic, and the 15-minute grid, the horizon, the notice, and busy intervals are unchanged). The engine still sees one continuous duration and is not changed. The eligible set is the **intersection** of the StaffMembers assigned to each Service (active, working the interval). An empty intersection yields no slots. `booking-options` for the selection reports the same intersection (empty when nobody supports the combination); an explicit StaffMember who does not support every Service is `BOOKING_STAFF_UNAVAILABLE`.

### 5. Request normalization and fingerprint encoding v2 (amends ADR-0024)

The normalized request holds an ordered list of distinct Service identifiers (ascending UUID) and, for the new request shape, the **reviewed facts** of each (below). Every other field is normalized as before.

**Encoding version 2** (frozen in the implementation phase with golden vectors; any later change is a new version):

```
bytes   := "SYSBFP" | uint16 version = 2 | field*8
field   := tag (1 byte) | length (uint32, big endian) | value
0x01 BUSINESS          16 bytes (as v1)
0x02 SERVICES          count (1 byte, 1..5) | reviewed flag (1 byte: 0x00 absent, 0x01 present) |
                       per Service, in ascending UUID order (unsigned lexicographic order of the 16 bytes, see section 2):
                         service id (16 bytes: most significant half then least significant half, each big endian) |
                         [if the flag is 0x01] reviewed duration minutes (uint32 big endian) |
                                               reviewed price cents (int64 big endian)
0x03 STAFF_PREFERENCE  as v1
0x04 START             as v1
0x05 CUSTOMER_NAME     as v1
0x06 CUSTOMER_PHONE    as v1
0x07 CUSTOMER_EMAIL    as v1
0x08 NOTE              as v1
```

The Service identifiers are sorted by the **unsigned bytewise order of section 2** before encoding, whatever order the request listed them in; the 16 bytes of each identifier are laid out exactly as in encoding v1 (most significant 8 bytes, then least significant 8 bytes, big endian), so an identifier whose first byte is `0x80` or higher sorts **after** one below `0x80` (Java's signed order would place it first and would produce different bytes). The flag is 0x00 only for a request in the legacy shape and 0x01 only for the new shape, never mixed. The attempt identifier, clocks, and every server-computed value (totals, positions, names, the assigned StaffMember) are not encoded. **New attempts always use encoding v2** (also for one Service); the key version is unchanged. **Encoding v1 stays in the encoding registry for ever.** Replay recomputes the fingerprint with the encoding and key versions stored on the original row (ADR-0024): a v1 row verifies only against a request that normalizes to exactly one Service and no reviewed facts (the legacy shape, or any request of that content); a request with reviewed facts or more than one Service cannot equal a v1 attempt and is `BOOKING_ATTEMPT_MISMATCH`, which is correct because it is a different request. An unknown stored encoding or key version is still the uncertain technical failure of ADR-0024.

### 6. The review-consistency check

- **Reviewed facts (the whole list).** For each selected Service: its **duration in minutes** and its **price in EUR cents**. Nothing else: not the name or description (a rename or a description change does not change the terms), not the StaffMember (an explicit StaffMember is identified by id; with no preference the review states that the member is determined at confirmation), not the slot (the freshly offered slots decide it), not the timezone. The visit totals are the sums of these facts, so equal lines give equal totals and totals are not sent.
- **Wire representation (new request shape only).** Each element of `services` is exactly `{serviceId, reviewedDurationMinutes, reviewedPriceCents}`: `reviewedDurationMinutes` a JSON integer 1..480, `reviewedPriceCents` a JSON integer 0..999 999 999 999 (the range of `numeric(12,2)`); strings, fractions, negative values, and extra keys are `400 VALIDATION_ERROR`. Integer cents avoid decimal-string and floating-point ambiguity; the server converts a locked `price_eur` with scale 2 to exact cents.
- **Placement.** Inside the booking transaction, **after** the Business, the replay lookup, the StaffMember locks, the schedule revision lock, and the locking and activity check of every selected Service, and **before** the availability calculation, the Customer capability, and every write. A missing, inactive, or foreign Service is therefore still `BOOKING_SERVICE_UNAVAILABLE`, and a changed fact takes precedence over a vanished slot. A replay never reaches the check: an attempt that committed under the reviewed terms replays unchanged even after the terms change.
- **Comparison.** Exact equality of integer minutes and integer cents per Service of the locked set. Any difference means the review changed. The claims are discarded after the comparison; **persisted duration, price, totals, and snapshots always come from the locked rows**.
- **Response.** `409` with code `BOOKING_REVIEW_CHANGED` and the message «Условията на избраните услуги са променени. Прегледайте ги и потвърдете отново.» (**Proposed wording**, for human review). The body carries no price, duration, name, or identifier. The current facts are public (they are in the Business profile), so the client refreshes them with the existing read endpoints. No Appointment, no Customer, no counter other than the rate limiter's is written; the transaction rolls back; this is a **known outcome and a deterministic rejection**, never retried by the server and never `BOOKING_OUTCOME_UNCERTAIN`.
- **Client behavior.**
  - *First send of the attempt:* a proven rejection. The attempt is dropped (the next explicit confirmation draws a new attempt identifier). The journey re-reads the Business profile and the options and availability of the selection, keeps the choices that are still valid, updates every displayed duration, price, and total, clears a time that is no longer offered, removes a Service that is no longer offered with a notice (back to the Service step if none is left), shows «Условията на избраните услуги са променени. Прегледайте ги и потвърдете отново.», and **requires another explicit confirmation**: nothing is sent automatically.
  - *After an earlier uncertain send of the same attempt:* the later answer proves nothing about the earlier send (ADR-0024 notes). The attempt stays **frozen** with the same identifier and the same exact bytes (including the reviewed facts), the review stays uneditable, sticky uncertainty and the leave warning stay in force, and the message adds that a reservation that already exists was made with the terms the guest reviewed (**Proposed wording**: «Условията на услугите са променени. Ако резервацията вече е направена, тя е с условията, които прегледахте.»). «Опитайте отново» may still be used (a replay that finds the committed visit resolves it); «Започни отначало» goes through the warned restart and refreshes everything.
- **Legacy shape.** A request in the legacy shape carries no reviewed facts and is not checked: it is for direct API callers and compatibility, and the frontend always sends the new shape.

### 7. Transaction, locks, and retry (amends ADR-0023)

The attempt keeps its order: Business `FOR SHARE`, replay lookup, ACTIVE check, **eligible StaffMembers `FOR SHARE` in ascending identifier order**, the schedule revision `FOR SHARE`, then **all selected Services `FOR SHARE` in ascending identifier order** (the order `ServiceStore.lockReferences` already uses for assignment replacement, so one total order covers booking, assignment, and Service administration), then the active check, the **review-consistency check**, availability for the summed duration, the slot, the assignment, the Customer, and the single insert of the Appointment and its lines. The replay still holds only the Business lock and writes nothing. The total lock order of ADR-0025 is unchanged (Service rows are one level; within it, ascending identifier). **Identifier order for locks (clarified).** The lock order is the order of the locking statement itself: one statement of the form `SELECT … WHERE business_id = :b AND id IN (:ids) ORDER BY id ASC FOR SHARE`, which `ServiceStore.lockReferences` already is and the StaffMember locking statement already is (`ORDER BY id ASC FOR SHARE`). PostgreSQL sorts before it locks, and its `uuid` order is the unsigned bytewise order of section 2; so booking, assignment replacement, and every administration mutation take Service and StaffMember rows in one and the same order. The application **never pre-sorts identifiers with Java `UUID.compareTo` to decide a lock order**, and the booking reuses `lockReferences` (or a statement with the identical `ORDER BY`) instead of locking row by row in a Java-sorted loop. Where Java code needs the same order (the fingerprint, an assertion in a test), it uses the unsigned comparator of section 2, and a test proves that it agrees with the statement on identifiers across the high-bit boundary. **Observation, not changed here:** committed code also uses Java's signed `UUID.compareTo` for two other purposes, the ordering invariant of the published availability slot's StaffMember list and the final identifier tie-break of the deterministic StaffMember assignment; neither decides a lock, and aligning them would change which StaffMember a tie assigns, so that is a separate decision. Retry stays at most three whole new transactions; `ServiceUnavailable`, `BOOKING_REVIEW_CHANGED`, a slot or identity outcome, and validation are deterministic and never retried; a lost Service or StaffMember lock race (`40001`) retries and then sees the new facts. The Appointment and all its lines are inserted in the one transaction, so an overlap, a trigger violation, or any later failure rolls the whole visit back, with its Customer insert.

### 8. HTTP contracts (amends ADR-0026)

All routes keep the properties of ADR-0026 (session independence, `no-store`, exact route matchers, the fixed `instance`, `credentials: 'omit'`). The two new read routes are added to `PublicBookingRoutes` as exact matchers; every other path stays denied.

| Route | Contract |
|---|---|
| `GET …/{slug}/services/{serviceId}/booking-options` and `…/availability` | **Unchanged.** |
| `GET …/{slug}/booking-options?serviceId=…&serviceId=…` | `1..5` distinct valid identifiers (otherwise `400 VALIDATION_ERROR`). Response `{timezone, firstDate, lastDate, staff:[{id, displayName}]}` where `staff` is the intersection of the StaffMembers assigned to every Service (empty when none). A missing, inactive, or foreign Service is `409 BOOKING_SERVICE_UNAVAILABLE`; a selection whose current durations sum to more than 480 minutes is `409 BOOKING_SELECTION_TOO_LONG`. |
| `GET …/{slug}/availability?serviceId=…&serviceId=…&date=yyyy-MM-dd[&staffMemberId=uuid]` | The same shape as before, for the summed duration and the intersection; the same errors. |
| `POST …/{slug}/bookings`, **legacy shape** | `{attemptId, serviceId, staffMemberId\|null, start, customer, note}`: unchanged, one Service, no review check. |
| `POST …/{slug}/bookings`, **new shape** | `{attemptId, services:[{serviceId, reviewedDurationMinutes, reviewedPriceCents}], staffMemberId\|null, start, customer, note}`. Exactly one of `serviceId` and `services` (both or neither is `400`); `services` has 1..5 distinct elements; the claimed total is at most 480 (`400` otherwise); unknown keys at any level are `400`. The order of the array is irrelevant. |

**Responses.** Success is `201` for a new visit and `200` for a replay (including `CANCELLED`). The **shape follows the request shape**. Legacy: the existing key set `reference, status, service, staff, start, end, timezone`. New: `reference, status, services, totalDurationMinutes, totalPrice, staff, start, end, timezone` with `services[]` `name, durationMinutes, price` in **execution order** (the stored positions) and `totalPrice` a JSON number like `price`. A visit replays with its stored lines and its current status. A legacy-shape request can only match a single-line visit (a longer visit is a different request).

**New codes** (each with the fixed `title`, no submitted value, no identifier):

| HTTP | Code | When | Message |
|---|---|---|---|
| 409 | `BOOKING_REVIEW_CHANGED` | a reviewed duration or price differs from the locked Service (POST only) | «Условията на избраните услуги са променени. Прегледайте ги и потвърдете отново.» (Proposed) |
| 409 | `BOOKING_SELECTION_TOO_LONG` | the current durations of a selection sum to more than 480 minutes (GET only) | «Избраните услуги са с обща продължителност над 8 часа. Изберете по-малко услуги.» (Proposed) |

Every other code, status, and message of ADR-0026 is unchanged, including the rate-limit budgets (the two new reads share the read budgets), the 16 KiB body bound (five claims add well under 1 KiB), and the contact budget. `fieldErrors` are unchanged.

### 9. Replay and cancellation

A replay describes the whole visit (the stored lines, totals, and current status). Cancellation, when issue #21 adds it, is one status change on the visit row; the lines are historical snapshots and never change; a cancelled visit stops blocking time through the existing `WHERE (status = 'CONFIRMED')` predicate. No cancellation endpoint is added by this decision.

### 10. Frontend contract

- **Selection:** real checkboxes in a labelled group («Избор на услуги»), at most five, none required to be ordered. When five are selected, or when selecting another would make the total exceed 480 minutes, the remaining unchecked boxes are disabled with a visible explanation («Най-много 5 услуги и общо 8 часа за едно посещение.»); a Service whose own duration already exceeds the room left is disabled the same way.
- **Before «Напред»:** a polite status summary lists the selected Services, the total duration, and the total price in EUR; «Напред» is disabled until one is selected. The totals are the sums of the same facts that are later sent as reviewed.
- **State:** the selection is a set of Service identifiers; display and the request use the profile order. A change of the set clears the StaffMember preference, the date, and the time (as a Service change does today) and preserves the details.
- **Staff step:** when the reads report an empty intersection the step says «В момента няма служител, който предлага всички избрани услуги.» (**Proposed wording**) and offers «Промяна на избора», returning to the Service step.
- **Review:** one whole-visit review: every Service with its duration and price, the totals, the StaffMember, the date and time with the summed end, the details; «Промени» on the Service row opens the Service step in edit mode (ADR-0026 notes, task 08a).
- **Confirmation:** the server-returned Services, totals, StaffMember, date, time (Business timezone with offsets only where repeated), and the existing wording.
- **Attempt:** one attempt per visit; the exact frozen body now includes the services and reviewed facts; classification of `BOOKING_REVIEW_CHANGED` as above; every other rule of ADR-0024 is unchanged.

### 11. Compatibility

| Stored row | Request | Result |
|---|---|---|
| Existing Appointment (one backfilled line, encoding v1) | legacy shape, same payload | replays (`200`), the legacy response |
| the same | new shape with one Service and claims | `BOOKING_ATTEMPT_MISMATCH` (claims are not part of a v1 request; a different request) |
| New visit (encoding v2) | the same request again | replays (`200`) in the request's shape |
| New visit | the same Services in another click order | the same request, replays |
| New visit | any differing field, including a reviewed fact | `BOOKING_ATTEMPT_MISMATCH` |
| none | a single-Service legacy request | creates a one-line visit (encoding v2, flag 0x00) |

## Rationale

One Appointment row with immutable lines keeps one overlap range, one status, one idempotency key, and one reference, so atomicity, replay, and cancellation need no group concept, while per-Service snapshots keep the history and the confirmation stable. Two orders separate what the guest experiences from what identifies a request, so renames never break a replay. Putting the reviewed facts in the request, comparing them to locked rows before any write, and fingerprinting them makes "the guest agreed to these terms" a verified, replay-safe statement without ever trusting a client number as a price. Keeping the check distinct from the uncertain outcome keeps a clear rejection from being misreported as a lost commit.

## Tradeoffs and disadvantages

- A larger migration (a table, two triggers, a backfill) and a second encoding to support for ever.
- The headline columns of `appointment` duplicate line 1; readers that need the whole visit must read the lines.
- The reviewed facts add contract surface and a new `409`; a direct API caller using the legacy shape bypasses the check.
- A guest who is refused with `BOOKING_REVIEW_CHANGED` must review again, even for a small change.
- The two read routes duplicate the shape of the path routes.

## Risks and mitigations

- **Replay regression.** Stored-row fixtures written by the current code, golden vectors for v2, and compatibility tests (section 11).
- **Migration on populated data.** A real PostgreSQL upgrade test over rows created before V13, the end-of-migration verification, and the unchanged V1 to V12 checksums.
- **Deadlock or lock inversion.** One total order (ascending identifiers within each level, defined by the locking statement's `ORDER BY id`, which is PostgreSQL's unsigned bytewise `uuid` order and **not** Java's signed `UUID.compareTo`) and a deterministic two-transaction test, no sleeps, with identifiers on both sides of the high-bit boundary.
- **Overflow of the visit total.** The total column is widened to `numeric(13,2)` in V13; exact `BigDecimal`, `numeric`, and integer-cent arithmetic; tests at the maximum.
- **A long visit across a clock change.** Tests for a visit spanning the repeated and the skipped hour.
- **A reviewed-facts race.** The check runs under the Service share locks; a concurrent change either waits, or fails the lock with `40001` and the retry sees the new facts.
- **Client mistakes.** Strict parser (integers only), the 480 and count bounds on both sides, and frontend tests for every outcome.

## Consequences

The implementation is split into seven phases (task 08b): persistence, availability, orchestration and fingerprinting, HTTP, frontend, rendered review, and browser E2E; each needs its own approval and none has started. Until they are done the product books exactly one Service per booking. ADR-0013, ADR-0016, ADR-0022, ADR-0023, ADR-0024, and ADR-0026 carry amendment notes; `docs/data-model.md`, `docs/architecture.md`, `docs/product-spec.md`, `docs/security.md`, `docs/testing-strategy.md`, `docs/implementation-plan.md`, `docs/product-roadmap.md`, the UI guide, and task 08a are reconciled. Issue #19 and the release dependency on issue #21 are unchanged; issue #18 is not complete.

## Evidence

Direct evidence: `V11__add_appointments.sql` and `V12__add_business_schedule_revision.sql`; `FingerprintEncodingV1` and `RequestFingerprinter`; `BookingAttemptProcedure` (the order, the replay, the single Service); `AvailabilityQueryService` and `AvailabilityEngine` (one `occupiedDuration`); `ServiceStore.lockReferences` (ascending identifier locks) and its profile order; `PublicBookingRequestParser`, `PublicBookingHttpRecords`, and ADR-0026 (the contracts); the frontend attempt state machine and its tests (ADR-0024 notes). Inference: that a deferred constraint trigger plus an immutability trigger gives the integrity of the visit totals without a new application-level race, that widening `price_eur` to `numeric(13,2)` is a metadata-only change that leaves every stored value unchanged, and that the locking statements' `ORDER BY id` is the unsigned bytewise order of the Java comparator of section 2; the implementation phases must prove each on real PostgreSQL. Also direct evidence: `service.price numeric(12,2)` (`V5`), `ServiceInputValidator.MAX_PRICE_INTEGER_DIGITS = 10`, `AppointmentRules.MAX_PRICE_INTEGER_DIGITS = 10`, `StaffMemberStore.lockEligibleForBooking` and `ServiceStore.lockReferences` (`ORDER BY id ASC FOR SHARE`), `StaffAssignmentPolicy` and `AvailabilityRecords.AvailabilitySlot` (Java `UUID.compareTo`).

## Conditions for revisiting

Revisit for buffers between Services, a different StaffMember per Service, package or discount pricing, payments or deposits (which may need a stricter review contract), a configurable horizon, retiring the legacy request shape, Business-created multi-Service appointments (issue #21), or measured abuse of the new reads.
