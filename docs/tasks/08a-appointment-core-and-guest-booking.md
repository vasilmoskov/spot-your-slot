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

**Amendment (2026-10-07):** the multi-Service extension (ADR-0027, task 08b) adds seven separately approved phases M1 to M7 after Phase 7 and **replaces Phase 8**: the browser E2E and final acceptance are phase M7, covering the
single-Service and the multi-Service journeys. Phase 8 as written above has not been started and does not start before the extension is complete.

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
payments; waiting lists; slot holds or reservations; recurring or group appointments; multi-Service appointments (*originally excluded; since decided on 2026-10-07, see the multi-Service extension below and ADR-0027, and not yet implemented*); buffers;
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

## Phase 6 record (implemented, verified by automated tests, and committed as `584ea32`)

Scope: the public guest-booking frontend inside the existing `/{slug}` page, against the committed Phase 5 contracts, plus the
documentation. No backend, security, migration (V1 to V12 are byte-identical to `HEAD`), dependency, or configuration change; no
real API defect was found. It has **not** been reviewed in a rendered browser and the design is **not** visually approved (that
is Phase 7). Playwright journeys are Phase 8 and were not written; the existing `public-business-profile` Playwright spec was
updated and executed after the Phase 6 correction (19 passed).

### What was implemented

- **Entry and composition.** The profile keeps its approved composition. The hero gains one «Запази час» action (only when the Business
  has Services) and each Service card gains «Запази час за {Service}» (entering at the StaffMember step with the Service chosen). *(Superseded by the Phase 7 UX correction: the cards are informational again and the hero holds the only action.)*
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

### Phase 7 browser-review checklist (the input to the Phase 7 record below)

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

## Phase 7 record (developer rendered review performed on 2026-10-07; human visual approval is PENDING)

Scope: a rendered and behavioral review of the committed Phase 6 journey (`584ea32`) against the real backend, with the small frontend
corrections it found. No backend, migration, dependency, or configuration change; Phase 8 (Playwright journeys) was not started. This is
**developer review, not human visual approval**: the design, the privacy sentence, and the leave-warning wording remain proposed until a human
approves them, and nothing here is recorded as an enduring decision in the UI guide.

### Review environment (disposable, supported APIs only)

- Own Compose project `spotyourslot-review` (PostgreSQL `spotyourslot_review`, port 55433), the real backend on 18090 (booking budgets raised
  through the documented `BOOKING_RATE_LIMIT_*` variables so the review itself is not throttled), and one origin on 15190 that serves the
  **production build** of the frontend and reverse-proxies `/api` (the same-origin shape of production, so `Retry-After` is readable and no
  cookie crosses origins). The developer database, its container, and every unrelated port were not touched.
- A throwaway proxy (outside the repository) can arm a fault for the next booking POST (`drop-after-commit`, `rollback`, `uncertain` with
  `Retry-After: 2`, `ratelimit` with `Retry-After: 30`, `unreadable201`, `cancelled`, `mismatch`, `slow`). It logs only the attempt ID, a body hash,
  and the length, never a body, and it closes connections so the browser cannot reuse a dropped one. Faults armed through it are **simulated evidence**.
- Fixtures were created with the platform-administrator, invitation, Service, StaffMember, assignment, working-schedule and activation APIs,
  and with the public booking API for competing bookings: `salon-aurora` (7 Services including a 180-character name, a long description, a price with
  cents, a 120-minute Service, one with no eligible StaffMember, one whose only StaffMember has no working time, and an inactive one; 3 StaffMembers
  including a 120-character name), `studio-dst` (a Sunday period 01:00–06:00 so 2026-10-25 offers both `03:xx` hours; the review itself consumed that hour) and `studio-chas-2` (the same schedule, left untouched for the human review), `bez-uslugi` (only an inactive
  Service), `chernova` (DRAFT), `spryan` (SUSPENDED). Real bookings were made in the review database by the review itself.
  Cleanup: stop the review backend and the review server by their exact process IDs (never by a name pattern), then run
  `docker compose --project-name spotyourslot-review down --volumes` (this project only; it is never the development project).

### What was reviewed, and how (evidence kinds are kept apart)

- **Rendered, real backend (headless Chromium driven by Playwright, full-page screenshots inspected):** viewports 1280, 1024, 800, 375 (device
  scale 2), 320, and two *emulations* of 200% zoom: a 640 px-wide viewport at scale 2 (200% of 1280) and a 188 px-wide one (200% of 375). Both
  entry points and the whole journey (service, StaffMember, date and time, details, review, confirmation) at every width; the profile, the unavailable
  page (draft, suspended, unknown), a Business with no Services, no eligible StaffMember, no available date, the repeated hour, the review with
  long text, details with errors, the leave dialog, the restart dialog, and each outcome below. No horizontal overflow at 1280, 1024, 800, 375, 320, or
  the 640 px emulation. Touch targets were measured: every action and the whole choice, date and time rows are at least 44 px; the only smaller
  target is the profile's telephone link (24 px tall, the approved Issue #17 profile).
