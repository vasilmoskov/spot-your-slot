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

The exact origin permits credentialed `GET`, `POST`, `PUT`, and `OPTIONS`
requests with only `Content-Type` and `X-XSRF-TOKEN` request headers. POST and
PUT remain CSRF-protected. Filter-level missing-authentication and
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
`BUSINESS_INVALID_LIFECYCLE` (“Промяната на статуса не е разрешена.”),
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
