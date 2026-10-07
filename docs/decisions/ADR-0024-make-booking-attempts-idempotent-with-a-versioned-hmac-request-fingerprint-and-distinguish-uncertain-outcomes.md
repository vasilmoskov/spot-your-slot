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
guarantee entropy. Unpredictable generation by every caller is therefore a **requirement
on callers**, not something the server enforces, and neither the format check nor the
payload comparison of the fingerprint justifies any claim about what a caller that
supplies a predictable or reused ID can or cannot affect; the consequences of such a
caller are treated as unanalysed residual risk. The ID is not an authentication
credential. The server stores only
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
- **Replay enumeration:** requiring callers to generate unpredictable attempt IDs (the server validates the format only and cannot prove entropy), a required exact payload, and rate
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

## Implementation notes (Phase 4, 2026-10-06)

- **Attempt hash.** SHA-256 over the 36 ASCII bytes of the canonical lowercase UUID v4 text (golden vector in
  `BookingAttemptIdTests`). The identifier itself is never stored or logged.
- **Normalization (settles the Proposed note rule).** Name, phone, and email follow the shared contact policy the
  Customer module applies (NFKC, collapsed whitespace, compact E.164, lowercase email; name at most 200 code points;
  at least one of phone and email). The note: `CRLF`/`CR` to `LF`, surrounding Unicode white space removed, blank is
  absent, at most 500 code points, no control character other than tab and line feed, no unpaired surrogate. The start
  must be a whole-second instant (the encoding stores epoch seconds, so a sub-second difference would otherwise be
  invisible to the fingerprint).
- **Encoding version 1 (frozen, golden vectors in `FingerprintEncodingTests`).** `"SYSBFP"` | uint16 version |
  eight fields, each `tag(1) | uint32 length | value`, in the order Business (16 bytes), Service (16), staff
  preference (`00` none, or `01` + 16 bytes), start (int64 epoch seconds), name (UTF-8), phone, email, note (each `00`
  absent or `01` + UTF-8). The attempt identifier and clocks are not encoded; the requested preference, never the
  assigned member, is.
- **Keys.** `spotyourslot.booking.fingerprint.active-key-version` and `...keys.<version>` (environment:
  `SPOTYOURSLOT_BOOKING_FINGERPRINT_ACTIVE_KEY_VERSION`, `SPOTYOURSLOT_BOOKING_FINGERPRINT_KEYS_<version>`), standard
  Base64 of at least 32 bytes, versions `1..32767`, active version configured. Validated at startup into a fixed
  reason (`FingerprintConfigurationException`) that never contains a value; values bind as plain text so a binding
  error cannot echo a key. The `prod` profile rejects any key whose bytes start with `NON-SECRET-TEST-KEY`, the
  prefix of the committed development (`application-dev.yaml`) and test (`application-test.yaml`) keys, which are
  non-secret. Production supplies its own keys through the environment; rotation: add the new version, make it
  active, keep every old version while any Appointment references it. A check that every stored version is
  configured at startup is **not** implemented; a missing key surfaces on replay as below.
- **Comparison.** `MessageDigest.isEqual` (constant time) on the recomputed HMAC with the stored versions.
- **Unverifiable replay.** A missing key or unsupported stored encoding returns `OutcomeUncertain`, writes nothing, logs only
  the event code, and is never a mismatch (`RequestFingerprinter.UnverifiableFingerprint`).