- **Keyboard (real backend):** the whole journey by keyboard alone (Tab, Arrow keys, Enter), the heading focused on every step, the visible 3 px focus
  outline on every control, Escape and «Остани» returning focus to the leave button.
- **Back, Forward, leaving (real browser history):** in-page Back and the browser's Back and Forward keep inputs; a Back that would leave a journey with a
  chosen time asks «Остани» / «Напусни» (both verified); a clean journey leaves without a dialog; `beforeunload` fired on a reload with details.
  The Business switch within one tab (history navigation to another slug) showed nothing of the first Business.
- **Privacy and network (real backend):** in 161 recorded public requests none carried a `Cookie`; every URL was one of five fixed shapes
  (profile, booking-options, availability with `date` and optional `staffMemberId`, bookings, profile of the other Business) with no personal value;
  `localStorage`, `sessionStorage`, `document.cookie`, the title, and the URL were empty or fixed with details entered; `history.state` held only
  `{spyBooking: {journey, step}}` (a random journey marker, never the attempt ID). The backend and proxy logs held no name, telephone, email, or attempt ID.
- **Real behavior against the real backend:** a successful booking (201), a competing booking of the same slot made through the public API before
  submit (slot conflict, with «Избор на друг час» recovery), the Service deactivated by the owner before submit, the StaffMember deactivated before
  submit (both reactivated afterwards), and an identity conflict (a telephone of one Customer with the email of another).
- **Simulated faults (proxy or browser route, labelled as such):** a response lost after a real commit, followed by the guest's real replay (200);
  a known rollback and its retry; an uncertain response with `Retry-After: 2` (retry disabled for 2.0 s, no POST meanwhile, then enabled);
  a `429` with `Retry-After: 30` (gated, no POST); an unreadable 201 followed by the real replay; a `CANCELLED` replay (no cancellation API exists);
  an attempt mismatch; a later rollback and a later 429 after an earlier lost response (still the same attempt, still frozen, still warned); a double
  click and an extra click during a slow response (one request); read failures (offline then retry; a read `429` with a gate); server field errors (400).
- **Frozen attempts (proxy log):** in every scenario with more than one send, the attempt ID and the SHA-256 of the body were identical across sends and
  the browser sent byte-identical bodies; the retry after an uncertain send was always a user click; no POST occurred while idle (3 s) or during a gate.
  Observation: when the proxy dropped a response on a *reused* connection, Chromium itself silently re-sent the identical request (same attempt ID and
  bytes) and the backend answered with a real replay. That is transport behavior, not an application retry, and ADR-0024's idempotency makes it
  safe; the proxy then closed every connection so the loss became visible to the page.
- **Accessibility measured (not a screen reader):** computed contrast, the accessibility tree of the review step (alert, disabled retry, named «Промени»
  buttons, term and definition rows), and the keyboard pass above.

### Defects found and corrected (frontend only; reused shared styles and components)

1. **Description text rendered semibold** (inherited from the shared `label` weight): long Service descriptions were heavy and hard to read. Supporting text of a choice is now regular.
2. **Duplicate Service line on the review:** the Service context bar repeated the «Услуга» row beneath it (UI guide §20). It is hidden on the review step only; steps 2–4 keep it. Test added (the control run fails without the fix).
3. **Selection by color alone:** a selected date or time chip differed from the others only by color. It now has a thicker border and heavier text as well.
4. **Contrast of the journey's status panels:** the shared danger text measured 4.44:1 and the shared success text 3.85:1 on their panels (below 4.5:1). Derived tokens `--color-danger-text` (6.05:1) and `--color-success-text` (5.04:1) apply inside the booking journey only.
5. A comment in `messages.ts` claimed no backend text is rendered; the fixed per-field validation sentences of a 400 are. The comment was corrected, behavior unchanged.

### Observations that were not changed (for the human review or a later decision)

- **Shared palette:** the same contrast shortfall exists on every administration status panel. Changing it app-wide is a design decision that needs approval; the journey-only tokens are a documented deviation until then.
- **Public shell minimum width of 320 px:** at 188 px (375 px at 200% zoom) the shell scrolls horizontally. 320 px is the WCAG reflow baseline, so this is outside the criterion and pre-existing in the approved shell; 200% at 1280 and 320 px itself are clean.
- The profile's telephone link is 24 px tall (meets the 24 px minimum of WCAG 2.2 but not the 44 px used elsewhere); a focus outline is drawn around the heading when the profile first loads under an automated browser. Both belong to the approved Issue #17 profile.
- The retry gate has no live countdown; the first-send `429` keeps the review editable while the retry is gated (as specified).
- The server's `fieldErrors` sentences are shown verbatim (they are fixed backend sentences).
- Primary and secondary buttons share one style, as the guide prescribes; the hierarchy between «Назад» and «Напред» is by order and label only.

