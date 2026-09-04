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
| ADR-0008 — `ADR-0008-use-bounded-process-local-authentication-rate-limiting-for-the-mvp.md` | Use bounded process-local authentication rate limiting for the MVP | Pending | Unknown | — | #8 |
| ADR-0009 — `ADR-0009-test-persistence-and-concurrency-against-real-postgresql.md` | Test persistence and concurrency against real PostgreSQL | Pending | Unknown | — | #8 |
