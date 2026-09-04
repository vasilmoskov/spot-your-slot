# ADR-0006: Protect security tokens with hash-only persistence and database-backed lifecycle guarantees

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-08-12
- **Recorded date:** 2026-08-25
- **Related issues:** #1, #3, #4, #6, #8
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

Owner invitations and password resets authorize security-sensitive actions for
someone who presents a link. The raw token in that link is a bearer credential:
possession can be sufficient to exercise some or all of the flow. Persisting a
usable copy would therefore turn a database disclosure into direct credential
disclosure.

SpotYourSlot also needs expiry, replacement, invalidation, and single-use
behavior to remain correct when requests race. This requires more than checking
timestamps and flags in application memory.

The product foundation selected high-entropy opaque tokens, hash-only
persistence, and transactional lifecycle controls. This record explains that
decision and the distinct implemented behavior of invitation and reset tokens.

## Evidenced constraints

Repository evidence establishes that:

- invitation and password-reset tokens use at least 256 bits of secure
  randomness;
- raw tokens must not be stored in PostgreSQL or normal logs;
- invitation and reset tokens expire and are single-use;
- replacement invalidates the previous active generation for the same purpose
  and owner scope;
- concurrent issuance and consumption require database-backed guarantees; and
- real email delivery, production retention, and operational monitoring remain
  future work.

ADR-0005 owns the reusable browser session credential and cookie authentication.
ADR-0004 owns immutable migration history, ADR-0007 will own general Business
optimistic concurrency, and ADR-0008 will own authentication rate limiting.

## Options considered

Hash-only lifecycle records are the only option directly documented as
selected in the historical repository. The alternatives below are a
retrospective assessment, not evidence that they were debated on 2026-08-12.

### Store raw tokens in the database — retrospective assessment

Raw storage makes lookup, support inspection, and retransmission simple. It
also makes every readable database copy a source of immediately usable bearer
credentials and gives operators no technical separation between lifecycle
metadata and the secret.

### Store encrypted reversible tokens — retrospective assessment

Encryption permits controlled recovery or resend and can protect tokens from a
database-only disclosure when keys are isolated. It introduces encryption-key
storage, rotation, access-control, and compromise scope; anyone with data and
key access can recover the original credential. These flows do not require
server-side token recovery.

### Store only token hashes with database-backed lifecycle state

Hash-only storage supports deterministic lookup and lifecycle enforcement
without retaining a recoverable credential. It limits recovery and resend to
issuing a replacement, and still depends on strong token entropy, safe delivery,
transactions, constraints, and database availability.

### Use self-contained signed tokens without server-side lifecycle state — retrospective assessment

Signed tokens can carry purpose and expiry and be verified without a database
lookup. Without additional state, immediate replacement, revocation, and strict
single use are difficult: a valid token can normally be replayed until it
expires. Signing proves integrity but does not hide token contents.

### Enforce lifecycle only in application code — retrospective assessment

Application-only checks are straightforward in a single sequential request and
can keep the schema smaller. Separate requests can both observe an apparently
usable token or both issue a new generation unless the database serializes the
operation or rejects the conflicting state.

## Decision

Generate security tokens from 32 random bytes using a cryptographically secure
random generator. Deliver the raw opaque token only through the intended flow,
and persist its SHA-256 digest rather than the raw value. Never log the raw token
or treat the digest as encryption.

Persist purpose-specific ownership and lifecycle state. Validate purpose,
ownership, expiry, invalidation, and consumption in a transaction, and combine
application rules with database uniqueness, locking, and conditional updates
where implemented. A lifecycle record or database constraint is not by itself
authorization to use a token.

## Token lifecycle semantics

### Owner invitation

An invitation is scoped by its `owner_invitation` record to one Business,
normalized intended-owner email, creating User, and the fixed owner-invitation
flow. Its raw token does not carry those claims. The service stores only the
hash with creation time, 48-hour expiry, and nullable consumed and invalidated
timestamps.

Issuing another invitation for the same Business and normalized email
invalidates every prior unconsumed, non-invalidated generation before inserting
the replacement. An expired row is unusable but remains an active generation
for the partial uniqueness rule until it is invalidated or consumed.

Acceptance hashes the presented token and locks that row. It rejects an absent,
expired, consumed, or invalidated record. A successful transaction creates or
links the User as allowed by the flow, grants the `BUSINESS_OWNER` Membership,
and marks the invitation consumed. If any step fails, the transaction does not
commit partial acceptance.

### Password reset

A reset is scoped by its `password_reset` record to one User and the password-
reset flow. The service stores only the hash with creation time, 30-minute
expiry, and nullable consumed and invalidated timestamps.

