# SpotYourSlot — Appointment Core and Guest Booking

Status: Phase 1 (decisions and documentation), Phase 2 (the `appointment` schema with its overlap exclusion, the
internal domain and persistence, and the real busy-interval source), Phase 3 (the Business schedule revision guard and the
schedule mutations' participation), and Phase 4 (booking orchestration, idempotency, HMAC fingerprints, bounded retry)
are committed (`a0243f7`, `18052b8`, `70e31c9`, `63f6019`). Phase 5 (the public availability and booking API,
session-independent security, and the bounded limiter; backend only) was reviewed and approved and is committed as
`3576843`. Phase 6 (the public guest-booking frontend) is implemented and verified by automated tests and awaits review and commit; it
has had **no rendered browser review and no human visual approval**. A safety correction of its attempt state machine (see "Phase 6 correction") is part of it. Phases 7 and 8 are planned and not started. Issue #18 is closed
only on explicit approval; issue #19 is not changed.
GitHub issue: #18 — Build appointment core and guest booking flow
Depends on: #16, #17, #20; relates to #19 (partly satisfied here) and #21 (release dependency)
Decision records: [ADR-0022](../decisions/ADR-0022-model-appointments-with-snapshots-two-statuses-and-a-database-overlap-exclusion.md),
[ADR-0023](../decisions/ADR-0023-book-appointments-in-one-repeatable-read-transaction-with-ordered-locks-and-bounded-whole-transaction-retry.md),
[ADR-0024](../decisions/ADR-0024-make-booking-attempts-idempotent-with-a-versioned-hmac-request-fingerprint-and-distinguish-uncertain-outcomes.md),
[ADR-0025](../decisions/ADR-0025-coordinate-schedule-changes-with-booking-through-a-business-level-schedule-revision-guard.md),
[ADR-0026](../decisions/ADR-0026-expose-guest-booking-through-narrow-public-contracts-with-session-independent-endpoints-and-bounded-abuse-protection.md)

## Task purpose

Create the authoritative Appointment record and let an unauthenticated guest create one
`CONFIRMED` Appointment through a mobile-friendly public journey, without an account, without a
double booking, and without exposing private data. Issue #18 is Strict risk: a migration, transaction
boundaries, concurrency and locking, a cross-module contract change, the first public state-changing
endpoint, and personal-data handling. Each phase below needs its own explicit approval before
persistent changes.

## Verified starting facts

Verified against the repository at `d3c82dc` (not only the prompt):

- `scheduling.AvailabilityQuery` provides the internal calculation under fixed MVP values and joins
  only a repeatable-read or serializable transaction; it has no public endpoint.
- `GET /api/public/businesses/{slug}` is the only public route; `PublicService` has no identifier; no
  StaffMember data is public; deeper public paths are denied.
- `CustomerIdentification.findOrCreate` is `MANDATORY`; `CustomerConcurrentConflict` requires rollback
  and a completely new transaction; `CustomerOperationFailure` is not retryable.
- No `booking` module, no `appointment` table (migrations end at `V10`), and no non-authentication
  rate limiter exist; `NoBookingBusyIntervalSource` is still the placeholder.
- There is no Business working-hours store: Business-wide availability inputs are lifecycle, timezone,
  and `BUSINESS_CLOSURE` exceptions.

## Approved decisions (2026-10-06)

| # | Decision | Record |
|---|---|---|
| D1 | `appointment` and its exclusion constraint are created together in `V11`; #19 is reconciled below | ADR-0022 |
| D2 | No Customer contact snapshot on the Appointment | ADR-0022 |
| D3 | Statuses `CONFIRMED` and `CANCELLED` only; documents amended below | ADR-0022 |
| D4 | Source `ONLINE`/`MANUAL` | ADR-0022 |
| D5 | Store `start_at`, `end_at`, and `occupied_until` (zero buffers: equal ends) | ADR-0022 |
| D6 | Reuse the existing Service and StaffMember UUIDs in narrow Business-scoped public contracts; the public allowlist is amended | ADR-0026 |
| D7 | Snapshot Service name, duration, EUR price, and the assigned StaffMember display name; replay returns the original snapshots and the current status | ADR-0022, ADR-0024 |
| D8 | Narrow CSRF exemption, session-independent endpoints, public requests omit credentials, exact-origin CORS | ADR-0026 |
| D9 | Booking-owned bounded in-process limiter with the initial limits; replay is limited; documented single-instance and trusted-proxy limits | ADR-0026 |
| D10 | No verification, no Customer account; a name and at least one of phone or email (Issue #20 policies); a short privacy notice; no marketing consent | ADR-0026 |
| D11 | Optional plain-text note, at most 500 code points | ADR-0022, ADR-0026 |
| D12 | "Без предпочитание" uses the approved deterministic assignment rule; the assigned member is shown after confirmation | ADR-0023, ADR-0026 |
| D13 | One `/{slug}` URL, in-memory steps, no personal data in URLs, storage, or `history.state` | ADR-0026 |
| D14 | The Business timezone is public in the booking contracts only | ADR-0026 |
| D15 | Neutral step headings; informal wording amended | ADR-0026 |
| D17 | Versioned HMAC request fingerprint over all normalized fields; mismatch on any change | ADR-0024 |
| D18 | Matching replay after suspension is permitted; a later-cancelled Appointment replays `200` with `CANCELLED` | ADR-0024 |
| D19 | Approved known-rollback and uncertain-outcome messages | ADR-0024, ADR-0026 |
| D20 | Business-level schedule revision guard (Option B) | ADR-0025 |

Technical clarifications recorded in the ADRs: the complete mutation audit and total lock order
(ADR-0025); encoding versus key version and rotation (ADR-0024); proven rollback versus uncertain
commit and the frozen uncertain UI state (ADR-0024); never continue an aborted transaction and retry only
in a completely new one (ADR-0023); the release dependency (below).

Corrections recorded after the first Phase 1 review (2026-10-06):

- **Transaction ownership (ADR-0023):** booking orchestration is invoked with no active transaction and
  rejects an active caller transaction before any booking work; `TransactionTemplate` with `REQUIRED` alone does not guarantee a
  new transaction, `REQUIRES_NEW` stays rejected, and every attempt is a separate transaction.
- **Replay locking (ADR-0023, ADR-0024):** a replay takes only the initial Business `FOR SHARE` lock; no other
  booking lock, no availability validation, no Customer call, no write.
- **Attempt-ID entropy (ADR-0024):** the browser generates the ID with a cryptographically secure generator and
  direct callers must supply an unpredictable one; the server validates only the format, cannot prove entropy, and
  the ID is not an authentication credential.
- **Customer identification scope (ADR-0022):** Customer matching is the only way to obtain a Customer ID for
  guest booking only; future manual booking (#21) may select an existing same-Business Customer through
  `CustomerReferenceAccess`.

## Issue #19 reconciliation

D1 satisfies these #19 requirements for creation. #19 remains incomplete and is not changed by this work.

| #19 requirement | Status |
|---|---|
| Overlap of blocking appointments for one StaffMember is impossible; different StaffMembers and Businesses are independent; half-open adjacency; full duration counted | Satisfied by the `V11` exclusion (Phase 2 tests) |
| Cancelled appointments do not block | Satisfied (a directly inserted `CANCELLED` row is tested in Phase 2) |
| Two concurrent identical or partially overlapping creations: at most one succeeds; the loser leaves no partial or hidden data | Satisfied for guest creation (Phases 2 and 4) |
| Revalidate at the final mutation; refreshed availability no longer offers the time | Satisfied (Phases 2 and 4: the real `BusyIntervalSource`) |
| Safe, private public conflict feedback; client input cannot bypass protection | Satisfied for the public contract (Phase 5) |
| Timezone and DST: no duplicate or ambiguous booking | Satisfied at the instant level; calendar-level behavior with #21 |
| Statuses that occupy time (`CONFIRMED` only), no pending state, half-open boundary model, repeated-submission recognition (attempt ID plus fingerprint), no override in the MVP | Decided here |
| Business-created (manual) creation, move, StaffMember or Service change, return to `CONFIRMED`, "a failed update leaves the original unchanged", authenticated conflict feedback, Business-user races, buffers (not applicable: zero), automatic alternative suggestions | **Remain for #19/#21** (the constraint also guards `UPDATE`) |

The manual-creation path reuses the same store and typed overlap outcome. The GitHub issue and board
are not modified by this work.

## Statuses: the amendment of conflicting permanent documents

No ADR defines Appointment statuses. These approved permanent-document passages are amended by
ADR-0022 (original text quoted so the history is preserved):

- `docs/implementation-plan.md`: "Use only `CONFIRMED`, `CANCELLED_BY_CUSTOMER`, `CANCELLED_BY_BUSINESS`,
  `COMPLETED`, and `NO_SHOW`."
- `docs/product-spec.md`: "MVP statuses are `CONFIRMED`, `CANCELLED_BY_CUSTOMER`, `CANCELLED_BY_BUSINESS`,
  `COMPLETED`, and `NO_SHOW`. `COMPLETED`/`NO_SHOW` are allowed only at or after start."
- `docs/data-model.md`: "Statuses are `CONFIRMED`, `CANCELLED_BY_CUSTOMER`, `CANCELLED_BY_BUSINESS`,
  `COMPLETED`, and `NO_SHOW`." and "source (`ONLINE`/`STAFF`)".
- `docs/architecture.md` and `docs/security.md`: the rule that `COMPLETED` and `NO_SHOW` require the
  current time at or after `start_at`.
- `docs/testing-strategy.md`: "`COMPLETED`/`NO_SHOW` rejection before start and acceptance at/after start."

Amended rule: `CONFIRMED` and `CANCELLED` only; cancellation attribution, cancellation time, the
late-cancellation flag, `COMPLETED`, `NO_SHOW`, and the before-start rule are deferred to the issues that
implement them (#13 and #21) through forward migrations; source is `ONLINE` or `MANUAL`.

## Wording amendment

The informal question «При кого искаш да запазиш час?» and the rule against a mandatory performer noun are
replaced by the neutral headings of D15 and formal register in the public flow. Amended: `AGENTS.md`,
`docs/product-spec.md`, `docs/implementation-plan.md`, and `docs/testing-strategy.md`. The generic
administration wording «Екип» and «Член на екипа» is unchanged.

## Contracts and modules (decisions; exact names finalized in the named phase)

- **Schema (Phase 2, `V11`):** `appointment` as in ADR-0022. **Phase 3, `V12`:** `business_schedule_revision`
  (ADR-0025; implemented).
- **Public routes (Phase 5, implemented):** the amended profile (`services[].id`), `booking-options`, `availability`, and
  `POST …/bookings` (ADR-0026; exact contract in its Phase 5 implementation notes).
- **New `booking` module:** depends on `business`, `catalog`, `workforce`, `scheduling`, `customer`, and
  `shared.contact`; nothing depends on it; `scheduling` never depends on it. It implements
  `scheduling.BusyIntervalSource` and removes `NoBookingBusyIntervalSource` and its wiring test in Phase 2
  (ADR-0016).
- **New narrow published contracts:** `business` (lock by slug at any lifecycle status, Phase 4; the schedule
  revision `ScheduleRevisionBump` and `ScheduleRevisionGuard`, implemented in Phase 3), `catalog` (Service identifier in the public contract; a lockable bookable-Service snapshot
  with name, duration, and price), `workforce` (public bookable StaffMembers with display name; a lockable
  StaffMember snapshot with display name and creation time). No existing module gains a dependency on
  `booking` or `customer`. For guest booking the Customer ID comes only from
  `CustomerIdentification.findOrCreate`; manual booking (#21) may instead select an existing same-Business
  Customer through `CustomerReferenceAccess`.

## Test plan by phase

| Phase | Required evidence |
|---|---|
| 2 | Schema tests (columns, every constraint, same-Business composite keys, cross-Business rejection, the exclusion: identical, partial, adjacent, different StaffMembers and Businesses, cancelled rows, DST instants); two-writer PostgreSQL races without sleeps; snapshot immutability; the real busy source returning only `CONFIRMED` windows in one bulk query; placeholder removal and wiring; module boundaries |
| 3 | Revision migration, backfill, and new-Business row; each audited mutation (weekly replacement, every exception kind and operation) bumps exactly once and a rejected mutation does not; exclusive and shared lock conflicts with PostgreSQL wait evidence; no change to existing mutation contracts; an enumeration test of availability-affecting statements |
| 4 | Whole transaction: an active caller transaction is rejected before any booking work; every attempt and retry runs in a distinct PostgreSQL transaction with its own transaction identifier and snapshot and without `REQUIRES_NEW`; replay holds only the initial Business lock; rollback leaves zero Customer rows on every failure path; lock order; revalidation (suspension, deactivation, assignment, Service change); assignment rule; fingerprint golden vectors and a matrix (each field changed, preference versus assigned member, other Business); key and encoding versions and rotation; replay after suspension, deactivation, and cancellation; concurrent identical and conflicting attempts; retry classification and exhaustion; proven-rollback versus uncertain classification; **all schedule commit-order tests of ADR-0025** |
| 5 | Exact key sets and privacy sentinels; Business A/B isolation and guessed identifiers; collapsed 404; unknown-field rejection; CSRF exemption scope and session independence; limiter capacity, expiry, saturation, replay limiting; error contract; no-store and no cookie |
| 6 | Vitest with fake clocks and deferred promises: abort and stale ordering, the uncertain-state machine, duplicate-submit protection, no personal data in URL, storage, or `history.state`; golden contact vectors; DST repeated-hour display |
| 7 | Rendered browser review and **human visual approval** (below) |
| 8 | Playwright journeys, final acceptance mapping every acceptance criterion to evidence, documentation completion |

Reliability rules in `docs/testing-strategy.md` apply: wait for the asserted state, control time, order
overlapping work with deferred promises, diagnose CI failures before calling them flaky.

## Phase plan

| Phase | Scope | Risk | Gate |
|---|---|---|---|
| 1 | Decisions, ADRs, documentation (this record) | Fast | Review and commit approval |
| 2 | `V11` appointment schema with the exclusion constraint, domain and store, real busy source, placeholder removal | Strict | Approval |
| 3 | Schedule coordination (`V12`, guard contract, weekly-schedule and exception participation) | Strict | Approval |
| 4 | Booking orchestration, new contracts, Customer integration, retries, idempotency | Strict | Approval |
| 5 | Public API, security, abuse protection | Strict | Approval |
| 6 | Frontend implementation | Standard | Approval |
| **7** | **Rendered browser review and human visual approval** | Standard | **Stop for human approval** |
| 8 | Browser E2E and final acceptance | Standard | Closure only on explicit approval |

Phase 7 follows the UI guide process (§18): exact startup, fixtures created only through supported APIs,
viewports about 1280, 1024, 800, and 375, 200% zoom, keyboard-only use, wrapped validation errors, the
frozen uncertain state, replay and cancelled results, a repeated DST hour, duplicate-submit protection. It
is separate from, and precedes, the Phase 8 end-to-end acceptance.

## Release dependency

Issue #18 can be implemented and verified independently. **Production public booking must wait for #21**
so that a Business can see and manage the appointments it receives. This is a release dependency, not an
implementation blocker, and is repeated in the roadmap.

## Proposed details (not approved; do not treat as Accepted)

These stay Proposed until the named phase settles them with evidence:

1. ~~Exact names and column layout of the revision table, its contract, and the migration number~~ Settled in
   Phase 3: `V12`, `business_schedule_revision`, `ScheduleRevisionBump` and `ScheduleRevisionGuard` (ADR-0025
   implementation notes, `docs/data-model.md`).
2. ~~How a new Business receives its revision row~~ Settled in Phase 3: a database trigger on `business` inserts it
   in the same statement and transaction.
3. ~~Final contract and constraint names, and the public-reference alphabet~~ Settled in Phase 2: the constraint names
   are in `docs/data-model.md` and the alphabet is Crockford base32 (ten characters). Published-contract names remain
   Phase 4.
4. ~~The byte layout of the canonical request encoding and the exact note normalization~~ Settled in Phase 4
   (ADR-0024 implementation notes).
5. ~~Fingerprint key ring names and startup validation~~ Settled in Phase 4 (ADR-0024 notes; a startup check of every
   stored key version is not implemented); the limiter was settled in Phase 5 (ADR-0026 notes).
6. ~~COMMIT classification~~ Settled in Phase 4 (ADR-0024 notes; real commit errors could not be provoked).
7. ~~The HTTP status of the `GET` routes for an unavailable Service, `Retry-After`, and the fixed problem
   `instance`~~ Settled in Phase 5: `409 BOOKING_SERVICE_UNAVAILABLE` on the `GET` routes too; `Retry-After` on `429`
   (seconds until the window or capacity frees) and `2` on both `503`; the instance `/api/public/businesses`.
8. ~~The per-address aggregate limiter budget and the capacity default~~ Settled in Phase 5: 30 bookings and 600 reads
   per address across Businesses per 15 minutes; 50 000 counters.
9. The wording of the privacy notice and of the confirmation shown when leaving the frozen uncertain state. **Still Proposed.** Phase 6
   implemented the recommendation below so that it can be reviewed in the browser in Phase 7; it is not approved. Privacy notice (details
   step), corrected after review: «Данните ви се предоставят на бизнеса за записване и управление на резервацията.» (it says that the data is given to the Business and does not imply
   that the Business does not keep it; no legal-consent, retention, or marketing claim). It is wording **awaiting human visual review**, not approved. Leave confirmation while data is entered: «Резервацията не е завършена. / Ако напуснете, въведените данни ще бъдат
   загубени.»; while sending or uncertain: «Резервацията може вече да е направена. / Ако напуснете, няма да видите резултата.» plus the
   Business telephone when it is public.
10. ~~The mechanism that makes public endpoints ignore the session~~ Settled in Phase 5: `PublicBookingRoutes` and
    `DatabaseSessionFilter.shouldNotFilter`.

## Exclusions

Customer accounts and login; contact verification; marketing consent; self-service cancellation and
rescheduling; email and SMS; the Business calendar and manual creation (#21); deferred #19 requirements;
payments; waiting lists; slot holds or reservations; recurring, group, or multi-Service appointments; buffers;
Business-configurable horizon or notice; impact warnings for confirmed appointments; a read-by-reference
endpoint; trusted-proxy, edge, or shared rate limiting; hosting.

## Known residual risks

- An unverified guest can create fake bookings; limits only slow it, and the release dependency prevents
  exposure before owners can manage them.
- Behind a proxy all guests share one remote address until trusted forwarding is separately approved.
- A refresh or closed tab while the uncertain state is shown can lead to a second booking; `beforeunload` is
  best-effort.
- A returning guest whose phone or email changed gets only the generic identity-conflict message and must
  contact the Business (ADR-0020).
- Historical fingerprint keys must be retained while Appointments exist.

## Phase 1 record

Phase 1 is documentation only: this record; ADR-0022 to ADR-0026; amendment notes on ADR-0008, ADR-0012,
ADR-0015, ADR-0016, ADR-0017, and ADR-0020; the four review corrections listed under the approved decisions; reconciliation of the README, architecture, data model, security,
product specification, implementation plan, testing strategy, roadmap, ADR index, and `AGENTS.md`. It changes
no application code, migration, test, configuration, or dependency, and runs no application test. GitHub issues
and the board are unchanged. The roadmap shows Issue #18 and #19 work as planned and in progress only; no
implementation is complete and no future UI is visually approved.

## Phase 2 record (implemented, verified, reviewed, and committed as `18052b8`)

Scope: the `appointment` schema with its overlap exclusion, the internal domain and persistence, and the
real Scheduling busy-interval source. No booking operation, Customer integration, schedule revision,
HMAC computation, key configuration, public endpoint, security change, rate limiting, frontend, or E2E was
added, and no dependency, configuration, or existing migration changed. The clarifications and the two
deliberate tightenings are recorded as implementation notes in ADR-0022.

### What was implemented

- **`V11__add_appointments.sql`** (SHA-256 `3135ee8ffc34df9f5a10ce85170a160e5dfc1c4579c1c1aa1f118318783bbb0f`): the `appointment` table and the exclusion
  `appointment_staff_no_overlap EXCLUDE USING gist (staff_member_id WITH =, tstzrange(start_at, occupied_until, '[)')
  WITH &&) WHERE (status = 'CONFIRMED')` in one migration. Four composite or single restrictive foreign keys
  (`appointment_business_fk`, `appointment_customer_fk`, `appointment_service_fk`,
  `appointment_staff_member_fk`); nineteen CHECK constraints; three UNIQUE constraints
  (`appointment_business_id_id_unique`, `appointment_business_public_reference_unique`,
  `appointment_business_attempt_hash_unique`); no other index. Every column is `NOT NULL` except `customer_note`
  and the four idempotency columns. The exact names are listed in `docs/data-model.md`.
- **`bg.spotyourslot.booking.domain`** (seven files): `AppointmentSource` (`ONLINE`, `MANUAL`), `AppointmentStatus`
  (`CONFIRMED`, `CANCELLED`), `BookingAttempt` (deeply immutable, redacted), `NewAppointment`, `Appointment`,
  `BlockingWindow`, and the package-private `AppointmentRules`, which mirror the database constraints. A snapshot
  name must already equal its own canonical form under the repository's existing `shared.contact`
  canonicalization (NFKC, approved whitespace collapsed to single spaces, no edge whitespace): a noncanonical
  Service or StaffMember name is **rejected**, never changed, because it would no longer be the source fact; the
  `V11` canonical-name checks remain as the database guarantee. No Customer contact data exists on any record.
- **`bg.spotyourslot.booking.infrastructure`:** `AppointmentStore` (`insert`, `find`, `findByAttemptHash`,
  `findBlockingWindows`; one statement each, no lock, no own transaction, every statement Business-scoped),
  the sealed cause-free `AppointmentPersistenceException`, and `BookingBusyIntervalSource`.
- **Placeholder removal:** `NoBookingBusyIntervalSource`, `NoBookingBusyIntervalSourceTests`, and
  `BusyIntervalSourceWiringIntegrationTests` (placeholder-only) were deleted (3 files); `BusyIntervalSourceFailFastTests`
  now registers test stubs and still proves that zero or two implementations fail startup.

### Existing tests changed because V11 legitimately changes their subject

- The migration lists in `BusinessSchemaIntegrationTests`, `WorkingScheduleSchemaIntegrationTests`,
  `ScheduleExceptionSchemaIntegrationTests`, and `CustomerSchemaIntegrationTests` now end with `"11"`, and the two
  upgrade tests assert latest version 11. Their byte-integrity checks of V1 to V10 are unchanged.
- Assertions that no `appointment` table exists were removed from `BusinessSchemaIntegrationTests`,
  `StaffSchemaIntegrationTests`, `WorkingScheduleSchemaIntegrationTests`, and `IdentitySchemaIntegrationTests`,
  and the Customer foreign-key test now expects `customer_business_fk->business` plus
  `appointment_customer_fk->customer` (renamed `customerReferencesOnlyItsBusinessAndIsReferencedOnlyByTheAppointmentTable`).
- `AvailabilityQueryStatementCountIntegrationTests` now expects five statements (four availability reads plus the
  one bulk busy read) for one and for ten StaffMembers, and asserts the fifth is `from appointment` filtered to
  `CONFIRMED`; the no-StaffMember case is still three.
- `AvailabilityModuleBoundaryTests` and `CustomerModuleBoundaryTests` no longer assert that no `booking` module
  exists; they assert that it exists, that `scheduling` and `customer` do not depend on it, and (new
  `BookingModuleBoundaryTests`) that `booking` depends only on `scheduling` and the `shared.contact` policy and nothing depends on `booking`.

### Verification (executed)

The final run was on 2026-10-06 against base commit `a0243f7` (the committed Phase 1) **plus the uncommitted Phase 2
working tree** (nothing staged); no `backend/` file changed after it started. Local verification only; no CI run
exists for this uncommitted state.

- **Final complete `./mvnw --batch-mode verify`** (PostgreSQL 18.4 through Testcontainers): **120 test classes, 2469
  tests, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS`** (1 min 28 s).
- **Verification history (three different working-tree states, not reruns of one tree):**
  1. A first complete run (2430 tests) failed 13 tests. All 13 were older tests whose expectations `V11`
     legitimately changes (listed above): the expectations were corrected and no other assertion was weakened.
  2. A second complete run on that corrected tree passed (2430 tests, 0 failures).
  3. A review correction then made the domain reject noncanonical snapshot names and added regression tests (26 in
     `AppointmentDomainTests`, 13 in `AppointmentSchemaIntegrationTests`), moved one store test, and widened the
     boundary tests for the `shared.contact` dependency. The focused tests and then a new complete run on this final
     tree passed with 2469 tests (2430 + 39 added). Only this third run tests the final code.
- **New Phase 2 classes (244 tests):** `AppointmentSchemaIntegrationTests` 98, `AppointmentDomainTests` 60,
  `AppointmentStoreFailureTranslationTests` 43, `AppointmentStoreIntegrationTests` 13,
  `BookingBusyIntervalSourceIntegrationTests` 10, `AppointmentOverlapConcurrencyIntegrationTests` 7,
  `BookingModuleBoundaryTests` 7, `BookingAvailabilityIntegrationTests` 4,
  `BookingBusyIntervalSourceWiringIntegrationTests` 2 (`AppointmentFixtures` is a test support class).
- **Changed existing classes (all passing):** `AvailabilityQueryStatementCountIntegrationTests` 10,
  `BusyIntervalSourceFailFastTests` 3, `BusinessSchemaIntegrationTests` 57, `CustomerSchemaIntegrationTests` 34,
  `ScheduleExceptionSchemaIntegrationTests` 33, `WorkingScheduleSchemaIntegrationTests` 16,
  `StaffSchemaIntegrationTests` 15, `IdentitySchemaIntegrationTests` 3, `AvailabilityModuleBoundaryTests` 5,
  `CustomerModuleBoundaryTests` 17.
- **Migration integrity:** V1 to V10 are byte-identical to their pinned SHA-256 values and to `HEAD` (checked in
  `AppointmentSchemaIntegrationTests` and by hash against `HEAD`), V11 is new and pinned, and `git status` shows no
  V1 to V10 file changed.
- **Java hygiene:** a focused search over every changed or new Java file (32 files present in the tree, the three
  deleted ones excluded) found no compressed empty constructor, method, class, or record body, and no wildcard
  import. `git diff --check` is clean.
- The frontend suites were not run: no frontend file changed.

### File inventory (exact, from `git`; 46 changed paths)

| Group | Count | Detail |
|---|---:|---|
| New (untracked) | 22 | 7 `booking.domain` files, 3 `booking.infrastructure` files, `booking/package-info.java`, `V11__add_appointments.sql`, and 10 test files (`AppointmentFixtures`, `AppointmentDomainTests`, `AppointmentOverlapConcurrencyIntegrationTests`, `AppointmentStoreFailureTranslationTests`, `AppointmentStoreIntegrationTests`, `BookingAvailabilityIntegrationTests`, `BookingBusyIntervalSourceIntegrationTests`, `BookingBusyIntervalSourceWiringIntegrationTests`, `AppointmentSchemaIntegrationTests`, `BookingModuleBoundaryTests`) |
| Modified (present) | 21 | 1 main file (`BusyIntervalSource.java`, Javadoc), 10 existing test files, 10 documents |
| **Current changed files** | **43** | new + modified |
| Deleted | 3 | `NoBookingBusyIntervalSource.java`, `NoBookingBusyIntervalSourceTests.java`, `BusyIntervalSourceWiringIntegrationTests.java` |
| **Total changed paths** | **46** | 22 + 21 + 3 |

The domain package has seven files (not six). The review archive carries the 43 current files under `files/`, the 3
deletions in `deleted-files.txt`, and the 46 paths in `changed-files.txt`.

### Evidence by requirement

- **Overlap protection and tenant isolation:** `AppointmentSchemaIntegrationTests` (identical, partial, contained,
  containing, adjacent both sides, different StaffMember, different Business, `CANCELLED` both directions,
  update back to `CONFIRMED`, exact constraint definition, every cross-Business reference, restrictive deletes);
  `AppointmentOverlapConcurrencyIntegrationTests` (below).
- **Nullability and CHECKs:** each of the 19 `NOT NULL` columns is rejected with SQLState `23502` and the column
  name; every CHECK is rejected with exactly its own constraint name (one case per rule, and the codepoint, control
  character, length, version, finite, and idempotency-invariant boundaries).
- **Snapshot-name canonicality:** `AppointmentDomainTests` accepts canonical Bulgarian names and rejects, for both the
  Service and the StaffMember name (and in the persisted record), an empty or whitespace-only value, edge
  whitespace, repeated internal spaces, a tab, line feed, non-breaking, em, or ideographic space inside, and strings
  NFKC changes (full-width letters, a ligature, a superscript, an enclosed digit), with a fixed message and no value;
  `AppointmentSchemaIntegrationTests.theDomainAndTheDatabaseAgreeOnWhichSnapshotNamesAreCanonical` proves that
  PostgreSQL rejects exactly the names the domain rejects (and accepts the canonical ones) for both columns.
- **Snapshots:** later Service, StaffMember, Business-timezone, and assignment changes leave stored snapshots
  unchanged (`AppointmentSchemaIntegrationTests`, `AppointmentStoreIntegrationTests`).
- **Concurrency (deterministic, no sleeps):** two transactions race through `AppointmentStore`; the second is
  proven waiting on a PostgreSQL lock through `pg_stat_activity` before the first commits (loser gets the typed
  `OverlapConflict`, one blocking row remains, the connection stays healthy) or rolls back (the second succeeds);
  adjacent ranges, different StaffMembers, different Businesses, and a `CANCELLED` insert finish while the first
  transaction is still open, so no lock wait exists; a losing multi-statement transaction leaves neither its own
  earlier write nor the winner altered.
- **DST instants:** derived from `ZoneRules` for `Europe/Sofia`: a repeated wall-clock time is two disjoint instant
  ranges that both insert, ranges overlapping in instants are rejected, an appointment crossing the autumn overlap
  and the spring gap keeps its elapsed duration (`end_at - start_at` is one hour) and blocks in instants.
- **Busy source:** `CONFIRMED` rows only; Business and requested-StaffMember filtering (another Business's
  StaffMember is never returned even when named); half-open overlap (a window that only touches a boundary is
  excluded; a straddling or spanning window is returned unclipped); ordering; deep immutability; null and
  reversed-window arguments; `MANDATORY` (an `IllegalTransactionStateException` outside a transaction); and
  exactly one `from appointment` statement for ten StaffMembers, one for duplicate IDs, none for an empty request.
- **Refreshed availability:** `BookingAvailabilityIntegrationTests` runs the real `AvailabilityQuery` over committed
  Appointments with a fixed clock: a booked 10:00 to 10:30 removes the 09:45, 10:00, and 10:15 starts, keeps 09:30
  and 10:30, cancelling releases them, another StaffMember stays free (and "any" lists only the free one), and
  another Business is unaffected.
- **Wiring and boundaries:** one `BusyIntervalSource` bean and it is the Booking-owned class; ArchUnit proves the
  only implementing class, `scheduling` imports nothing from `booking`, the domain uses only the JDK and `shared.contact`, no
  controller, public contract, or logging in the module, and Spring Modulith `verify()`.
- **Privacy:** records redact `toString()`; every translated failure has no cause or suppressed exception and
  contains no sentinel note or name, constraint name, or SQL (`AppointmentStoreFailureTranslationTests`,
  `AppointmentStoreIntegrationTests`); classification never parses a message.

### Deviations and limitations

- **Deliberate tightenings of ADR-0022** (recorded there): `occupied_until = end_at` instead of `>=`; a `MANUAL`
  row may carry the (all-or-none) idempotency columns.
- Proposed item 3 of this record (names and the public-reference alphabet) is now finalized: the names are in
  `docs/data-model.md`, the alphabet is Crockford base32 (ten characters).
- The store has no update, cancellation, or manual-creation operation, and `ConcurrentFailure` is defined but its
  retry use belongs to Phase 4.
- A real `40001` or `40P01` from the store is not provoked in Phase 2 (their classification is a unit test).
- The migration-integrity hashes are pinned in the test source, so they must be updated by a new migration's
  author, never by editing V1 to V11.
- Nothing in Phase 2 is visible in the UI, so no visual review applies.

## Phase 3 record (implemented, verified, reviewed, and committed as `70e31c9`)

Scope: the Business-owned schedule revision row and its two narrow published contracts, the forward migration
`V12`, and the participation of the weekly working-schedule replacement and every schedule-exception create,
replace, and delete. No booking orchestration, retry policy, HMAC fingerprint, public endpoint, rate limiting,
frontend, cancellation, or Appointment administration was added, and no dependency, configuration, or existing
migration changed. The clarifications and the one tightening are recorded as implementation notes in ADR-0025.

### What was implemented

- **`V12__add_business_schedule_revision.sql`** (SHA-256
  `98717e79648bee52401d5c9ba379bc82b8da50435028313f7d1e93eb48ba4615`): table `business_schedule_revision`
  (`business_id` primary key and restrictive foreign key to `business(id)`, `revision bigint NOT NULL DEFAULT 0`
  with a nonnegative check, `updated_at timestamptz NOT NULL`); a backfill of one row at revision `0` for every
  existing Business (stamped with its `created_at`); and the `AFTER INSERT` trigger
  `business_create_schedule_revision`, which gives every new Business its row in the same statement and
  transaction. V1 to V11 are byte-identical to `HEAD`.
- **`business` module:** published `ScheduleRevisionBump.advance` (new revision) and `ScheduleRevisionGuard.lockShared`
  (protected revision), the cause-free `ScheduleRevisionConcurrentConflict` (`40001`, `40P01`) and
  `ScheduleRevisionFailure`, the internal `ScheduleRevisionService` (both operations `MANDATORY`; the guard also
  requires a repeatable-read or serializable caller transaction) and `ScheduleRevisionStore` (the only code that
  touches the table). `business` still depends on no other module.
- **Participation:** `StaffWorkingScheduleService.replace` and the three mutations of
  `ScheduleExceptionAdministrationService` advance the revision once per accepted mutation in the same
  transaction, after the StaffMember lock and immediately before the aggregate statement. Replace and delete compare
  the stored version first (same `ConcurrentUpdate`), so a stale request never takes the revision's exclusive
  lock. A concurrency victim at the bump becomes the existing `ConcurrentUpdate`. No response, version, or HTTP
  contract changed.

### Audit reconciliation with ADR-0025

Every production `INSERT`, `UPDATE`, and `DELETE` statement was inspected (the Business, Service, StaffMember,
assignment, weekly-schedule, schedule-exception, Appointment, Customer, and identity stores). The ADR-0025 table is
complete and exact: only the weekly replacement (`staff_working_schedule`, `staff_working_period`) and the
schedule-exception statements (`schedule_exception`, `schedule_exception_period`) needed the guard. Business
lifecycle, profile, and timezone changes are protected by the Business row, Service changes by the Service row,
StaffMember profile, activity, and assignment changes by the StaffMember row (assignment replacement updates
`staff_member` before it changes `staff_member_service`), and the three creation statements only add availability.
There is no Business working-hours store. No divergence from the ADR was found.
`AvailabilityMutationInventoryTests` pins the inventory (any new, changed, or removed write statement fails it until
classified) and requires every production caller of a bumped write to depend on `ScheduleRevisionBump`.

### Transaction and lock behavior

- The revision `UPDATE` runs in the mutation's transaction, so the schedule write and the bump commit or roll back
  together. Proven for the weekly replacement and all four exception kinds with create, replace, and delete
  (rollback of an enclosing transaction; a rejection after the bump, such as the exclusion violation; a missing
  revision row).
- Lock order observed with PostgreSQL backend identifiers: Business, Membership, StaffMember (scoped kinds and the
  weekly replacement), schedule revision, then the aggregate (`ScheduleExceptionLockingIntegrationTests`,
  `StaffWorkingScheduleLockingIntegrationTests`). Services still lock only Business, Membership, and Service.
- Schedule writers of one Business now wait on the revision row before the aggregate. Behavior is preserved (one
  winner, same exceptions), but two earlier tests changed their probe point from the store to the revision, and
  the opposite-order replacement test, which relied on a deadlock between exception rows, was replaced by a test
  that proves the serialization plus a new deadlock test that violates the order on purpose and shows the bump's
  victim becomes the sanitized `ConcurrentUpdate`.

### Existing tests changed because V12 or the new lock legitimately changes their subject

- Migration lists in `BusinessSchemaIntegrationTests`, `WorkingScheduleSchemaIntegrationTests`,
  `ScheduleExceptionSchemaIntegrationTests`, `CustomerSchemaIntegrationTests`, and `AppointmentSchemaIntegrationTests`
  now end with `"12"`. The two upgrade tests that migrate to the latest version now expect 12, and the V11 upgrade
  test pins Flyway's target to 11 so it still proves the V11 step. Their byte-integrity checks of V1 to V10 are
  unchanged.
- `ScheduleExceptionAdministrationServiceTests` constructs the service with the revision mock, and one test's stored
  version was corrected from 3 to 2 because the pre-check now reports a stale version before the store.
- `ScheduleExceptionLockingIntegrationTests` and `StaffWorkingScheduleLockingIntegrationTests`: described above; the
  assertions of winners, losers, and stored state are unchanged and revision assertions were added.

### Verification (executed)

Local only; the tested state is base commit `18052b8` (`HEAD`, Phase 2 committed) **plus the uncommitted Phase 3
working tree** (nothing staged). No CI run exists for this uncommitted state.

- **Final complete `./mvnw --batch-mode verify`** (PostgreSQL 18.4 through Testcontainers): **127 test classes,
  2594 tests, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS`** (1 min 39 s). Phase 2's 2469 plus 125.
- **Verification history (not reruns of one tree):** the first complete run (2594 tests, 0 failures) was on a tree
  that differed from the final one only by replacing a few fully qualified type names and removing one unused import in
  new test files; those cosmetic changes were then verified by the final complete run above. Focused runs during
  development found and fixed: the migration-list and latest-version assertions (V12), five
  `ScheduleExceptionLockingIntegrationTests` cases whose probe point moved, and one test of mine that wrongly
  expected a time-off creation to overlap a closure of a different kind. No assertion was weakened.
- **Control runs:** with the bump removed from the weekly replacement and from exception creation, 22 tests of
  `ScheduleRevisionMutationIntegrationTests` failed (and the lock-wait cases timed out waiting for a lock that
  never existed), then the production code was restored. The no-guard control test shows the stale view never
  learns of a committed change.
- **New classes (117 tests):** `ScheduleRevisionMutationIntegrationTests` 59, `ScheduleRevisionServiceTests` 18,
  `ScheduleRevisionIntegrationTests` 16, `ScheduleRevisionSchemaIntegrationTests` 9,
  `ScheduleRevisionModuleBoundaryTests` 7, `StaffWorkingScheduleServiceRevisionTests` 5,
  `AvailabilityMutationInventoryTests` 3 (`ConcurrencyTestSupport` is test support). **Added to existing classes
  (8):** `ScheduleExceptionAdministrationServiceTests` +4 (20 to 24), `StaffWorkingScheduleLockingIntegrationTests`
  +2 (4 to 6), `ScheduleExceptionLockingIntegrationTests` +1 (13 to 14, one replaced by two),
  `BusinessScheduleExceptionApiIntegrationTests` +1 (21 to 22). 2469 + 125 = 2594.
- **Migration integrity:** V1 to V11 are byte-identical to `HEAD` and to their pinned hashes
  (`ScheduleRevisionSchemaIntegrationTests`, `AppointmentSchemaIntegrationTests`); V12 is new and pinned.
- **Java hygiene:** a focused search over every changed or new Java file found no compressed empty constructor,
  method, class, or record body and no wildcard import in changed code; unused imports left by earlier phases
  in untouched lines were not changed. `git diff --check` and a new-file whitespace check are clean.
- The frontend suites were not run: no frontend file changed.

### File inventory

Exact, from `git status` (nothing staged); 39 changed paths.

| Group | Count | Detail |
|---|---:|---|
| New (untracked) | 15 | 4 published `business` contract files (`ScheduleRevisionBump`, `ScheduleRevisionGuard`, `ScheduleRevisionConcurrentConflict`, `ScheduleRevisionFailure`), `business.application.ScheduleRevisionService`, `business.infrastructure.ScheduleRevisionStore`, `V12__add_business_schedule_revision.sql`, and 8 test files (`ScheduleRevisionSchemaIntegrationTests`, `ScheduleRevisionIntegrationTests`, `ScheduleRevisionServiceTests`, `ScheduleRevisionMutationIntegrationTests`, `StaffWorkingScheduleServiceRevisionTests`, `ScheduleRevisionModuleBoundaryTests`, `AvailabilityMutationInventoryTests`, and the support class `ConcurrencyTestSupport`) |
| Modified | 24 | 2 main files (`StaffWorkingScheduleService`, `ScheduleExceptionAdministrationService`), 10 existing test files, 11 documents under `docs/` (this record, ADR-0012, ADR-0015, ADR-0016, ADR-0025, architecture, data model, security, testing strategy, implementation plan, roadmap), and `README.md` |
| Deleted | 0 | none |
| **Total changed paths** | **39** | 15 + 24 + 0 |

The review archive carries the 39 current files under `files/`, their `HEAD` versions under `committed-before/`, the
complete patch, and the empty `deleted-files.txt`.

### Evidence by requirement

- **Initialization (existing and new Businesses):** `ScheduleRevisionSchemaIntegrationTests` (upgrade from V11 with
  three Businesses at distinct creation times: rows, revision 0, timestamps, latest version 12, and a new Business
  after the upgrade; a multi-row insert; a rolled-back insert leaves no row; exact columns, constraints,
  trigger), `ScheduleRevisionIntegrationTests` (a Business created through the application).
- **Business isolation and missing-Business handling:** a bump of one Business never changes another's revision
  or timestamp (`ScheduleRevisionIntegrationTests`, `ScheduleRevisionMutationIntegrationTests`); an unknown
  Business or a missing row is a sanitized `ScheduleRevisionFailure` for both operations, never a silent success,
  and fails every mutation without a write; a different Business's bump never fails or blocks the guard.
- **Every required mutation path:** the 13 paths (weekly replacement; four kinds times create, replace, delete) each
  advance the revision by exactly one and change the schedule (`anAcceptedMutationAdvances...`); unrelated
  Business revisions are unchanged.
- **Rollback of both writes:** `aRolledBackEnclosingTransactionLeavesNeitherTheWriteNorTheBump` for all 13 paths, a
  failure after the bump in an enclosing transaction, and the rejected overlap that raises after the bump.
- **No bump for rejected work:** validation, authorization (stranger, no session, no selected Business), lifecycle
  (suspended), StaffMember (missing, foreign, inactive), missing exception, foreign exception, stale version, and
  exclusion rejections all leave every revision and the schedule unchanged
  (`aRejectedWeeklyReplacementChanges...`, `aRejectedExceptionMutationChanges...`, the HTTP test, and the mocked
  ordering tests).
- **No bump for unrelated writes:** Business timezone update, suspension, reactivation, Service
  create, update, deactivate, reactivate, StaffMember create, update, deactivate, reactivate, and assignment
  replacement leave the revision at its value (`lifecycleTimezoneServiceStaffMemberAndAssignment...`).
- **Transaction participation:** both operations fail with `IllegalTransactionStateException` outside a
  transaction and never open one; the guard rejects a weak, default, or unexposed isolation before any statement.
- **Lock order and concurrent writers:** see above; two weekly replacements for different StaffMembers of one
  Business serialize (one is shown blocked by the other's backend, then both commit); two exception writers
  serialize; losers leave no bump.
- **ADR-0025 guarantee with coordinated PostgreSQL transactions:** a repeatable-read stand-in whose snapshot
  predates a committed bump fails the guard with `40001` (`ScheduleRevisionConcurrentConflict`) without waiting, for the
  bump alone and for each of the 13 real mutation paths; an uncommitted bump makes the guard wait (blocked by
  the bump's backend per `pg_blocking_pids`) and the guard then fails when the bump commits or succeeds at the old
  revision when it rolls back; a snapshot taken after the commit succeeds; shared guards never block each other;
  a held guard makes the bump and each real mutation wait (the blocked statement is the revision `UPDATE`) and the
  mutation commits after the stand-in; two bumps serialize in the default isolation without a serialization failure.
- **Sanitized failures:** the exceptions carry a fixed message and no cause or suppressed exception; none contains
  an identifier, SQL, or constraint text for any SQLState; the HTTP test shows `INTERNAL_ERROR` with no table name or
  identifier, writes nothing, and no response contains a revision field.

### Distinguishing Phase 3 evidence from Phase 4

Phase 3 proves the contracts, the migration, the mutation participation, and the guard against real mutations.
The "booking" in these tests is a transaction that calls only `ScheduleRevisionGuard`; there is no Appointment,
Customer, availability recomputation, retry, or booking-side rejection. The real booking-orchestration race tests
(the ADR-0025 commit orders against Appointments and Customers, the control run without the guard in the real
booking, the lock order including the Service row, and the rollback of the Customer insert) remain Phase 4.

### Deviations and limitations

- **Tightening of ADR-0025 text (recorded there):** replace and delete compare the stored version before the bump.
- **Trigger instead of application code** for new Businesses (ADR-0025 left the mechanism Proposed).
- The guard's isolation requirement is an addition to the contract described in ADR-0025 (a booking is
  repeatable-read by ADR-0023, so no approved behavior changes).
- A real `40P01` is produced and mapped at the bump (`aLockOrderViolatingCaller...`); the victim is chosen by
  PostgreSQL, and the test is deterministic because the transaction that started waiting first runs the deadlock
  check first.
- Documentation counts the audit by reading every write statement; the inventory test detects later drift only for
  statements written as `INSERT INTO`, `UPDATE`, or `DELETE FROM` followed by a lowercase table name in Java
  sources.
- Nothing in Phase 3 is visible in the UI, so no visual review applies. Phase 4 remains outstanding.

## Phase 4 record (implemented, verified, reviewed, and committed as `63f6019`)

Scope: the internal `GuestBooking` capability: transaction ownership, one attempt, idempotency, HMAC request
fingerprints, replay, bounded whole-transaction retry, and outcome classification, with the narrow published
contracts it needs. No HTTP controller, security or CSRF change, limiter, frontend, owner calendar, manual booking,
cancellation, migration, or dependency was added; V1 to V12 are byte-identical to `HEAD`. Decisions, refinements, and
observed behavior are ADR implementation notes (ADR-0023, ADR-0024, ADR-0025).

### What was implemented

- **Published contracts (new, narrow, `MANDATORY`, sanitized, cause-free failures):** `business.BusinessBookingAccess`
  (`lockBySlug`: Business `FOR SHARE` at any lifecycle status, id and status only), `catalog.ServiceBookingAccess`
  (`lockForBooking`: name, duration, price, active), `workforce.StaffBookingAccess` (`lockEligibleForBooking`:
  qualifying members locked in one statement in identifier order, display name and creation time). Existing
  contracts used unchanged: `ScheduleRevisionGuard`, `AvailabilityQuery`, `CustomerIdentification`, and the booking-owned
  `AppointmentStore` (one added read, `countConfirmedStartingBetween`).
- **`booking` module:** root contract `GuestBooking`, `GuestBookingRequest`, sealed `BookingResult`
  (`Created`, `Replayed`, `BusinessUnavailable`, `InvalidRequest`, `ServiceUnavailable`, `StaffMemberUnavailable`,
  `SlotUnavailable`, `IdentityConflict`, `AttemptMismatch`, `TemporarilyUnavailable` = known rollback,
  `OutcomeUncertain`), `BookedAppointment`, `BookingField`, `BookingOrchestrationFailure`; `domain` (attempt ID,
  normalization, encoding v1, assignment policy); `application` (`GuestBookingService`, `BookingAttemptProcedure`,
  `RequestFingerprinter`, `FingerprintKeyRing`, public reference source, diagnostics); `configuration` (key ring).
- **Configuration:** `spotyourslot.booking.fingerprint.*` (ADR-0024 notes); non-secret keys in `application-dev.yaml` and
  `application-test.yaml`; `ProductionProfileIntegrationTests` supplies a synthetic production-style key.

### Verification (executed)

Local only; base `HEAD` `70e31c9` plus the uncommitted Phase 4 working tree (nothing staged). No CI run exists for this state.

- **Final complete `./mvnw --batch-mode verify`** (PostgreSQL 18.4 through Testcontainers): **2943 tests, 0 failures,
  0 errors, 0 skipped, `BUILD SUCCESS`** (1 min 48 s) on the final code after the review correction below. Phase 3's
  2594 plus 349.
- **New classes (347 tests):** `GuestBookingScheduleRaceIntegrationTests` 60, `GuestBookingServiceTests` 90,
  `GuestBookingRejectionIntegrationTests` 32, `GuestBookingReplayIntegrationTests` 24, `GuestBookingIntegrationTests` 23,
  `GuestBookingConcurrencyIntegrationTests` 21, `BookingAttemptIdTests` 20, `FingerprintEncodingTests` 19,
  `NormalizedBookingRequestTests` 12, `FingerprintKeyRingTests` 9, `RequestFingerprinterTests` 8,
  `BookingContractsIntegrationTests` 7, `StaffAssignmentPolicyTests` 5, `GuestBookingCommitFailureIntegrationTests` 10,
  `BookingFingerprintConfigurationTests` 4, `GuestBookingKeyRotationIntegrationTests` 3. **Added to existing (2):**
  `BookingModuleBoundaryTests` 7 to 9. Changed existing assertions: `CustomerModuleBoundaryTests` and
  `ScheduleRevisionModuleBoundaryTests` (they had asserted that nothing yet depends on Customer or takes the guard),
  `BookingModuleBoundaryTests` (dependency set, root package, logging limited to the diagnostics class),
  `ProductionProfileIntegrationTests` (key property), and `ScheduleExceptionLockingIntegrationTests` (one deadlock test, see the flaky-test diagnosis below). No assertion was weakened.
- **Review correction (second pass).** Commit-outcome handling was corrected after review: classification by
  Spring's completion status instead of exception content, and a verification read for `Created` (see the
  limitations below and the ADR-0023 and ADR-0024 notes). It added 26 tests (`GuestBookingServiceTests` 70 to 90,
  `GuestBookingCommitFailureIntegrationTests` 4 to 10), including real-PostgreSQL regressions where `afterCommit`
  throws a wrapped `40001`, `40P01`, `25P02`, or an `UnexpectedRollbackException`.
- **Flaky-test diagnosis (documented reliability standard).** The first complete run after the correction failed
  once: Phase 3's `ScheduleExceptionLockingIntegrationTests.aLockOrderViolatingCallerDeadlocksOnTheRevisionAndTheVictimIsAConcurrentUpdate`.
  It is intermittent on the **unmodified Phase 3 code too** (1 failing run in 14 class runs on a pristine `HEAD`
  export; about 40% of class runs in this working tree, for a reason not identified: the timing differs), and the new
  `GuestBookingConcurrencyIntegrationTests` deadlock test had the same defect (it failed in several of about a dozen class runs). Root cause: both assumed that PostgreSQL aborts the transaction that began waiting
  first; it aborts whichever participant's `deadlock_timeout` check finds the cycle first, which depends on timer
  timing. Fix, without weakening any assertion: the participant that must **not** be the victim runs
  `SET LOCAL deadlock_timeout = '60s'` (a superuser setting scoped to its transaction), so only the intended victim
  can run the check. Afterwards 8 of 8 runs of both classes together and 10 of 10 runs of the two tests passed, then the
  final complete run above.
- **Iteration history:** a first draft made a collaborator that swallows a failure look like a success (see below,
  fixed with a final read-your-write); a deadlock test first had the holder as victim and was reordered so the booking
  waits first; a burst test wrongly expected `SlotUnavailable` for every loser (see the observed deadlocks).
- **Hygiene:** a search over every new or changed Java file found no compressed empty body and no wildcard or unused
  import; `git diff --check` and a new-file trailing-whitespace check are clean; V1 to V12 verified unchanged against `HEAD`.
- The frontend suites were not run: no frontend file changed.

### File inventory (exact, from `git`; 73 changed paths)

| Group | Count | Detail |
|---|---:|---|
| New (untracked) | 52 | 30 main (6 `booking` root, 8 `booking.application`, 1 `booking.configuration`, 8 `booking.domain`, 3 `business`, 2 `catalog`, 2 `workforce`) and 22 test files (16 test classes, the base class `BookingIntegrationTest`, `BookingTestHooks`, `BookingHookConfiguration`, `RecordingJpaTransactionManager`, `TransactionLog`, and `AppointmentStoreFailures`) |
| Modified | 21 | 10 code and configuration files (3 stores `AppointmentStore`, `BusinessStore`, `StaffMemberStore`; `application-dev.yaml`; `application-test.yaml`; 3 boundary tests; `ProductionProfileIntegrationTests`; the Phase 3 test `ScheduleExceptionLockingIntegrationTests`) and **11 Markdown documents** (`README.md` and 10 under `docs/`) |
| Deleted | 0 | none |

### Evidence by requirement

- **Transaction ownership:** unit and PostgreSQL tests reject an active transaction and a bare synchronization before
  any clock read, hook point, or SQL; one begun, never suspended transaction per attempt (`RecordingJpaTransactionManager`).
- **Retries:** real `40001` (Service and StaffMember changed after the snapshot), real `40P01` (a constructed cycle,
  the booking the victim), real exclusion and unique races, a real Customer conflict; each retry has its own
  PostgreSQL transaction id and snapshot; exhaustion at three; known rollback leaves nothing.
- **Idempotency and replay:** see `GuestBookingReplayIntegrationTests`; golden vectors in the domain tests;
  key rotation with a real second context.
- **Schedule races:** `GuestBookingScheduleRaceIntegrationTests`, all 13 paths, every ADR-0025 order, two control runs.
- **Boundaries and privacy:** ArchUnit and Modulith tests; diagnostics tests with sentinel values; contracts reveal only
  what they need.

### Deviations and limitations (all for review)

- **Order refinement** (ADR-0023 note): locks, including every qualifying StaffMember for "no preference", precede
  availability, as the Phase 4 instruction required; ADR-0023 step 5 locked only the chosen member.
- **Observed exclusion-index deadlocks between simultaneous same-time inserts** (ADR-0023 note): retried in new
  transactions, but a burst of contenders can end as `TemporarilyUnavailable` after three attempts and each deadlock
  costs `deadlock_timeout` (one second). It is a latency and message-quality limitation, **not a data-integrity
  failure**: one Appointment, complete rollback of every loser, no orphan. The design (shared locks, exclusion
  constraint, three attempts) is unchanged; a contention optimization is an **explicit follow-up** needing its own
  decision and measurement.
- **Commit outcome (corrected after the first review; ADR-0023 and ADR-0024 notes):** classification no longer reads
  exception content. It follows the completion status Spring reports to a synchronization registered in the
  attempt's transaction: `COMMITTED` after a failed callback is `OutcomeUncertain` and never retried (whatever
  SQLState or `UnexpectedRollbackException` it carries); `ROLLED_BACK` is a proven rollback; `UNKNOWN` needs a
  server-reported conflict or `25P02`. Real PostgreSQL evidence covers `COMMITTED` and `ROLLED_BACK`; the `UNKNOWN`
  phase with `40001`, `40P01`, `25P02` rests on injected statuses (a real commit error could not be provoked).
- **False `Created` after PostgreSQL's silent rollback (corrected):** the in-body read cannot see a failure that
  happens after it. A normal completion of a `Created` attempt is now confirmed by one primary-key read outside any
  transaction. Present: `Created`. Absent: the known-rollback `TemporarilyUnavailable`, no automatic retry, the same
  attempt can be repeated. The read fails: `OutcomeUncertain`. Cost: one indexed read per successful booking. It
  cannot explain why the transaction did not persist, and no `REQUIRES_NEW`, savepoint, or manager change is used.
- A retry after an uncertain result is the client's repeat of the same attempt; the server does not auto-resolve.
- No startup check that every stored key version is configured (ADR-0024 Proposed item); a missing key is an uncertain
  replay.
- Nothing is visible in the UI, so no visual review applies. Issue #18 and #19 are not complete; production public
  booking still depends on Issue #21.

## Phase 5 record (implemented and verified; awaiting review and commit)

Scope: the public HTTP contracts of guest booking, session-independent security with the one narrow CSRF exemption, the
bounded Booking-owned limiter, and the bound on the booking request body, backend only (plus the compatibility check of the
existing public-profile frontend consumers). No frontend, cancellation, manual booking, owner calendar, Customer
history, payment, notification, migration (V1 to V12 are byte-identical to `HEAD`), dependency, booking transaction
semantics, HMAC encoding, or lock strategy changed. The final contract, the settled Proposed details, and every
refinement are the **Phase 5 implementation notes of ADR-0026**; this record maps them to evidence.

### What was implemented

- **Routes (`publicbooking` module):** `GET …/services/{serviceId}/booking-options`, `GET …/services/{serviceId}/availability?date=`
  `[&staffMemberId=]`, and `POST …/bookings` (`201` new, `200` replay including `CANCELLED`); the profile gains `services[].id`.
  The controller only delegates: `GuestBooking` for booking, `AvailabilityQuery` (in one repeatable-read snapshot with the
  new `workforce.PublicStaffAccess`) for the reads. It contains no orchestration, matching, availability calculation, or
  retry logic. Every error is a sanitized RFC 7807 body with the fixed `instance` `/api/public/businesses` and the approved
  Bulgarian messages; the known rollback and the uncertain outcome are two `503` results with distinct codes and messages.
- **Security (`identity`):** `PublicBookingRoutes` is the one definition of the four exact matchers used by the `permitAll`
  rule, `DatabaseSessionFilter.shouldNotFilter` (so a session is never read, refreshed, revoked, or set), and
  `ignoringRequestMatchers` (only `POST …/bookings`). Authorization, CORS (exact origin, unchanged), and every other CSRF
  rule are unchanged.
- **Limiter (`booking`):** `PublicBookingRateLimiter` (published), `BookingRateLimiter`, `FixedWindowCounters`, `Digest`,
  `RateLimitSettings`, and `BookingRateLimitConfiguration`; the configuration keys are `spotyourslot.booking.rate-limit.*`
  (documented in `application.yaml` with `BOOKING_RATE_LIMIT_*` environment names).
- **Contract additions:** `workforce.PublicStaffAccess`, `PublicService.id`, `AvailabilitySnapshot.firstDate()` and
  `lastDate()` (derived, no new component).

### Existing tests changed because Phase 5 legitimately changes their subject

`PublicProfileApiIntegrationTests` (the Service key set now starts with `id`; the privacy test asserts the Service reference
is the only identifier), `PublicProfileControllerTests` and `PublicServiceAccessServiceTests` (the new value),
`BookingModuleBoundaryTests` and `ScheduleRevisionModuleBoundaryTests` (only `publicbooking` may depend on `booking`; the new
published type). No assertion was weakened or removed.

### Verification (executed)

Local only; the tested state is base `HEAD` `63f6019` **plus the uncommitted Phase 5 working tree** (nothing staged). No CI
run exists for this state. The final `./mvnw --batch-mode verify` ran after the last change to any file under `backend/`; the
only later edits are Markdown.

- **Final complete `./mvnw --batch-mode verify`** (PostgreSQL 18.4 through Testcontainers), run after the last change under
  `backend/` (the second correction pass below): **158 test classes, 3153 tests, 0 failures, 0 errors, 0 skipped,
  `BUILD SUCCESS`** (2 min 3 s). Phase 4's 2943 plus 210 new tests (the first Phase 5 verify, before the corrections, was 3131).
- **New classes (188 tests):** `BookingRateLimiterTests` 38, `PublicBookingRequestParserTests` 35, `PublicBookingCreationApiIntegrationTests`
  32, `PublicBookingAvailabilityApiIntegrationTests` 19, `PublicBookingRateLimitApiIntegrationTests` 16,
  `PublicBookingSessionSecurityApiIntegrationTests` 15, `PublicBookingResultMappingApiIntegrationTests` 11,
  `PublicBookingModuleBoundaryTests` 8, `PublicBookingRoutesTests` 5, `PublicBookingRateLimitCapacityApiIntegrationTests` 4,
  `PublicBookingDefaultRateLimitApiIntegrationTests` 3, `PublicStaffAccessServiceTests` 2 (`PublicBookingApiIntegrationTest` is the base class); added by the review corrections: `BookingBodyLimitFilterTests` 6, `PublicBookingBodyLimitApiIntegrationTests` 8, `PublicBookingBodyLimitContainerIntegrationTests` 5, and +2 in `BookingRateLimiterTests` (38 to 40) and +1 in `PublicBookingRateLimitApiIntegrationTests` (16 to 17).
- **Reliability.** Before the final run, the Phase 5 classes were run alone repeatedly while they were written; after the final
  run, every `PublicBooking*` class, `BookingRateLimiterTests`, and `PublicProfileApiIntegrationTests` were run twice more as
  one selection (both runs exit 0; these are extra checks and not part of the test total, and predate the review corrections). Failures found while writing were diagnosed, not worked around: the
  shared limiter coupling tests through a common remote address (each request now uses its own address, and the capacity class
  advances the injected clock one window per test), the aggregate contact budget that a replay legitimately consumes
  (tests were restructured, not the budget), a preflight that Spring answers with only the allowed header, and a
  `@JsonIgnoreProperties(ignoreUnknown = false)` that cannot force a failure (which led to the strict tree parser). No sleep, no
  larger timeout, and no weakened assertion was used.
- **Hygiene:** a search over every new or changed Java file found no compressed empty constructor, method, class, or record
  body, no wildcard import, no unused import, and no trailing whitespace; `git diff --check` is clean; V1 to V12 are
  byte-identical to `HEAD` (hashes in the review archive). Frontend: see the review corrections.

### File inventory (exact, from `git`; 70 changed paths, after the review corrections)

| Group | Count | Detail |
|---|---:|---|
| New (untracked) | 41 | 25 main: 6 `booking` (`PublicBookingRateLimiter`, `BookingRateLimiter`, `FixedWindowCounters`, `Digest`, `RateLimitSettings`, `BookingRateLimitConfiguration`), `PublicBookingRoutes`, 14 `publicbooking` (including `BookingBodyLimitFilter` and `RequestBodyTooLarge`), 3 `workforce`; 16 test files |
| Modified | 29 | 12 main (11 Java and `application.yaml`), 5 backend test files, 4 frontend files (`api.ts`, `api.test.ts`, `e2e/support/publicProfile.ts`, `e2e/public-business-profile.spec.ts`), 7 documents under `docs/`, and `README.md` |
| Deleted | 0 | none |

### Evidence by requirement

- **Contracts, allowlists, privacy:** exact key sets and values in the three API classes; sentinel strings for the Customer,
  the note, the attempt ID, the contact, and staff private data in responses, errors, and captured logs; no internal
  identifier, version, or audit value in any response; no lookup-by-reference route (`GET …/bookings/{reference}` is the denied route).
- **Status and message of every `BookingResult`:** `PublicBookingResultMappingApiIntegrationTests` (all eleven results, the field
  errors, `Retry-After`, 500) and the real-fault 503 tests in `PublicBookingCreationApiIntegrationTests`.
- **Session independence:** `PublicBookingSessionSecurityApiIntegrationTests` (byte-identical for anonymous, owner,
  administrator, invalid, and expired callers; every `user_session` row unchanged; controls on private routes show a refresh and
  a revocation would be detected; no `Set-Cookie`; no `identity` dependency in `PublicBookingModuleBoundaryTests`).
- **CSRF and CORS:** the same class (the exemption for exactly the booking POST, 403 for every sibling, other verb, deeper path, and
  private mutation; exact-origin reads, bookings, and preflight).
- **Limiter:** `BookingRateLimiterTests` (every budget and window boundary, expiry, capacity and saturation, canonicalization,
  aggregates, IPv6, concurrency) and the HTTP classes (429 contract, charging, no database or orchestration work for a
  rejected request, spoofed forwarded headers, small configured budgets, shipped defaults).
- **Evidence boundary:** injected outcomes prove only the HTTP mapping; transaction, commit, retry, and idempotency behavior
  is proven by the Phase 4 classes, and the two real-fault tests use their mechanism.

### Deviations and limitations (all for review)

- **The profile route is also session-independent** (the session filter skips it), a small extension of ADR-0026's list,
  because it is the first request of the same journey.
- **The booking body is read as a JSON tree** and checked against an allowlist, because the global Jackson configuration ignores
  unknown properties. Text fields must be JSON strings or null.
- **A new `publicbooking` module** hosts the controller (the `booking` module may not contain one, by its boundary tests); it
  depends on `booking`, `business`, `scheduling`, and `workforce` only.
- **`AvailabilitySnapshot` gained two derived accessors**, an additive change to a published `scheduling` record.
- **Pre-existing, not changed:** the shared `ApiExceptionHandler` maps an unsupported request `Content-Type` on any other route to
  a 500 `INTERNAL_ERROR` whose `instance` is the request path (observed with a private `POST`); the public routes handle it as 415 with the
  fixed instance. It deserves its own correction.
- **Limiter limits** (ADR-0026 notes): one process, restart resets, the proxy address is shared by all guests behind a proxy, no
  distributed limiter, the proxy or edge must still bound requests (the application bounds only the booking body), a third party can exhaust a known contact's budget.
- **Release dependency (unchanged):** production public booking waits for issue #21. Issue #18 and #19 are not complete and
  GitHub and the board are unchanged. Nothing is visible in the UI, so no visual review applies.

### Review corrections (2026-10-07)

1. **Booking body bound.** `BookingBodyLimitFilter` bounds `POST …/bookings` (nothing else) to 16 KiB by default
   (`spotyourslot.booking.max-request-body-bytes`), counting the bytes the application actually reads and failing at the first byte
   over the limit, before any JSON tree exists; chunked, absent, and false `Content-Length` are covered, and a declared length above the
   limit fails on the first read. The result is a sanitized `413 REQUEST_TOO_LARGE` with the fixed instance and `no-store`. An oversized
   request is charged **once to the address budgets** (the interceptor runs before the body is read) and never to a contact budget, and it
   does no booking, Customer, or database work. Session independence, CSRF, CORS, and private endpoints are unchanged (tests).
   *Evidence:* MockMvc (`PublicBookingBodyLimitApiIntegrationTests`, `BookingBodyLimitFilterTests`) proves the application logic: the
   exact boundary, bytes versus characters, a valid multibyte Bulgarian payload, the 413 contract, charging, and no database work.
   Real embedded Tomcat on a real socket (`PublicBookingBodyLimitContainerIntegrationTests`) proves chunked bodies at, within, and
   over the limit, a declared length far above the body (413 without waiting for the bytes), and a declared length below the body
   (the container delivers only the declared bytes: 400, never more). MockMvc cannot produce an absent or false `Content-Length`. Proxy
   and edge size protection remains an additional production requirement.
2. **Public-profile consumers.** The frontend decoder copies only documented fields, so the new `services[].id` is tolerated and dropped;
   `decodeService` and the page needed no change (a comment in `api.ts` was updated and a Vitest case now decodes the revised
   contract). The stale end-to-end contract assertions were corrected only where `id` legitimately changes them: `SERVICE_KEYS` includes
   `id`, the ACTIVE Services' API ids must equal the ids the fixture created, the inactive Service's id must be absent, exactly one `"id"`
   member per Service may appear, and no Service id may be rendered on the page. All privacy assertions remain. *Executed:*
   `npm run lint` (clean), the full frontend `npm test` (61 files, 1443 tests passed), `npm run build` (succeeded), and the existing
   `public-business-profile` Playwright spec (19 journeys passed) against the final backend (`spring-boot:run` from the working tree), run
   through a copy of `scripts/run-e2e.sh` that differs only by naming that spec and fixing the repository path, with the script's own isolated
   disposable Compose project. No booking UI was implemented.
3. **Limiter charging wording.** Atomicity applies within one admission decision. The address decision and the contact decision are
   separate: an address rejection changes no counter and never reaches the contact decision; a contact rejection after an admitted address
   decision **retains the address charge**; the phone and the email within the contact decision are all-or-none. The behavior is unchanged;
   the earlier wording ("a rejected call charges nothing" read across both decisions) is corrected in the limiter contract
   (`PublicBookingRateLimiter`), ADR-0026, `security.md`, and this record. Deterministic tests:
   `BookingRateLimiterTests.aLaterContactRejectionRetainsTheEarlierAddressCharge` and `anAddressRejectionChangesNoCounterAndTheContactBudgetIsUntouched`,
   and `PublicBookingRateLimitApiIntegrationTests.aContactRejectionRetainsTheAddressChargeAndAnAddressRejectionNeverChargesTheContact` through HTTP.

## Phase 6 record (implemented and verified by automated tests; awaiting review and commit)

Scope: the public guest-booking frontend inside the existing `/{slug}` page, against the committed Phase 5 contracts, plus the
documentation. No backend, security, migration (V1 to V12 are byte-identical to `HEAD`), dependency, or configuration change; no
real API defect was found. It has **not** been reviewed in a rendered browser and the design is **not** visually approved (that
is Phase 7). Playwright journeys are Phase 8 and were not written; the existing `public-business-profile` Playwright spec was
updated and executed after the Phase 6 correction (19 passed).

### What was implemented

- **Entry and composition.** The profile keeps its approved composition. The hero gains one «Запази час» action (only when the Business
  has Services) and each Service card gains «Запази час за {Service}» (entering at the StaffMember step with the Service chosen).
  Opening the journey replaces the profile body inside the same `<main>`; «Към страницата на бизнеса» returns to it. `services[].id` is
  now decoded (`PublicService.id`) and is the only reference sent back.
- **`src/public/booking/` (new):** `api.ts` (the three real endpoints, strict decoders, `credentials: 'omit'`, classification of every POST
  outcome), `attempt.ts` (the pure submission state machine and the request body), `dates.ts` (date-only versus instant handling, Business
  timezone formatting, the repeated-hour test), `details.ts` (Customer validation through the shared contact policies and the 500 code
  point note), `hooks.ts` (`useRead`: abortable, key-scoped reads; `useWaiting`: a retry gate), `useJourneyHistory.ts` (step markers
  only), `messages.ts` (the approved wording), the step components (`steps.tsx`, `DetailsStep.tsx`, `ReviewStep.tsx`,
  `ConfirmationView.tsx`), and `BookingJourney.tsx` (state, reads, history, guard, submission). `testSupport.tsx` is test support.
- **Shared change:** `UnsavedChangesGuard` accepts an optional `{label, lines}` notice (default unchanged), so the public journey reuses the shared
  «Остани» / «Напусни» dialog with its own sentences. `PublicBusinessPage` wraps itself in the guard provider.
- **Steps (neutral headings, formal register):** «Избор на услуга», «Избор на служител» (with «Без предпочитание», preselected, and the text that
  the member is determined at confirmation), «Дата и час» (date radios from `availableDates`, slot radios, a note that times are in the Business
  timezone), «Вашите данни» (name, phone, email, optional note with a code point counter, privacy sentence), «Преглед и потвърждение», then the
  result. Each is a native radio group or a labelled form; focus moves to the step heading on every step change; the step progress is text.

### Behavior and rules

- **Reads.** `booking-options` is keyed by Business and Service only, so a date change never reloads it (test). `availability` is keyed by
  Service, preference, and requested date; the first request uses the server's `firstDate`, then the first available date is selected and
  its slots shown (no second request when it is the same date). Every key change aborts the previous request, and an answer, failure, or abort
  for another key is never shown. Leaving a read's step discards its result, so returning always loads fresh data (a taken slot is never shown
  from an earlier answer). The chosen time counts only while the current offer contains it. A Service change clears preference, date, and time; a preference change
  clears date and time; a date change clears the time. Nothing is computed in the frontend: no slot, price, duration, end, or assignment.
- **Dates.** `yyyy-MM-dd` is parsed to numeric parts and formatted in UTC from those parts, so no browser timezone can shift the day; an instant
  is formatted in the returned Business timezone. The repeated hour is detected by formatting the neighbouring hours in that timezone, and a
  repeated time carries its offset («03:30 (UTC+03:00)»). Verified with the browser timezone set to `Pacific/Kiritimati`,
  `America/Los_Angeles`, and `UTC`.
- **Attempt lifetime (applies ADR-0024; corrected, see "Phase 6 correction").** One attempt is one browser-generated ID (`crypto.randomUUID()`, with a
  `getRandomValues` UUID-v4 fallback and no `Math.random`) together with the **exact JSON text of its first send**, held only in component memory. Once any send of it ends
  uncertain, `possiblyCommitted` is **sticky**: nothing but a valid success (201, or a 200 replay including `CANCELLED`) or the warned abandonment resolves it.

| Answer to a send | First send of the attempt | After an earlier uncertain send of the same attempt |
|---|---|---|
| network failure, abort, timeout, unreadable or malformed 2xx, any 5xx other than the documented rollback, 408, `503 BOOKING_OUTCOME_UNCERTAIN`, or **any 4xx that is not a documented code on its documented status** | **uncertain**: frozen, possibly committed | uncertain, same attempt |
| `503 BOOKING_TEMPORARILY_UNAVAILABLE` (proven rollback of *that* request) | retryable: same ID and bytes, or change a choice (drops the attempt) | **uncertain**, same attempt (it says nothing about the earlier send) |
| `429 RATE_LIMITED` | retryable (refused before booking work) | uncertain (rate-limited message), same attempt |
| documented rejection: `400 VALIDATION_ERROR`, `404 BUSINESS_PAGE_UNAVAILABLE`, `409` slot, Service, StaffMember or identity conflict, `413 REQUEST_TOO_LARGE`, `415 UNSUPPORTED_MEDIA_TYPE` | attempt **dropped**; the next submit draws a **new** ID (a `404` ends the journey) | **uncertain**, same attempt; the journey stays mounted (a `404` shows that the Business page is unavailable, with the telephone and the leave warning) |
| `409 BOOKING_ATTEMPT_MISMATCH` | **unresolved**, not a rejection: it means an Appointment exists under this ID with other data; frozen **and marked possibly committed** (same ID and bytes), only the warned restart | same |
| `201` / `200` (`CONFIRMED` or `CANCELLED`) | confirmed; customer details cleared from memory | **resolves** the attempt |

  A new ID is therefore created only when no live attempt exists: at the first submit, after a first-send documented rejection, after the guest changed a choice following a first-send
  proven rollback or `429`, or after an explicit restart through the warning. It is never created because of a timeout, network error, rate limit, uncertain response, rerender, or
  navigation, and an unresolved attempt cannot be edited. A page-level callback never discards an unresolved attempt (the journey is not unmounted by a Business-unavailable answer
  while an attempt is unresolved). Residual risks (unchanged, ADR-0024): a refresh or closed tab discards the in-memory attempt, so a second booking is possible; `beforeunload` is best effort.
- **Duplicate submission.** A synchronous ref guard, plus a disabled control and a frozen review while sending. Nothing is posted on mount, refresh,
  navigation, a timer, or a rerender; the only POST trigger is the explicit button.
- **`Retry-After`.** Seconds or an IMF-fixdate (anything else is ignored), clamped to one hour, turned into a retry gate that is released by one
  timer; it never submits. Tested with a fake clock and no sleeps.
- **History and leaving (ADR-0026).** Each step pushes one history entry whose state is only `{spyBooking: {journey, step}}`; the URL, the
  title, storage, and cookies are never touched. In-page Back is a history traversal; a traversal the choices do not allow, or one that would leave a frozen
  review or a confirmation, is undone. Leaving to the profile asks «Остани» / «Напусни» only when there is data to lose (a chosen time or any
  detail), a send is pending, or the outcome is uncertain; from the browser's Back the shown entry is restored first, then the question is asked. `beforeunload` is armed
  under the same condition and is best effort. A Business switch remounts the page, so state and pending answers end with it.
- **Confirmation.** Only the server's facts: status, reference (informational), Service and StaffMember snapshots, date and time in the returned
  timezone with offsets where repeated, duration, EUR price, timezone. A `CANCELLED` replay shows «Резервацията е отменена» with no success
  styling. The text states that no email or SMS is sent and to contact the Business to change or cancel; there is no lookup, cancellation, payment, or
  notification promise.

### Verification (executed)

Local only; base `HEAD` `3576843` plus the uncommitted Phase 6 working tree (nothing staged). No CI run exists for this state.

- **Focused:** `npx vitest run src/public src/ui/UnsavedChangesGuard` (275 tests), three consecutive times, and again with
  `TZ=Pacific/Kiritimati`, `TZ=America/Los_Angeles`, and `TZ=UTC`.
- **Complete frontend suite (`npm test`): 67 test files, 1656 tests, 0 failures** (Phase 5: 61 files, 1443 tests; +6 files, +213 tests), after the correction below.
  `npm run lint` clean; `npm run build` succeeded (`tsc -b && vite build`).
- **Test files (211 new tests):** `BookingJourney.flow.test.tsx` 16, `BookingJourney.submission.test.tsx` 57, `BookingJourney.states.test.tsx` 32,
  `api.test.ts` 57, `attempt.test.ts` 39, `dates.test.ts` 10 (all under `src/public/booking/`); +1 in `src/public/api.test.ts`, +1 in `UnsavedChangesGuard.test.tsx`.
  Existing public-profile assertions changed only where the contract legitimately changed (the Service `id`; the profile now has booking buttons instead of the
  «все още не е налично» notice); none was weakened.
- **Flaky-test diagnosis.** One intermittent failure was found while writing the tests (about 1 run in 8 in one test) and diagnosed, not retried away: the test
  asserted before the refreshed offer had cleared a stale selected time, which exposed a real one-render inconsistency (the next action was enabled for a time the
  list no longer offered). The step now derives that state in the same render, and the test waits for the refreshed list. 60 consecutive runs of that test then passed.
- **Control runs.** Making the submit always draw a new attempt failed 15 of 40 submission tests; classifying an unreadable success as a rejection failed 4; after the correction, disabling the sticky-uncertainty branch failed 22 of 203 booking tests (before the final mismatch correction) and letting the Business-unavailable callbacks unmount an unresolved journey failed 2. All mutations were reverted and the suites re-run green.
- **Playwright, executed after the correction:** the existing `public-business-profile` spec (edited for the new booking buttons) ran against the final frontend and the working-tree backend through a copy of
  `scripts/run-e2e.sh` that differs only by naming that spec and fixing the repository path: **19 passed**, in the script's own disposable `spotyourslot-e2e` Compose project, which was removed with its volume afterwards. The development database and containers were not touched.
- **Not executed:** any rendered browser review and any backend test (no backend file changed); the booking journey itself has no Playwright coverage (Phase 8).
- **Hygiene:** `git diff --check` clean; V1 to V12 byte-identical to `HEAD` (hashes in the review archive).

### File inventory

Frontend source and tests: 13 new files in `src/public/booking/` (12 source files and the `testSupport.tsx` support file; 6 test files in addition), changed:
`PublicBusinessPage.tsx`, `api.ts`, `styles.css`, `UnsavedChangesGuard.tsx`, and the tests `PublicBusinessPage.test.tsx`, `api.test.ts`,
`UnsavedChangesGuard.test.tsx`; `e2e/public-business-profile.spec.ts` and `e2e/support/publicProfile.ts`; nine documents. The review archive carries the exact list.

### Evidence by requirement

- **Happy path with the real shapes, explicit and no preference:** `BookingJourney.flow.test.tsx` (the request key set and order, the null preference,
  the server-assigned member in the result, the advertised versus final price and duration).
- **Dependent-state reset, options not reloaded on a date change, retained values on Back and Forward:** the same file.
- **Timezone, date-only values, repeated hour:** `dates.test.ts`, the flow tests for 25 October (both `03:30` starts, the review, and the result).
- **Stale answers, aborted reads, Business switching:** the flow tests with deferred promises (signals observed aborted; late answers never rendered; a late POST
  answer after a Business switch is ignored).
- **Validation, retained values, focus:** `BookingJourney.states.test.tsx` (blur, wrapped inline errors, first invalid control, contact group, 500 and 501 code points
  counted as code points, server field errors).
- **Duplicate-submit protection, exact ID and body on every retry path, no new attempt while uncertain, `Retry-After`:** `BookingJourney.submission.test.tsx`
  (a table of eleven outcomes, repeated uncertain results, the frozen review, browser Back refused, the leave warning with the telephone, fake-clock waits) and `attempt.test.ts`.
- **Uncertainty preserved across later answers:** `BookingJourney.submission.test.tsx` (uncertain followed by each of eleven answers: rollback, 429, slot, Service, StaffMember, identity conflict, validation, oversized request, an unknown 4xx, an unavailable Business, a mismatch; the same ID and bytes, edits and the page unchanged, the leave warning still in force; a 200 replay and a `CANCELLED` replay each resolve it; the unavailable Business does not unmount the journey) and `attempt.test.ts` (the same matrix on the pure state machine).
- **201, 200, CANCELLED replay, malformed success:** the submission tests and `api.test.ts` (a table of eleven uncertain classifications and the documented rejections).
- **Unavailable, empty, network, 429, and backend-rejection states:** the states tests and the submission rejection tests.
- **Navigation guards and memory-only privacy:** the states tests (history entries carry only the marker; URL, title, head, storage, cookie, GET URLs, and `history.state`
  contain no sentinel detail or attempt ID; credentials omitted; no details in the DOM after success).
- **Regressions:** the public-profile suites and the whole suite (administration, platform, Customer, schedule) pass unchanged.

The frontend tests prove client behavior against stubbed HTTP; they do not prove the booking transaction, idempotency, or tenant isolation, which the backend Phase 4 and 5 tests prove.

### Deviations and limitations (all for review)

- **Undo of a refused traversal** uses `history.go`; it is verified in jsdom, which processes history traversals asynchronously, so the tests use bounded waits for the final state, never sleeps.
  Real browsers, especially Safari's swipe-back, must be reviewed in Phase 7.
- **Rapid repeated Back** while the leave dialog is open is not specially handled beyond the shared guard (one pending confirmation).
- **Retry gate granularity.** The wait is released by one timer and is not shown as a live countdown.
- **No server-side hint of the Business timezone before the first read;** the step shows it after `booking-options` loads.
- **Playwright** covers only the revised `public-business-profile` expectations (19 passed); the booking journey has no browser E2E until Phase 8.
- **Proposed items remain:** the wording in Proposed detail 9 (the corrected privacy sentence and the leave warnings, awaiting human visual review). The design is not visually approved.
- **Release dependency (unchanged):** production public booking waits for Issue #21. Issue #18 and #19 are not complete, and GitHub and the board are unchanged.

### Phase 6 correction (2026-10-07, before commit)

Review found that the first Phase 6 state machine could lose an unresolved attempt. Corrected evidence boundary:

1. **Sticky uncertainty.** `applyOutcome` no longer clears `possiblyCommitted` on a later rollback and no longer drops the attempt on a later rejection. After an uncertain send, a later rollback,
   429, validation, slot, Service, StaffMember, identity, size, unknown 4xx, Business-unavailable, or mismatch answer keeps the same attempt (same ID, same bytes), keeps edits and new attempts blocked, and keeps the leave warning. Only a valid
   success (201, a 200 replay, or a `CANCELLED` replay) or the warned restart or leave resolves it. The frozen state has a `cause` (`unknown`, `rate-limited`, `mismatch`, `business-unavailable`) that only selects the message.
2. **No silent loss through callbacks.** The Business-unavailable callback (from a booking answer or a read) no longer unmounts the journey while an attempt is unresolved; the review stays with an unavailable-page notice, the telephone, and the leave warning.
3. **Classification.** `BOOKING_ATTEMPT_MISMATCH` is no longer an ordinary rejection (it is unresolved even as a first answer). A problem code now counts only with its documented HTTP status (`400` validation, `404`
   Business, `409` slot, Service, StaffMember, identity, mismatch, `413`, `415`, `429` `RATE_LIMITED`, `503` rollback); every other answer, including an unknown 4xx, a proxy refusal, a documented code on the wrong status, and an unreadable body, is
   conservatively unresolved. Genuinely proven first-send failures stay editable. No POST is ever retried automatically.
4. **Privacy copy.** «Данните ви се използват само за тази резервация и са достъпни за бизнеса.» was replaced by «Данните ви се предоставят на бизнеса за записване и управление на резервацията.» (still wording awaiting human visual review).
5. **Tests.** Tests that expected an unknown 4xx to be a definite failure or a mismatch to allow a new attempt were replaced; 46 tests were added (unit matrix, API classification table, and the uncertain-then-answer integration matrix).
6. **Mismatch invariant (final correction).** A first mismatch now also marks the attempt `possiblyCommitted` (exact ID and body preserved), so the state machine itself, not only the UI hiding the retry button, keeps it frozen. A later rollback, rate limit, ordinary or unavailable-Business rejection, or unknown answer cannot make it editable or discard it; only a valid success (including a `CANCELLED` replay) or the warned abandonment resolves it. 8 sequential unit tests were added (frozen mark, five follow-up answers, two resolving replays).

The earlier statement that a rejected retry clears an earlier uncertain send was wrong and is withdrawn here, in ADR-0024, and in the code comments.

### Phase 7 browser-review checklist (not yet performed)

Process: `docs/ui-design-guidelines.md` section 18 and 22. Fixtures are created only through supported APIs or normal application flows (never by writing to the
database), in the disposable E2E stack or an equivalent review instance; the report lists every record created and how to remove it. Phase 7 prepares the review
app; this list is its input. Today is 2026-10-07, so the booking window is 2026-10-07 to 2026-11-05 and includes the 2026-10-25 clock change.

**Fixtures**

1. **Full Business** (ACTIVE, public telephone and address): at least three active Services, one with a long description, one 120 minutes with cents in the price, one with a 180-character name;
   at least two active StaffMembers assigned to them (one with a 120-character name) with weekly schedules covering weekdays so that several dates have slots.
2. **Repeated hour:** a StaffMember whose Sunday working period includes 02:00 to 06:00, so that 2026-10-25 offers both `03:xx` starts (summer and winter time).
3. **No eligible StaffMember:** an active Service with no assigned active StaffMember. **No availability:** a Service whose only StaffMember has no working periods in the window (or a Business closure over the whole window).
4. **Suspended and DRAFT Business** (the single unavailable page) and a Business with no Services (no booking entry).
5. **Races that need two browser tabs:** (a) in tab A reach the review for a slot, book that slot in tab B, submit in A (slot rejection); (b) deactivate the Service in the administration, then submit (Service rejection);
   (c) deactivate or unassign the StaffMember, then submit (StaffMember rejection); (d) book the same contact repeatedly (identity and rate limit; the review instance may set small `BOOKING_RATE_LIMIT_*` budgets).
6. **Throwaway mock proxy** (never the database) in front of the real backend for states that cannot be produced on demand: a response dropped after the backend committed (real uncertain outcome, then a real `200` replay on retry); `503
   BOOKING_TEMPORARILY_UNAVAILABLE`; `503 BOOKING_OUTCOME_UNCERTAIN` with `Retry-After: 2`; `429` with `Retry-After: 30`; an unreadable `201`; a `200` replay with `status: CANCELLED` (no cancellation exists yet); a slow response for the duplicate-click check.

**Per viewport (about 1280, 1024, 800, 375, and 200% zoom at 1280 and 375):** no horizontal page scroll; profile unchanged until a booking action is used; the hero action and each Service action wrap and keep a 44 px target; date chips and time chips wrap
and stay readable (the wide repeated-hour chips included); the review rows stack at narrow widths; long names wrap without breaking the layout; dialogs are content-sized; there is no excessive empty space and no adjacent elements stuck together.

**Walkthrough and states (record desktop and mobile):**

- Step 1 to 5 from the hero and from a Service card; progress text and heading correct; focus lands on the heading at every step; the keyboard alone can complete the whole journey (radio groups by arrow keys, visible focus everywhere).
- Back and Forward in the page and with the browser's Back and Forward (and the Safari edge swipe); inputs retained; changing the Service or the preference clears the dependent choices.
- Loading, no eligible StaffMember, no available date, a read failure (offline) with retry, a read `429` with the retry gate, an unavailable Service or StaffMember on a read.
- Details: untouched form, blur errors, wrapped long errors, submit with several errors (focus on the first), contact-or-email hint, 500 and 501 characters (including emoji) in the note, the privacy sentence wording.
- Review: summary content, the «not reserved until confirmed» sentence, «Промени» actions, the duplicate-click check (one request in the network panel), the pending state.
- Each outcome: success (201), replay (200), cancelled replay, slot, Service, StaffMember, identity conflict, mismatch (restart), validation (inline field errors), the known rollback and its retry, the unprocessed `429` and its gate, the **frozen uncertain state** (nothing editable, only «Опитайте отново», the telephone, the warning on leave and on restart),
  retry after a real dropped response returning the same booking, and the repeated-hour result (both `03:30` offsets distinguishable in the list, review, and confirmation).
- Leaving: nothing entered (no dialog), data entered (dialog, «Остани» and «Напусни», Escape), the browser Back out of a dirty journey, a closed or reloaded tab (the browser's own prompt appears and a reload restarts), after confirmation (no prompt).
- Network panel and storage: every public request without cookies, no personal data in any URL, `localStorage`, `sessionStorage`, `history.state`, or the title; a Business switch (another slug in the same tab) shows nothing from the first.
- Accessibility: a screen-reader pass of one step, the review, an error alert, and the dialog; contrast of the chips, the selected state, and the notices; reduced motion is unaffected (no animation exists).

Human approval is required before any of this is recorded in the UI guide, and Phase 8 follows only after it.
