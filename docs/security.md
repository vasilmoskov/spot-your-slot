# SpotYourSlot security and privacy design

## Objectives and authentication

SpotYourSlot protects credentials and guest tokens, prevents cross-Business
access, rejects forged state changes, limits abuse, and minimizes personal data.
Backend/database enforcement is authoritative; UI visibility is not security.

Administrative users use opaque server-managed sessions. Cookies are random,
`Secure`, `HttpOnly`, appropriately `SameSite`, and narrowly scoped to the
example `spotyourslot.bg` origin in production. HTTPS is mandatory. Rotate
sessions at login/privilege change; expire and invalidate them at logout/reset.
Sessions persist hash-only opaque identifiers. Maximum lifetime is 12 hours and
idle timeout is two hours. Logout revokes the current session; password change
revokes other sessions and advances the current credential version; password
reset revokes every session.

Use Spring Security Argon2id with its v5.8 parameters (16-byte salt, 32-byte
hash, 16 MiB memory, two iterations, parallelism one) and stable Bouncy Castle.
Stored hashes carry an `argon2id` identifier for future upgrades. Passwords
contain 8–128 Unicode code points without composition rules or truncation.
Rate-limit attempts and use enumeration-safe responses. Never log passwords.

Invitations, password resets, and Customer cancellation links use at least 256
bits of secure randomness, expiry, revocation, single use, transactional
consumption, and hash-only persistence. Redact tokens from logs, analytics,
referrers, and stored URLs.

## Browser controls

Cookie-authenticated unsafe methods and login require CSRF protection. CORS
allows only exact configured origins/methods/headers with credentials—never a
wildcard origin. Use restrictive CSP, frame protection, `X-Content-Type-Options`,
strict referrer policy, production HSTS, and text rendering for user content.

The session cookie is `SPOTYOURSESSION`, `HttpOnly`, `SameSite=Lax`, path `/`,
persistent for 12 hours, and `Secure` in production. Credentialed CORS uses one
exact environment-configured origin. Forwarding headers are ignored unless a
trusted deployment is explicitly configured.

The exact origin permits credentialed `GET`, `POST`, `PUT`, `DELETE`, and
`OPTIONS` requests with only `Content-Type` and `X-XSRF-TOKEN` request headers.
POST, PUT, and DELETE remain CSRF-protected. Filter-level missing-authentication and
access-denied/CSRF failures return `application/problem+json` without exposing
roles, sessions, tokens, or framework details.

## Authorization and tenant isolation

Every protected use case requires authentication, active Membership in the
server-resolved Business, and the required role. PLATFORM_ADMIN routes are
separate. Public Business context comes from `{businessSlug}`; authenticated
context comes from Membership. Client Business IDs are untrusted references.

Every `/api/platform/businesses` operation requires the independent
`PLATFORM_ADMIN` authority; `BUSINESS_OWNER`, `MANAGER`, and `STAFF` Memberships
never imply it. Initial `DRAFT → ACTIVE` activation additionally requires an
active `BUSINESS_OWNER` Membership for that same Business. The qualifying row is
held with PostgreSQL `FOR SHARE` inside the activation transaction, preventing
concurrent deactivation from invalidating readiness before activation commits.

Platform Business failures use stable safe codes and Bulgarian details:
`VALIDATION_ERROR` (400, “Проверете въведените данни.”), `AUTH_REQUIRED` (401,
“Необходим е вход.”), `ACCESS_DENIED` (403, “Нямате достъп до тази операция.”),
and `BUSINESS_NOT_FOUND` (404, “Бизнесът не е намерен.”). Conflict responses are
`BUSINESS_SLUG_CONFLICT` (“Този адрес на бизнеса вече се използва.”),
`BUSINESS_SLUG_IMMUTABLE` (“Публичният адрес на активиран бизнес не може да бъде
променян.”), `BUSINESS_SLUG_RESERVED` (“Променете публичния адрес на бизнеса преди
активиране.”), `BUSINESS_INVALID_LIFECYCLE` (“Промяната на статуса не е разрешена.”),
`BUSINESS_MISSING_ACTIVE_OWNER` (“За активиране е необходим активен
собственик.”), and `BUSINESS_CONCURRENT_UPDATE` (“Бизнесът е променен. Обновете
данните и опитайте отново.”), all with status 409. Arbitrary exception messages,
SQL details, constraint names, stack traces, and Membership data are never
response content.

### Business Services authorization

The authenticated Business Services API derives the user and selected Business
only from the server-managed security context. No Service path, query, request,
or response field supplies authoritative `businessId`. Access requires an active
`BUSINESS_OWNER` Membership for that exact user and selected Business.
`PLATFORM_ADMIN` alone, `MANAGER`, `STAFF`, inactive Memberships, missing
Memberships, and Memberships in another Business do not grant access. A user who
is both `PLATFORM_ADMIN` and an active owner is authorized through the owner
Membership. Future `MANAGER` Service access requires a separate approved design.

A genuinely absent selection returns `ACTIVE_BUSINESS_REQUIRED`. The session
filter also clears a selected Business that is no longer supported by a current
active Membership, producing the same safe outcome before the controller runs.
A retained selected context with a non-owner role reaches application
authorization and returns `ACCESS_DENIED`; a selected Business that no longer
exists is also denied without revealing additional tenant information. Missing
and guessed cross-Business Service IDs both return `SERVICE_NOT_FOUND`.

DRAFT and ACTIVE Businesses permit Service reads and mutations. SUSPENDED
Businesses permit reads but reject create, update, deactivate, and reactivate.
Reads use non-locking Business and Membership checks. Each mutation runs in one
transaction and acquires shared locks in this order: Business lifecycle row,
the exact user's Membership row, then the tenant-scoped optimistic Service
mutation. The final mutation retains its Business, Service, expected-version,
and applicable lifecycle predicates. No Service-row pessimistic lock replaces
optimistic concurrency. Every POST and PUT Service request remains CSRF-protected.

Service failures use this stable public contract:

| Status | Code | Bulgarian public wording |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | `Проверете въведените данни.` |
| 401 | `AUTH_REQUIRED` | `Необходим е вход.` |
| 403 | `ACTIVE_BUSINESS_REQUIRED` | `Изберете бизнес, за да продължите.` |
| 403 | `ACCESS_DENIED` | `Нямате достъп до тази операция.` |
| 404 | `SERVICE_NOT_FOUND` | `Услугата не е намерена.` |
| 409 | `SERVICE_NAME_CONFLICT` | `Вече съществува услуга с това име.` |
| 409 | `SERVICE_INVALID_LIFECYCLE` | `Промяната на състоянието на услугата не е разрешена.` |
| 409 | `SERVICE_CONCURRENT_UPDATE` | `Услугата е променена. Обновете данните и опитайте отново.` |
| 409 | `BUSINESS_SUSPENDED` | `Спрян бизнес може само да преглежда данните си.` |
| 500 | `INTERNAL_ERROR` | `Възникна неочаквана грешка.` |

These responses never contain SQL diagnostics, constraint names, stack traces,
nested causes, credentials, session identifiers, Membership details, or private
cross-Business information.

### Business StaffMember authorization

Every `/api/business/staff-members` operation receives the user and selected
Business from the server-managed authenticated context. No path, query, request,
or response field provides a Business, user, Membership, role, or other
authorization identity. Access requires an active `BUSINESS_OWNER` Membership
for that exact user and Business. `PLATFORM_ADMIN` alone, `MANAGER`, `STAFF`,
inactive or missing Memberships, and foreign Memberships are denied. Missing and
cross-Business StaffMember or Service identifiers have the same safe not-found
behavior.