### Verification (executed on the final code; local only, base `HEAD` `584ea32` plus the uncommitted Phase 7 working tree)

- `npm run lint` clean; `npm run build` succeeded; `npm test`: **67 test files, 1657 tests, 0 failures** (Phase 6: 1656; +1). The review used the production build of the same code.
- **Not executed:** any backend test (no backend file changed), Playwright E2E (Phase 8), CI.
- **Not verified, stated explicitly:** a real screen reader (VoiceOver, NVDA, TalkBack) pass; Safari and its edge-swipe back gesture; real touch input; true browser zoom (it is emulated by a narrower viewport at double scale); real mobile devices. The built-in browser pane showed the page once at 1280 px; every other screenshot is from the Playwright Chromium.
- Human visual approval, the privacy sentence, and the leave-warning wording: **pending**.

### Phase 7 follow-up: the CI E2E failure of run 37634315740 (commit `584ea32`)

- **Failure:** 1 of 74 browser tests failed in CI: «administration stays reachable, the retired form notes stay absent and public visits create no session», with
  `request.allHeaders: Target page, context or browser has been closed`. Frontend and backend jobs passed.
- **Diagnosis (log and code; the mechanism reproduced with fakes):** `recordApiRequests` started `request.allHeaders()` with `void ...then(...)`: no handler for a
  rejection, `hasCookie` defaulting to `false`, and nothing that waited. In that test the owner page navigated on (`owner.goto('/#/business/services')`) and the
  `finally` closed the contexts while header reads of API requests were still pending; those reads rejected, nothing handled them, and Playwright attributed the unhandled
  rejection to the running test. The same shape could also let an assertion read `hasCookie === false` before the read had finished, which is a latent false negative of the privacy
  assertion. It is a test-support race, not a product defect, and it was not a flaky test to retry.
- **Change (test support only; no production code, no assertion weakened, no sleep, no timeout, no skipped test):** `recordApiRequests(page)` now returns an audit whose only
  data accessor is `stop()`. `stop()` removes the listeners, awaits every started header read, drops requests the page aborted (`ERR_ABORTED`, explicit), and **throws**
  when a read failed for any other request instead of reporting "no cookie"; every read has a handler from the start, so a closing context can no longer raise an unhandled
  rejection. The five call sites call `stop()` right where they previously asserted, before navigating on or closing. New `e2e/request-audit.spec.ts` (7 tests, fake page and
  deferred reads, no timers): a pending read is awaited, a missing `Cookie` header is `false` and a present one `true`, a failed read throws, an aborted request is dropped even if its read
  fails, another failure is judged by its headers, other origins are ignored and listeners are removed, and no unhandled rejection occurs. `docs/testing-strategy.md` records the rule.
- **Control runs (executed):** treating a failed read as "no cookie" failed 2 of the 7; removing the rejection handler (the original defect) failed 3 of the 7; both were reverted
  (`diff` against the saved good file is empty).
- **Executed on this working tree (base `HEAD` `584ea32` plus the uncommitted Phase 7 and follow-up changes), local only:**
  `eslint e2e` and a strict `tsc` of the two touched specs clean; `npm run lint` clean; `npm run build` succeeded; `npm test` 67 files, 1657 tests, 0 failures;
  the two affected specs through a copy of `scripts/run-e2e.sh` that differs only by the repository path and the spec arguments: **26 passed**; the failing test repeated 8 times:
  **8 passed**; and the **complete suite through the unmodified `scripts/run-e2e.sh`: 81 passed, 0 failed** (the earlier 74 plus the 7 audit tests), in the disposable
  `spotyourslot-e2e` Compose project, which the script removed with its volume. The development database and the review stack were not touched.
- **Evidence limits:** these are local runs; no CI run exists for this working tree. The original race is timing dependent, so passes alone do not prove its absence; the proof is the
  deterministic audit spec and the removal of the unhandled and defaulted paths. The original failure was not reproduced in the real browser flow, only its mechanism with fakes.

## Phase 7 UX correction after the human review (2026-10-07; human visual approval still PENDING)

Base `HEAD` `41e13fb` (the request-audit correction is committed) plus the uncommitted working tree. Scope: the frontend only. No backend booking
semantics, migration, API contract, or dependency changed, and Phase 8 was not started. Every item below is the developer's implementation of the human feedback;
none is a human approval, and the composition stays «awaiting human visual approval».

### What changed

1. **Profile.** One «Запази час» action in the hero, no box around it; the Service cards are informational and hold no button, link, or other control (a unit and a
   browser test assert it). The journey always starts at the Service step (the `initialServiceId` entry and its history entry were removed).
2. **Single-Service booking is unchanged.** The Service stays a radio group and one booking is one Service, one StaffMember, one POST. Existing combined Services stay bookable through the
   current contract.
