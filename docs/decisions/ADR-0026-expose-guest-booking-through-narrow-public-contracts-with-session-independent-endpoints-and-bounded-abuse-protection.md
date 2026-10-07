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
is 409, settled in Phase 5). A missing, inactive, or unassigned StaffMember is
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
per-address aggregate budget mitigates it; settled in Phase 5, see the implementation notes). Keys are
irreversible digests; no raw slug, address, contact, or attempt ID is stored in
state or logs. The contact digest is computed from each supplied canonical
identifier. **A replay is an ordinary POST** and consumes the same budgets; a
saturated limiter can reject it. The limiter works for one instance only and relies
on the servlet remote address; behind a proxy the address is shared until a trusted
forwarding policy is separately approved (the production default is no forwarded
headers). Capacity and limits are configurable; the configuration was implemented in
Phase 5 (see the implementation notes).

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

## Implementation notes (Phase 5, 2026-10-07)

Phase 5 implemented the routes, the security rules, the limiter, and the error mapping above. It settles the Proposed
details that this ADR left to it and records every refinement; none changes an accepted decision.

### Final HTTP contract

All four routes are unauthenticated and session-independent; every response is `Cache-Control: no-store` (the default
security headers, also on errors), sets no cookie, and every problem body has the fixed `instance`
`/api/public/businesses`, `type` `about:blank` (omitted), a Bulgarian `title` «Заявката не може да бъде изпълнена.», a
stable `code`, and the approved `detail`. A body never echoes a slug, identifier, attempt ID, submitted value, SQL, or
exception text.

| Route | Success | Key set (exact, in order) |
|---|---|---|
| `GET /api/public/businesses/{slug}` | `200` | `slug, displayName, businessType, description, phone, address, services`; `services[]`: **`id`**, `name, description, durationMinutes, price` (the Phase 5 amendment: the Service UUID) |
| `GET …/{slug}/services/{serviceId}/booking-options` | `200` | `timezone, firstDate, lastDate, staff`; `staff[]`: `id, displayName` |
| `GET …/{slug}/services/{serviceId}/availability?date=yyyy-MM-dd[&staffMemberId=uuid]` | `200` | `date, timezone, availableDates, slots`; `slots[]`: `start, end` |
| `POST …/{slug}/bookings` | `201` new, `200` exact replay (including `CANCELLED`) | `reference, status, service, staff, start, end, timezone`; `service`: `name, durationMinutes, price`; `staff`: `displayName` |

- Instants are ISO-8601 with the Business-local offset (`2026-10-01T10:00:00+03:00`), so the repeated autumn hour stays
  distinct; `start` and `end` of a booking use the stored timezone snapshot. `firstDate` is the Business-local date of the
  server clock and `lastDate` is `firstDate + 29` (the fixed MVP horizon, ADR-0016); `availableDates` lists, in order and
  without duplicates, every date of that window that has at least one slot for the requested Service and StaffMember
  preference. `slots` lists the starts of the requested date only (empty for a date without slots **or outside the
  window**: a syntactically valid date is never an error), ordered by instant. No slot carries a StaffMember, even for
  "no preference". `staff` is the active StaffMembers assigned to the Service in the administration name order
  (`normalized_display_name`, then identifier), an empty array when none qualifies.
- The booking request is `{attemptId, serviceId, staffMemberId|null, start, customer:{displayName, phone, email}, note}`.
  `start` is an ISO-8601 date-time **with an offset** (an instant). It is read **strictly** from the JSON tree by
  `PublicBookingRequestParser`: an unknown property at either level, a non-string value for a text field, a missing or
  malformed `serviceId` or `start`, a malformed `staffMemberId`, a non-object root, and a non-object `customer` are all a
  `400 VALIDATION_ERROR` without a field error. (The global Jackson configuration ignores unknown properties, and
  `@JsonIgnoreProperties(ignoreUnknown = false)` cannot force a failure, which is why the body is a tree and not a record.)
  Every value the parser accepts is passed to `GuestBooking` unchanged; validation and normalization stay in the
  orchestration.

