# ADR-0026: Expose guest booking through narrow public contracts with session-independent endpoints and bounded abuse protection

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-10-06
- **Recorded date:** 2026-10-06
- **Related issues:** #18
- **Supersedes:** None (amends ADR-0017 and ADR-0020 in part; see their amendment notes)
- **Superseded by:** None

## Context and problem

Issue #17 published only the read-only profile; its allowlist has no identifier,
StaffMember, timezone, or availability, and every deeper path under
`/api/public/businesses/` is denied. A guest booking needs public Service and
StaffMember selection, a public availability view, and one unauthenticated state
change, without exposing private data, enabling cross-Business manipulation, or
trusting any client value that the platform did not offer. The unauthenticated POST
conflicts with the repository-wide CSRF rule unless that rule is deliberately and
narrowly adjusted, and the first unauthenticated write needs abuse protection.

## Constraints

- Tenant context comes only from the slug; every identifier is resolved inside that
  Business and a foreign or missing identifier is indistinguishable.
- DRAFT, SUSPENDED, unknown, and malformed slugs are externally identical
  (ADR-0017); a non-matching booking never reveals lifecycle or tenant existence.
- No personal data in URLs, logs, safe errors, browser storage, `history.state`, or
  responses to other guests; no raw Customer or internal diagnostics in errors.
- The public page lives only at `/{slug}` (ADR-0018); booking steps are in-memory.
- Public in-memory state needs a capacity limit, expiry, and documented saturation
  behavior (AGENTS.md; ADR-0008).
- No Customer account, verification, payment, or marketing consent.
- The Business timezone, price, duration, end, status, source, and Business are
  server-determined and never client-controlled.

## Options considered

- **Opaque references:** a separate random public reference per Service and
  StaffMember (extra column, migration, second identity) versus reusing the existing
  random UUIDs. **Reuse selected:** they are never an authority and every use is
  Business-scoped.
- **CSRF for the public POST:** require the double-submit token through
  `/api/auth/csrf` (a round trip that protects nothing, because the endpoint ignores
  identity) versus **a narrow exemption for exactly the public booking POST,
  selected**, with a session-independent endpoint.
- **Abuse limiter:** extract a shared limiter (touches authentication) versus a
  **booking-owned bounded limiter, selected**, which follows ADR-0008 without changing
  authentication.
- **Verification before booking:** rejected for the MVP (friction, a new
  infrastructure dependency, and no Customer accounts).
- **Public URL:** sub-routes versus **one `/{slug}` URL with in-memory steps,
  selected** (no personal data in the URL or history).

## Decision

**Endpoints** (all unauthenticated; the exact JSON key sets are asserted by tests,
and `Cache-Control: no-store`, no cookie, and a fixed RFC 7807 `instance` apply):

| Endpoint | Contract |
|---|---|
| `GET /api/public/businesses/{slug}` (amended) | Adds `services[].id` (the Service UUID). Nothing else changes; the existing allowlist, 404 collapse, and key-set test are updated for the one new key. |
| `GET …/{slug}/services/{serviceId}/booking-options` | `{timezone, firstDate, lastDate, staff:[{id, displayName}]}`: the active StaffMembers assigned to that active Service, in the existing name order, display name only (no contact data, schedule, or assignment detail). `firstDate`/`lastDate` are the server's Business-local bookable range. |
| `GET …/{slug}/services/{serviceId}/availability?date=yyyy-MM-dd[&staffMemberId=uuid]` | `{date, timezone, availableDates:[…], slots:[{start, end}]}`. A slot is an ISO instant with its offset (ADR-0013 identity). **No StaffMember ID is attached to a slot**, even for "no preference". |
| `POST …/{slug}/bookings` | Request `{attemptId, serviceId, staffMemberId or null, start, customer:{displayName, phone, email}, note}`. Success `{reference, status, service:{name, durationMinutes, price}, staff:{displayName}, start, end, timezone}`: `201` for a new Appointment, `200` for a replay (ADR-0024). |