Issuing another reset for the User invalidates prior unconsumed,
non-invalidated generations before inserting the replacement. Reset hashes are
looked up only in the reset table; invitation hashes are looked up only in the
invitation table. Hash uniqueness is enforced within each table, not globally
across token types.

Completion validates the new password, hashes and locks the presented reset
token row, and rejects an absent, expired, consumed, or invalidated record. A
successful transaction changes the password, consumes the selected reset,
invalidates the User's other active reset generations, and revokes all sessions.

### Session credential as a related pattern

The session credential also uses 32 random bytes and hash-only PostgreSQL
lookup, but it is intentionally reusable until expiry or revocation. It does
not have invitation/reset replacement or single-use semantics. ADR-0005 owns
its lifecycle, cookie transport, CSRF model, and authorization behavior.

Customer cancellation tokens are future work. This ADR does not claim that
their schema or exact lifecycle is already implemented.

## Concurrency and database guarantees

V1 makes token hashes unique within each token table and requires expiry after
creation. V2 adds partial unique indexes allowing at most one unconsumed,
non-invalidated invitation generation per `(business_id, normalized_email)` and
one such reset generation per User. Expiry is evaluated by the application and
is intentionally not part of those index predicates.

Issuance runs in a transaction and takes a transaction-scoped PostgreSQL
advisory lock derived from the invitation scope or reset User before invalidating
and inserting. The partial unique indexes remain the final database guard
against duplicate active generations.

Consumption uses `SELECT ... FOR UPDATE` for the row identified by the digest.
Concurrent consumers serialize on that row; after the first commits, the next
consumer observes the changed lifecycle state and fails. The consume updates
also condition on both `consumed_at` and `invalidated_at` remaining null. The
application still validates the flow and expiry because locks and constraints
do not decide whether the caller is allowed to perform the operation.

These mechanisms provide the implemented concurrency guarantees for issuance
of the same scoped generation and consumption of the same token. They do not
prove every possible interaction between issuance, consumption, account
changes, or future token types.

## Rationale

A database disclosure containing a token digest does not directly reveal the
raw credential. High entropy remains essential: a fast digest would not protect
a guessable token from offline enumeration. Here, 256-bit random input makes
preimage guessing infeasible under the design assumptions.

This differs from password storage. Passwords are user-selected and may have
low effective entropy, so SpotYourSlot uses salted, deliberately expensive
Argon2id password hashing. Random opaque tokens need deterministic indexed
lookup and derive their resistance from cryptographic randomness, making
SHA-256 suitable for this distinct purpose. SHA-256 must not be reused as the
password hashing policy.

Database lifecycle state permits immediate replacement and revocation, while
transactions, row locks, advisory locks, conditional updates, and uniqueness
constraints close races that sequential application checks alone cannot.

## Tradeoffs and disadvantages

- Every validation requires a database lookup and security-sensitive
  consumption requires a transaction and lock.
- Hash-only tokens cannot be recovered or resent; replacement must issue a new
  token and invalidate the old generation.
- Lifecycle rows accumulate. The current implementation has no evidenced
  scheduled deletion or retention policy for consumed, invalidated, or expired
  invitation and reset records.
- PostgreSQL advisory locking and partial indexes improve correctness but add
  database-specific behavior and operational coupling.
- Expired rows remain stored and count as an active generation for uniqueness
  until a later issuance invalidates them.
- Separate token tables provide purpose-specific lookup but do not enforce
  hash uniqueness across all token types.

## Risks and mitigations

Hash-only persistence does not protect a raw token stolen from its recipient,
delivery channel, browser URL/history, frontend runtime, or transport. Use
HTTPS, short validity, single use, safe referrer and logging practices, and
purpose-specific endpoints. Never include raw tokens in normal logs,
diagnostics, analytics, or persistent support records.

Weak randomness would make fast hash enumeration practical. Generate tokens
only through the configured cryptographically secure generator, retain the
full entropy, and do not substitute human-readable or predictable values.

Concurrency defects could permit duplicate generations or repeated use.
Retain the V2 partial indexes, transactional advisory locks, row locks,
conditional updates, and real-PostgreSQL concurrency tests. Do not weaken these
controls to application-only preflight checks.

The development mailbox necessarily holds raw token-bearing URLs temporarily
so local and automated flows can consume them. It is limited to 50 in-memory
entries, restricted to development/test profiles and platform administrators,
and absent in production. Real email delivery, delivery monitoring, token
cleanup, retention, and incident procedures are not implemented.

## Consequences

New security-token flows must define entropy, purpose, owner scope, lifetime,
replacement, revocation, consumption, concurrent behavior, and raw-token
delivery before implementation. Store only a digest when the server does not
need to recover the credential, and verify database invariants against real
PostgreSQL.