3. **Date and time.** One shared section: a monthly calendar (`BookingCalendar.tsx`, pure arithmetic in `calendar.ts`) beside the slots of the selected date from 48rem up, stacked below it.
   Bulgarian fixed month and weekday names, Monday first, month buttons limited to the booking horizon (`booking-options` `firstDate` and `lastDate`), a polite month title, a polite
   «Избрано: …» summary. A date is selectable only when `availableDates` lists it; nothing is computed. «Today» is the Business-local date of the browser clock in the returned timezone, used only to
   label that cell (the contract defines `firstDate` as the first bookable date, not as today). The technical timezone identifier is no longer shown anywhere; the page says
   «Часовете са по местното време на бизнеса.» and the offset label stays only for the repeated hour. No dependency was added: the grid is small, its arithmetic is pure and
   tested, and a date-picker library would have brought its own markup, locale data, and styling to override.
   Accessibility follows the WAI-ARIA date picker grid pattern: `role="grid"` labelled by the month, `gridcell` with `aria-selected`, `aria-disabled`, and `aria-current="date"`, a labelled cell
   («сряда, 7 октомври 2026 г., днес, няма свободни часове»), one tab stop, arrow keys by day and week, Home and End within the week, Page Up and Page Down by month, Enter and Space to select.
4. **Hierarchy and details.** The selected-Service line sits above the heading on steps 2 to 4 and is absent on step 1 and the review. The permanent «0 / 500 знака» counter is gone; from 400 code points the
   form shows «Остават N знака.» (and «Надвишавате ограничението с N знака.» past 500); the input is never truncated and the 500-code-point validation is unchanged. The approved privacy sentence is
   unchanged and uses the form's width (the 65ch limit was removed).
5. **Editing from the review.** «Промени» opens the step in an edit mode (`editing` in the journey state, ended on reaching the review). While a time is still chosen each step offers «Към прегледа» in place of «Напред».
   A Service or StaffMember change clears exactly the dependent choices (time; date; the preference after a Service change), the details are preserved, and the guest is asked only for what was cleared.
   The way back is the browser's own history: `useJourneyHistory` now tracks the highest existing entry (`top`) and `goTo` goes forward through it when the entry exists, otherwise pushes the missing entries, so the
   entry index still equals the step ordinal and Back and Forward stay coherent. The browser's Forward to a review that is no longer reachable is refused as before, a sending or unresolved attempt is still uneditable, and
   the recovery buttons of the review (another time, Service, StaffMember, details) use the same edit mode.
6. **Confirmation.** One heading; the server's Service, StaffMember, date, time, duration and price; «Запазете данните за резервацията.»; the Business contact. The duplicate success sentences, the status row, the
   reference row, and the timezone row are not rendered (the reference and timezone stay in the decoded answer and in the state, and the date and time use the returned timezone with offsets only where repeated).
   A `CANCELLED` replay keeps its own heading and its error-styled notice. No notification is implied.
7. **Action order.** Audit of every `action-group`: 18 groups in 13 files put the confirming action before Cancel or the safe action. They were reordered in the DOM, with no behavior change: Cancel before Save or Create
   (the profile and Customer, Service, StaffMember, Business, schedule-change, and working-schedule forms, the StaffMember Service assignments, and the period dialogs), the safe action before the confirming one in every
   confirmation (deactivation, lifecycle, resend invitation, clear day, clear schedule, delete schedule change, copy). The shared unsaved-changes dialog already had «Остани» first. The authoritative rule is in
   `docs/ui-design-guidelines.md` section 6, with pointers in `AGENTS.md` and `CLAUDE.md`, and `src/ui/actionOrder.test.ts` enforces it. One existing test asserted the old destructive-first order and was updated.
   Groups that are neither cancelling nor confirming (an «Редактирай» beside an «Изтрий», a retry beside a link back to a list) were left as they are and are covered by no rule: that is the one deliberate exception.

### Earlier proposed follow-up: booking several Services in one visit (SUPERSEDED by the approved decisions of the multi-Service extension below; still NOT implemented)

The request to choose several Services needs product and contract decisions before any code. Radios were not replaced by checkboxes, and no multi-Service booking is simulated with several POSTs. Decisions required:

