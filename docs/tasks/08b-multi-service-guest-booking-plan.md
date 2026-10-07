# SpotYourSlot — Multi-Service Guest Booking: decisions and implementation plan

## Status

**Decided and documented; not implemented.** The product owner approved the multi-Service model on 2026-10-07 and the decisions are recorded in
[ADR-0027](../decisions/ADR-0027-book-several-services-as-one-atomic-visit-with-service-lines-a-versioned-set-fingerprint-and-an-explicit-review-consistency-check.md)
(read it for the normative contract, schema, fingerprint encoding, and review-consistency check; this task lists the work and the verification). The documentation
phase is the only phase done. **Until the implementation phases below are completed and approved, the product books exactly one Service per booking**: one StaffMember, one POST, one
Appointment. The single-Service limitation is real and is not hidden in the interface. Issue #18 is not complete, issue #19 is unchanged, and production public booking still
waits for issue #21. The original Phase 8 of issue #18 (browser journeys) is **not started**; the multi-Service browser journeys are phase M7 below and replace it.

## Approved decisions (summary; ADR-0027 is normative)

| # | Decision |
|---|---|
| 1 | One booking is one visit: one to five **distinct active Services**, **one StaffMember** performing them consecutively. |
| 2 | An `appointment_service` line table with per-Service snapshots; the Appointment carries the **visit totals**. New migration `V13` with a backfill of one line per existing Appointment; no committed migration is edited. |
| 3 | Execution order is the **public-profile order**; the checkbox click order affects neither execution nor request identity (the fingerprint uses ascending Service UUID). |
| 4 | At most **5 Services** and **480 total minutes** per visit. The visit total price (at most 49 999 999 999.95 EUR) is stored in `appointment.price_eur` **widened to `numeric(13,2)` in V13**; no guest price limit is added. |
| 5 | Eligible StaffMembers support **every** selected Service; availability is one continuous interval for the summed duration; an empty intersection is explained at the staff step with a return to the Service selection. |
| 6 | One atomic submission reserves the whole visit; no separate POSTs, partial bookings, or cosmetic multi-selection. |
| 7 | Single-Service (legacy-shape) requests and historical idempotent replays stay supported; fingerprint **encoding v1 stays**; **encoding v2** serves every new attempt. |
| 8 | Cancellation and replay describe the whole visit; no cancellation endpoint is introduced here. |
| 9 | The decisions are recorded in ADR-0027, amend ADR-0013, 0016, 0022, 0023, 0024, and 0026 through amendment notes (their text is preserved), and are reconciled in this task and 08a. |
| 10 | **Explicit review-consistency check** (adjustment to the drift policy): the request carries the reviewed duration and price of each Service as claims; the server compares them with the locked Services before availability, the Customer, and every write, and answers `409 BOOKING_REVIEW_CHANGED` when they differ. See ADR-0027 section 6. |

## Decisions the plan fixes on its own (routine technical choices within the approved model)

