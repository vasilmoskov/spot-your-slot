# API route reference

An index of the HTTP routes the backend serves, with the purpose of each. It is deliberately brief: request and response
shapes, validation bounds, error codes and authorization rules live with the capability that defines them, in
[security.md](security.md), the [ADRs](decisions/README.md) and the tests. OpenAPI is development-only.

## Conventions

- **Format.** JSON requests and responses. Errors are RFC 7807 problem bodies with a stable `code` and a Bulgarian
  `detail`; they never contain SQL, another tenant's identifiers or submitted values.
- **How a route knows the Business.**
  - **`/api/business/...` (Business owner):** the Business and the user come from the server-side session. These routes
    take no Business identifier in the path, query or body, so a client cannot choose which Business it acts on.
    Resources of another Business are indistinguishable from missing ones.
  - **`/api/platform/...` (platform administrator):** administration explicitly addresses Businesses by identifier
    (`{businessId}`), and requires the `PLATFORM_ADMIN` role. That role alone grants no access to a Business's private
    data through the `/api/business` routes.
  - **`POST /api/auth/business`:** the one session endpoint that accepts a Business identifier. It only selects a
    Business the user is already authorized for (an active Membership) as the session's current Business.
  - **`/api/public/businesses/{slug}/...`:** the public Business is resolved from its slug and only an allowlisted
    public contract is exposed; no session is involved.
- **CSRF.** Unsafe methods (`POST`, `PUT`, `DELETE`) need the `X-XSRF-TOKEN` header from `GET /api/auth/csrf` plus the
  session cookie. The only exception is `POST /api/public/businesses/{slug}/bookings`.
- **Versions.** Mutations of versioned records carry `expectedVersion`; a stale version is a 409.
- **Lists** use server-side sorting with a deterministic tie-breaker and, where paginated, a `size` of exactly 10, 25 or 50.

## Authentication and identity

| Method and path | Purpose |
|---|---|
| `GET /api/auth/csrf` | CSRF token for unsafe requests |
| `POST /api/auth/login` | log in |
| `POST /api/auth/logout` | log out |
| `GET /api/auth/session` | the current user and authorized Businesses |
| `POST /api/auth/business` | select an already-authorized Business |
| `POST /api/auth/profile` | update the authenticated user's display name |
| `POST /api/auth/password/change` | change password and revoke other sessions |
| `POST /api/auth/password/forgot` | start a password reset |
| `POST /api/auth/password/reset` | complete a password reset |
| `POST /api/auth/invitations/accept` | accept an owner invitation |
| `POST /api/platform/identity/businesses/{businessId}/owner-invitation` | create or replace an owner invitation (platform administrator) |
| `GET /api/dev/mailbox` | development-only invitation and reset links (platform administrator; absent in production) |

## Platform administration (`PLATFORM_ADMIN`)

| Method and path | Purpose |
|---|---|
| `GET /api/platform/businesses` | list Businesses (`page`, `size`, `sort` of `displayName`, `slug`, `businessType` or `status`, `direction`) |
| `GET /api/platform/businesses/{businessId}` | one Business |
| `POST /api/platform/businesses` | create a `DRAFT` Business |
| `PUT /api/platform/businesses/{businessId}` | update the profile |
| `POST /api/platform/businesses/{businessId}/activate` | `DRAFT → ACTIVE` (needs an active owner Membership) |
| `POST /api/platform/businesses/{businessId}/suspend` | `ACTIVE → SUSPENDED` |
| `POST /api/platform/businesses/{businessId}/reactivate` | `SUSPENDED → ACTIVE` |

## Business owner configuration