- **StaffMembers:** one StaffMember for all Services, or a possibly different one per Service; how «Без предпочитание» resolves; whether each Service needs its own eligible StaffMember.
- **Order:** who decides the order of the Services, whether the guest can change it, and how it is shown and stored.
- **Totals:** the total duration and total price rules (sum, rounding, a package price), and whether buffers exist between Services (the MVP has none).
- **Continuous availability:** whether the Services must be back to back, how a continuous block is found by the backend (the frontend must never compute it), and how gaps or a DST change inside the block behave.
- **Atomic reservation:** one transaction for all parts or none; how the overlap exclusion and the schedule revision guard of ADR-0023 and ADR-0025 apply to several intervals; the partial-failure and rollback semantics.
- **Snapshots:** whether a booking holds several Appointment records or one with parts, what each snapshots (Service name, duration, price, StaffMember), and how the owner's calendar and Customer history show them.
- **Idempotency:** one attempt identity and one request fingerprint for the whole selection (ADR-0024), the uncertain-outcome and replay response for several parts, and the confirmation and cancelled-replay shapes.
- **Contract:** a new endpoint or a versioned change of `POST …/bookings` (an API contract change, Strict work), the rate-limit budgets, and the public wording.

It would be proposed as a separate ADR and issue with its own review; until then a Business that wants a combined visit offers a combined Service.

### Verification (executed on this working tree, local only)

- `npm run lint` clean; `npm run build` succeeded; `npm test`: **71 test files, 1706 tests, 0 failures** (before this correction: 67 files, 1657 tests). New: `calendar.test.ts` (13), `BookingJourney.calendar.test.tsx` (19), `BookingJourney.edit.test.tsx` (12),
  `actionOrder.test.ts` (4) and a counter test. Existing public-profile, journey, and administration tests changed only where the contract legitimately changed; none was weakened, and every attempt-ID, exact-body, sticky-uncertainty,
  retry-gate, and privacy test passes unchanged.
- **Control runs (executed, each reverted):** never offering «Към прегледа» failed 12 tests; allowing the browser's Forward to a stale review failed 1; allowing an unavailable date failed 2; a counter from 300 code points failed 1; `goTo` always pushing failed 4;
  unbounded month navigation failed 1; an edit that never ends failed 1 (found by this run, then covered by a new assertion); a confirming action first in a form group failed the action-order test.
- **Browser:** the complete suite through the unmodified `scripts/run-e2e.sh`: the first run found **1 failure**, a stale assertion that still counted one booking button per Service («the page shows exactly the approved public information of the full Business»);
  it was updated to the new contract and the **second complete run passed 81 of 81**. The disposable `spotyourslot-e2e` project was removed by the script; the development database was not touched.
- **Rendered, real backend (Playwright Chromium, screenshots inspected):** 1280, 800, 375, and 320 px viewports, the profile, the calendar and slots, month navigation, the details with the counter, the review, the edit mode, the confirmation, and the
  repeated hour of 2026-10-25 (`03:30 (UTC+03:00)` and `03:30 (UTC+02:00)`, in the list and in the summary). No horizontal overflow at any of them. Keyboard, run in the real browser: Tab reaches the month buttons and then the grid's single tab stop; Arrow, Page Down and Page Up, Home, End, and Enter
  behave as specified; every focused control shows a 3 px outline. Contrast measured: selected day 5.15:1, available day text 16.27:1, the weekday names and the unavailable days 4.97:1 on white.
- **Viewport emulation, not real zoom:** every width above is a Playwright viewport size, and 320 px approximates 400% zoom of 1280 px. **No real browser zoom was performed**, and no screen reader, Safari, touch input, or real device was used.

### Observations and limits (for the human review or a later decision)

- At 320 px a day cell is 30 px wide and 44 px tall (the 24 px WCAG 2.2 minimum holds, a 44 px square does not); at 375 px it is 38 x 44. The month title wraps to two lines at 320 px.
- Days outside the booking period are shown at reduced emphasis (inactive content, not a control) and are announced as outside the period.
- The shared danger and success palette still measures 4.44:1 and 3.85:1 as panel text; only the booking journey uses the darker text tokens (see the Phase 7 record above).
- A discrepancy found by the action-order audit and **not changed** (it is behavior, not order): the Business lifecycle confirmation in the platform administration (`BusinessDetail.tsx`) moves initial focus to its confirming button, which is the destructive one for a
  suspension, while section 19 says a destructive confirmation never receives default focus. It needs a decision before it is changed.
- The profile's telephone link (24 px tall) and the heading focus ring on first load belong to the approved Issue #17 profile and were not touched.
- Human visual approval of the redesigned composition, the privacy sentence, and the leave-warning wording: **pending**.

## Phase 7 human-review corrections, second round (2026-10-07; human visual approval still PENDING)

Base `HEAD` `41e13fb` plus the uncommitted working tree. Frontend only: no backend semantics, migration, API contract, or dependency changed, and Phase 8 was not started. Booking several Services is a
**requested capability, since decided in ADR-0027** ([task 08b](08b-multi-service-guest-booking-plan.md), see the multi-Service extension at the end of this record); **it is not implemented, and the product books exactly one Service per booking.**

### Wording amendment (approved by the human review)