| HTTP | Code | When | `Retry-After` |
|---|---|---|---|
| 200 / 201 | none | `Replayed` / `Created` | none |
| 400 | `VALIDATION_ERROR` | structurally invalid request, invalid attempt ID or start, or `fieldErrors` below | none |
| 404 | `BUSINESS_PAGE_UNAVAILABLE` | `BusinessUnavailable`, and an unavailable Business on the two reads | none |
| 409 | `BOOKING_SERVICE_UNAVAILABLE` | missing, inactive, or foreign Service, **also on the two `GET` routes** (settles Proposed detail 7) | none |
| 409 | `BOOKING_STAFF_UNAVAILABLE` | missing, inactive, foreign, or unassigned StaffMember (`availability` with `staffMemberId`, and the POST) | none |
| 409 | `BOOKING_SLOT_UNAVAILABLE`, `BOOKING_NOT_COMPLETED_ONLINE`, `BOOKING_ATTEMPT_MISMATCH` | as in the table above | none |
| 413 | `REQUEST_TOO_LARGE` | the booking body exceeds the byte bound | none |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | a body whose media type is not JSON | none |
| 429 | `RATE_LIMITED` | a limiter budget or its capacity | whole seconds until the window or capacity frees, at least 1 |
| 503 | `BOOKING_TEMPORARILY_UNAVAILABLE` | `TemporarilyUnavailable` (proven rollback: nothing was committed) | `2` |
| 503 | `BOOKING_OUTCOME_UNCERTAIN` | `OutcomeUncertain` (a commit may have succeeded) | `2` |
| 500 | `INTERNAL_ERROR` | any other failure, including a violated orchestration precondition | none |

`fieldErrors` (only for a `VALIDATION_ERROR` from the orchestration, in this order, each only when invalid):
`displayName` «Въведете име до 200 знака.», `phone` «Въведеният телефонен номер не е валиден.», `email` «Въведеният имейл адрес не е
валиден.», `contact` «Въведете телефон или имейл.», `note` «Бележката може да съдържа най-много 500 знака.». The attempt ID
and the start are produced by the application and are reported without a field. The two 503 results differ in code and
message and are never merged, so a client can repeat the **same attempt** safely; the fixed 2-second `Retry-After` is an
advisory minimum delay and not a guarantee. A missing request body is a `400`; an unsupported verb, a sibling, or a
deeper path under the prefix is still the denied route (401 anonymous, 403 authenticated) of the unchanged blanket rule.

### Session independence and CSRF (settles Proposed detail 10)

One class, `identity.infrastructure.PublicBookingRoutes`, defines the four exact route matchers (a method plus a
full path with single-segment variables, no wildcard) and feeds three consumers, so they cannot disagree:
the `permitAll` authorization rule, the `DatabaseSessionFilter.shouldNotFilter` bypass, and the CSRF exemption (only the
`POST …/bookings` matcher, through `ignoringRequestMatchers`). A bypassed request never reads or validates the
cookie, so a valid session is not refreshed, an expired one is not revoked, an invalid one is ignored, and no
authentication state or cookie is created, cleared, or set; the controllers import nothing from `identity` or
`org.springframework.security` (an architecture test pins it). **Deviation:** the bypass also covers the public
profile route, which is part of the same journey; its response never depended on identity and still does not.

### The limiter (settles Proposed details 8 and 5)

`booking.PublicBookingRateLimiter` is published from the Booking root; `BookingRateLimiter` over `FixedWindowCounters`
implements it, wired by `BookingRateLimitConfiguration` from `spotyourslot.booking.rate-limit.*` (environment names
`BOOKING_RATE_LIMIT_*`, validated at startup).

| Budget | Charged by | Key | Default |
|---|---|---|---|
| booking, address and Business | interceptor | address, slug | 10 |
| booking, address aggregate across Businesses | interceptor | address | **30** |
| booking, contact | controller | slug, each canonical phone and each canonical email | 5 |
| booking-options and availability, address and Business | interceptor | address, slug | 300 |
| booking-options and availability, address aggregate | interceptor | address | **600** |
| every budget | | window (one value) | 15 minutes |
| capacity | | retained counters | **50 000** |

- **Algorithm.** A fixed window per counter, starting at its first admitted charge and ending (inclusively) 15 minutes
  later, when the counter disappears. A rejected decision changes **nothing** (no counter is created, incremented, or
  extended), and each decision charges all of its counters or none under one lock, so concurrent requests can never exceed a
  limit and a decision never half-charges. **Atomicity applies within one admission decision, not across them:** the address
  decision and the contact decision are separate calls made in sequence (corrected after review; an earlier wording could be
  read as one atomic charge). A rejected address decision changes no counter and never reaches the contact decision, so it
  charges no contact counter; a contact rejection that follows an admitted address decision **retains the address charge**,
  because the attempt reached the endpoint; the phone and the email within the contact decision are all-or-none. All counters share one window and are created in time order, so a FIFO
  queue is also the expiry order and expired counters are removed from its head before every decision. The clock is
  injected and clamped never to move backwards, so a clock step can delay a removal but never expire a counter early.