DRAFT and ACTIVE Businesses allow StaffMember and assignment reads and
mutations. SUSPENDED Businesses remain readable and reject every mutation.
Active and inactive StaffMembers remain administratively configurable: profile
editing and assignment replacement do not require an active StaffMember.
Deactivation preserves existing Service assignments. Only a Service newly added
to the desired assignment set must be active; an already assigned inactive
Service may be retained or removed.

Assignment replacement acquires shared locks on the Business lifecycle row and
the exact owner's Membership row, then executes the conditional StaffMember
`UPDATE` that performs the expected-version guard and takes PostgreSQL's
row-level write lock. Catalog next locks only added Service rows with
`FOR SHARE` in deterministic UUID order before the assignment relationships are
reconciled in the same transaction. Complete desired-set reconciliation and the
single StaffMember version increment are atomic. Assignment reads use
repeatable-read isolation so the aggregate version, timestamps, and ordered
Service summaries come from one consistent snapshot. Every POST and PUT remains
CSRF-protected.

StaffMember failures use this stable public contract:

| Status | Code | Bulgarian public wording |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | `Проверете въведените данни.` |
| 401 | `AUTH_REQUIRED` | `Необходим е вход.` |
| 403 | `ACTIVE_BUSINESS_REQUIRED` | `Изберете бизнес, за да продължите.` |
| 403 | `ACCESS_DENIED` | `Нямате достъп до тази операция.` |
| 404 | `STAFF_MEMBER_NOT_FOUND` | `Членът на екипа не е намерен.` |
| 404 | `SERVICE_NOT_FOUND` | `Услугата не е намерена.` |
| 409 | `STAFF_MEMBER_INVALID_LIFECYCLE` | `Промяната на състоянието на члена на екипа не е разрешена.` |
| 409 | `STAFF_MEMBER_CONCURRENT_UPDATE` | `Данните за члена на екипа са променени. Обновете данните и опитайте отново.` |
| 409 | `SERVICE_INACTIVE` | `Неактивна услуга не може да бъде добавена към член на екипа.` |
| 409 | `BUSINESS_SUSPENDED` | `Спрян бизнес може само да преглежда данните си.` |
| 500 | `INTERNAL_ERROR` | `Възникна неочаквана грешка.` |

There is no inactive-StaffMember public error. Validation, persistence, Catalog
reference, and unexpected technical failures never expose rejected personal
input, UUIDs, tenant identity, SQL or driver diagnostics, constraints, causes,
stack traces, Membership details, or cross-Business existence. Unexpected
failures retain the shared sanitized `INTERNAL_ERROR` response.

### Business StaffMember working-schedule authorization

Every `/api/business/staff-members/{staffMemberId}/working-schedule` operation
derives the user and selected Business only from the authenticated
server-managed context; no path, query, request, or response field carries
Business, user, Membership, role, or session identity. Access requires an
active `BUSINESS_OWNER` Membership for that exact user and Business. An
absent, inactive, or foreign Membership for the selected Business is rejected
by authenticated-context resolution as `ACTIVE_BUSINESS_REQUIRED`, the same
established outcome the session filter produces before the controller runs.
An active Membership for the selected Business that lacks `BUSINESS_OWNER`
authority — `MANAGER` or `STAFF` — is rejected by application authorization as
`ACCESS_DENIED`. `PLATFORM_ADMIN` alone does not bypass either requirement.
Missing and cross-Business StaffMember identifiers share the same safe
`STAFF_MEMBER_NOT_FOUND` outcome.

DRAFT and ACTIVE Businesses permit reads and mutations. SUSPENDED Businesses
remain readable and reject mutations with `BUSINESS_SUSPENDED`. Active and
inactive StaffMembers retain readable schedules; only an active StaffMember
may receive a mutation, enforced by `STAFF_MEMBER_INACTIVE`. Deactivation and
Business suspension preserve all schedule data.

Complete replacement uses `expectedVersion`, independent of the StaffMember
aggregate version. The mutation transaction locks, in order, the Business
lifecycle row, the exact owner Membership row, then the StaffMember row to
stabilize its active state, before the conditional schedule version update and
complete child-period replacement in the same transaction. Any validation,
version, ownership, or persistence failure rolls back the version and periods
together; concurrent same-version replacements produce one accepted schedule
and one safe `WORKING_SCHEDULE_CONCURRENT_UPDATE` conflict. Reads use a
repeatable-read transaction so aggregate metadata and ordered periods come
from one consistent snapshot. Every PUT remains CSRF-protected.

Accepted local clock values have one-minute precision from `00:00` through
`23:59`, in strict canonical `HH:mm` lexical form; PostgreSQL's special
`24:00:00` value and any lenient wraparound are rejected before reaching
application validation. Periods on one weekday cannot overlap; adjacent
half-open periods are valid. A request may contain at most 100 periods.

Working-schedule failures use this stable public contract:

| Status | Code | Bulgarian public wording |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | `Проверете въведените данни.` |
| 401 | `AUTH_REQUIRED` | `Необходим е вход.` |
| 403 | `ACTIVE_BUSINESS_REQUIRED` | `Изберете бизнес, за да продължите.` |
| 403 | `ACCESS_DENIED` | `Нямате достъп до тази операция.` |
| 404 | `STAFF_MEMBER_NOT_FOUND` | `Членът на екипа не е намерен.` |
| 409 | `STAFF_MEMBER_INACTIVE` | `Неактивен член на екипа не може да получи работен график.` |
| 409 | `WORKING_SCHEDULE_CONCURRENT_UPDATE` | `Работният график е променен от друга операция. Обновете данните и опитайте отново.` |
| 409 | `BUSINESS_SUSPENDED` | `Спрян бизнес може само да преглежда данните си.` |
| 500 | `INTERNAL_ERROR` | `Възникна неочаквана грешка.` |

These responses never expose SQL diagnostics, constraint names, stack traces,
rejected personal input, internal identifiers, Membership details, or tenant
existence. Exceptions, holidays, leave, time off, working overrides, and
breaks remain outside this contract.

### Business schedule-exception authorization

Every `/api/business/schedule-exceptions` operation derives the user and
selected Business only from the authenticated server-managed context; no path,
query, request, or response field carries Business, user, Membership, role, or
session identity, and a `businessId` sent in a body is ignored. Access requires
an active `BUSINESS_OWNER` Membership for that exact user and Business.
`PLATFORM_ADMIN` alone, `MANAGER`, `STAFF`, inactive or missing Memberships, and
foreign Memberships are denied exactly as for the working-schedule API (an
absent, inactive, or foreign Membership is rejected by the session filter as
`ACTIVE_BUSINESS_REQUIRED`; a retained non-owner role as `ACCESS_DENIED`).
Missing and cross-Business exception identifiers share `SCHEDULE_EXCEPTION_NOT_FOUND`.

DRAFT and ACTIVE Businesses permit reads and mutations; SUSPENDED Businesses
remain readable and reject create, replace, and delete with `BUSINESS_SUSPENDED`.
StaffMember-scoped mutations require a same-Business active StaffMember: a
missing or foreign StaffMember is `STAFF_MEMBER_NOT_FOUND`, an inactive one
`STAFF_MEMBER_INACTIVE`. Inactive StaffMembers' exceptions stay readable but
cannot be created, replaced, or deleted, so removing one requires temporary
reactivation. No Membership or login account is created or implied.

Mutations lock, in order, the Business lifecycle row, the exact owner Membership
row, the StaffMember row (StaffMember-scoped kinds only), and finally the
aggregate through a conditional expected-version `UPDATE` or `DELETE`. There is
no overlap pre-check; PostgreSQL exclusion constraints are authoritative. Replace
and delete distinguish a missing exception (empty tenant-scoped read) from a
concurrent change (failed conditional mutation after a successful read). Replace
retains the stored kind and StaffMember, which the request cannot supply. Every
POST, PUT, and DELETE remains CSRF-protected.