| Place | Before | After |
|---|---|---|
| Missing or blank name | «Въведете име до 200 знака.» | «Въведете име.» |
| Invalid or overlong name | the same combined sentence | «Проверете въведеното име.» (no number; the 200-character rule is unchanged; the input is never truncated) |
| Backend `displayName` field error | shown verbatim | mapped to the same public sentence; no backend field text is shown for any field |
| Identity conflict (`409 BOOKING_NOT_COMPLETED_ONLINE` only) | «Не можем да завършим резервацията онлайн. Моля, свържете се с бизнеса.» | «Телефонът и имейлът не съответстват. Проверете ги или въведете само единия контакт.» |
| Date and time step | «Часовете са по местното време на бизнеса.» | removed (the calculation and the offset label of the repeated hour are unchanged) |

ADR-0020 and task 07a still quote the earlier conflict sentence as history; this record is the amendment (accepted ADRs are not rewritten, `docs/decisions/README.md`). The administration wording («Въведете име на клиента до 200 знака.») is unchanged.

### Diagnosis of the observed rejection

- **What it was.** The sentence belongs to exactly one outcome: `BookingResult.IdentityConflict`, mapped by `PublicProblems` to `409 BOOKING_NOT_COMPLETED_ONLINE`.
- **The precise condition** (ADR-0020 rows 8 to 10, conservative matching): the guest supplied **both** a telephone and an email, and they do not both belong to the same stored Customer of that Business. That is: the telephone belongs to one Customer
  and the email to another; or the telephone belongs to a Customer who has no email or a different one and the email is held by nobody; or the same with the roles reversed. Matching never merges, attaches, moves, or overwrites a contact,
  and a single supplied contact never conflicts.
- **Evidence for the original event, and its limits.** The review proxy recorded 54 booking requests since its last start: all earlier `409` answers came from the review's own scripted races, and exactly one came after them (request 54, 21:39 local time) with no scripted fault; that is the one the human saw. The proxy
  logs only the attempt identifier, a body hash and length, and the status, so **the response body of that event was not retained and its problem code cannot be proven from the log**. The sentence reported by the human, the status, and the state of the data are consistent with an identity conflict
  and with no other documented `409`.
- **The data.** The review Business held six Customers read through the owner's Customer API (shapes only): three with a telephone only, two with an email only, one with both. The telephone-only Customers were created by the review's own automated bookings, which reused a few telephone numbers without an email.
  A guest who then enters one of those telephones **with an email** matches row 9.
- **Reproduction (a reproduction, not the original event).** On a clean review Business, with synthetic contacts and the supported public API, the matrix was reproduced exactly: a new telephone and email creates a Customer (201); the same pair again is an existing Customer (201); a telephone only creates (201) and
  again finds it (201); the same telephone with a new email is `409 BOOKING_NOT_COMPLETED_ONLINE`; an email only creates, and the same email with a new telephone is `409 BOOKING_NOT_COMPLETED_ONLINE`; the telephone of one Customer with the email of another is `409 BOOKING_NOT_COMPLETED_ONLINE`. After the conflicts the Customers still
  had exactly the contacts they started with (read back through the owner API): nothing was attached, merged, or overwritten.
- **Verdict.** An **expected conservative contact conflict** on **polluted review fixtures** (phone-only Customers reused by earlier automated runs). **Not a product defect:** the matching policy was not changed and was not weakened to make the review booking pass. The product cost, accepted in ADR-0020, is that a
  returning guest who adds an email later cannot complete online; the new sentence tells the guest what to do (check both, or enter only one contact) instead of sending them to the Business.
- **Privacy note on the new sentence.** It names neither the contact that matched nor whether a Customer exists. As before, an unauthenticated visitor can tell a conflict from a success (the existing, rate-limited oracle of ADR-0020 and ADR-0026); the wording does not widen it.
- **Scope of the wording.** Used only for the documented status and code (`409` with `BOOKING_NOT_COMPLETED_ONLINE`); any other `409` code, an unknown 4xx, or a documented code on another status keeps its own handling. The entered details are kept, «Промяна на данните» returns to them, and the Business telephone stays shown when configured.

### Clean synthetic fixtures for manual validation

Business `kontakti-demo` («Студио Контакти (за проверка)»), created and filled only through supported APIs (platform administration, invitation, Service, StaffMember, schedule, and the public booking API); no database write. It holds the scenarios below; the contact values are synthetic and are given to the reviewer in the handoff, not in this document.

| Scenario | Prepared state | Guest enters | Expected result |
|---|---|---|---|
| New Customer | nothing | an unused telephone and an unused email | booking confirmed; a Customer with both contacts is created |
| Existing Customer, exactly matching | a Customer with both contacts | exactly those two | booking confirmed; the same Customer is used |
| Conflict, telephone with another email | a Customer with a telephone only | that telephone and a new email | the new conflict sentence; details kept; «Промяна на данните» |
| Conflict, two Customers | one telephone-only and one email-only Customer | the telephone of one and the email of the other | the new conflict sentence |

### Destructive-focus correction

