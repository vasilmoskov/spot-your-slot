# ADR-0020: Match Customers conservatively and publish narrow Customer contracts

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-10-01
- **Recorded date:** 2026-10-01
- **Related issues:** #20 (future consumers: #18, #21)
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

A future guest booking (#18) and a future manual Appointment (#21) must link an
Appointment to the correct Customer of one Business without an account and without
silently merging different people. Matching is unauthenticated and therefore must
not be able to change stored contact data or reveal whether another Customer owns an
identifier. The product specification said "match by phone, then email; ambiguous
cases remain separate", which cannot be satisfied once phone and email are unique
per Business (ADR-0019). No Booking module exists, so the contract must be defined
without constraining its transaction design.

## Constraints

- A normalized non-empty phone or email is unique within one Business (ADR-0019).
- A phone and an email matching two different Customers must be rejected, never
  merged (issue #20).
- A shared, recycled, or mistyped identifier may belong to another person, so an
  incorrect Customer-to-Appointment relationship is worse than an incomplete
  automatic booking.
- `customer` must not depend on the future `booking` module; `booking` depends on
  `customer` through published interfaces only (ADR-0001, ADR-0016).
- Matching never changes stored contact data and never uses the name.
- No Appointment table, column, or API is created by issue #20.

## Options considered

### When exactly one supplied identifier matches and the other does not

1. Accept the matched Customer and ignore the unmatched value. It completes the
   booking, but an unauthenticated visitor can attach a booking to another person's
   record by typing that person's phone.
2. Add or replace the second identifier on the matched Customer. Rejected: it lets
   an unauthenticated submission plant a contact value on someone else's record.
3. **Reject as an identity conflict and require explicit owner correction.
   Selected.** It never links an Appointment to a possibly wrong person and never
   writes. The cost is that a returning Customer with a changed email, a family
   sharing a phone, or a Customer stored with only one identifier cannot complete
   automatically and must contact the Business.

### Customer creation race handling

A caught unique violation aborts the PostgreSQL transaction and would require a
savepoint or a separate transaction. A separate (`REQUIRES_NEW`) transaction would
persist Customers for bookings that later roll back. **A single
`INSERT … ON CONFLICT DO NOTHING` followed by a re-read inside the caller's
transaction is selected.**

### Transaction ownership and isolation

Fail fast on any isolation other than `READ_COMMITTED`, joining or opening its own
transaction, or requiring a caller-owned transaction and leaving isolation to the
caller. **A required caller-owned transaction is selected**, documented and tested
under `READ_COMMITTED` (the current expected booking baseline), without rejecting
stronger isolation. Issue #18 owns the complete Appointment transaction and retry
policy.

### Customer reference contract

A reference lookup with a shared lock, a plain lookup, or nothing. No consumer has
demonstrated a need for a lock, and there is no Customer deletion or lifecycle.
**A plain same-Business lookup is selected.**

## Decision

**Published contracts** (owned by `customer`, consumed later by `booking`):

```java
interface CustomerIdentification {
    CustomerMatchOutcome findOrCreate(UUID businessId, CustomerIdentity identity);
}
record CustomerIdentity(String displayName, String phone, String email) { }

sealed interface CustomerMatchOutcome permits ExistingCustomer, CreatedCustomer,
        InvalidIdentity, IdentityConflict { }
record ExistingCustomer(UUID customerId) { }
record CreatedCustomer(UUID customerId) { }
record InvalidIdentity(Set<IdentityField> fields) { }
record IdentityConflict() { }

/** Unchecked, sanitized, retryable; thrown by findOrCreate, never returned. */
final class CustomerConcurrentConflict extends RuntimeException { }

interface CustomerReferenceAccess {
    Optional<CustomerReference> find(UUID businessId, UUID customerId);
}
record CustomerReference(UUID id) { }
```

The result carries no match basis, no flag about unrecorded contact data, and no
name, phone, or email. Customer-internal diagnostics may exist for tests and are not
part of the published contract. The identity record carries raw input; the module
canonicalizes it with the shared contact policy (ADR-0019). The name is required and
validated for every call but is used only when creating.

**Matching rule.** Canonicalize and validate. Look up the Customers of that Business
holding the supplied phone or the supplied email (at most two rows, because each
identifier is unique). Only the supplied identifiers are considered.

| # | Phone | Email | Holders | Outcome |
|---|---|---|---|---|
| 1 | none | none | – | `InvalidIdentity` (contact) |
| 2 | malformed, extension, or letters | any | – | `InvalidIdentity` (field) |
| 3 | only phone | – | nobody | create, `CreatedCustomer` |
| 4 | only phone | – | Customer A | `ExistingCustomer(A)` |
| 5 | – | only email | nobody | create, `CreatedCustomer` |
| 6 | – | only email | Customer A | `ExistingCustomer(A)` |
| 7 | both | both | phone and email both A | `ExistingCustomer(A)` |
| 8 | both | both | phone A, email B (A ≠ B) | `IdentityConflict`, no write |
| 9 | both | both | phone A, email nobody (A has no email or a different one) | `IdentityConflict`, no write |
| 10 | both | both | email A, phone nobody (A has no phone or a different one) | `IdentityConflict`, no write |
| 11 | both | both | nobody | create, `CreatedCustomer` |
| 12 | concurrent identical new submissions | | | one `CreatedCustomer`; the other waits on the unique index, re-reads, and returns `ExistingCustomer` (row 7) |
| 13 | concurrent partially overlapping submissions | | | after the race the re-read is evaluated by rows 4 to 10 |
| 14 | race reveals phone A and email B | | | `IdentityConflict` |
| 15 | serialization failure (`40001`), deadlock (`40P01`), or a re-read still inconsistent after two attempts | | | **not an outcome:** throws `CustomerConcurrentConflict`, and the caller's transaction is rolled back |

Rows 9 and 10 apply even when the matched Customer currently has no value for the
second identifier. The operation never attaches an Appointment to a partially
matched Customer, never adds or replaces an identifier, never moves an identifier,
and never merges records. A submission with only one identifier may still find or
create deterministically (rows 3 to 6). The only write is inserting a new Customer
when no supplied identifier is held.

**Guest-facing response.** The future guest response to `IdentityConflict` is the
single generic message `Не можем да завършим резервацията онлайн. Моля, свържете се
с бизнеса.` It does not reveal which identifier matched or that a Customer exists.
An authenticated owner corrects the Customer explicitly through Customer
administration (ADR-0021) and the booking is retried.

**Creation race.** A new Customer is inserted with `INSERT … ON CONFLICT DO NOTHING`
and, when no row is returned, the lookup is repeated at most once more. Normal
paths therefore do not raise unique violations, which also keeps offending values
out of PostgreSQL's error detail.

**Transactions.** `findOrCreate` and `find` require an existing caller-owned
transaction (`MANDATORY`) and open none. Behavior is documented and tested under
PostgreSQL `READ_COMMITTED`. Stronger isolation is not rejected.

**Concurrent failures are exceptions, not outcomes.** `ExistingCustomer`,
`CreatedCustomer`, `InvalidIdentity`, and `IdentityConflict` are the four normal
matching results. A PostgreSQL serialization failure (`40001`), a deadlock (`40P01`),
or an unrecoverable race (a re-read that is still inconsistent after the bounded
retry) is not a return value. It raises the sanitized, typed, unchecked
`CustomerConcurrentConflict` (no message or cause containing personal data). The
exception propagates out of the Customer capability and, because the capability
participates in the caller's transaction, marks that transaction for rollback. A failed
statement has in any case already aborted the PostgreSQL transaction, so the caller must
never continue, for example by creating an Appointment, inside it. Retry belongs at the
outer transaction boundary, after rollback, as a completely new transaction; issue #18
finalizes the Appointment transaction and retry policy. The Customer capability never
uses `REQUIRES_NEW`, and it promises no savepoint recovery. A savepoint guarantee would
be added only if Phase 3 deliberately implements it and proves it with PostgreSQL
integration tests, and until then none is promised. An ordinary uniqueness race that
`INSERT … ON CONFLICT DO NOTHING` resolves by re-reading an existing Customer does not
abort the transaction and is a normal outcome (rows 12 to 14). If the caller rolls back,
a Customer created in it is rolled back with it, so a failed booking leaves no orphan
Customer and no partial write.

**Business and lifecycle.** The caller supplies the Business. Whether the Business
accepts bookings (ACTIVE) is the caller's responsibility; the Customer module checks
only that every read and write is scoped to the given Business.

**Same-Business and persistence guarantee.** The future Appointment foreign key
`(business_id, customer_id) REFERENCES customer(business_id, id)` is the
authoritative persistence guarantee. `CustomerReferenceAccess.find` provides the safe
application-level check. A locking operation is not published; issue #18 may request
one only if its real transaction design proves it necessary.

**Appointment contact snapshots.** Whether an Appointment keeps its own copy of the
submitted name, phone, or email is **deferred to issue #18**. The decision affects
duplicated personal data, notification destination, historical presentation,
correction semantics, and retention and anonymization. Issue #20 guarantees only
that a Customer contact update never changes the Customer ID or reassigns existing
Appointment relationships, and it invents no Appointment column.

**Module dependencies.** `customer` depends on `identity`, `business`, and
`shared.contact`. Nothing depends on `customer` yet. `booking` will depend on
`customer` and `scheduling`; `customer` never depends on `booking`. `publicprofile`,
`workforce`, `catalog`, `scheduling`, `business`, `identity`, and `platform` must not
depend on `customer`. A leaf module that consumes only modules which do not depend on
it cannot create a cycle, and the Modulith verification test and a dedicated boundary
test enforce it.

**History.** Customer appointment history is not part of this contract. Issue #21 will
add a Booking-owned query that validates the Customer through
`CustomerReferenceAccess`; the interface composes the two.

## Rationale

Rejecting every partial match is deterministic, explainable, write-free, and does not
reveal whether another Customer owns an identifier through a different response. It
puts an owner decision, not an unauthenticated visitor's input, between two people
and one record. Keeping the published result minimal leaves Booking free to define its
own transaction and snapshot design.

## Tradeoffs and disadvantages

- Legitimate returning Customers who changed an email or phone, and families sharing a
  phone, cannot always complete online and must contact the Business.
- A Customer stored with only one identifier cannot be matched by a submission that
  supplies both until the owner adds the other.
- The generic guest message gives the visitor no way to self-correct.
- Returning only IDs means Booking cannot explain a match in staff flows.
- A concurrent database failure surfaces as `CustomerConcurrentConflict`, so the
  consumer must handle an exception and retry the whole outer transaction rather than
  branch on a result.
- The residual oracle: a visitor who already knows two distinct Customers' identifiers
  learns that they are separate. Rate limiting belongs to #18.

## Risks and mitigations

- **Wrong Customer–Appointment relationship:** rows 8 to 10 reject; no Appointment is
  attached to a partial match.
- **Identifier injection:** matching never writes to an existing Customer.
- **Duplicate Customers under concurrency:** unique indexes with
  `ON CONFLICT DO NOTHING`; real-PostgreSQL race tests without sleeps.
- **Orphan Customers:** the caller's transaction owns the insert.
- **Premature constraint on Booking:** no isolation level is enforced; #18 owns the
  transaction and retry design.
- **Continuing in an aborted transaction:** concurrent failures are exceptions that mark
  the caller's transaction for rollback, never return values.

## Consequences

Phase 3 implements these contracts and tests them with a test-only consumer, including
that serialization and deadlock errors produce the sanitized typed exception, the caller
transaction rolls back, no Customer or partial consumer write remains, a completely new
outer transaction can retry, and ordinary identical-create races still resolve to one
created Customer and one existing Customer with no duplicate. No
Booking class is created. Issue #18 adds the Appointment relationship, its composite
foreign key, the snapshot decision, retry policy, rate limiting, and the generic guest
response. Issue #21 adds history. `architecture.md`, `security.md`, `data-model.md`,
and the task record describe the contract.

## Implementation clarifications (Phase 3)

- **Bounded resolution.** Row 15's "two attempts" means the initial resolution followed by one
  bounded re-read and resolution, not two insert attempts. `findOrCreate` runs one holder lookup,
  at most one `INSERT ... ON CONFLICT DO NOTHING`, and, only when that returns no row, one final
  lookup: at most three Customer statements and no loop. A final lookup that finds nobody (for
  example an ID collision) throws `CustomerConcurrentConflict`; it never becomes a match.
- **A second sanitized exception.** Non-concurrency persistence failures (an unavailable database,
  an unknown SQLState, an unknown Business, rejected data, an unresolved duplicate, a corrupt stored
  row) must neither leak `customer.infrastructure` types nor be confused with the retryable conflict.
  The root package therefore also publishes the sanitized unchecked `CustomerOperationFailure`: a
  fixed message, no reason, no cause or suppressed exception, its own stack trace, and a rollback-only
  caller transaction. It is never returned as an outcome and does not change the four outcomes. Only
  SQLStates `40001` and `40P01` and the bounded inconsistent re-read produce
  `CustomerConcurrentConflict`, so a later phase can map the two differently without inspecting an
  infrastructure exception. Both exceptions invalidate the current transaction. Only
  `CustomerConcurrentConflict` is declared retryable, in a completely new transaction;
  `CustomerOperationFailure` is not declared retryable by this capability, and the caller abandons
  the transaction and applies its own higher-level failure policy.
- **Published shape.** The successful outcomes and `CustomerReference` reject a null ID. `IdentityField` mirrors the internal field enum; `InvalidIdentity.fields()` is
  an unmodifiable, nonempty set; the outcome and reference records are nested in their interfaces;
  `CustomerIdentity.toString()` is redacted.

## Evidence

Direct evidence: issue #20 identity and uniqueness rules; ADR-0016 (published
contracts and a leaf consumer relationship); `ServiceReferenceAccess` and
`StaffMemberReferenceAccess`; `docs/architecture.md` (booking creates or matches the
Customer in one transaction); PostgreSQL documentation for `INSERT … ON CONFLICT` and
isolation levels.

Inference: the repeatable-read behavior of `ON CONFLICT DO NOTHING` (a serialization
failure when the winning row is not visible to the snapshot) is documented PostgreSQL
behavior to be proven by the Phase 3 tests, not yet observed in this repository.

## Conditions for revisiting

Revisit when #18 finalizes the Appointment transaction, if a locking reference is
demonstrated to be required, if owners cannot resolve conflicts acceptably, for
verified Customer identity or accounts, for a merge capability, or for staff flows
that need to explain a match.

## Amendment note (issue #18, 2026-10-06)

The decision above is preserved. Issue #18 resolved the items it deferred: **no Appointment contact snapshot**
(ADR-0022; the fingerprint of ADR-0024 covers idempotency instead); the Appointment transaction and retry policy
(ADR-0023: three attempts in total, each a completely new transaction, with `CustomerConcurrentConflict`
retryable and `CustomerOperationFailure` not retried); rate limiting and the generic guest response
(ADR-0026, the guest message is unchanged); and that the Appointment's composite foreign key
`(business_id, customer_id)` is the persistence guarantee. Customer matching under repeatable read, which the
booking transaction uses, is accepted by this ADR as stronger isolation.