- **Reviewed facts:** per Service the duration in minutes and the price in EUR **cents** (integers); not the name, StaffMember, slot, or timezone; totals are derived, not sent. They are inside the fingerprint (a different claim is a different request) and are never persisted.
- **Response of the review change:** a bare `409` code with no figures; the client refreshes the public profile and the selection's reads.
- **Two codes are added** (`BOOKING_REVIEW_CHANGED` on POST, `BOOKING_SELECTION_TOO_LONG` on the new reads); the wording of both and of the two new frontend sentences is **Proposed** until the human review.
- **Two request shapes:** the legacy shape (`serviceId`, no review check, legacy response) for compatibility, and the new shape (`services[]` with the reviewed facts, the new response). The frontend always uses the new shape.
- **Read routes:** `GET …/booking-options?serviceId=…` and `GET …/availability?serviceId=…&date=…`; the path routes stay.
- **Appointment headline columns** (`service_id`, `service_name`) stay as a copy of line 1, so no V11 constraint changes and every existing reader keeps working.
- **Money capacity (clarified 2026-10-07):** five Services of the largest valid price (`9 999 999 999.99` each) sum to `49 999 999 999.95`, which does not fit `numeric(12,2)`; V13 widens only the visit-total column `appointment.price_eur` to `numeric(13,2)` (values preserved, metadata-only change) and leaves `service.price` and `appointment_service.price_eur` at `numeric(12,2)`. Exact `BigDecimal`, `numeric`, and integer-cent arithmetic everywhere (ADR-0027 section 3).
- **UUID order (clarified 2026-10-07):** "ascending UUID" is the unsigned lexicographic order of the 16 big-endian bytes (PostgreSQL's `uuid` order), **not** Java's signed `UUID.compareTo`. It defines the fingerprint order; lock order is whatever the locking statement's `ORDER BY id ASC FOR SHARE` produces (the existing `lockReferences` and StaffMember statements), and the application never pre-sorts for locks (ADR-0027 sections 2 and 7).

## What exists today (verified in the code)

| Area | Fact | Source |
|---|---|---|
| Appointment | one row = one Service, one StaffMember, one Customer; `duration_minutes` 1 to 480; `end_at = start + duration`; `occupied_until = end_at` | `V11__add_appointments.sql` |
| Overlap | `EXCLUDE USING gist (staff_member_id WITH =, tstzrange(start_at, occupied_until, '[)') WITH &&) WHERE (status = 'CONFIRMED')` | V11 |
| Idempotency | `booking_attempt_hash` unique per Business; `request_fingerprint`; encoding and key versions; HMAC over `FingerprintEncodingV1` (one Service) | V11, `FingerprintEncodingV1`, `RequestFingerprinter` |
| Availability | `AvailabilityQuery.calculate(businessId, serviceId, staffPreference)`; one `occupiedDuration`; 15-minute grid; 30-date horizon | `AvailabilityQueryService`, `AvailabilityEngine` |
| Booking transaction | Business share, replay, ACTIVE, eligible StaffMembers share (id order), revision share, the Service share, availability, assignment, Customer, insert | `BookingAttemptProcedure`, ADR-0023, ADR-0025 |
| Service locks | `ServiceStore.lockReferences` already locks several Services `FOR SHARE` in ascending identifier order (assignment replacement) | `ServiceStore` |
| Public Service order | `ORDER BY normalized_name ASC, id ASC` | `ServiceStore` |
| Request and response | strict key set; one `serviceId`; response with one `service` | `PublicBookingRequestParser`, `PublicBookingHttpRecords`, ADR-0026 |
| Latest migration | `V12`; the next is `V13` | `db/migration` |

## Implementation phases

Each phase is its own change with its own approval, is verified before the next starts, and keeps every earlier test green. Phases M1 to M4 are Strict work (a migration, transaction and lock behavior, the fingerprint, and a public contract); M5 is Standard; M6 and M7 are review and browser verification.

| Phase | Scope | Main verification |
|---|---|---|
| **M1 Persistence** | `V13__add_appointment_services.sql` (the `price_eur` widening to `numeric(13,2)`, the table, `service_count`, backfill, end-of-migration verification, deferred visit-consistency trigger, immutability trigger); the visit-total price rule of the Appointment domain (11 integer digits, exact `BigDecimal`); `AppointmentStore` writes and reads the lines; the Appointment domain records carry the lines; typed translation of the new constraint | real-PostgreSQL migration and constraint tests (below); V1 to V12 byte-identical |
| **M2 Availability and eligibility** | set-based `AvailabilityQuery`; the staff-eligibility contract reports the intersection; summed duration; the booking-options read model; no HTTP | engine and integration tests including a visit across 2026-10-25 |
| **M3 Orchestration and fingerprinting** | normalized multi-Service request; encoding v2 with golden vectors and v1 kept; the shared unsigned UUID comparator; Service locks through the `ORDER BY id` statement; the review-consistency check; the single insert of the visit and lines; replay of both shapes | transaction, replay-compatibility, concurrency, rollback, and drift tests |
| **M4 HTTP contract** | strict parser for both shapes; the two read routes; the new response; the two codes; exact security matchers; limiter and body bound unchanged | contract, privacy, security-routing, and compatibility tests |
| **M5 Frontend** | checkboxes, selected-Service summary with the total duration and EUR price before «Напред», set state, staff-step empty state, one whole-visit review, confirmation, the reviewed facts in the frozen body, the review-change handling | unit and component tests, lint, build; no attempt or privacy test weakened |
| **M6 Rendered review** | the isolated review stack and fixtures through supported APIs; simulated faults only for what supported APIs cannot produce; the human visual checkpoint | screenshots at 1280, 800, 375, and 320 px; keyboard, focus, contrast |
| **M7 Browser E2E** | Playwright journeys of the multi-Service booking against the real backend (this is the original Phase 8 of issue #18, extended), then the complete suite | the unmodified runner |

The stable acceptance for the final UI (M5 and M6): real checkboxes in a labelled group; a polite summary of the selected Services with the total duration and the total EUR price before «Напред»; the limits explained and enforced at the checkboxes; one whole-visit review; the confirmation lists the server-returned Services; the single-Service path (one checked box) behaves as today.

## Test plan (by concern)

**Upgrade and backfill (M1, real PostgreSQL).** Start from a database migrated to V12 with Appointments of several states (CONFIRMED and CANCELLED, with and without idempotency columns, ONLINE and MANUAL, DST-edge instants, two Businesses), apply V13: every Appointment has exactly one line equal to its own snapshot and `service_count = 1`; totals and headline equal the line; the consistency trigger accepts the data; the migration fails (and leaves nothing) if the verification query finds an inconsistent row (a controlled corrupt fixture); Flyway checksums of V1 to V12 unchanged; the schema after V13 equals the schema of a fresh install.

**Money capacity (M1, real PostgreSQL, plus M3 and M5 for the arithmetic).** Upgrade a V12 database holding an Appointment priced `9 999 999 999.99` (and `0.00`, `0.01`, and an ordinary value): after V13 each value is identical as text and as `numeric`, `pg_class.relfilenode` of `appointment` is unchanged (no rewrite), and the V11 check still rejects a negative price; insert a visit of **five lines at `9 999 999 999.99`**: it commits with total `49 999 999 999.95`, the consistency trigger accepts it, and a total that differs by one cent is rejected at commit; a value above the column capacity (`100 000 000 000.00`) is rejected; a replay of the historical maximum-price Appointment returns the same price text; domain tests: the exact sum of five maximum prices, `longValueExact` and `addExact` on the per-Service and total cent limits (`999 999 999 999` and `4 999 999 999 995` accepted, one cent more rejected), a price with three fraction digits and an eleven-digit single price rejected for a line while the eleven-digit total is accepted for a visit; the parser accepts `reviewedPriceCents` up to `999 999 999 999` as a JSON integer and rejects `1 000 000 000 000`, a fraction, and a string; frontend: the sum and the formatted total of five maximum prices are exact (integer cents) and equal the server's `totalPrice`.

**Constraints (M1).** Line count versus `service_count`, positions `1..n`, totals equal to the sums, headline equals line 1, a duplicate Service in a visit, a position of 6, a cross-Business Service or Appointment reference, a deferred violation seen only at commit, immutability of lines (update and delete rejected), the overlap exclusion still blocks an overlapping visit and allows adjacency, a cancelled visit stops blocking.

**Availability (M2).** The summed duration offers only intervals that fit; the intersection of assignments; an empty intersection yields no slots; an inactive Service or StaffMember; the 480-minute ceiling; a visit that crosses the repeated hour and one that crosses the skipped hour (elapsed time, offsets); the busy intervals of another visit.

**UUID order (M1 helper if needed, M3 for locks and fingerprint; real PostgreSQL).** The ordering fixture is four identifiers spanning the high-bit boundary: `00000000-0000-4000-8000-000000000001` (A), `7fffffff-ffff-4fff-bfff-ffffffffffff` (B), `80000000-0000-4000-8000-000000000000` (C), `ffffffff-ffff-4fff-bfff-ffffffffffff` (D), and a second fixture with an identical most significant half and least significant halves `7fff…`, `8000…`, `bfff…` (the half-boundary). (1) A unit test proves the unsigned comparator orders A, B, C, D (and `7fff…`, `8000…`, `bfff…`) and that Java's `UUID.compareTo` orders C, D, A, B (and `8000…`, `bfff…`, `7fff…`), so the trap is visible and a regression to `compareTo` fails. (2) A PostgreSQL test proves that `ORDER BY id` over the same identifiers returns the unsigned order and equals the comparator's order. (3) A statement-level test proves that `ServiceStore.lockReferences` (and the StaffMember statement) return and lock rows in that order whatever the input order. (4) **Deadlock test across the boundary:** two transactions lock overlapping Service sets that include identifiers from both sides (`A, C` and `C, A`; `B, D` and `D, B`) through the booking path, coordinated deterministically (no sleeps); neither deadlocks. (5) Golden vectors for fingerprint v2 include the sets {A, B, C, D} given in every permutation (the bytes are identical and list the identifiers in the order A, B, C, D), {C, A} (A first), the half-boundary triple, and a one-element set; changing one identifier byte changes the bytes. (6) A guard test (source scan, like the action-order test) fails if booking or fingerprint code calls `UUID.compareTo` or a signed comparison to order Service identifiers.

**Historical replay (M3).** Rows written by the current code (encoding v1) replay with the legacy request, with the same payload in any property order, and return the original snapshot and current status including `CANCELLED`; the new shape with one Service against a v1 row is a mismatch; a stored unknown encoding or key version is the uncertain technical failure, never a mismatch; golden vectors freeze v2 (including the flag, the ascending order, and a changed claim changing the bytes); the click order of the Services does not change the fingerprint.

**Atomic rollback (M3).** A failure injected after each step of the attempt (after the Service locks, after the review check, after availability, after the Customer, after the Appointment insert, after the first line insert, in a deferred trigger) leaves no Appointment, no line, and no Customer; an overlap loser leaves nothing; an identity conflict leaves nothing; the whole visit appears or none of it.

**Concurrency (M3, real PostgreSQL, deterministic coordination, no sleeps).** Two identical visits (one creates, one replays); two overlapping visits for one StaffMember (one wins); overlapping visits sharing only some Services; the same attempt with different Services (one success, one mismatch); a Service deactivation, an edit of a reviewed price or duration, and a StaffMember assignment change racing the booking (the lock either waits or fails with `40001`, the retry sees the new facts); two bookings that select the same Services in opposite click orders cannot deadlock (ascending locks); the schedule revision guard still serializes a schedule change against the visit.

**Review consistency and drift (M3 and M4).** Equal facts book; a changed duration, a changed price, a changed duration and price, one changed Service among several, a Service made longer so the total no longer fits, a changed price and an unchanged slot: each is `BOOKING_REVIEW_CHANGED` with no Appointment, no Customer, no counter written, and a rolled-back transaction; precedence (an inactive Service is `BOOKING_SERVICE_UNAVAILABLE` before the review check; the review check precedes a vanished slot and the Customer); a changed name or description is not a review change; a replay after the terms changed still replays; the claims are never persisted (the stored price and duration come from the locked rows); a claim mismatch with the fingerprint (a replay with other claims is `BOOKING_ATTEMPT_MISMATCH`); malformed claims (strings, fractions, negatives, out of range) are `400`; the legacy shape is unchecked.

**HTTP (M4).** The exact key sets of both request and response shapes; both shapes together or neither is `400`; one to five distinct identifiers, six and duplicates are `400`; the claimed total above 480 is `400`; `BOOKING_SELECTION_TOO_LONG` on the reads; unknown keys; the empty and the non-empty intersections of `booking-options`; the new matchers are exact and every other path stays denied; session independence and `no-store`; the limiter and the body bound unchanged; privacy sentinels across responses, logs, and errors.

**Frontend (M5).** Checkbox group semantics and keyboard; the summary of the Services, the duration, and the EUR price before «Напред»; the limits (a sixth, and a Service that exceeds 480 minutes, are disabled with the explanation); the click order does not change the request body; a change of the set clears the preference, the date, and the time and keeps the details; the empty-intersection explanation with «Промяна на избора»; the whole-visit review and edit mode; the confirmation of several Services; the request shape and its exact frozen bytes.

**Frontend uncertainty and drift (M5).** `BOOKING_REVIEW_CHANGED` on a first send: the attempt is dropped, the profile and the selection are re-read, the figures and totals are refreshed, an invalid time is cleared, a vanished Service is removed with a notice, nothing is sent automatically, and the next explicit confirmation uses a **new** attempt identifier; after an earlier uncertain send it stays frozen with the same identifier and exact bytes, sticky uncertainty, the leave warning, and the warned restart; a later rollback, `429`, or review change never clears an earlier uncertainty; no automatic POST retry; `Retry-After` only releases the user-triggered retry; no personal data or attempt identifier in URLs, storage, `history.state`, titles, or logs (the existing privacy tests stay).

**Browser (M6 and M7).** The journeys against the real backend: a one-Service visit and a three-Service visit with the repeated hour; the combination nobody supports; a price changed by the owner between review and confirmation (a supported administration API call) and the refreshed review; the competing booking; the lost-response replay and the review change after an uncertain send through the throwaway proxy (labelled simulated).

## Risks

- **Replay regression:** stored v1 fixtures, golden v2 vectors, the compatibility matrix.
- **Migration on populated data:** the upgrade test, the end-of-migration verification, unchanged V1 to V12 checksums.
- **Lock inversion:** ascending identifiers at every level and a deterministic test.
- **A new public contract surface:** strict parsing, exact key sets, and tests for every shape and code.
- **Consumers still to come** (issue #21 calendar and history, issue #19) read one Service per Appointment today; the headline columns keep them working until they read the lines.
- **Scope creep** into per-Service StaffMembers, packages, payments, or a cancellation endpoint; excluded by the approved model.

## Remaining questions

None blocks starting M1. For the human review: the wording of `BOOKING_REVIEW_CHANGED`, `BOOKING_SELECTION_TOO_LONG`, the frozen-state sentence, the staff-step sentence, and the limit explanation is **Proposed**; the legacy request shape is kept for compatibility without the review check and its retirement is a later decision.