Accepted dates are strict `yyyy-MM-dd` and times strict `HH:mm`
(`00:00`–`23:59`, no `24:00`, seconds, or lenient forms). Dates must lie between
`2000-01-01` and `2100-12-31` inclusive for create, replace, and list; a
full-day span is at most 366 dates, an exception has at most 24 periods, and a
list window at most 93 dates. Periods must not duplicate or overlap; adjacent
periods are valid. These bounds are an MVP technical safety boundary, not the
booking horizon.

Schedule-exception failures use this stable public contract:

| Status | Code | Bulgarian public wording |
|---:|---|---|
| 400 | `VALIDATION_ERROR` (optional `fieldErrors`) | `Проверете въведените данни.` |
| 401 | `AUTH_REQUIRED` | `Необходим е вход.` |
| 403 | `ACTIVE_BUSINESS_REQUIRED` | `Изберете бизнес, за да продължите.` |
| 403 | `ACCESS_DENIED` | `Нямате достъп до тази операция.` |
| 404 | `STAFF_MEMBER_NOT_FOUND` | `Членът на екипа не е намерен.` |
| 404 | `SCHEDULE_EXCEPTION_NOT_FOUND` | `Изключението от графика не е намерено.` |
| 409 | `STAFF_MEMBER_INACTIVE` | `Изключенията на неактивен член на екипа не могат да бъдат променяни.` |
| 409 | `SCHEDULE_EXCEPTION_OVERLAP` | `Вече има изключение от същия вид за тези дати.` |
| 409 | `SCHEDULE_EXCEPTION_CONCURRENT_UPDATE` | `Изключението от графика е променено от друга операция. Обновете данните и опитайте отново.` |
| 409 | `BUSINESS_SUSPENDED` | `Спрян бизнес може само да преглежда данните си.` |
| 500 | `INTERNAL_ERROR` | `Възникна неочаквана грешка.` |

A stale version, a concurrent delete, and a PostgreSQL deadlock or serialization
victim share `SCHEDULE_EXCEPTION_CONCURRENT_UPDATE`; the guidance is to reload
and retry. `fieldErrors` names only correctable body fields (`kind`,
`staffMemberId`, `firstDate`, `lastDate`, `allDay`, `periods`); identifier,
version, list-window, and whole-body failures stay generic. Responses never
expose SQL diagnostics, constraint names, stack traces, submitted identifiers,
Membership details, or tenant existence.

### Business Appointment administration authorization (issues #19 and #21; decided in ADR-0028 and ADR-0029; the read routes are implemented in Phase 3, create in Phase 4, reschedule and reassign in Phase 5; cancel and the note edit in Phase 6, backend only)

