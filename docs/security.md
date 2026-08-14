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
contain 12–128 Unicode code points without composition rules or truncation.
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

## Authorization and tenant isolation

Every protected use case requires authentication, active Membership in the
server-resolved Business, and the required role. PLATFORM_ADMIN routes are
separate. Public Business context comes from `{businessSlug}`; authenticated
context comes from Membership. Client Business IDs are untrusted references.

Repositories require `business_id`; foreign/composite constraints validate
common ownership. STAFF is restricted to the linked StaffMember’s schedule and
Appointments. Cross-Business attempts return safe 404 where existence need not
be disclosed, or 403 for an established Business context. Tests cover Business
A versus Business B reads, lists, writes, indirect IDs, and bulk operations.

DRAFT rejects public booking but allows configuration. ACTIVE allows booking
and authorized mutations. SUSPENDED rejects booking, preserves data, and makes
Business administration read-only except logout/account-security; PLATFORM_ADMIN
may reactivate it.

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