- **Keys.** An HMAC-SHA-256 of length-prefixed parts under a random per-process secret, truncated to 128 bits. No raw
  address, slug, phone, email, or attempt ID is stored or logged, and a low-entropy contact cannot be recovered by a
  dictionary. The IPv6 address is reduced to its /64 prefix, an IPv4-mapped IPv6 address shares its IPv4 budget, and an
  unparsable value shares one bucket (the literal is parsed with `InetAddress.ofLiteral`, so a name is never resolved).
  The slug is stripped and lower-cased; a value that is not a plausible slug (repository grammar, at most 100
  characters) shares one bucket, so malformed requests create no per-value key.
- **Charging.** The interceptor charges the address budgets **before** the body is parsed, so a malformed or
  invalid body is charged like any other attempt; the controller charges the contact budget once the body is
  structurally valid. A replay, a rejected booking (409), and an orchestration validation failure are charged like a
  creation; a structurally invalid body charges the address budgets only; an **oversized body** (below) is charged to the
  address budgets exactly once (the interceptor charges before the body is read) and never to a contact budget, because the
  body is never parsed; a request without a canonical identifier
  charges no contact counter. A request that supplies **both** a phone and an email charges **each canonical
  identifier one unit** (all or none), so changing only one of them cannot evade the budget; different writings of one
  contact ("0888 123 456", "+359888123456") share one counter, and an email is compared case-insensitively through the shared
  contact policy. Reads and bookings have separate aggregates, so a read flood does not block booking.
- **Unknown slugs.** A well-formed unknown slug creates a counter, but every request first charges the address
  aggregate, so one address creates at most 30 booking or 600 read counters per window; malformed slugs share one.
- **Saturation.** A decision that needs a counter when the capacity is full is rejected (fail closed) with `429` and the
  seconds until the oldest counter expires; **no active counter is ever evicted**, so saturation cannot reset a limit,
  and an address that is already counted keeps being served and limited. Legitimate new callers can be refused during
  saturation, which only a flood across many addresses (or many /64 prefixes) can cause.
- **Address source.** Only `HttpServletRequest.getRemoteAddr()`. `Forwarded`, `X-Forwarded-For`, and `X-Real-IP` are never
  read by the application (a test sends all three). The production default `server.forward-headers-strategy=none` keeps
  the container from rewriting the address; the `framework` strategy must never be enabled on a directly reachable
  instance, and `native` only behind a proxy that Tomcat is configured to trust.

### Request body bound (added after review, 2026-10-07)

`BookingBodyLimitFilter` bounds `POST /api/public/businesses/{slug}/bookings` and nothing else (another verb, another path, and
every private endpoint are untouched). The default maximum is **16 KiB** (16 384 bytes; the approved fields need well under
2 KiB, and a 500-code-point Cyrillic note is 1 000 bytes), configurable as `spotyourslot.booking.max-request-body-bytes`
(`BOOKING_MAX_REQUEST_BODY_BYTES`; a non-positive value fails startup). The request is wrapped so that its stream counts the bytes
**actually read** and fails at the first byte over the maximum, so the JSON tree is never built from an oversized body and memory is
bounded by the maximum plus one read buffer; the count is in bytes, not characters. `Content-Length` is not trusted: a chunked body
or an absent length is counted like any other, a false declared length cannot make the application read more than the maximum, and a
declared length above the maximum fails on the first read without reading the body. The result is `413 REQUEST_TOO_LARGE`
(«Заявката е твърде голяма.») with the fixed `instance`, `no-store`, and no cookie. The filter reads nothing before the controller,
so the interceptor has already charged the address budgets once, no contact budget is charged, and no booking, Customer, or database work occurs.
Session independence, the CSRF exemption, and CORS are untouched (an oversized request from an owner session without a token is a
413, and one from a foreign origin is still a 403).

**Evidence.** MockMvc proves the byte boundary (exactly the maximum accepted, one byte more refused), bytes versus characters, a valid
multibyte Bulgarian payload, the 413 contract, charging, and no database work. A real embedded Tomcat on a real socket proves chunked
bodies (within the limit, exactly at it, and over it), a declared length far above the body (413 without waiting for the bytes), and a
declared length below the body (the container delivers only the declared bytes, so the application sees an unreadable prefix, a 400,
and never more). MockMvc derives the declared length from the content it holds, so absent and false lengths are evidenced only by
the container tests.

