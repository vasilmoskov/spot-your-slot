# ADR-0024: Make booking attempts idempotent with a versioned HMAC request fingerprint and distinguish uncertain outcomes

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-10-06
- **Recorded date:** 2026-10-06
- **Related issues:** #18 (also answers a #19 product decision)
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

A guest can double-click, retry automatically, or lose the response to a request
that already committed. The system must return one consistent result and never
create a second Appointment or Customer, and it must never report success for a
different request than the one that succeeded. The Appointment keeps no copy of the
guest's contact data (ADR-0022), so "same request" cannot be proved by comparing
stored columns. Some failures prove a rollback; others (a lost response or an
unknown commit) do not, and the guest must not be told the booking failed when it
may exist. Frontend behavior alone cannot be relied on: direct API callers must be
handled correctly.

## Constraints

- No raw personal data in logs, URLs, or browser storage; no Customer snapshot on
  the Appointment (ADR-0022).
- Low-entropy values (phone numbers, names, notes) must not be stored as plain or
  unkeyed hashes.
- Replay after a lost response must not revalidate availability, call the Customer
  module, take any booking lock beyond the initial Business `FOR SHARE` lock
  (ADR-0023), or write.
- Retry occurs only in a completely new transaction (ADR-0020, ADR-0023).
- Security tokens are stored hash-only (ADR-0006). An attempt ID is **not** an
  authentication credential and grants no authority; it is an idempotency identifier
  that must be unpredictable so another caller cannot guess it.
- A new secret and its rotation must be documented before it is configured;
  Phase 1 configures nothing.

## Options considered

1. **A Customer snapshot and column comparison:** rejected (ADR-0022).
2. **Compare only Service and start:** rejected; a different contact, preference,
   or note under the same key would silently return another request's success.
3. **Unkeyed SHA-256 of the request, or a per-row salt:** rejected; a leaked
   database allows dictionary confirmation of phone numbers, notes, and erased
   Customers.
4. **A separate expiring idempotency table:** rejected; it adds machinery and a
   cleanup job that does not exist.
5. **A versioned HMAC fingerprint stored with the Appointment. Selected.**

## Decision

**Attempt ID.** The browser generates the `attemptId` with a cryptographically
secure random generator and sends it as a lowercase UUID version 4 in the request body
(a header would need a CORS change). Direct API callers must likewise supply an
unpredictable identifier. The server validates only the **format** (a canonical
lowercase UUID version 4; any other form is a validation error): format validation
cannot prove that the value was randomly generated, so the server does not and cannot
guarantee entropy, and a caller that supplies a predictable ID can only affect its
own attempts, because replay also requires the exact payload and fingerprint. The ID
is not an authentication credential. The server stores only
`SHA-256(canonical attempt ID)` in `booking_attempt_hash`, unique per Business.

**Canonical request and fingerprint.** The fingerprint identifies a request by
exactly these normalized values, and nothing else (not the attempt ID, not client
clocks):

| Field | Normalization |
|---|---|
| Business | the resolved Business ID |
| Service | the Service UUID |
| StaffMember preference | the **requested** preference: a specific StaffMember UUID or the literal "no preference", never the member the server assigns |
| Start | the offered slot instant as a UTC whole-second instant |
| Customer name, phone, email | the shared canonical forms of ADR-0019 (empty when absent, with explicit presence markers) |
| Note | trimmed, line breaks normalized, blank equals absent (exact rules are Proposed) |

The encoding is a length-prefixed, tag-delimited byte sequence in this field order
with an encoding version tag. Its exact layout is frozen in Phase 4 with golden
vectors; any later change is a **new encoding version**. The fingerprint is
`HMAC-SHA-256(key, canonical bytes)`.

**Two versions are stored per attempt and are distinct:**
`fingerprint_encoding_version` (how the canonical bytes were built) and
`fingerprint_key_version` (which key produced the HMAC). New attempts always use the
current encoding and the **active** key.

**Replay and mismatch rules** (comparison is constant-time):

| Situation | Result |
|---|---|
| Same attempt hash, recomputed fingerprint equals the stored one | Replay: HTTP 200 with the stored result |
| Same attempt hash, fingerprint differs in **any** field | 409 `BOOKING_ATTEMPT_MISMATCH`; never success |
| Same attempt ID in another Business | Independent |
| Stored key version or encoding version unavailable to the server | A safe technical failure that is **not** a mismatch (below) |

Replay recomputes the fingerprint of the incoming request with the encoding and key
versions **stored with the original attempt**, not the active ones. Historical keys
therefore remain configured for as long as any retained Appointment references them.

**What replay returns and does not do.** It returns the original snapshots
(reference, Service name, duration, price, StaffMember display name, start, end,
timezone) and the Appointment's **current** status. A later-cancelled Appointment
replays with HTTP 200 and status `CANCELLED`. A Business that has since been
suspended still replays a matching success. Replay performs no availability check,
no Customer call, no insert, and no write. It holds **only** the initial Business
`FOR SHARE` lock of ADR-0023 step 1 and takes no StaffMember, schedule revision, or
Service lock. Replay consumes the same rate-limit budget as any booking request; a
saturated limiter can therefore reject a replay (ADR-0026). Contact data is never
returned.

**Concurrent identical submissions.** Neither request sees the other's
uncommitted row. Both proceed; one inserts. The other fails on the unique attempt
hash index (or the overlap exclusion, if both chose the same slot), its transaction
rolls back, and its retry in a new transaction begins with the replay lookup and
returns the winner: one Appointment, one result, one `201` and one `200`. Concurrent
requests with the same attempt ID and different payloads end with one success and
one mismatch.