The platform Business confirmations (suspend, activate, reactivate, resend invitation) moved initial focus to the confirming button, which is destructive for a suspension, against section 19. Initial focus now lands on the safe «Отказ» (the first button in the DOM, before the confirming one);
after a cancellation the focus returns to the control that opened the confirmation («Спри временно», «Активирай», «Изпрати покана»). These confirmations are inline `alertdialog` panels, not modal: they have no focus trap and Tab leaves the panel to the next control, as before; that existing containment behavior is
unchanged and not claimed to be modal. Regression tests: `BusinessDetail.test.tsx` (initial focus, the order, the destructive variant, focus restoration, and the resend dialog).

### Shared status text contrast

The base danger and success colors stay for borders, buttons, and icons. The text of `status-error`, `status-success`, and the success status badge now uses the shared tokens `--color-danger-text` and `--color-success-text`; the booking-journey-only override was removed.

| Text | Before | After |
|---|---|---|
| danger panel (`#d92d20` → `#b42318` on `#fef3f2`) | 4.44:1 | 6.05:1 |
| success panel and success badge (`#168f6b` → `#0f7a5a` on `#ecfdf3`) | 3.85:1 | 5.04:1 |
| `field-error` (danger on white, unchanged) | 4.83:1 | 4.83:1 |

Material visual change: status text is a shade darker; panel backgrounds, borders, and buttons are unchanged. Inspected at 1280 and 375 px in the public booking journey (the conflict notice) and the platform administration (the success panel «Промените са запазени.», the concurrent-edit error panel, and the status badge).
**Not changed and needing a decision:** the warning badge text (`#d97706` on the page background) measures 3.0:1. `src/ui/contrast.test.ts` pins the corrected ratios from the shared CSS.

### Verification (executed on this working tree, local only)

Commands and results on the final code (base `HEAD` `41e13fb` plus the uncommitted working tree; local only, no CI run):

- `npm run lint` clean; `npm run build` succeeded; `npm test`: **72 test files, 1715 tests, 0 failures** (previous entry: 71 files, 1706 tests); the strict `tsc` of the E2E specs clean.
- The complete browser suite through the unmodified `scripts/run-e2e.sh`: **81 passed, 0 failed**, run twice on the final code (the second after the control runs below); the disposable `spotyourslot-e2e` project was removed by the script.
- **Control runs (executed, each reverted and the suite re-run green):** a blank name using the invalid-name sentence failed 4 tests; the conflict sentence leaking to a slot rejection failed 5; focus on the confirming action failed 3; focus not restored after a cancellation failed 3; the base
  danger color as panel text failed the contrast test. One slip is recorded: the first attempt to back the files up did not run (a shell word-splitting error), so the five mutations stayed applied until they were reverted by their exact inverses; the restored tree was then verified by the full unit suite (1715 passed), lint, the type check, and the second complete browser run.
- **Migration integrity:** no backend file changed (`git diff -- backend` is empty); `V1` to `V12` are byte-identical to `HEAD`; no migration was added. The checksums are in the review archive.
- **Evidence limits:** local runs only; the diagnosis of the original rejection rests on the proxy's status log and the data shapes, not on the lost response body; the destructive-focus fix was keyboard-checked in Chromium, not with a screen reader; the contrast figures are computed from the shared CSS and read from the browser's computed styles,
  not from an assistive-technology audit; real browser zoom, Safari, touch, and real devices were not used.

Rendered, real backend (Playwright Chromium, screenshots inspected at 1280 and 375 px): the blank and the overlong name errors with focus on the first invalid field; the date and time step without the timezone note and the repeated
hour (`03:30` at both offsets); the conflict message, the Business telephone, the kept details, and the way back; the suspension confirmation by keyboard (initial focus «Отказ», Tab order, focus back on «Спри временно»); the admin success and error panels. **Not used:** a screen reader, Safari, touch, real browser zoom.

## Multi-Service extension: decisions and documentation phase (2026-10-07)

**Documentation phase only. Nothing is implemented, no backend, schema, API, or checkbox change was made, the product still books exactly one Service per booking, and issue #18 is not complete.** The product owner approved the model; it is
recorded in [ADR-0027](../decisions/ADR-0027-book-several-services-as-one-atomic-visit-with-service-lines-a-versioned-set-fingerprint-and-an-explicit-review-consistency-check.md) (which amends ADR-0013, 0016, 0022, 0023, 0024, and 0026 through amendment notes that preserve their text) and planned in [task 08b](08b-multi-service-guest-booking-plan.md).

### Approved decisions and where they are specified