| Method and path | Purpose |
|---|---|
| `GET /api/business/services` | list Services |
| `POST /api/business/services` | create a Service |
| `GET /api/business/services/{serviceId}` | one Service |
| `PUT /api/business/services/{serviceId}` | update a Service |
| `POST /api/business/services/{serviceId}/deactivate` | deactivate a Service |
| `POST /api/business/services/{serviceId}/reactivate` | reactivate a Service |
| `GET /api/business/staff-members` | list StaffMembers |
| `POST /api/business/staff-members` | create a StaffMember |
| `GET /api/business/staff-members/{staffMemberId}` | one StaffMember |
| `PUT /api/business/staff-members/{staffMemberId}` | update a StaffMember |
| `POST /api/business/staff-members/{staffMemberId}/deactivate` | deactivate a StaffMember |
| `POST /api/business/staff-members/{staffMemberId}/reactivate` | reactivate a StaffMember |
| `GET /api/business/staff-members/{staffMemberId}/service-assignments` | the assigned Services |
| `PUT /api/business/staff-members/{staffMemberId}/service-assignments` | atomically replace the assigned Services |
| `GET /api/business/staff-members/{staffMemberId}/working-schedule` | the recurring weekly schedule |
| `PUT /api/business/staff-members/{staffMemberId}/working-schedule` | atomically replace the weekly schedule |
| `GET /api/business/schedule-exceptions` | list exceptions overlapping a `from`/`to` window of at most 93 dates |
| `POST /api/business/schedule-exceptions` | create a closure, time off, working-day override or additional working periods |
| `GET /api/business/schedule-exceptions/{exceptionId}` | one exception |
| `PUT /api/business/schedule-exceptions/{exceptionId}` | atomically replace its dates, all-day flag and periods |
| `DELETE /api/business/schedule-exceptions/{exceptionId}` | delete (takes `expectedVersion` as a query parameter) |
| `GET /api/business/customers` | list Customers |
| `POST /api/business/customers/search` | body-based Customer search |
| `POST /api/business/customers` | create a Customer |
| `GET /api/business/customers/{customerId}` | one Customer |
| `PUT /api/business/customers/{customerId}` | update a Customer |

`SUSPENDED` Businesses remain readable and reject mutations (existing Customers can still be updated).

## Business calendar and Appointments

| Method and path | Purpose |
|---|---|
| `GET /api/business/calendar` | Appointments and working time for at most 31 Business-local dates (`from`, `to`, optional `staffMemberId`) |
| `GET /api/business/appointments` | the same facts as a paginated agenda |
| `GET /api/business/appointments/{appointmentId}` | one Appointment with Customer contact and notes |
| `GET /api/business/customers/{customerId}/appointments` | a Customer's history (`direction`, optional Business-local `date`) |
| `GET /api/business/appointment-options` | StaffMembers who can perform the chosen Services, with the summed duration |
| `GET /api/business/appointment-options/availability` | the offered starts of one StaffMember on one date (advisory; reserves nothing) |
| `POST /api/business/appointments` | create a visit of one to five Services with one StaffMember |
| `GET /api/business/appointments/{appointmentId}/reschedule-options` | the StaffMember choices behind the reschedule form |
| `GET /api/business/appointments/{appointmentId}/reschedule-options/availability` | the offered starts for a reschedule |
| `POST /api/business/appointments/{appointmentId}/reschedule` | move and/or reassign in one atomic request |
| `POST /api/business/appointments/{appointmentId}/cancel` | cancel (history kept, time released) |
| `PUT /api/business/appointments/{appointmentId}/internal-note` | edit the private internal note |

## Public (no session)

| Method and path | Purpose |
|---|---|
| `GET /api/public/businesses/{slug}` | the allowlisted profile of an `ACTIVE` Business with its active Services |
| `GET /api/public/businesses/{slug}/booking-options` | for a repeated `serviceId` (one to five), the eligible StaffMembers |
| `GET /api/public/businesses/{slug}/availability` | for the same selection, the available starts of one date |
| `GET /api/public/businesses/{slug}/services/{serviceId}/booking-options` | the single-Service form of the options read |
| `GET /api/public/businesses/{slug}/services/{serviceId}/availability` | the single-Service form of the availability read |
| `POST /api/public/businesses/{slug}/bookings` | book one visit; idempotent by attempt identifier; guarded by the bounded public limiter |

The booking `POST` is the only public route exempt from CSRF protection. Public routes do not reveal more about a
Business or an Appointment than the public contract states
([ADR-0026](decisions/ADR-0026-expose-guest-booking-through-narrow-public-contracts-with-session-independent-endpoints-and-bounded-abuse-protection.md)).