- **COMMIT classification (settles the Proposed detail; corrected after review).** The body returning proves
  `COMMIT` was not issued before it; a failure earlier (including a failed begin or rollback) is a known rollback.
  After a `Created` body the classification follows the completion status Spring reports to a synchronization
  registered in the attempt's own transaction, **not** the content of any exception: `COMMITTED` (the database
  committed, a later callback failed) is `OutcomeUncertain`, never retried, even if the exception carries `40001`,
  `40P01`, `25P02`, or `UnexpectedRollbackException`; `ROLLED_BACK` is a proven rollback (retry only for
  `40001`/`40P01`); `UNKNOWN` (the commit call failed) proves a rollback only through a server-reported
  `40001`/`40P01` (retry) or `25P02` (known), otherwise uncertain; no report is uncertain. A normal completion of a
  `Created` attempt is additionally confirmed by one read of the Appointment outside any transaction; if it is absent
  (PostgreSQL's silent rollback of an aborted transaction) the result is the known-rollback outcome without a
  retry, and if the read fails the result is uncertain (details in the ADR-0023 Phase 4 note).
  **Evidence, separated.** *Real PostgreSQL and the real transaction manager* (`GuestBookingCommitFailureIntegrationTests`):
  `afterCommit` failures with wrapped `40001`, `40P01`, `25P02`, an `UnexpectedRollbackException`, and a plain exception
  (all uncertain, one Appointment and one Customer remain, the repeat replays); a pre-commit failure carrying `40001`
  (rolled back and retried) and another (known rollback, not retried); a swallowed failure in the body and in a
  `beforeCommit` callback (no `Created`, nothing persisted, no retry, the repeat creates exactly one Appointment); a
  terminated connection before the commit (uncertain although nothing committed). *Injected statuses and exceptions*
  (`GuestBookingServiceTests`, a fake transaction manager): the `UNKNOWN` phase with `40001`, `40P01`, `25P02`,
  I/O, timeout, `08xxx`, `57xxx`, heuristic, and unknown failures, and the verification outcomes. A real `COMMIT`
  error with `40001`, `40P01`, or `25P02` could not be provoked (repeatable read without deferred constraints produces
  none, and an aborted transaction commits as a silent rollback), so the `UNKNOWN`-phase conflict rules rest on
  injected evidence only.

## Implementation notes (Phase 6, 2026-10-07)

The public frontend applies the decisions above without changing them; the refinements are recorded here.

- **Attempt lifetime.** An attempt is the browser-generated ID (`crypto.randomUUID()`, falling back to a `getRandomValues` UUID version 4, and never to
  `Math.random`) with the exact JSON text of its first send, held only in component memory. Every resend posts that same string. A **new** ID is drawn only when no
  live attempt exists: the first submit, after a **first-send** documented rejection (`400`, `404`, `409` other than a mismatch, `413`, `415`; the attempt is dropped), after the guest changes a choice following
  a first-send proven rollback or `429` (the attempt is dropped), or after an explicit restart that went through the leave warning. It is not drawn for a timeout, a network failure,
  a rate limit, an uncertain response, a rerender, or navigation.
- **Uncertainty is sticky (corrected).** A send that ends uncertain marks the attempt possibly committed. A **later** request's proven rollback, `429`, rejection (including Business unavailable and a mismatch),
  or unknown answer says nothing about the **earlier** send, so none of them clears the mark, drops the attempt, unfreezes the review, or permits a new attempt. Only a valid success, including a `CANCELLED`
  replay, resolves it, or the guest abandons the journey after the warning. An earlier draft of this note wrongly treated a rejected retry as proof that the earlier send did not commit; that is withdrawn.
- **Client-side classification (corrected).** Uncertain: a transport failure, an unreadable or schema-invalid `200`/`201`, a `408`, any `5xx` other than the documented
  `BOOKING_TEMPORARILY_UNAVAILABLE`, `BOOKING_OUTCOME_UNCERTAIN`, and **any 4xx that is not a documented code on its documented status**. A problem code is trusted only with its own status. Known rollback: `BOOKING_TEMPORARILY_UNAVAILABLE`
  on a first send. A `429 RATE_LIMITED` is a refusal before booking work. `BOOKING_ATTEMPT_MISMATCH` is **not** evidence that no Appointment exists (it means one exists under this ID with other data): the attempt is frozen, marked possibly committed with its exact ID and body, and only the warned restart is offered; no later answer except a success can resolve it.
- **Frozen state.** While uncertain the review ignores every edit, the browser Back is undone, leaving or restarting asks first (showing the Business telephone when it is public), and only the same attempt can be sent again;
  a retry that is refused by the limiter, rolled back, or rejected stays frozen. `Retry-After` (whole seconds or an IMF-fixdate, clamped to one hour) only releases the retry control; nothing is ever sent by a timer.
- The wording of the leave warning remains Proposed (task 08a, Proposed detail 9).

## Conditions for revisiting

Revisit for Customer verification or accounts, a snapshot decision, a separate
idempotency store, key-management tooling, a server-side status lookup that does
not need the payload, or measured abuse of replay.
