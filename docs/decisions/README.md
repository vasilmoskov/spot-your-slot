# SpotYourSlot architecture decision records

## Purpose

Architecture decision records (ADRs) preserve significant product architecture
and engineering decisions, their context, considered alternatives, and lasting
consequences. They complement the permanent documentation without turning it
into a historical decision log.

Create an ADR when a decision materially affects architecture, security, data
integrity, module boundaries, operational behavior, technology direction, or a
costly-to-reverse engineering constraint. Do not create one for routine
implementation details, local refactoring, formatting, minor bug fixes, or a
choice that has no meaningful alternatives or lasting consequences.

Keep one independently reviewable decision in each ADR. ADRs should be concise
decision records, not long architecture essays. Review whether significant new
decisions need ADRs at the end of every substantial issue or phase.

## Identification and filenames

Assign identifiers sequentially as `ADR-0001`, `ADR-0002`, and so on. Do not
reuse identifiers. Use the filename convention
`ADR-NNNN-short-kebab-case-title.md`.

An ADR has one of these lifecycle statuses:

- `Proposed`: under review and not yet authoritative;
- `Accepted`: approved and currently authoritative;
- `Superseded`: replaced by a later ADR;
- `Deprecated`: retained for history but no longer recommended or applicable.

`Pending` is only an index workflow marker for a planned ADR file that has not
yet been written. It is not an ADR lifecycle status.

## History, dates, and evidence

Do not rewrite an accepted ADR to make a later decision appear original. A new
ADR supersedes an earlier one by linking to it, and the earlier ADR is updated
only to record its `Superseded` status and the replacing ADR.

Record the original **decision date** separately from the **recorded date** on
which the ADR file was created. For retrospective ADRs, use the best evidenced
original date available. Mark it as `Unknown` or clearly `Approximate` when the
evidence does not support an exact date; never invent precision. The recorded
date is always the actual ADR creation date.

Retrospective ADRs must distinguish facts supported directly by historical
repository evidence from later interpretation. Label later interpretation as
inference. Link evidence using repository-relative paths to relevant permanent
documents, task files, source, migrations, or tests. Current passing tests may
demonstrate present behavior, but do not prove the historical reasoning behind
a decision.

Keep rejected alternatives visible and describe their genuine merits and
disadvantages. Do not present the selected approach as inevitable.

## Required ADR structure

Each ADR contains:

1. title and metadata: identifier, lifecycle status, decision date, recorded
   date, related issue, and supersession links when applicable;
2. context and problem;
3. constraints;
4. options considered;
5. decision;
6. rationale;
7. tradeoffs and disadvantages;
8. risks and mitigations;
9. consequences;
10. evidence, distinguishing direct evidence from inference;
11. conditions for revisiting the decision.

## Decision index

Until an ADR exists, show its planned filename as inline code and mark it
`Pending`. After the ADR is created and independently accepted, replace the
inline filename with a repository-relative Markdown link and replace `Pending`
with the ADR's actual lifecycle status.