The request has no field for Business, price, duration, end, status, source, or
timezone, and unknown JSON properties are a `VALIDATION_ERROR`. The `start` must
equal an instant freshly offered (ADR-0023). The timezone is public **only** in
these contracts and is a narrow amendment of ADR-0017, which excluded it only for the
profile. The assigned StaffMember is returned only in the success response (D12).

Security routing replaces the blanket deny of deeper paths with exact matchers for
exactly these routes; every other verb or deeper path under the prefix stays denied.

**Session independence and CSRF.** These endpoints never authenticate from a session
cookie, never refresh session activity, and their responses never depend on
identity; a test with a valid owner session proves it. Only the `POST …/bookings`
route is exempt from CSRF validation; the exemption is exact and does not change any
other route. The public frontend sends `credentials: 'omit'`. Exact-origin CORS is
unchanged (the permitted `Content-Type` already covers JSON). The exemption is safe
because there is no cookie-authenticated state to forge and an attacker can call the
endpoint directly; the protections are idempotency (ADR-0024), validation, and the
limiter below.

**Errors** use RFC 7807 with a stable `code`, optional `fieldErrors` (`displayName`,
`phone`, `email`, `contact`, `note`), safe Bulgarian text, and never SQL, constraint
names, identifiers, or submitted values. Formal register throughout.

| HTTP | Code | Bulgarian message |
|---|---|---|
| 404 | `BUSINESS_PAGE_UNAVAILABLE` (existing) | «Страницата не е налична.» |
| 400 | `VALIDATION_ERROR` | «Проверете въведените данни.» |
| 409 | `BOOKING_SLOT_UNAVAILABLE` | «Избраният час вече не е свободен. Изберете друг час.» |
| 409 | `BOOKING_SERVICE_UNAVAILABLE` | «Избраната услуга вече не е налична. Изберете друга услуга.» |
| 409 | `BOOKING_STAFF_UNAVAILABLE` | «Избраният служител вече не е наличен. Изберете друг или „Без предпочитание“.» |
| 409 | `BOOKING_NOT_COMPLETED_ONLINE` | «Не можем да завършим резервацията онлайн. Моля, свържете се с бизнеса.» (the ADR-0020 text, identical for every identity conflict) |
| 409 | `BOOKING_ATTEMPT_MISMATCH` | «Тази заявка вече е използвана с други данни. Започнете резервацията отново.» |
| 429 | `RATE_LIMITED` (existing) | «Твърде много опити. Опитайте по-късно.» |
| 503 | `BOOKING_TEMPORARILY_UNAVAILABLE` | «Резервацията не беше направена. Опитайте отново.» |
| 503 | `BOOKING_OUTCOME_UNCERTAIN` | «Не получихме потвърждение за резервацията. Опитайте отново.» |
| 500 | `INTERNAL_ERROR` (existing) | «Възникна неочаквана грешка.» |

A missing, inactive, or foreign Service identifier on the `GET` routes and on the
POST is the same `BOOKING_SERVICE_UNAVAILABLE` (the HTTP status of the `GET` routes
is a Proposed detail). A missing, inactive, or unassigned StaffMember is
`BOOKING_STAFF_UNAVAILABLE`. Invalid input, an unavailable slot, an identity
conflict, a mismatch, and an uncertain outcome are always distinct codes and
messages. A response never reveals who holds a slot or whether an identifier matched.

**Guest-visible journey and wording.** One `/{slug}` page; the in-memory steps and
their headings are «Избор на услуга», «Избор на служител», «Дата и час», «Вашите
данни», and «Преглед и потвърждение»; the no-preference option is «Без
предпочитание». The informal wording «При кого искаш да запазиш час?» is withdrawn
and the repository documents are amended. The page states that a slot is not reserved
until the booking is confirmed. Contact: a name and at least one of phone and email,
validated with the Issue #20 shared policies; a short privacy notice (text
Proposed); no consent or marketing feature; an optional plain-text note of at most
500 code points. The result is shown on screen; email and cancellation links belong
to later issues, so the confirmation tells the guest to contact the Business to change
or cancel. Navigation: each step adds a history entry holding only a step marker
(no personal data); Back moves one step; a refresh restarts the journey.

