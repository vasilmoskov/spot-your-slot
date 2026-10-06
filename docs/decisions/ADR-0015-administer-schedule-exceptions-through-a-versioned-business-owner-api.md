# ADR-0015: Administer schedule exceptions through a versioned Business-owner API

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-09-29
- **Recorded date:** 2026-09-29
- **Related issues:** #16
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

ADR-0014 stores Business closures, StaffMember time off, working-day overrides,
and additional working periods as versioned aggregates and leaves authorization,
lifecycle, lock order, validation bounds, and the HTTP contract to a later phase.
Issue #16 Phase 3 needs a private Business-owner API to list, get, create,
atomically replace, and hard-delete one exception, without availability
orchestration, a public endpoint, or a frontend.

## Constraints

- Owner authorization, DRAFT/ACTIVE/SUSPENDED behavior, and inactive-StaffMember
  behavior follow ADR-0012 and the existing configuration APIs.
- PostgreSQL exclusion constraints remain the only overlap arbiter (ADR-0002,
  ADR-0014); the store distinguishes neither a missing nor a stale aggregate.
- `kind` and the StaffMember are immutable (ADR-0014).
- `scheduling` may not read `workforce` internals (ADR-0001).
- The current CORS policy allows only `GET`, `POST`, `PUT`, and `OPTIONS`.

## Decision

**Endpoints** under `/api/business/schedule-exceptions`: `GET ?from&to` (list),
`GET /{id}`, `POST` (201 with `Location`), `PUT /{id}` (200), and
`DELETE /{id}?expectedVersion=N` (204, no body). The selected Business comes only
from the server-managed session; no `businessId` appears in any request or
response. `DELETE` is added to the credentialed CORS methods; the origin,
headers, credentials, and CSRF policy are unchanged, and `DELETE` requires CSRF.

**Contract.** Create carries `kind`, `staffMemberId` (required exactly for
StaffMember-scoped kinds), `firstDate`, `lastDate`, `allDay`, and `periods`.
Replace carries only `expectedVersion`, `firstDate`, `lastDate`, `allDay`, and
`periods`; the stored `kind` and StaffMember are retained and cannot be
altered, so the contract does not imply they are editable. Dates are strict
`yyyy-MM-dd`, times strict `HH:mm`, both rejected on any other lexical form. A
single exception's response carries `id`, `kind`, `staffMemberId` (explicit
`null` for closures), dates, `allDay`, `periods`, the live Business `timezone`,
`version`, and timestamps. The list wrapper carries `from`, `to`, the timezone
once, and `exceptions` whose items omit it. The list is unpaginated, requires
both bounds, returns every kind and StaffMember of the selected Business, and
keeps the store's deterministic order.

**Lifecycle.** Reads need an active owner Membership. Create, replace, and delete
additionally reject SUSPENDED Businesses and, for StaffMember-scoped kinds, need
a same-Business active StaffMember. An inactive StaffMember's exceptions stay
readable but cannot be created, replaced, or deleted; removing one therefore
requires temporarily reactivating the StaffMember. Foreign exception ids behave
exactly like missing ids.

**Lock order and transaction.** One transaction locks the Business lifecycle row,
the exact owner Membership row, the StaffMember row (for StaffMember-scoped kinds
only) through the new narrow published `workforce.StaffMemberReferenceAccess`,
and finally the aggregate through the conditional store statement. Replace and
delete read the aggregate first inside that transaction, and the stored aggregate
decides whether the StaffMember lock is needed. Not-found is reported only when
that read is empty; a failed conditional mutation after a successful read is a
concurrent update. There is no overlap pre-check before insert.

**Failures.** A deadlock or serialization victim maps to the same 409
`SCHEDULE_EXCEPTION_CONCURRENT_UPDATE` as a stale version; the guidance is to
reload and retry. An exclusion violation maps to 409 `SCHEDULE_EXCEPTION_OVERLAP`.
`InvalidReference` is unreachable after the locks under the no-hard-delete model
and maps to the sanitized 500. No response exposes SQL, constraint names,
submitted identifiers, or tenant existence.

**Validation bounds** are an MVP technical safety boundary, not the booking
horizon of ADR-0013, and are fixed and centralized in
`ScheduleExceptionInputValidator`: dates `2000-01-01` through `2100-12-31` for
create, replace, and list; a 366-date maximum full-day span; at most 24 periods;
and a 93-date maximum list window. Fixed (not today-relative) bounds mean a
stored exception never becomes non-editable as time passes.

## Rationale

Retaining kind and StaffMember from the stored aggregate keeps them immutable by
construction and removes mismatch validation. A single lock order shared with
ADR-0012 reuses proven Business, Membership, StaffMember sequencing. Reporting
stale and concurrent changes identically matches how clients recover, and keeps
the store's deliberate inability to distinguish them out of the public contract.

## Tradeoffs and disadvantages

- Cleaning up an inactive StaffMember's exception needs temporary reactivation.
- The list is unpaginated and its worst case grows with StaffMember count and
  window size (about 93 dates times one closure plus three kinds per
  StaffMember). This assumes the current small-Business MVP.
- Two replacements moving aggregates into each other's dates can deadlock; one
  is aborted and receives the retryable 409.
- `DELETE` widens the credentialed CORS method list.
- The fixed date bounds accept dates decades away.
- `scheduling` depends on `business`, `identity`, and `workforce` published
  contracts, adding module edges; there are no cycles.

## Risks and mitigations

Lock-order regressions are caught by observing wrappers that record the order per
backend connection. Races use latches and PostgreSQL lock-wait observation, never
sleeps. The strict date and time deserializers are private to `scheduling.web`
so they cannot leak into other modules.

## Consequences

Later phases add availability orchestration on the same store and may reuse
`StaffMemberReferenceAccess`. A frontend must send `expectedVersion` on replace
and delete and treat 409 as reload-and-retry.

## Evidence

Issue #16; ADR-0001, ADR-0002, ADR-0009, ADR-0012, ADR-0013, ADR-0014; the
`ScheduleException*` tests including `ScheduleExceptionLockingIntegrationTests`
and `BusinessScheduleExceptionApiIntegrationTests`.

## Conditions for revisiting

Revisit for pagination or filtering when StaffMember volume or response size
becomes material, a bounded but Business-configurable horizon, time-precise
conflicts, per-Business limits, or allowing cleanup of an inactive StaffMember's
exceptions without reactivation.

## Amendment note (issue #18, 2026-10-06)

The decision above is preserved. ADR-0025 adds one step to the mutation lock order for every create, replace, and
delete of every exception kind: after the StaffMember row (for StaffMember-scoped kinds) and before the
conditional aggregate statement, the transaction bumps the Business-level schedule revision row. Versions,
overlap handling, failure mapping, and responses are unchanged. This takes effect when Phase 3 of issue #18 is
implemented; until then the order above is the implemented behavior.