| ADR and planned file | Title / problem | Status | Decision date | Recorded date | Related issue |
|---|---|---|---|---|---|
| [ADR-0001](ADR-0001-use-a-modular-monolith.md) | Use a modular monolith | Accepted | 2026-08-12 | 2026-08-25 | #1, #2, #3, #4, #5, #8 |
| [ADR-0002](ADR-0002-use-postgresql-as-the-transactional-system-of-record.md) | Use PostgreSQL as the transactional system of record | Accepted | 2026-08-12 | 2026-08-25 | #1, #2, #3, #4, #5, #8 |
| [ADR-0003](ADR-0003-use-business-scoped-memberships-for-multi-tenancy.md) | Use Business-scoped Memberships for multi-tenancy | Accepted | 2026-08-12 | 2026-08-25 | #1, #3, #4, #5, #8 |
| [ADR-0004](ADR-0004-use-immutable-forward-only-flyway-migrations.md) | Use immutable forward-only Flyway migrations | Accepted | 2026-08-12 | 2026-08-25 | #1, #2, #3, #4, #5, #8 |
| [ADR-0005](ADR-0005-use-server-managed-cookie-authentication.md) | Use server-managed cookie authentication | Accepted | 2026-08-12 | 2026-08-25 | #1, #2, #3, #4, #5, #6, #8 |
| [ADR-0006](ADR-0006-protect-security-tokens-with-hash-only-persistence-and-database-backed-lifecycle-guarantees.md) | Protect security tokens with hash-only persistence and database-backed lifecycle guarantees | Accepted | 2026-08-12 | 2026-08-25 | #1, #3, #4, #6, #8 |
| [ADR-0007](ADR-0007-use-optimistic-concurrency-for-business-mutations.md) | Use optimistic concurrency for Business mutations | Accepted | 2026-08-14 | 2026-08-25 | #3, #4, #5, #6, #8 |
| [ADR-0008](ADR-0008-use-bounded-process-local-authentication-rate-limiting-for-the-mvp.md) | Use bounded process-local authentication rate limiting for the MVP | Accepted | 2026-08-14 | 2026-08-25 | #1, #3, #4, #6, #8 |
| [ADR-0009](ADR-0009-test-persistence-and-concurrency-against-real-postgresql.md) | Test persistence and concurrency against real PostgreSQL | Accepted | 2026-08-12 | 2026-08-25 | #1, #2, #3, #4, #5, #6, #8 |
| [ADR-0010](ADR-0010-use-optimistic-concurrency-for-service-mutations.md) | Use optimistic concurrency for Service mutations | Accepted | 2026-09-15 | 2026-09-15 | #10, #11 |
| [ADR-0011](ADR-0011-use-staff-aggregate-versioning-for-service-assignments.md) | Use StaffMember aggregate versioning for Service assignments | Accepted | 2026-09-18 | 2026-09-18 | #10, #12 |
| [ADR-0012](ADR-0012-use-versioned-atomic-replacement-for-staff-working-schedules.md) | Use versioned atomic replacement for StaffMember working schedules | Accepted | 2026-09-23 | 2026-09-23 | #10, #13 |
| [ADR-0013](ADR-0013-define-availability-interval-precedence-grid-and-dst-semantics.md) | Define availability interval, precedence, grid, and DST semantics | Accepted | 2026-09-29 | 2026-09-29 | #16 |
| [ADR-0014](ADR-0014-store-schedule-exceptions-as-versioned-aggregates-with-same-kind-date-exclusion.md) | Store schedule exceptions as versioned aggregates with same-kind date exclusion | Accepted | 2026-09-29 | 2026-09-29 | #16 |
| [ADR-0015](ADR-0015-administer-schedule-exceptions-through-a-versioned-business-owner-api.md) | Administer schedule exceptions through a versioned Business-owner API | Accepted | 2026-09-29 | 2026-09-29 | #16 |
| [ADR-0016](ADR-0016-orchestrate-availability-through-published-contracts-and-a-scheduling-owned-busy-interval-seam.md) | Orchestrate availability through published contracts and a Scheduling-owned busy-interval seam | Accepted | 2026-09-29 | 2026-09-29 | #16 |
| [ADR-0017](ADR-0017-expose-public-business-profile-through-an-allowlisted-read-only-contract.md) | Expose the public Business profile through an allowlisted read-only contract | Accepted | 2026-09-30 | 2026-09-30 | #17 |
| [ADR-0018](ADR-0018-serve-public-business-pages-at-a-top-level-path-with-reserved-roots-and-stable-slugs.md) | Serve public Business pages at a top-level path with reserved roots and stable slugs | Accepted | 2026-09-30 | 2026-09-30 | #17 |
| [ADR-0019](ADR-0019-model-business-scoped-customers-with-canonical-contact-identifiers-and-no-lifecycle.md) | Model Business-scoped Customers with canonical contact identifiers, per-Business uniqueness, and no lifecycle | Accepted | 2026-10-01 | 2026-10-01 | #20 |
| [ADR-0020](ADR-0020-match-customers-conservatively-and-publish-narrow-customer-contracts.md) | Match Customers conservatively and publish narrow Customer contracts | Accepted | 2026-10-01 | 2026-10-01 | #20 |
| [ADR-0021](ADR-0021-administer-customers-through-a-private-owner-only-versioned-api-with-body-search.md) | Administer Customers through a private owner-only versioned API with body-based search | Accepted | 2026-10-01 | 2026-10-01 | #20 |
| [ADR-0022](ADR-0022-model-appointments-with-snapshots-two-statuses-and-a-database-overlap-exclusion.md) | Model Appointments with snapshots, two statuses, and a database overlap exclusion | Accepted | 2026-10-06 | 2026-10-06 | #18 |
| [ADR-0023](ADR-0023-book-appointments-in-one-repeatable-read-transaction-with-ordered-locks-and-bounded-whole-transaction-retry.md) | Book Appointments in one repeatable-read transaction with ordered locks and bounded whole-transaction retry | Accepted | 2026-10-06 | 2026-10-06 | #18 |
| [ADR-0024](ADR-0024-make-booking-attempts-idempotent-with-a-versioned-hmac-request-fingerprint-and-distinguish-uncertain-outcomes.md) | Make booking attempts idempotent with a versioned HMAC request fingerprint and distinguish uncertain outcomes | Accepted | 2026-10-06 | 2026-10-06 | #18 |
| [ADR-0025](ADR-0025-coordinate-schedule-changes-with-booking-through-a-business-level-schedule-revision-guard.md) | Coordinate schedule changes with booking through a Business-level schedule revision guard | Accepted | 2026-10-06 | 2026-10-06 | #18 |
| [ADR-0026](ADR-0026-expose-guest-booking-through-narrow-public-contracts-with-session-independent-endpoints-and-bounded-abuse-protection.md) | Expose guest booking through narrow public contracts with session-independent endpoints and bounded abuse protection | Accepted | 2026-10-06 | 2026-10-06 | #18 |
| [ADR-0027](ADR-0027-book-several-services-as-one-atomic-visit-with-service-lines-a-versioned-set-fingerprint-and-an-explicit-review-consistency-check.md) | Book several Services as one atomic visit with Service lines, a versioned set fingerprint, and an explicit review-consistency check | Accepted | 2026-10-07 | 2026-10-07 | #18 |