**Abuse protection (initial configurable values, a booking-owned limiter).**

| Budget | Limit |
|---|---|
| `POST …/bookings`, per remote address and Business | 10 per 15 minutes |
| `POST …/bookings`, per Business and contact digest | 5 per 15 minutes |
| `booking-options` and `availability`, per remote address and Business | 300 per 15 minutes |

The limiter is process-local, a fixed window, with an explicit capacity bound,
expiry cleanup before each decision, and fail-closed behavior for previously unseen
keys at saturation (documented limitation: legitimate new keys can be rejected
during saturation, and an attacker spraying many distinct keys could cause it; a
per-address aggregate budget is a Proposed mitigation to settle in Phase 5). Keys are
irreversible digests; no raw slug, address, contact, or attempt ID is stored in
state or logs. The contact digest is computed from each supplied canonical
identifier. **A replay is an ordinary POST** and consumes the same budgets; a
saturated limiter can reject it. The limiter works for one instance only and relies
on the servlet remote address; behind a proxy the address is shared until a trusted
forwarding policy is separately approved (the production default is no forwarded
headers). Capacity and limits are configurable, but **no configuration is
implemented in Phase 1**.

**Logging and privacy.** Nothing logs a request body, a note, contact data, an
attempt ID, an appointment reference, or a fingerprint. Records redact `toString()`.
Tests use unique sentinel values across responses, errors, captured logs, URLs,
`localStorage`, `sessionStorage`, IndexedDB, script-visible cookies, the document
title, and `history.state`.

## Rationale

Reusing the existing identifiers needs no migration and cannot create authority,
because every resolution is Business-scoped and uniform. Excluding slot-level
StaffMember identifiers avoids implying a stable assignment that is recomputed at
commit. A single narrow CSRF exemption plus session independence is more honest than a
token that protects nothing, and idempotency makes direct callers safe. A
booking-owned limiter leaves authentication unchanged.

## Tradeoffs and disadvantages

- Service and StaffMember UUIDs become permanent public identifiers.
- Per-address limiting is weak until trusted-proxy handling exists; limits can
  produce false positives for shared networks.
- An unverified guest can create fake bookings; limits only slow it.
- Fail-closed saturation can block new keys.
- The exemption adds a routing exception that tests must pin.
- The guest cannot self-correct an identity conflict.

## Risks and mitigations

- **Cross-Business manipulation:** Business-scoped resolution; tests with guessed and
  foreign identifiers.
- **Private data leakage:** purpose-built records, an exact key-set test, sentinel
  tests.
- **Lifecycle enumeration:** the single collapsed 404.
- **Calendar flooding:** limits, plus the release dependency below.

## Consequences

Phase 5 implements the routes, security rules, limiter, and error mapping; Phase 6
the frontend. ADR-0017 and ADR-0020 receive amendment notes. `security.md`,
`architecture.md`, `product-spec.md`, and the UI guide §13 (after human approval) are
reconciled. **Release dependency:** the issue can be implemented and verified
independently, but **production public booking must wait for issue #21**, so that a
Business can see and manage the appointments it receives; this is a release
dependency, not an implementation blocker.

## Evidence

Direct evidence: ADR-0008, ADR-0017, ADR-0018, ADR-0020, ADR-0023, ADR-0024;
`SecurityConfiguration`; `ApiExceptionHandler` (`RATE_LIMITED`); the existing public
frontend (`readPublicRoute`, `credentials: 'omit'`); issues #18 and #21.

## Conditions for revisiting

Revisit for contact verification, Customer accounts, trusted-proxy or edge rate
limiting, multi-instance deployment, per-field public visibility, CDN caching,
measured abuse, or server-rendered public pages.