**Rotation and key availability.**

- Exactly one key is active; every configured key has a version identifier.
- A key may be removed only when no retained Appointment references its version;
  until a documented privacy procedure nulls the fingerprint columns, keys are
  retained.
- A missing or unreadable historical key or an unknown encoding version is a
  **safe technical failure**: no data is returned, no write occurs, diagnostics are
  logged without personal data, and the response is classified **uncertain** for the
  client (below), because the Appointment exists but cannot be verified. It must
  never be reported as `BOOKING_ATTEMPT_MISMATCH` or as a failed booking.
- Startup validation requirements (implemented later, not in Phase 1): the active
  key exists, is at least 32 random bytes, key versions are unique, and test and
  development keys are non-secret test values never accepted by the production
  profile. A check that every key version stored in the database is configured is
  Proposed.

**Outcome classification.** A client must not be told a booking failed when it may
exist.

- **Proven rollback** (a known outcome): the failure happened before `COMMIT` was
  issued, so the transaction could only abort; or PostgreSQL reported the rollback
  as the result of `COMMIT`. Examples: validation, rate limiting, slot or Service or
  StaffMember unavailable, identity conflict, mismatch, retries exhausted,
  `CustomerOperationFailure`, deadlock or serialization victims.
- **Uncertain**: an I/O failure, timeout, or unknown error **during or after** the
  `COMMIT` was sent, any unexpected error after a successful commit, a missing
  historical key on replay, and, on the client, any network failure, timeout,
  aborted request, unreadable response, or 5xx that is not a documented known code.
  The server returns `BOOKING_OUTCOME_UNCERTAIN` only for the server-detectable
  cases.
- A commit failure is **not** automatically uncertain. The exact SQLState and driver
  conditions that prove a server-reported rollback at commit are a Proposed detail
  to be verified by Phase 4 tests; until verified, only the I/O, timeout, and
  unknown cases above are classified uncertain.

**Approved Bulgarian messages.**

| Case | Code | Message |
|---|---|---|
| Known rollback (retries exhausted, temporary failure) | 503 `BOOKING_TEMPORARILY_UNAVAILABLE` | «Резервацията не беше направена. Опитайте отново.» |
| Uncertain outcome | 503 `BOOKING_OUTCOME_UNCERTAIN` (and any client-side uncertain case) | «Не получихме потвърждение за резервацията. Опитайте отново.» |
| Mismatch | 409 `BOOKING_ATTEMPT_MISMATCH` | «Тази заявка вече е използвана с други данни. Започнете резервацията отново.» |

The sentence «Не успяхме да запазим часа» is never used.

**Frozen uncertain UI state.** On an uncertain result the review is frozen: the
steps, inputs, and slot controls are unavailable. The UI keeps one immutable
in-memory copy of the exact submitted payload and the attempt ID (never in storage,
URL, or `history.state`) and offers only «Опитайте отново», which resends that
exact payload with the same attempt ID. Leaving or restarting while uncertain goes
through a confirmation that warns the booking may already exist (the wording is
Proposed) and shows the Business telephone when it is public. A new attempt ID is
created only after a **known** failure. A `beforeunload` warning is armed while the
request is in flight or the state is uncertain; it is **best-effort, not a
guarantee**. A page refresh, a closed tab, or a crash discards the in-memory copy, so
a double booking after a refresh in the uncertain state remains a documented
residual risk.

## Rationale

A keyed fingerprint binds every submitted field to one attempt without storing
personal data in a form that a database leak could reverse, and it works for direct
API callers. Storing the encoding and key versions with the attempt lets keys and
encodings change without invalidating retained replay. Treating a replay of a
missing key as uncertain keeps the guest from believing a committed booking failed.
Distinguishing proven rollback from genuine uncertainty keeps the approved
messages truthful.

## Tradeoffs and disadvantages

- A new required secret, a key ring, and a rotation procedure.
- Historical keys must be retained while appointments exist; losing one blocks
  replay of those attempts.
- The fingerprint columns are personal-data-adjacent replay material that a future
  erasure procedure must clear.
- A guest who changes any field after a success cannot reuse the attempt ID.
- A refresh during an uncertain state can still lead to a second booking.

## Risks and mitigations

- **Key loss or misconfiguration:** startup validation, a safe uncertain failure on
  replay, and operational diagnostics without personal data.
- **Encoding drift:** a frozen encoding with golden vectors and a version column.
- **Misclassification of a commit failure:** conservative, evidence-based
  classification; Phase 4 tests.
- **Replay enumeration:** browser-generated secure random attempt IDs (format validated, entropy not provable by the server), a required exact payload, and rate
  limiting.

## Consequences

Phase 2 adds the columns and the partial unique index (ADR-0022). Phase 4 implements
the fingerprint, the replay path, the classification, and the key ring. Phase 5
maps the codes. Configuration property names and the startup validation mechanics
are finalized later. The task record lists the Proposed details.

## Evidence

Direct evidence: ADR-0006, ADR-0019, ADR-0020, ADR-0022, ADR-0023;
`docs/security.md` (hash-only tokens, no personal data in logs). Inference: PostgreSQL
reports an error on `COMMIT` of an aborted transaction as a rollback; the precise
driver behavior is not yet observed in this repository and is a Phase 4 test.

## Conditions for revisiting

Revisit for Customer verification or accounts, a snapshot decision, a separate
idempotency store, key-management tooling, a server-side status lookup that does
not need the payload, or measured abuse of replay.
