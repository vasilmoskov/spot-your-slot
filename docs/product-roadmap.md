# SpotYourSlot product roadmap

## Purpose

This roadmap tracks the business functionality needed for SpotYourSlot to serve
one real appointment-based service Business, learn from its use, and then add
capabilities with clear operational value. It records complete product outcomes,
not partial implementation work.

## Status legend

- `✅ Implemented` — the complete business capability is committed and verified.
- `🚧 In progress` — implementation has started, but the complete business
  capability is not yet available and verified.
- `⬜ Todo` — implementation of the complete business capability has not started.

## MVP roadmap

| Order | Feature | Business value | Status | Completed | Related issue |
| ----- | ------- | -------------- | ------ | --------- | ------------- |
| 1 | Secure accounts and access | Gives authorized users secure login, logout, password recovery, profile management, and isolated Business access. | ✅ Implemented | 2026-08-17 | [#3](https://github.com/vasilmoskov/spot-your-slot/issues/3) |
| 2 | Controlled Business onboarding and lifecycle | Lets the platform create a Business, securely onboard its first owner, and activate, suspend, or reactivate it without self-service privilege escalation. | ✅ Implemented | 2026-09-14 | [#4](https://github.com/vasilmoskov/spot-your-slot/issues/4) |
| 3 | Business details configuration | Lets the platform configure the Business name, type, timezone, contact details, and unique booking identity required for later public booking. | ✅ Implemented | 2026-09-14 | [#4](https://github.com/vasilmoskov/spot-your-slot/issues/4) |
| 4 | Services | Lets a Business manage active and inactive Services with configurable default durations and EUR prices, such as separate haircut, beard, or eyebrow Services. | 🚧 In progress | — | [#11](https://github.com/vasilmoskov/spot-your-slot/issues/11), [#14](https://github.com/vasilmoskov/spot-your-slot/issues/14), [#15](https://github.com/vasilmoskov/spot-your-slot/issues/15) |
| 5 | Staff and Service assignments | Lets a Business manage multiple Staff members and define which active Services each person performs. | 🚧 In progress | — | [#12](https://github.com/vasilmoskov/spot-your-slot/issues/12), [#14](https://github.com/vasilmoskov/spot-your-slot/issues/14), [#15](https://github.com/vasilmoskov/spot-your-slot/issues/15) |
| 6 | Working schedules and exceptions | Lets each Staff member work multiple weekdays and split periods while accounting for required time off and exceptional working dates. | ✅ Implemented | 2026-09-30 | [#13](https://github.com/vasilmoskov/spot-your-slot/issues/13), [#14](https://github.com/vasilmoskov/spot-your-slot/issues/14), [#15](https://github.com/vasilmoskov/spot-your-slot/issues/15), [#16](https://github.com/vasilmoskov/spot-your-slot/issues/16) |
| 7 | Availability and booking horizon | Shows valid slots using Service duration, configurable booking buffers, qualified Staff, working schedules, schedule exceptions and time off, existing appointments, and each Business's configurable future booking horizon. | 🚧 In progress | — | [#16](https://github.com/vasilmoskov/spot-your-slot/issues/16) |
| 8 | Public Business booking page | Gives each Business a mobile-friendly page where Customers can view Services and choose a Service, Staff preference, date, and available slot. Issue #17 delivers only its read-only profile part, which is implemented and browser-verified: stable public slugs, the unauthenticated read API and the public profile page at `/{slug}` with Services. Service and Staff selection, availability, slot choice, and guest booking were delivered afterwards by issue #18 (closed; see row 11); the row stays in progress because production public booking must not go live before the Business calendar (row 12, issue #21) exists. | 🚧 In progress | — | [#17](https://github.com/vasilmoskov/spot-your-slot/issues/17) |
| 9 | Customer records | Maintains the minimum Business-scoped Customer information needed for appointments and safe recognition of returning Customers. Complete and committed, and Issue #20 is closed (decisions in ADR-0019 to ADR-0021): the shared contact policy, `V10` schema, internal persistence, conservative matching, the private owner-only administration API, the Business-owner interface (list, live search, create, detail, edit; a SUSPENDED Business may view and update existing Customers but not create new ones) with human visual approval, and the browser E2E journeys. Guest booking in #18 consumes the matching contract. Customer appointment history and manual-appointment Customer selection remain with #21; Customer accounts, merge, deletion, anonymization, and retention automation are not part of this capability. | ✅ Implemented | 2026-10-14 | [#20](https://github.com/vasilmoskov/spot-your-slot/issues/20) |
| 10 | Appointment conflict protection | Prevents double booking and gives the Customer a safe, understandable response when a selected slot is no longer available. Issue #18 (closed) delivered the overlap protection for guest creation (the `appointment` table and its exclusion constraint, [ADR-0022](decisions/ADR-0022-model-appointments-with-snapshots-two-statuses-and-a-database-overlap-exclusion.md)); manual creation, move, reassignment, and authenticated conflict feedback remain for #19 and #21, so this capability stays incomplete. Issues #19 and #21 are now one combined delivery (decisions recorded in [ADR-0028](decisions/ADR-0028-administer-appointments-through-a-private-owner-only-api-with-immutable-visit-identity-and-business-cancellation.md) and [ADR-0029](decisions/ADR-0029-calculate-business-availability-with-a-separate-policy-and-serialize-appointment-mutations-with-ordered-locks-optimistic-versions-and-bounded-retry.md), plan in [task 09a](tasks/09a-business-calendar-and-appointment-management.md); no implementation yet): #19 closes first, after the Business-side browser evidence, and #21 immediately afterwards. | 🚧 In progress (guest-creation protection delivered and verified by #18; the Business-side criteria are decided, not implemented) | — | [#19](https://github.com/vasilmoskov/spot-your-slot/issues/19), [#18](https://github.com/vasilmoskov/spot-your-slot/issues/18) |
| 11 | Guest booking | Lets a Customer book without an account using only required contact information and an optional note, with appropriate protection from repeated abusive submissions. Delivered by issue #18 (closed as completed; Project item Done): decisions ([task 08a](tasks/08a-appointment-core-and-guest-booking.md), ADR-0022 to ADR-0026), the Appointment schema with its overlap exclusion, the schedule revision guard, the transactional idempotent booking, the public API with bounded abuse protection, the public guest-booking frontend, and, decided on 2026-10-07 ([ADR-0027](decisions/ADR-0027-book-several-services-as-one-atomic-visit-with-service-lines-a-versioned-set-fingerprint-and-an-explicit-review-consistency-check.md), [task 08b](tasks/08b-multi-service-guest-booking-plan.md)), booking of one to five Services as one atomic visit (M1 to M7, all committed; M6 received explicit human visual approval). Exact-HEAD CI passed for `1d6b45099c1aebfb9bd6fc444dc171d228dca9e1`; recorded final local evidence: backend 3476 tests, frontend 76 files / 1841 tests, and two consecutive full browser E2E runs of 110/110. Production release of public booking still depends on row 12 (issue #21); issue #19 remains incomplete, and cancellation, notifications, manual appointment management, and Customer appointment history remain deferred. | ✅ Implemented | 2026-10-14 | [#18](https://github.com/vasilmoskov/spot-your-slot/issues/18) |
| 12 | Business calendar and appointment management | Lets authorized Business users view schedules and create, move, or cancel appointments received online, by phone, or in person. Release dependency: production public booking (row 11) must not go live before this capability lets a Business see and manage its appointments. Decided for the Business owner only (ADR-0028, ADR-0029, [task 09a](tasks/09a-business-calendar-and-appointment-management.md)): day, week, and agenda views, manual creation of a visit of one to five Services, rescheduling and reassignment by form, an internal note, Business-side cancellation, and a Customer appointment history. Drag-and-drop is a separate future issue; Manager and Staff access is a separate future capability. | 🚧 In progress (decisions recorded; persistence and availability foundation, the calendar and Customer-history read APIs, the owner-created multi-Service Appointment API, and the form-based reschedule and reassignment API implemented; cancellation, the note edit, the options route, and the interface are not) | — | [#21](https://github.com/vasilmoskov/spot-your-slot/issues/21) |
| 13 | Customer cancellation | Lets a Customer securely cancel the correct future appointment, applies the approved 24-hour default and late-cancellation marking, and protects private appointment and Business information. Business-side cancellation (no reason, with `cancelled_at` and `cancelled_by`) belongs to row 12; the `CUSTOMER` actor and the late flag arrive with this row. | ⬜ Todo | — | [#35](https://github.com/vasilmoskov/spot-your-slot/issues/35) |
| 14 | Confirmations and reminders | Confirms bookings and cancellations, informs the relevant Business or Staff member, and sends essential appointment reminders. | ⬜ Todo | — | — |

## Nice-to-have roadmap

| Order | Feature | Business value | Status | Completed | Related issue |
| ----- | ------- | -------------- | ------ | --------- | ------------- |
| 1 | Customer self-service rescheduling | Lets a Customer move an appointment once to a valid available slot when policy conditions are satisfied, while preventing conflicts and notifying affected parties. | ⬜ Todo | — | — |
| 2 | Customer-specific Service durations | Lets a Business adjust a Service's usual duration for a returning Customer so future availability reflects the time normally required. | ⬜ Todo | — | — |
| 3 | Cancellation and no-show policies | Helps a Business apply configurable cancellation cutoffs, record no-shows, and respond consistently to repeated booking abuse. | ⬜ Todo | — | — |
| 4 | Expanded Business roles | Gives managers and Staff members appropriate operational access beyond the initial owner-managed workflow. Includes Manager and Staff calendar access and the optional Membership-to-StaffMember link, deferred from issue #21. | ⬜ Todo | — | — |
| 5 | Customer accounts and identity verification | Lets Customers optionally verify their identity, reuse their details, and view appointment history without making registration mandatory. | ⬜ Todo | — | — |
| 6 | Additional notification channels | Adds optional SMS, push, or in-app notifications after the essential email flow is established. | ⬜ Todo | — | — |
| 7 | Online payments and deposits | May reduce no-shows through deposits or prepayment once product value, cancellation rules, and refund expectations are validated. | ⬜ Todo | — | — |
| 8 | Customer data export and anonymization | Helps Businesses respond safely to appropriate Customer data access and deletion requests. | ⬜ Todo | — | — |
| 9 | Multiple Business locations | Lets a validated Business operate from more than one physical location while keeping appointments and availability clear. | ⬜ Todo | — | — |

## Open product decisions

- Customer identity matching is decided for issue #20 (ADR-0020): any partial match is
  rejected for explicit owner correction. Decided by issue #18 (ADR-0022): an Appointment keeps no
  snapshot of Customer contact data.
- Decide whether the approved 24-hour cancellation default and late-cancellation
  marking need different Business-configurable cutoff or fairness rules.
- Define how no-shows affect future booking and what restrictions a Business may
  apply.
- Define the cutoff and policy conditions for Customer self-service rescheduling,
  including whether the first change is automatic or approval-based, and require
  Business or Staff involvement for further changes.
- Decide whether Staff-created appointments follow the same Customer self-service
  rules as online bookings.
- Decide when Customer accounts or verified email or phone become necessary.
- Decide whether and when payments or deposits provide enough validated value.

## Maintenance rules

- Update status when the complete business capability changes state.
- Add a completion date only after the functionality is committed and verified.
- Do not mark a feature complete from a partial backend, frontend, or test
  subtask.
- Keep technical detail in implementation tasks, ADRs, and issues.
- Preserve completed dates.
- Reorder remaining items only when product priorities or dependencies change.
- Add new functionality only when it provides clear user or Business value.