### Remaining limitations (documented, not mitigated)

- **One process.** Counters are in memory and lost at restart or redeploy; each instance of a multi-instance deployment
  has its own, multiplying every budget. Production behind a reverse proxy needs a trusted client-address policy (so
  guests do not share the proxy's address) and, beyond one instance, a shared or edge limiter; Redis and any
  deployment change were not added (a separate approval, ADR-0008's conditions).
- **Proxy and edge size limits remain an additional production requirement.** The application bounds only the booking
  body (below), once the request has reached it; the proxy or edge must bound request lines, headers, and every body
  before the application, and should reject an oversized body before it is forwarded.
- **A third party can exhaust a known contact's budget.** Five attempts that name a guest's phone or email block that
  guest at that Business for the rest of the window (the 429 carries the wait). The limit is the approved abuse
  protection and the trade-off is accepted until contact verification exists.
- **Shared networks** share one address budget; 10 attempts in 15 minutes per Business is the approved value.
- **Release dependency (unchanged):** production public booking waits for issue #21.

### Other refinements

- The adapter is a new module, `publicbooking`, depending only on the published contracts of `booking`, `business`,
  `scheduling`, and `workforce` (never on `identity`, `customer`, or `catalog`); nothing depends on it. The controller
  contains no orchestration, Customer matching, availability calculation, or retry logic.
- New narrow contracts: `workforce.PublicStaffAccess` (identifier and display name of the bookable StaffMembers, one
  statement), `catalog.PublicServiceAccess.PublicService.id`, and the derived accessors
  `AvailabilitySnapshot.firstDate()` and `lastDate()` (so the horizon is not duplicated).
- The public reads run in one read-only repeatable-read transaction that resolves the ACTIVE Business, calls
  `AvailabilityQuery`, and reads the staff list from the same snapshot; the isolation is verified before any read.
- Any exception from a public route, including Spring's, is mapped by an advice for the public controller with the fixed
  `instance`. (An unmatched `Content-Type` is now a 415 on these routes; the shared handler still maps the same
  failure on other routes to a 500, which is an existing behavior this phase does not change.)
- The privacy notice text remains Proposed (Phase 6).

## Implementation notes (Phase 6, 2026-10-07)

Phase 6 implemented the public journey on the Phase 5 contracts without changing any contract or decision; it is verified by automated tests only and is **not visually approved** (Phase 7).

- The journey lives inside the existing `/{slug}` page and is keyed by Business, so a Business switch discards its state and pending answers. Steps, choices, details, the note, and the attempt are in component memory only. Each step
  adds a history entry whose state is `{spyBooking: {journey, step}}` (a random per-journey label and the step number; no URL change, no personal data, no attempt ID). A refresh starts a new journey.
- All public requests use `credentials: 'omit'`. `booking-options` is read once per Service and `availability` once per Service, preference, and date, with the previous request aborted and any answer for another key ignored.
- Date-only values are never converted through the browser timezone; instants are formatted in the returned Business timezone, and a repeated wall-clock time carries its offset.
- The Customer details use the shared contact policies and the approved public field messages; the note is limited to 500 code points.
- The privacy sentence (corrected to «Данните ви се предоставят на бизнеса за записване и управление на резервацията.») and the leave warnings remain **Proposed**, wording awaiting human visual review (task 08a, Proposed detail 9). The confirmation promises no email, SMS, lookup, cancellation, or payment.
- The attempt and uncertain-state rules are in the ADR-0024 Phase 6 notes.

## Conditions for revisiting

Revisit for contact verification, Customer accounts, trusted-proxy or edge rate
limiting, multi-instance deployment, per-field public visibility, CDN caching,
measured abuse, or server-rendered public pages.

## Amendment note (ADR-0027, 2026-10-07)

The decision above is preserved as the single-Service record. ADR-0027 amends the public contracts: two read routes take a repeated `serviceId` query parameter, the booking request gains a second shape with `services[]` carrying the reviewed duration and price of each Service (the legacy shape stays), the response gains `services`, `totalDurationMinutes`, and `totalPrice` for that shape, and the codes `BOOKING_REVIEW_CHANGED` and `BOOKING_SELECTION_TOO_LONG` are added; every other route, code, message, budget, and the body bound are unchanged. Effective when the HTTP phase of the multi-Service extension is implemented.