| Decision | Specified in |
|---|---|
| A visit of one to five distinct active Services, one StaffMember, consecutively | ADR-0027 section 1 |
| `appointment_service` line table with per-Service snapshots and visit totals on the Appointment; migration `V13` with a backfill; no committed migration edited | sections 3 and 11 |
| Execution order is the public-profile order; click order affects neither execution nor request identity (fingerprint: ascending UUID) | section 2 |
| At most 5 Services and 480 total minutes | sections 1 and 8 |
| Eligible StaffMembers support every Service; one continuous interval for the summed duration; an empty intersection is explained at the staff step with a return to the Service selection | sections 4 and 10 |
| One atomic submission; no separate POSTs, partial bookings, or cosmetic multi-selection | sections 1 and 7 |
| Legacy single-Service requests and historical replays preserved; encoding v1 kept, encoding v2 for new attempts | sections 5 and 11 |
| Cancellation and replay describe the whole visit; no cancellation endpoint | section 9 |
| **Explicit review-consistency check** (reviewed duration and price per Service, integer minutes and cents, inside the fingerprint, compared with the locked Services before availability, the Customer, and any write; `409 BOOKING_REVIEW_CHANGED`; a proven first-send rejection permits a fresh attempt; after an earlier uncertain send the attempt stays frozen) | section 6 |

### Reconciliation of this record

- The earlier proposed follow-up above (the list of open questions) is answered by the table; its policy on price and duration drift (the server silently authoritative) is **superseded** by the review-consistency check.
- The Phase 6 attempt rules (sticky uncertainty, exact frozen body, a first-send documented rejection drops the attempt) are unchanged; `BOOKING_REVIEW_CHANGED` joins the documented first-send rejections and, after an earlier uncertain send, the frozen causes.
- The final UI of the extension keeps the Phase 7 corrections and adds real checkboxes, the selected-Service summary with the total duration and EUR price before «Напред», and one whole-visit review; it enters the UI guide after human approval.
- Permanent documents reconciled: `docs/data-model.md`, `docs/architecture.md`, `docs/product-spec.md`, `docs/security.md`, `docs/testing-strategy.md`, `docs/implementation-plan.md`, `docs/product-roadmap.md`, `docs/ui-design-guidelines.md`, and the `docs/decisions/README.md` index.
- Unchanged: issue #19 (incomplete), the release dependency on issue #21, the human visual approval still pending for the Phase 7 composition, and the proposed wording (the new codes' messages and sentences are **Proposed**).

### Contradictions and open product questions

No contradiction needs a product decision. Two judgements were resolved as routine technical choices and are recorded for the review: (1) "request identity independent of click order" and "execution in profile order" are both met by using
two orders (ascending Service UUID for the fingerprint, which a rename cannot change; the profile order for the stored positions); (2) the legacy single-Service request shape carries no reviewed facts and therefore **is not review-checked**; it
exists for compatibility and direct API callers, the frontend always sends the new shape, and retiring the legacy shape is a later decision.

### Specification corrections before M1 (2026-10-07; documentation only)

Two gaps found in the ADR-0027 specification were closed before any implementation; neither changes an approved decision.

1. **Capacity of the visit total.** A Service price is at most `9 999 999 999.99` (10 integer digits, `numeric(12,2)`), so a visit of five such Services totals `49 999 999 999.95` (11 integer digits), which does not fit the `numeric(12,2)` of `appointment.price_eur`.
   `V13` widens only that column to `numeric(13,2)` (maximum `99 999 999 999.99`); `service.price` and the line prices stay `numeric(12,2)`; no guest limit is added. The widening keeps every stored value (the plan tests this, including that the table is not rewritten);
   all arithmetic is exact (`BigDecimal` with `addExact` on integer cents, `numeric` in SQL, integer cents in the browser); the bounds are `999 999 999 999` cents per Service and `4 999 999 999 995` cents per visit. ADR-0027 section 3 and the M1 and money test plan are updated.
2. **UUID order.** "Ascending UUID" is defined as the unsigned lexicographic order of the 16 big-endian bytes (equal to the canonical hexadecimal order and to PostgreSQL's `uuid` order), **not** Java's `UUID.compareTo`, which is signed. For `00000000-0000-4000-8000-000000000001`, `7fffffff-ffff-4fff-bfff-ffffffffffff`, `80000000-0000-4000-8000-000000000000`,
   `ffffffff-ffff-4fff-bfff-ffffffffffff` the specified order is A, B, C, D and Java's is C, D, A, B. The fingerprint v2 order uses the unsigned comparator; **lock order is the order of the locking statement's `ORDER BY id ASC FOR SHARE`** (the existing `ServiceStore.lockReferences` and StaffMember statements), and the application never pre-sorts identifiers to decide a lock.
   The golden vectors, a PostgreSQL ordering test, a lock-statement test, a deadlock test across the high-bit boundary, and a guard against `UUID.compareTo` are in the plan. **Observation, not changed:** committed code uses Java's signed order for the published availability slot's StaffMember list invariant and for the final identifier tie-break of the deterministic
   StaffMember assignment; neither decides a lock, and aligning them would change which StaffMember a tie assigns, so it is a separate decision.
