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

Repositories require `business_id`; foreign/composite constraints validate
common ownership. STAFF is restricted to the linked StaffMember’s schedule and
Appointments. Cross-Business attempts return safe 404 where existence need not
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

Decided in ADR-0017 and ADR-0018; the backend contract is implemented and the public
frontend is not. One unauthenticated
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
each active Service's name, description, duration, and EUR price. `contact_email`,
timezone, status, identifiers, versions, timestamps, owner and Membership data,
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
structured Bulgarian errors. Reject `COMPLETED` and `NO_SHOW` before Appointment
start so a future slot cannot be released early.

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

### Customer administration and privacy (issue #20; persistence, matching, and the private API are implemented)

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
