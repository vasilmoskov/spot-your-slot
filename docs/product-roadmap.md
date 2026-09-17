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
| 5 | Staff and Service assignments | Lets a Business manage multiple Staff members and define which active Services each person performs. | ⬜ Todo | — | [#12](https://github.com/vasilmoskov/spot-your-slot/issues/12), [#14](https://github.com/vasilmoskov/spot-your-slot/issues/14), [#15](https://github.com/vasilmoskov/spot-your-slot/issues/15) |
| 6 | Working schedules and exceptions | Lets each Staff member work multiple weekdays and split periods while accounting for required time off and exceptional working dates. | ⬜ Todo | — | [#13](https://github.com/vasilmoskov/spot-your-slot/issues/13), [#14](https://github.com/vasilmoskov/spot-your-slot/issues/14), [#15](https://github.com/vasilmoskov/spot-your-slot/issues/15) |
| 7 | Availability and booking horizon | Shows valid slots using Service duration, configurable booking buffers, qualified Staff, working schedules, schedule exceptions and time off, existing appointments, and each Business's configurable future booking horizon. | ⬜ Todo | — | — |
| 8 | Public Business booking page | Gives each Business a mobile-friendly page where Customers can view Services and choose a Service, Staff preference, date, and available slot. | ⬜ Todo | — | — |
| 9 | Customer records | Maintains the minimum Business-scoped Customer information needed for appointments and safe recognition of returning Customers. | ⬜ Todo | — | — |
| 10 | Appointment conflict protection | Prevents double booking and gives the Customer a safe, understandable response when a selected slot is no longer available. | ⬜ Todo | — | — |
| 11 | Guest booking | Lets a Customer book without an account using only required contact information and an optional note, with appropriate protection from repeated abusive submissions. | ⬜ Todo | — | — |
| 12 | Business calendar and appointment management | Lets authorized Business users view schedules and create, move, or cancel appointments received online, by phone, or in person. | ⬜ Todo | — | — |
| 13 | Customer cancellation | Lets a Customer securely cancel the correct future appointment, applies the approved 24-hour default and late-cancellation marking, and protects private appointment and Business information. | ⬜ Todo | — | — |
| 14 | Confirmations and reminders | Confirms bookings and cancellations, informs the relevant Business or Staff member, and sends essential appointment reminders. | ⬜ Todo | — | — |

## Nice-to-have roadmap

| Order | Feature | Business value | Status | Completed | Related issue |
| ----- | ------- | -------------- | ------ | --------- | ------------- |
| 1 | Customer self-service rescheduling | Lets a Customer move an appointment once to a valid available slot when policy conditions are satisfied, while preventing conflicts and notifying affected parties. | ⬜ Todo | — | — |
| 2 | Customer-specific Service durations | Lets a Business adjust a Service's usual duration for a returning Customer so future availability reflects the time normally required. | ⬜ Todo | — | — |
| 3 | Cancellation and no-show policies | Helps a Business apply configurable cancellation cutoffs, record no-shows, and respond consistently to repeated booking abuse. | ⬜ Todo | — | — |
| 4 | Expanded Business roles | Gives managers and Staff members appropriate operational access beyond the initial owner-managed workflow. | ⬜ Todo | — | — |
| 5 | Customer accounts and identity verification | Lets Customers optionally verify their identity, reuse their details, and view appointment history without making registration mandatory. | ⬜ Todo | — | — |
| 6 | Additional notification channels | Adds optional SMS, push, or in-app notifications after the essential email flow is established. | ⬜ Todo | — | — |
| 7 | Online payments and deposits | May reduce no-shows through deposits or prepayment once product value, cancellation rules, and refund expectations are validated. | ⬜ Todo | — | — |
| 8 | Customer data export and anonymization | Helps Businesses respond safely to appropriate Customer data access and deletion requests. | ⬜ Todo | — | — |
| 9 | Multiple Business locations | Lets a validated Business operate from more than one physical location while keeping appointments and availability clear. | ⬜ Todo | — | — |

## Open product decisions

- Define exact Customer identity matching when phone or email is missing, shared,
  or changed, while keeping Customers isolated by Business.
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