Support tooling cannot reveal or retransmit a persisted token. It must issue a
new generation through the approved flow. Operational work must establish
retention and cleanup without weakening evidence needed for security review or
incident handling.

## Direct historical evidence

- The original [security design](../security.md) requires invitation, reset,
  and future cancellation links to use at least 256 bits of randomness, expiry,
  revocation, single use, transactional consumption, hash-only persistence, and
  token redaction. Git commit `9cf10e0` first recorded this on 2026-08-12 for
  issue #1.
- The [foundation task](../tasks/00-product-foundation.md) records hashed,
  expiring, single-use tokens and safe logging as approved requirements.
- The [data model](../data-model.md) separates invitation, password-reset, and
  session records and documents their hash-only lifecycle fields.
- The [identity and tenancy task](../tasks/02-identity-and-tenancy.md) defines
  invitation and reset ownership, expiry, replacement, transactional single
  use, safe local delivery, and PostgreSQL concurrency verification. Issue #3
  implemented these rules in commit `ed27ee0` on 2026-08-14.
- [V1](../../backend/src/main/resources/db/migration/V1__identity_and_tenancy.sql)
  creates the purpose-specific token tables, unique hash constraints, ownership,
  and lifecycle fields. [V2](../../backend/src/main/resources/db/migration/V2__enforce_single_active_identity_tokens.sql)
  adds the active-generation partial unique indexes.
- [TokenCodec](../../backend/src/main/java/bg/spotyourslot/identity/domain/TokenCodec.java)
  implements 32-byte secure-random token generation and SHA-256 digests.
  [InvitationService](../../backend/src/main/java/bg/spotyourslot/identity/application/InvitationService.java)
  and [RecoveryService](../../backend/src/main/java/bg/spotyourslot/identity/application/RecoveryService.java)
  implement the separate transactional lifecycle rules.
  [IdentityStore](../../backend/src/main/java/bg/spotyourslot/identity/infrastructure/IdentityStore.java)
  implements issuance advisory locks, invalidation, digest lookup with row
  locking, conditional consumption, and session revocation.
- [Identity lifecycle integration tests](../../backend/src/test/java/bg/spotyourslot/integration/IdentityLifecycleIntegrationTests.java)
  verify current hash-only storage, expiry, invalidation, replacement, replay,
  session effects, and concurrent issuance and consumption against PostgreSQL.
  [Identity schema tests](../../backend/src/test/java/bg/spotyourslot/integration/IdentitySchemaIntegrationTests.java)
  verify the current schema. These tests establish current behavior, not the
  original comparative reasoning or production operational safety.
- [DevelopmentMailbox](../../backend/src/main/java/bg/spotyourslot/identity/application/DevelopmentMailbox.java)
  and [PlatformIdentityController](../../backend/src/main/java/bg/spotyourslot/identity/web/PlatformIdentityController.java)
  show the bounded local/test raw-link exception and production-disabled
  delivery. The platform onboarding work is tracked by parent issue #4.
- The frontend identity flows use invitation and reset links through
  [App](../../frontend/src/App.tsx), while the
  [platform onboarding frontend task](../tasks/03b-platform-admin-onboarding-frontend.md)
  preserves token-storage and disclosure restrictions under issue #6.
- The current [product specification](../product-spec.md),
  [architecture](../architecture.md), [testing strategy](../testing-strategy.md),
  and [implementation plan](../implementation-plan.md) retain the token policy
  and distinguish implemented identity work from future cancellation tokens and
  production delivery.

## Retrospective inference

The comparison with raw storage, reversible encryption, self-contained signed
tokens, and application-only lifecycle checks is later analysis. The repository
does not show that those options were historically evaluated in August 2026.

Hash-only persistence reduces the value of a database-only disclosure, while
database lifecycle state enables prompt replacement and single-use enforcement.
That comparative rationale is consistent with the selected controls but is not
proof of the original deliberation. Current tests demonstrate present behavior,
not historical motivation, protection after recipient-side theft, or production
operational readiness.

## Conditions for revisiting

Revisit this decision if a flow requires offline verification, cross-service
validation without shared state, recoverable token material, different delivery
channels, substantially higher issuance volume, or retention and audit duties
that the current records cannot meet.

Any revision must define entropy, purpose binding, secret exposure, storage,
key management where applicable, expiry, revocation, replay prevention,
concurrent use, replacement, delivery, logging, cleanup, and migration of active
tokens. It must be supported by an explicit threat model rather than assuming
that hashing, encryption, or signatures are sufficient on their own.