The calendar, the Appointment detail, the Customer appointment history, and every Appointment mutation
(create, reschedule and reassign, cancel, internal-note edit) derive the user and the selected Business only
from the authenticated server-managed context; no path, query, or body field carries a Business, user,
Membership, or role. Access requires an active `BUSINESS_OWNER` Membership for that exact user and Business.
An absent, inactive, or foreign Membership, or a platform administrator alone, is `ACTIVE_BUSINESS_REQUIRED`;
an active `MANAGER` or `STAFF` Membership of the selected Business is `ACCESS_DENIED`; an unauthenticated caller is
`AUTH_REQUIRED`. A StaffMember record grants no access. Missing and cross-Business Appointment, Customer,
StaffMember, and Service identifiers share one safe `404` (amended by issue #19 Phase 4: a missing, foreign, or inactive Service or StaffMember is instead one byte-identical `409`; see the create route below).

| Business status | Reads | Mutations |
|---|---|---|
| `ACTIVE` | allowed | allowed |
| `DRAFT` | allowed (read-only preview) | `409 BUSINESS_NOT_ACTIVE` (a deliberate difference from the Service, StaffMember, schedule, and Customer APIs) |
| `SUSPENDED` | allowed | `409 BUSINESS_SUSPENDED` |

**Implemented read routes (issue #21 Phase 3; all `GET`, owner-only, no CSRF token needed, every response `Cache-Control: no-store` from the default security headers).** `/api/business/calendar` and `/api/business/appointments` take `from` and `to` (required, canonical `YYYY-MM-DD`, inclusive Business-local dates, at most 31, within 2000-01-01 to 2100-12-31) and an optional `staffMemberId`; the agenda also takes `page` (from 0) and `size` (10, 25, or 50; default 10). `/api/business/appointments/{appointmentId}` is the owner-only detail and `/api/business/customers/{customerId}/appointments` the Customer history (`page`, `size`). `ACTIVE`, `DRAFT`, and `SUSPENDED` Businesses are all readable and the calendar, agenda, and detail say which in `businessStatus`. Failures: `AUTH_REQUIRED` (401); `ACTIVE_BUSINESS_REQUIRED` and `ACCESS_DENIED` (403, as above); `VALIDATION_ERROR` (400, generic, never naming a field or echoing a value); `APPOINTMENT_NOT_FOUND` ("Часът не е намерен."), `CUSTOMER_NOT_FOUND` ("Клиентът не е намерен."), and `STAFF_MEMBER_NOT_FOUND` ("Членът на екипа не е намерен.") (404, byte-identical for a missing and a foreign identifier of the same resource type); `INTERNAL_ERROR` (500). Each route family has one fixed, non-echoing problem `instance`, the family's base path (the convention of the Customer API): `/api/business/calendar` for the calendar, `/api/business/appointments` for the agenda and the detail, and `/api/business/customers` for the Customer history; no identifier, query value, slug, or raw request path is ever echoed. Calendar and agenda rows carry no note, no cancellation actor, no public reference, and no contact data; the history carries neither a Customer name nor any note, reference, price, or attribution; only the owner detail carries the contact data, both notes, the public reference, the version, and the cancellation attribution. The unfiltered calendar lists every active StaffMember plus an inactive one only when it has an Appointment in the window; an explicit `staffMemberId` may name an inactive same-Business StaffMember.

**Implemented create route (issue #19 Phase 4).** `POST /api/business/appointments` creates one `CONFIRMED`, `MANUAL` Appointment of one to five distinct Services for an
existing same-Business Customer and one explicit StaffMember. The body has exactly the keys `customerId`, `staffMemberId`, `start` (an ISO-8601 date-time with an explicit
offset), `services` (each exactly `serviceId`, `reviewedServiceName`, `reviewedDurationMinutes`, `reviewedPriceCents`), and an optional `internalNote`; any other key, a
non-canonical UUID, a non-integral number, or a start without an offset is `400 VALIDATION_ERROR`. The domain bounds (one to five distinct Services, each duration 1 to 480
minutes, claimed durations at most 480, price 0 to 999,999,999,999 cents, a note of at most 500 code points, a start date inside 2000-01-01 to 2100-12-31) are checked by the
service after authorization and lifecycle, so `BUSINESS_NOT_ACTIVE` and `BUSINESS_SUSPENDED` win over an invalid domain value, and a missing Customer wins over every Service,
StaffMember, terms, and slot result. A reviewed name, duration, or price that differs from the locked Service is `409 APPOINTMENT_REVIEW_CHANGED` without saying which. A missing,
foreign, or inactive Service is one `409 APPOINTMENT_SERVICE_UNAVAILABLE`, and a missing, foreign, inactive, or unassigned StaffMember one `409 APPOINTMENT_STAFF_UNAVAILABLE`
(byte-identical, so no existence is revealed); a missing and a foreign Customer are one `404 CUSTOMER_NOT_FOUND`. This amends the earlier sentence that Service and StaffMember
identifiers share a `404`: only Appointment and Customer identifiers do. Success is `201` with `Location: /api/business/appointments/{id}` and the keys `id, reference, status,
source, start, end, timezone, staffMemberId, staffDisplayName, customerId, services[position,name,durationMinutes,price], durationMinutes, price, internalNote, version, createdAt`;
it carries no Customer name, phone, email, or Customer note. The problem `instance` is the fixed `/api/business/appointments`; `no-store` comes from the default headers; CSRF
is required. Wording: `BUSINESS_NOT_ACTIVE` "Бизнесът не е активен. Часове могат да се създават само в активен бизнес.", `APPOINTMENT_SERVICE_UNAVAILABLE` "Някоя от избраните
услуги вече не е налична.", `APPOINTMENT_STAFF_UNAVAILABLE` "Избраният член на екипа не е наличен за тези услуги.", `APPOINTMENT_REVIEW_CHANGED` "Услугите са променени.
Прегледайте ги отново и опитайте пак.", `APPOINTMENT_SLOT_UNAVAILABLE` "Избраният час вече не е свободен.", `APPOINTMENT_TEMPORARILY_UNAVAILABLE` (503) "Часът не беше
запазен. Опитайте отново след малко.", `APPOINTMENT_OUTCOME_UNCERTAIN` (503) "Не е ясно дали часът е запазен. Проверете графика, преди да опитате отново."; all remain
Proposed until the rendered human review. A request of another media type is `415 UNSUPPORTED_MEDIA_TYPE`.

**Implemented reschedule route (issue #19 Phase 5).** `POST /api/business/appointments/{appointmentId}/reschedule` moves a `CONFIRMED`, not yet started Appointment to a new start, to
another StaffMember, or both, in one atomic change guarded by the expected version. The path identifier must be a canonical UUID. The body has exactly the keys `expectedVersion` (required,
an integer) and, optionally, `start` (an ISO-8601 date-time with an explicit offset) and `staffMemberId` (a canonical UUID); an absent key keeps the current value. An explicit `null`, any
other key (a Customer, Service, duration, price, or Business included), a non-canonical identifier, a non-integral version, or a start without an offset is `400 VALIDATION_ERROR`. After
authorization and lifecycle the service also answers `400 VALIDATION_ERROR` for a negative version, a request that names neither `start` nor `staffMemberId`, a start outside 2000-01-01
to 2100-12-31, and a request that changes nothing (the same start and the same, or no, StaffMember): the last is judged only after the version and the state, and never advances the
version. Precedence, which keeps the results distinct: authentication and authorization; lifecycle (`BUSINESS_NOT_ACTIVE`, `BUSINESS_SUSPENDED`, before any domain validation); domain
bounds; existence (`404 APPOINTMENT_NOT_FOUND`, byte-identical for a missing and a foreign Appointment); the version (`409 APPOINTMENT_CONCURRENT_UPDATE`); the state (`409
APPOINTMENT_STATE_CONFLICT`: cancelled, or already started, an Appointment whose start is at or before the clock instant of the attempt having started); no change; the StaffMember (`409
APPOINTMENT_STAFF_UNAVAILABLE`); a start before the clock instant, a start that is not offered, and a start that is taken or lost the overlap race (all `409 APPOINTMENT_SLOT_UNAVAILABLE`).
A time-only move (no `staffMemberId`, or the current one) needs only the current StaffMember to be active: a Service deactivated later or an assignment removed later does not block it.
A reassignment needs a target that is active and currently assigned to every Service of the visit; a missing, foreign, inactive, or unassigned target is one byte-identical
`APPOINTMENT_STAFF_UNAVAILABLE`, so no existence is revealed. Customer, Services, duration, price, source, reference, and the StaffMember snapshot of a time-only move never change. Success is
`200` (no `Location`) with the keys `id, reference, status, source, start, end, timezone, staffMemberId, staffDisplayName, customerId, services[position,name,durationMinutes,price],
durationMinutes, price, internalNote, version, createdAt, updatedAt` and no Customer name, phone, email, or Customer note. The problem `instance` is the fixed `/api/business/appointments`;
`no-store` comes from the default headers; CSRF is required. The route has its own operation-appropriate wording, final for the backend (it may still receive human UX feedback; the same code and status are mapped to different details on the create and the reschedule routes, and no reschedule detail speaks of creating or saving an Appointment). Every detail this route can return: `VALIDATION_ERROR` (400) "Проверете въведените данни."; `ACCESS_DENIED` (403) "Нямате достъп до тази операция."; `BUSINESS_NOT_ACTIVE` (409) "Бизнесът не е активен. Часовете могат да се променят само в активен бизнес."; `BUSINESS_SUSPENDED` (409) "Спрян бизнес може само да преглежда данните си."; `APPOINTMENT_NOT_FOUND` (404) "Часът не е намерен."; `APPOINTMENT_CONCURRENT_UPDATE` (409) "Часът е променен междувременно. Презаредете графика и опитайте отново."; `APPOINTMENT_STATE_CONFLICT` (409) "Часът не може да бъде променен, защото е отменен или вече е започнал."; `APPOINTMENT_STAFF_UNAVAILABLE` (409) "Членът на екипа не е наличен за тази промяна."; `APPOINTMENT_SLOT_UNAVAILABLE` (409) "Избраният час вече не е свободен."; `APPOINTMENT_TEMPORARILY_UNAVAILABLE` (503) "Промяната не беше направена. Часът е непроменен. Опитайте отново след малко."; `APPOINTMENT_OUTCOME_UNCERTAIN` (503) "Не е ясно дали промяната е направена. Презаредете графика и проверете часа, преди да опитате отново."; `UNSUPPORTED_MEDIA_TYPE` (415) "Заявката не може да бъде изпълнена."; `INTERNAL_ERROR` (500) "Възникна неочаквана грешка." The create-only results (`CUSTOMER_NOT_FOUND`, `APPOINTMENT_SERVICE_UNAVAILABLE`, `APPOINTMENT_REVIEW_CHANGED`) cannot occur here and would be the generic `INTERNAL_ERROR`, never create wording. A proven rollback after three attempts is `503 APPOINTMENT_TEMPORARILY_UNAVAILABLE`; a change that cannot be confirmed (the commit may
have succeeded, or the row was changed again before the verification read) is `503 APPOINTMENT_OUTCOME_UNCERTAIN` and is never retried; success is reported only after a read outside
the transaction finds the row at the new version with the requested start and StaffMember.

**Implemented cancellation and internal-note routes (issue #21 Phase 6).** Both are owner-only, require CSRF, accept only `application/json` (`415 UNSUPPORTED_MEDIA_TYPE` otherwise), carry `Cache-Control: no-store`, and use the fixed problem `instance` `/api/business/appointments`. A non-canonical identifier in the path, a non-object body, a missing, `null`, or unknown key, and a non-integral or out-of-range `expectedVersion` are the same `400 VALIDATION_ERROR` that echoes nothing.

- `POST /api/business/appointments/{appointmentId}/cancel`, body exactly `{"expectedVersion": n}` (no reason, actor, time, or status can be named). Success: `200` with the complete private Appointment (keys `id, reference, status, source, start, end, timezone, staffMemberId, staffDisplayName, customerId, services[position,name,durationMinutes,price], durationMinutes, price, internalNote, version, createdAt, updatedAt`), `status` `CANCELLED`, `version` plus one, `updatedAt` the cancellation instant; it exposes no `cancelled_by`, no `cancelledAt`, no Customer name, phone, email, or Customer note.
- `PUT /api/business/appointments/{appointmentId}/internal-note`, body exactly `{"expectedVersion": n, "internalNote": "…"}`; `internalNote` is a required JSON string, a blank string clears the note (the response carries an explicit `null`), the shared note rule applies (at most 500 code points, no control character except tab and line feed, no unpaired surrogate, never truncated). Success: `200` with the same representation; an unchanged normalized note is a `200` with the current row and no `UPDATE`, `version`, and `updatedAt` unchanged.

Precedence: authentication and CSRF, selected Business and role, lifecycle (`DRAFT`, `SUSPENDED`), the domain bounds of the command, existence, version, state, and for the note the note rule. Every result, with its final operation-specific Bulgarian detail (the same code and status are mapped to different details than on the create and reschedule routes, and no detail names another operation):

| Status | Code | Cancellation detail | Internal-note detail |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | Проверете въведените данни. | Проверете въведените данни. |
| 401 | `AUTH_REQUIRED` | the fixed body of the security chain | the same |
| 403 | `ACTIVE_BUSINESS_REQUIRED` | as for every owner route | the same |
| 403 | `ACCESS_DENIED` | Нямате достъп до тази операция. | Нямате достъп до тази операция. |
| 404 | `APPOINTMENT_NOT_FOUND` (a missing and a foreign Appointment, byte-identical) | Часът не е намерен. | Часът не е намерен. |
| 409 | `BUSINESS_NOT_ACTIVE` | Бизнесът не е активен. Часовете могат да се отменят само в активен бизнес. | Бизнесът не е активен. Бележките могат да се променят само в активен бизнес. |
| 409 | `BUSINESS_SUSPENDED` | Спрян бизнес може само да преглежда данните си. | Спрян бизнес може само да преглежда данните си. |
| 409 | `APPOINTMENT_CONCURRENT_UPDATE` (a stale or lost version) | Часът е променен междувременно. Презаредете графика и опитайте отново. | Часът е променен междувременно. Презаредете графика и опитайте отново. |
| 409 | `APPOINTMENT_STATE_CONFLICT` (already cancelled, or started) | Часът не може да бъде отменен, защото вече е отменен или е започнал. | unreachable (a note is editable in every status): `INTERNAL_ERROR` |
| 503 | `APPOINTMENT_TEMPORARILY_UNAVAILABLE` (a proven rollback; never retried) | Часът не беше отменен. Той е непроменен. Опитайте отново след малко. | Бележката не беше променена. Опитайте отново след малко. |
| 503 | `APPOINTMENT_OUTCOME_UNCERTAIN` (the commit may have succeeded, or the written row cannot be confirmed) | Не е ясно дали часът е отменен. Презаредете графика и проверете часа, преди да опитате отново. | Не е ясно дали бележката е променена. Презаредете графика и проверете часа, преди да опитате отново. |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Заявката не може да бъде изпълнена. | Заявката не може да бъде изпълнена. |
| 500 | `INTERNAL_ERROR` | Възникна неочаквана грешка. | Възникна неочаквана грешка. |

The results that cannot occur on these routes (`CUSTOMER_NOT_FOUND`, `APPOINTMENT_SERVICE_UNAVAILABLE`, `APPOINTMENT_STAFF_UNAVAILABLE`, `APPOINTMENT_REVIEW_CHANGED`, `APPOINTMENT_SLOT_UNAVAILABLE`) are the generic `INTERNAL_ERROR`, never create or reschedule wording. A problem body never contains SQL, a constraint name, a class, a cause, a foreign identifier, a Customer contact value, or the note. The orchestration (one `READ COMMITTED` attempt, completion-status classification, the verification read, and no retry, not even after a proven rollback) is decided in the Phase 6 note of ADR-0029. The internal note is private Business data: it never appears in a guest booking response or replay, a public log, a public error, a URL, the calendar or agenda lists, or the Customer history; only the authenticated detail and these two mutation responses carry it.

**[Superseded for the cancellation and the note edit by the two routes above, whose codes, wording, and statuses are final for the backend; kept as the historical plan.]** Failures planned for the remaining mutations, cancel and note edit (the codes, route names, and Bulgarian wording are working names, finalised and tested in the implementing HTTP or interface phase, and the wording stays Proposed until the rendered human review; reschedule and reassign are final above): `VALIDATION_ERROR` (400),
`AUTH_REQUIRED`, `ACTIVE_BUSINESS_REQUIRED`, `ACCESS_DENIED`, `APPOINTMENT_NOT_FOUND` and `CUSTOMER_NOT_FOUND` (404),
`BUSINESS_NOT_ACTIVE`, `BUSINESS_SUSPENDED`, `APPOINTMENT_CONCURRENT_UPDATE` (a stale `expectedVersion`),
`APPOINTMENT_STATE_CONFLICT` (already cancelled or started), `APPOINTMENT_SLOT_UNAVAILABLE` (the time is not offered,
is past, or lost the overlap race), `APPOINTMENT_STAFF_UNAVAILABLE`, `APPOINTMENT_SERVICE_UNAVAILABLE`,
`APPOINTMENT_REVIEW_CHANGED` (409), `APPOINTMENT_TEMPORARILY_UNAVAILABLE` and `APPOINTMENT_OUTCOME_UNCERTAIN` (503), and
`INTERNAL_ERROR`. A stale version, a state conflict, and a lost slot are always distinct codes. A conflict response never
reveals another Customer, a note, or an Appointment detail; the owner sees who occupies a time only through the refreshed
calendar. Every mutation requires CSRF; reschedule, cancel, and note edit carry `expectedVersion`.

Privacy: the calendar and history lists carry the minimum facts (a Customer display name; no note, phone, or email);
the detail alone carries the contact data and the `customer_note` and `internal_note`, owner-only. Customer names,
search terms, notes, and the attempt's values never appear in a URL, `history.state`, browser storage, the document
title, a log, or an error; calendar URL state is limited to the view, the date or week, a StaffMember identifier, and the
agenda's page and size. `internal_note` is never public and never sent to a Customer. Cancellation never deletes: it
records `cancelled_at` and `cancelled_by`.

Mutation locks follow the total order of ADR-0029 (Business lifecycle, Membership, StaffMember, schedule revision,
Service, Customer, Appointment). That order is an ordering argument and not a claim of deadlock freedom; the exclusion-index
deadlock between two overlapping writers is a documented residual risk handled by a bounded retry. Manual creation has no
idempotency key in the MVP (the exclusion constraint prevents a duplicate confirmed Appointment); the interface never repeats a
create automatically after an uncertain result.

Repositories require `business_id`; foreign/composite constraints validate
common ownership. STAFF is restricted to the linked StaffMember’s schedule and
Appointments (a future capability: in issues #19 and #21 only `BUSINESS_OWNER` has Appointment access, ADR-0028). Cross-Business attempts return safe 404 where existence need not
be disclosed, or 403 for an established Business context. Tests cover Business
A versus Business B reads, lists, writes, indirect IDs, and bulk operations.

DRAFT rejects public booking but allows configuration. ACTIVE allows booking
and authorized mutations. SUSPENDED rejects booking, preserves data, and makes
Business administration read-only except logout/account-security; PLATFORM_ADMIN
may reactivate it.

Owner-invitation acceptance normalizes email and enforces the invitation's
single-use lifecycle. For a new normalized email it creates one global User
with the submitted display name. For an existing active, unlocked User it
requires that User's current password, retains the existing display name, and
adds the Business Membership. Invalid, expired, replaced, or consumed
invitations return 400 `INVITATION_INVALID` with “Поканата е невалидна, изтекла
или вече е използвана. Поискайте нова покана.” A valid invitation with an
incorrect existing-User password returns 400
`INVITATION_CREDENTIAL_MISMATCH` with “Паролата не съвпада със съществуващия
профил за този имейл.” Ordinary request and password-policy validation remains
`VALIDATION_ERROR`; rate limiting remains `RATE_LIMITED`. The unique normalized
email prevents a second global User. Frontend password confirmation is
local-only and is never sent or persisted.

## Public Business profile (issue #17; backend implemented in Phase 2B)

Decided in ADR-0017 and ADR-0018; the backend contract is implemented. **Amended by ADR-0026 (Phase 5):**
each active Service now also carries its identifier (`services[].id`), the response is session-independent in the
strict sense (the session filter skips the route), and exactly three more public routes exist (see the guest
booking section); the paragraphs below are otherwise unchanged. One unauthenticated
`GET /api/public/businesses/{slug}` (a single non-empty path segment) is the only new
public route. Every other verb or deeper path under `/api/public/businesses/**` is
denied (401 anonymous, 403 authenticated) before it reaches MVC, and every other
route remains authenticated, CSRF and the exact-origin CORS policy are
unchanged, and the response never depends on identity. Only an ACTIVE Business
resolves. DRAFT, SUSPENDED, unknown, malformed, and formerly used slugs all return
one identical 404 (`BUSINESS_PAGE_UNAVAILABLE`, «Страницата не е налична.») so
lifecycle state and tenant existence are not revealed; a malformed or reserved slug
is rejected before any query. The RFC 7807 `instance` is a fixed value, because the
default request path would echo the submitted slug. Path characters the servlet
firewall rejects (encoded slash, semicolon, backslash, NUL) receive its bare empty
400, which discloses nothing. The body is an allowlist: slug, display name, Business
type, optional description, optional telephone, optional structured address, and
each active Service's identifier, name, description, duration, and EUR price. `contact_email`,
timezone, status, other identifiers, versions, timestamps, owner and Membership data,
StaffMembers, and inactive Services are never public. Responses keep the default
`no-store` headers and set no cookie. The endpoint writes nothing, takes no lock,
and creates no Customer, Appointment, or session state; it issues two SQL statements
for an ACTIVE Business (independent of the number of Services), one for any other
well-formed slug, and none for a malformed or reserved slug, in a read-only
repeatable-read transaction whose isolation is enforced before any read.

There is deliberately no application-level rate limiter for this read-only,
index-backed endpoint and no cap on the number of active Services returned;
edge or CDN limiting and the public availability and booking endpoints are later
work, and this limitation is documented rather than mitigated. After first
activation a Business slug is immutable and reserved path roots cannot be slugs
(ADR-0018), so a shared link cannot be re-pointed at a different Business.

Reserved roots are enforced by the backend (`ReservedBusinessSlugs`, 19 values, exact
match on the canonical lowercase slug); the frontend copy is a convenience mirror and
never authoritative. A reserved slug on create or a DRAFT slug change is a
`VALIDATION_ERROR` naming only the `slug` field (“Изберете друг публичен адрес на
бизнеса.”); the list itself is never returned. A DRAFT that already holds a reserved
slug keeps it while other fields change but cannot be activated. ACTIVE and SUSPENDED
Businesses cannot change slug (`BUSINESS_SLUG_IMMUTABLE`); the version-guarded update
makes a slug change racing an activation fail as `BUSINESS_CONCURRENT_UPDATE`. The
error bodies contain no SQL, constraint names, identifiers, or submitted values.

## Public endpoint and booking controls

Availability, booking, login, reset, invitation, and cancellation endpoints
receive conservative per-endpoint limits using normalized IP plus a non-secret
slug/account/token fingerprint. Return 429 without account disclosure. An
in-process limiter is acceptable for one instance; scaling requires shared or
edge enforcement. CAPTCHA is not default. Phase 2 uses a deterministic
10-attempt/15-minute window per flow, remote address, and sensitive-input
fingerprint. Keys are irreversible SHA-256 digests; raw emails, tokens, and
passwords are never retained. Counters are per-process and non-durable. The
process retains at most 10,000 counters, removes expired counters before each
decision, and fails closed for previously unseen keys while capacity remains
full. This prevents memory exhaustion but can temporarily reject new
authentication attempts during a saturated 15-minute window. Multi-instance
deployment requires separately approved shared or edge enforcement.

Strictly validate all inputs. Revalidate availability transactionally and use
the PostgreSQL overlap exclusion constraint on `staff_member_id`. Return safe,
structured Bulgarian errors. When `COMPLETED` and `NO_SHOW` are introduced (deferred by ADR-0022; the
statuses are currently `CONFIRMED` and `CANCELLED`), reject them before Appointment
start so a future slot cannot be released early.

### Guest booking (issue #18; decided in ADR-0022 to ADR-0027; the backend and the frontend are implemented, the frontend journey was visually approved by the product owner in M6, and the public traffic and browser state are verified in real browsers in M7)

- **Multi-Service extension (the persistence phase M1 is implemented: `appointment_service` carries `business_id` with composite same-Business restrictive foreign keys, and its constraints are translated into sanitized fixed failures; everything else decided in [ADR-0027](decisions/ADR-0027-book-several-services-as-one-atomic-visit-with-service-lines-a-versioned-set-fingerprint-and-an-explicit-review-consistency-check.md), not implemented).** The new read routes and the second booking request shape (**implemented in phase M4**; the persistence of the claims is never done) follow the same rules as the routes above: exact matchers, session independence, `no-store`, `credentials: 'omit'`, Business-scoped resolution of every identifier (a foreign or missing Service is the same `BOOKING_SERVICE_UNAVAILABLE`; a malformed selection is a `400` whose only field error is `services`, read before any transaction; identifiers must be in the exact canonical 8-4-4-4-12 form because `UUID.fromString` also accepts short and signed forms), no personal data in URLs, logs, errors, or storage, and the unchanged limiter budgets and 16 KiB body bound. The reviewed duration and price in a request are **claims that are compared with the locked Services and never persisted**; a difference creates no Appointment and no Customer and answers `409 BOOKING_REVIEW_CHANGED` without any figure. The claims are part of the request fingerprint, so a replay with other claims is a mismatch. `appointment_service` lines carry `business_id` with composite same-Business foreign keys, and the Services of a visit are locked `FOR SHARE` in ascending identifier order.

- **Public surface (implemented, Phase 5).** Beyond the profile, exactly `GET …/{slug}/services/{serviceId}/booking-options`,
  `GET …/{slug}/services/{serviceId}/availability`, the Service-selection reads `GET …/{slug}/booking-options` and
  `GET …/{slug}/availability` (repeated `serviceId`, ADR-0027 phase M4), and `POST …/{slug}/bookings` are public, each with its one verb
  and an exact path (no wildcard); every other verb, sibling, or deeper path under `/api/public/businesses/` stays denied
  (401 anonymous, 403 authenticated). They are Business-scoped by the slug, resolve a foreign or missing identifier like a
  missing one (`BOOKING_SERVICE_UNAVAILABLE` or `BOOKING_STAFF_UNAVAILABLE`, 409), and collapse a DRAFT, SUSPENDED,
  unknown, malformed, or reserved slug into the existing `BUSINESS_PAGE_UNAVAILABLE` 404. Responses are `no-store`
  (also errors), set no cookie, use the fixed RFC 7807 `instance` `/api/public/businesses`, and expose no
  StaffMember identifier on a slot, no Customer or internal Appointment identifier, no contact data, no version or audit
  value, and no other guest's data. The only StaffMember data are the identifier and display name in `booking-options` and the
  assigned display name in the confirmation. There is no endpoint that looks an Appointment up by its informational
  reference. The request has no field for price, duration, end, status, source, timezone, or Business, and unknown
  properties (also in `customer`) and non-string text values are rejected as `VALIDATION_ERROR`; the body is read from the
  JSON tree because the global Jackson configuration ignores unknown properties. The exact contract is in ADR-0026
  (implementation notes).
- **CSRF and session (implemented, Phase 5).** The four public routes are session-independent: `DatabaseSessionFilter`
  skips them (the cookie is neither read nor validated), so a valid session is not refreshed, an expired one is not
  revoked, and no session or cookie is created or cleared; the controllers cannot reach `identity` or the principal
  (architecture tests). Only `POST …/bookings` is exempt from CSRF validation, through the same exact matcher
  (`PublicBookingRoutes`) that authorizes and bypasses it; every other mutation, including other verbs and deeper
  paths under the public prefix and every authenticated write, still needs the token (regression tests). The public
  frontend omits credentials; CORS stays exact-origin with the unchanged credentialed policy (no wildcard; a foreign
  `Origin` is refused for reads, bookings, and preflight, and nothing is booked).
- **Idempotency.** An attempt ID plus a versioned HMAC-SHA-256 fingerprint over the normalized Service,
  requested preference, start, Customer input, and note; a different request under the same attempt ID is
  `BOOKING_ATTEMPT_MISMATCH`; replay uses the stored encoding and key versions, holds only the initial Business
  lock, and never revalidates availability, calls the Customer capability, or writes. The attempt ID is generated by
  the browser with a secure random generator (direct callers must supply an unpredictable one); the server validates
  its format only, and it is not an authentication credential.
  The fingerprint key is a new server secret with a retained key ring; a missing historical key is a safe,
  uncertain technical failure and never a mismatch. The fingerprint columns are replay material that a future
  erasure procedure must clear (ADR-0024).
- **Outcomes (implemented, Phase 5).** A proven rollback is `503 BOOKING_TEMPORARILY_UNAVAILABLE` with «Резервацията не беше
  направена. Опитайте отново.»; an uncertain outcome is `503 BOOKING_OUTCOME_UNCERTAIN` with «Не получихме потвърждение за
  резервацията. Опитайте отново.»; both carry `Retry-After: 2` and the same attempt can be repeated (a replay returns the
  committed Appointment, `200`). A guest is never told a booking failed when it may exist.
- **Request body bound (implemented).** `POST …/bookings` accepts at most 16 KiB (`spotyourslot.booking.max-request-body-bytes`),
  counted in bytes actually read before the JSON tree is built (chunked, absent, and false `Content-Length` included); more is a
  sanitized `413 REQUEST_TOO_LARGE` with the fixed instance and `no-store`, charged once to the address budgets and never to the
  contact budget, with no booking or database work. Private endpoints are unchanged.
- **Frontend memory-only handling (implemented, Phase 6 and, for a visit of several Services, phase M5; visually approved in M6 and verified in real browsers in M7).** The public journey keeps the guest's choices, details, note, and
  attempt identity only in component memory: never in the URL, `history.state` (only a step marker), `localStorage`, `sessionStorage`, cookies, the page title or
  metadata, logs, or analytics. Every public request uses `credentials: 'omit'`. The details are cleared from memory as soon as the server has answered a
  booking, a Business switch discards the whole journey and ignores its pending answers, and an uncertain outcome keeps the one frozen attempt (same ID, same bytes) until the
  guest explicitly abandons it after a warning. The selected Services, their prices, and the reviewed terms are held in memory like the other choices; the Service identifiers appear only as repeated `serviceId` parameters of the public reads (public references, no name, price, or personal value), and the request carries the reviewed terms as claims the server compares and discards. `beforeunload` is best effort; a refresh discards the in-memory attempt (residual risk, ADR-0024). Evidence: the Vitest suites under
  `frontend/src/public/booking/` (they test client behavior against stubbed HTTP and do not prove backend guarantees).
- **Abuse protection (implemented, Phase 5).** A Booking-owned, bounded, process-local, fixed-window limiter charged before
  any database work (a rejected request writes nothing and reaches no orchestration). Defaults per 15 minutes: bookings 10
  per address and Business, 30 per address across Businesses, and 5 per Business and canonical phone or email (each supplied
  identifier is charged, all or none); `booking-options` and `availability` 300 per address and Business and 600 per
  address across Businesses; at most 50 000 counters. Keys are HMAC-SHA-256 digests under a per-process random secret
  (no raw address, slug, contact, or attempt ID is retained or logged); an IPv6 address is limited as its /64 prefix;
  malformed slugs share one bucket; a rejected admission decision charges nothing; a replay, a rejected booking, and an invalid body are
  charged (the address and the contact decision are separate: a contact rejection keeps the earlier address charge, and an address rejection never reaches the contact decision); at capacity a new counter is refused (`429`, fail closed) and no active counter is evicted. The sanitized
  `429 RATE_LIMITED` carries `Retry-After` in whole seconds. The address is only the servlet remote address; `Forwarded`,
  `X-Forwarded-For`, and `X-Real-IP` are never read. **Limits:** one process only (restart resets, each instance has its
  own counters), behind a proxy all guests share one address until a trusted-address policy is separately approved,
  a distributed or edge limiter is required beyond one instance, the proxy or edge must bound request lines, headers, and
  bodies as well (the application bounds only the booking body), and a third
  party who knows a guest's contact can exhaust that guest's budget for the window. No contact verification exists,
  so fake bookings can only be slowed; production public booking waits for the Business calendar (issue #21).
- **Logging.** No request body, note, contact, attempt ID, reference, or fingerprint is logged.
- **Schedule coordination.** Schedule mutations and bookings are ordered by the Business schedule revision row
  and the total lock order of ADR-0025. The mutation side is implemented (Phase 3): the revision change and the
  schedule write share one transaction, a missing revision row is a sanitized internal failure (never a silent
  success), the revision exposes no identifier, SQL, or personal data in any failure or response, and the
  revision is never reachable from a public route. The booking side is implemented (Phase 4): the attempt takes the shared guard before availability; fingerprint keys are configured per `docs/tasks/08a` and ADR-0024, validated at startup without echoing values, and non-secret development and test keys are rejected by the `prod` profile; booking diagnostics log only fixed event codes and exception class names.

## Secrets, logging, and asynchronous work

Production credentials come from hosting environment variables, never source or
frontend builds. Use least privilege, rotation, and encrypted database transport
where supported. OpenAPI is development-only; health output is minimal.

Structured logs omit passwords/hashes/tokens/cookies/authorization headers,
full query strings, email bodies, notes, and direct personal identifiers. Audit
actor, action, target, Business, time, outcome, and correlation ID without
sensitive payloads.

Appointments commit with an outbox event independently of email delivery.
Workers claim safely, retry boundedly, and use unique idempotency keys. Email
links use example origin `https://spotyourslot.bg` and Business pages use
`https://spotyourslot.bg/{businessSlug}`. Development/tests do not send. The
domain is not configured and its availability has not been legally verified.

### Customer administration and privacy (issue #20; persistence, matching, the private API, the interface, and browser E2E are implemented)

The private Customer API (`/api/business/customers`, ADR-0021) derives the user and the
selected Business only from the server-managed security context; no path, query, or body field
supplies an authoritative `businessId`. Access requires an active `BUSINESS_OWNER` Membership
for that exact user and selected Business. `PLATFORM_ADMIN` alone, `MANAGER`, `STAFF`,
inactive Memberships, and Memberships in another Business do not grant access. DRAFT and
ACTIVE Businesses permit reads and mutations; SUSPENDED permits reads (including the
body-based search) and rejects create with `BUSINESS_SUSPENDED`; updating an existing Customer is
the one approved exception and is allowed (amended 2026-10-06, same locks, authorization, version
check, validation, and uniqueness; no other operation is affected). Each mutation
locks the Business lifecycle row, then the user's Membership row, then performs the
optimistic write, and every POST and PUT remains CSRF-protected. A missing and a foreign
Customer ID both return the identical `CUSTOMER_NOT_FOUND` (404).

Implemented behavior (Phase 4): a session without a usable owner selection (`PLATFORM_ADMIN`
alone, an inactive Membership, a Membership of another Business) is rejected with
`ACTIVE_BUSINESS_REQUIRED` like every private API, `MANAGER` and `STAFF` of the selected
Business with `ACCESS_DENIED`, and a SUSPENDED Business rejects create with
`BUSINESS_SUSPENDED` before validation or lookup (update is allowed there, see above). Responses carry `no-store`; every problem has a
fixed `instance` so a Customer ID in the path is never echoed; no endpoint exists under
`/api/public`; all Customer commands, requests, and responses redact `toString()`; the search
term travels only in the POST body. Search uses `strpos` and `starts_with` on bound parameters, so
a wildcard character is literal and malformed phone-like text never widens the search.

Customer failures use `VALIDATION_ERROR` (400, with `fieldErrors` for `displayName`,
`phone`, `email`, and `contact`), `AUTH_REQUIRED`, `ACTIVE_BUSINESS_REQUIRED`,
`ACCESS_DENIED`, `CUSTOMER_NOT_FOUND`, `CUSTOMER_CONTACT_CONFLICT`,
`CUSTOMER_CONCURRENT_UPDATE`, `CUSTOMER_CONCURRENT_CONFLICT`, `BUSINESS_SUSPENDED`, and
`INTERNAL_ERROR`, with the Bulgarian wording recorded in
`docs/tasks/07a-business-customer-records.md`. No response echoes submitted data, a constraint
name, or a SQL diagnostic.

Customer names, phone numbers, email addresses, and appointment history never appear on
public pages, in URLs, browser storage, safe errors, normal application logs, screenshots,
generated test artifacts, or review archives. Only opaque UUIDs appear in paths. The search
term is sent in a POST body and held only in application memory (React state), tied to the selected Business and
dropped when the user leaves the Customer area, switches Business, or signs out. The Customer persistence layer
translates failures by SQLState and the exact structured constraint, table, or column name the
driver reports, using the driver's types directly (message text is never parsed), and rethrows
fixed exceptions that retain neither the cause nor a suppressed exception, because PostgreSQL's server
message contains the offending value; an unexpected failure keeps its own application-only stack trace
(implemented in `CustomerStore`); matching
avoids raising unique violations on its normal path. PostgreSQL's own server log can still
contain a key value for a rare administrative unique violation, a recorded operational
limitation. Matching is conservative (ADR-0020): it never writes to an existing Customer, and
the guest-facing identity-conflict message does not reveal which identifier matched or that a
Customer exists. Customer creation never creates credentials, a session, or a Membership.

The implemented matching capability logs nothing, so no name, phone, email, ID, or SQL can be logged
by it. `CustomerIdentity.toString()` is redacted, and the published outcomes and the two sanitized
exceptions (`CustomerConcurrentConflict` for a retryable concurrency failure and
`CustomerOperationFailure` for any other persistence failure) carry no submitted value, ID, SQL,
constraint text, cause, or suppressed exception. Every operation requires a caller-owned transaction
and is scoped by Business, so a foreign-Business Customer is treated as nonexistent.

Browser evidence (issue #20 Phase 6): real-browser journeys confirm that Customer search terms and values never appear in
the URL, `localStorage`, `sessionStorage`, IndexedDB, script-visible cookies, the document title, Customer request URLs or headers,
or the public profile surfaces; that owner B receives a byte-identical `CUSTOMER_NOT_FOUND` for Business A's Customer and sees none
of its data; and that a Platform Administrator without an owner Membership (`ACTIVE_BUSINESS_REQUIRED`) and anonymous callers
(`AUTH_REQUIRED`) are rejected. That no account, Membership, or Appointment row is created by Customer creation is proven by the backend
test `BusinessCustomerMutationApiIntegrationTests.createNeverUsesTheOwnersContactOrCreatesAnyAccountRecord`, not by the browser.

## Privacy and verification

Collect only name, phone, email, optional booking note, and Appointment data.
Internal notes are separately authorized and never sent to Customers. Do not
solicit medical, health, or special-category data. Records/exports are always
Business-scoped; cancellation retains Appointment history.

Before launch, approve notices, legal roles/bases, retention, data-subject and
breach procedures, vendors/regions/transfers, backup/restore, incident response,
and production access. This design supports privacy but does not claim complete
GDPR compliance.

Automated coverage includes role matrices, Business A/B isolation, CSRF/CORS,
session lifecycle, token expiry/single use, enumeration-safe errors, lifecycle
states, validation/log redaction, and real-PostgreSQL concurrent booking. A
pre-launch threat model and dependency/container scan are required.

### Business calendar frontend privacy (issue #21 Phase 7)

The read-only calendar, the Appointment detail, and the Customer history are owner-only in the interface as in the API (Manager, Staff, and platform-only sessions are redirected and never see the destination). The route carries only the view, a Business-local date, an opaque StaffMember identifier, and the agenda page and size; a Customer name, contact value, note, or Appointment detail never appears in a URL, `history.state`, the document title, browser storage, or the console (asserted by component and application tests, and by a mutation that tries to put private text in the URL). Failures render frontend-owned Bulgarian text and never the backend text or a network diagnostic; a missing, foreign, or malformed Appointment shows the same not-found state; a lost Business context uses the shared recovery. A late answer of a previous Business, date, view, or filter never replaces newer data. `version` and `cancelledBy` are not displayed.

### Manual-create read routes authorization (issue #21, discovered Phase 8 prerequisite)

`GET /api/business/appointment-options` and `GET /api/business/appointment-options/availability` are owner-only and derive the Business and the user only from the authenticated context (a `businessId` parameter is ignored). Anonymous is `401 AUTH_REQUIRED`; no selection, platform authority alone, and an inactive, missing, or foreign Membership are `403 ACTIVE_BUSINESS_REQUIRED`; an active Manager or Staff is `403 ACCESS_DENIED`. Only an `ACTIVE` Business offers choices (`409 BUSINESS_NOT_ACTIVE` for `DRAFT`, `409 BUSINESS_SUSPENDED`), checked before any Service or StaffMember is read. A missing, foreign, and inactive Service are one byte-identical `409 APPOINTMENT_SERVICE_UNAVAILABLE`, and a missing, foreign, inactive, and unassigned StaffMember one byte-identical `409 APPOINTMENT_STAFF_UNAVAILABLE`; every malformed request is `400 VALIDATION_ERROR` and echoes nothing. Reads need no CSRF token, answer `Cache-Control: no-store`, carry the fixed problem `instance` `/api/business/appointment-options`, and contain only StaffMember identifiers and names, the summed duration, and instants: no Customer, contact, note, price, or Appointment datum, in a URL, a response, a problem, or a log.

### Manual-create frontend privacy and authorization (issue #21 Phase 8)

The create flow is owner-only in the interface as in the API. The control exists only for the owner of an `ACTIVE` Business, the route redirects a `DRAFT` or `SUSPENDED` Business, a Manager, a Staff, and a platform-only session, and none of this is trusted: every refusal of the server (`ACCESS_DENIED`, `ACTIVE_BUSINESS_REQUIRED`, `BUSINESS_NOT_ACTIVE`, `BUSINESS_SUSPENDED`) blocks the confirmation. The form lives only in the memory of one mounted component, keyed by the active Business and dropped on a Business switch, on leaving, and after a success; the Customer search term travels only in the POST body of the existing search; the route carries only the calendar state; and no Customer, contact, note, Service claim, price, or attempt state is written to a URL, `history.state`, the title, local or session storage, cookies, or the console (asserted, with a mutation check). The request is exactly the Phase 4 contract with one POST per deliberate confirmation and no automatic repeat; a response that never arrived, an unreadable success, and any result that does not rule the creation out are unresolved: the form freezes (no send, edit, or reset), because the create has no idempotency, and the only way on is the calendar, where the owner checks whether the Appointment exists. A known rollback may be retried explicitly. Failures render frontend-owned Bulgarian text, never the backend text, an identifier, or SQL.
