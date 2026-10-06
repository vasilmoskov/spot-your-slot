# SpotYourSlot — Appointment Core and Guest Booking

Status: Phase 1 (decisions and documentation) is written and awaits review and commit.
No Appointment code, migration, test, configuration, or dependency exists yet; Phases 2 to 8 are
planned and not started. Issue #18 is closed only on explicit approval.
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

- **Schema (Phase 2, `V11`):** `appointment` as in ADR-0022. **Phase 3, `V12`:** the Business-level schedule
  revision (ADR-0025).
- **Public routes (Phase 5):** the amended profile (`services[].id`), `booking-options`, `availability`, and
  `POST …/bookings` (ADR-0026).
- **New `booking` module:** depends on `business`, `catalog`, `workforce`, `scheduling`, `customer`, and
  `shared.contact`; nothing depends on it; `scheduling` never depends on it. It implements
  `scheduling.BusyIntervalSource` and removes `NoBookingBusyIntervalSource` and its wiring test in Phase 2
  (ADR-0016).
- **New narrow published contracts:** `business` (lock by slug at any lifecycle status; schedule revision bump and
  shared lock), `catalog` (Service identifier in the public contract; a lockable bookable-Service snapshot
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

1. Exact names and column layout of the revision table, its contract, and the migration number (Phase 3).
2. How a new Business receives its revision row (the Business-creation transaction versus another mechanism).
3. Final contract and constraint names, and the public-reference alphabet (Phase 2).
4. The byte layout of the canonical request encoding (frozen in Phase 4 with golden vectors) and the exact note
   normalization.
5. Configuration property names for the fingerprint key ring, the limiter, and startup validation, and whether
   startup checks that every stored key version is configured (Phases 4 and 5).
6. Which SQLState and driver conditions prove a server-reported rollback at `COMMIT` (Phase 4 tests).
7. The HTTP status of the `GET` routes for an unavailable Service, `Retry-After`, and the fixed problem
   `instance`.
8. The per-address aggregate limiter budget that prevents one address from exhausting limiter capacity, and the
   capacity default.
9. The wording of the privacy notice and of the confirmation shown when leaving the frozen uncertain state.
10. The mechanism that makes public endpoints ignore the session (Phase 5).

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
