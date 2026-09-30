# SpotYourSlot — Availability, Schedule Exceptions, and Slot Calculation

Status: In progress — Phases 1–4 committed; Phase 5 implemented, awaiting human visual review
GitHub issue: #16 — Build availability, schedule exceptions, and slot calculation engine
Depends on: #11, #12, #13
Decision records: [ADR-0013](../decisions/ADR-0013-define-availability-interval-precedence-grid-and-dst-semantics.md),
[ADR-0014](../decisions/ADR-0014-store-schedule-exceptions-as-versioned-aggregates-with-same-kind-date-exclusion.md),
[ADR-0015](../decisions/ADR-0015-administer-schedule-exceptions-through-a-versioned-business-owner-api.md),
[ADR-0016](../decisions/ADR-0016-orchestrate-availability-through-published-contracts-and-a-scheduling-owned-busy-interval-seam.md)

## Task purpose

Calculate the appointment start times a Business can genuinely offer and let a
Business owner maintain temporary schedule changes. Issue #16 is Strict risk.
Each phase needs separate explicit approval before persistent changes.

## Approved architecture

A new `scheduling` module owns availability calculation, Business closures,
StaffMember time off, working-day overrides, and additional working periods.
Later orchestration may depend only on narrow published contracts from
Business, Catalog, and Workforce. `scheduling` never depends on `booking`; the
`BusyIntervalSource` interface is owned by `scheduling` and the real
implementation belongs to the future Booking/Appointment issue. No `booking`
module or appointment code exists yet; a temporary placeholder supplies no busy
time (Phase 4).

## Approved semantics

Recorded in ADR-0013: effective working periods, blocking precedence,
half-open intervals, unmerged segments, the wall-clock 15-minute grid,
minimum notice, the 30-date horizon, DST gap and overlap resolution, and
per-StaffMember calculation with deterministic aggregation.

Fixed MVP policy values: 15-minute grid, two-hour minimum notice inclusive,
Business-local dates today through today + 29, zero buffers.

## Phases

| Phase | Outcome | Status |
|---|---|---|
| 1 | Task record, ADR-0013, `scheduling` skeleton, pure engine, unit tests | Implemented; awaiting review |
| 2 | Exception schema and persistence (V9, ADR-0014) | Implemented; awaiting review |
| 3 | Exception administration API (ADR-0015) | Implemented; awaiting review |
| 4 | Availability orchestration and published contracts (ADR-0016) | Committed (`dcd32d0`) |
| 5 | Business-owner interface ("Промени в графика") | Implemented; awaiting human visual review |
| 6 | Documentation and acceptance | Not started |

Phase numbering follows the approved Phase 1 direction (persistence is Phase 2)
and supersedes the earlier orientation numbering that placed Business settings
first. Business-configurable horizon and minimum notice are a recorded
follow-up and need their own approval.

## Phase 1 scope

Delivered: `bg.spotyourslot.scheduling` and `scheduling.domain`, the pure
`AvailabilityEngine`, immutable input and output records, and JUnit tests.

Explicitly excluded: Flyway migrations, persistence, controllers or any HTTP
contract, frontend, dependencies, changes to Business, Catalog, Workforce, or
Identity code, appointments, a public availability endpoint, Business
configuration of horizon, notice, or buffers, and any GitHub mutation.

## Domain types (internal to `scheduling.domain`)

- `AvailabilityEngine` — `calculate(request)` aggregates every StaffMember;
  `calculateStarts(request, staff)` calculates one.
- `AvailabilityRequest` — zone, occupied duration, `now`, Business closures,
  eligible StaffMembers.
- `StaffAvailabilityInput` — recurring periods, overrides, additional periods,
  time off, busy intervals.
- `LocalPeriod` (whole-minute precision and start before end are enforced by the
  value object), `LocalBlock` (`FullDays`, `PartialDay`), `BusyInterval`.
- `AvailableSlot` — start, end, start offset, StaffMember IDs.
- `AvailabilityPolicy` — fixed MVP constants; `DayTimeline` is package-private.

Phase 1 published nothing outside the module; Phase 4 adds the published
contract described below.

## Phase 2 scope

Delivered: `V9__add_schedule_exceptions.sql`, ADR-0014, immutable stored-aggregate
records and the pure `ScheduleExceptionInputs` translator in `scheduling.domain`,
the internal `ScheduleExceptionStore` in `scheduling.infrastructure`, and
PostgreSQL schema, store, and concurrency tests. Explicitly excluded:
administration service, authorization, controllers, frontend, availability
orchestration, `BusyIntervalSource`, and Business configuration.

## Phase 3 scope

Delivered: the private Business-owner API under
`/api/business/schedule-exceptions` (list by inclusive date window, get, create,
atomic replace with `expectedVersion`, and hard delete with `expectedVersion`)
for the four exception kinds; the `scheduling` published
`ScheduleExceptionAdministration`; bounded application validation; strict
`yyyy-MM-dd` and `HH:mm` JSON parsing; the narrow published
`workforce.StaffMemberReferenceAccess`; `DELETE` in the credentialed CORS
methods; and the tests listed in `docs/testing-strategy.md`.

Final validation limits (fixed MVP technical safety boundary, unrelated to the
booking horizon): dates `2000-01-01` through `2100-12-31`, at most 366 dates for
a full-day span, at most 24 periods, and at most 93 dates in a list window.

Lock order: Business lifecycle row, exact owner Membership row, StaffMember row
(StaffMember-scoped kinds), then the conditional aggregate statement. Inactive
StaffMembers are read-only, so deleting their exception requires temporary
reactivation. The list is unpaginated and assumes the small-Business MVP; that
is a revisit condition if StaffMember volume or response size becomes material.

Explicitly excluded: availability orchestration, a public availability endpoint,
`BusyIntervalSource`, appointments, frontend, Business-configurable notice,
horizon, grid, or buffers, and any migration.

## Phase 4 scope

Delivered (ADR-0016): an internal published contract
`scheduling.AvailabilityQuery.calculate(businessId, serviceId,
staffMemberIdOrNull)` returning an immutable `AvailabilitySnapshot` (timezone,
`calculatedAt`, occupied duration, slots with start, end, start offset, and
naturally ordered StaffMember IDs); the published
`catalog.ServiceAvailabilityAccess` and `workforce.StaffAvailabilityAccess`
(one joined query); the reused `BusinessScheduleContextAccess`; the sealed
`AvailabilityApplicationException` family (`BusinessNotBookable`,
`ServiceNotBookable`, `StaffMemberNotEligible`, `AvailabilityFailure`); the
Scheduling-owned `BusyIntervalSource`; and the temporary
`NoBookingBusyIntervalSource`.

The orchestration creates a read-only repeatable-read transaction when called
standalone; inside an existing transaction it requires that transaction to be
repeatable-read or serializable and otherwise fails before any read. It performs
no writes or explicit locks (a joined outer transaction may be read-write), reads
the clock once, and issues four application SQL statements (Business,
Service, eligible StaffMembers with periods, exceptions) regardless of team
size; the busy call is a bulk read that currently issues none. The date window is
today through today + 29 and the busy-interval window uses zone-rule midnights.
The engine and `ScheduleExceptionInputs` are unchanged.

`NoBookingBusyIntervalSource` is a required ordinary bean. The real
implementation belongs to the future Booking/Appointment issue, not to issue #16;
when it is added, two beans make startup fail until the placeholder and its
wiring test are deleted. The real implementation must join the caller's
transaction and answer all StaffMembers with one bulk query.

Availability is a current view that reserves nothing. Booking must revalidate.
DRAFT and SUSPENDED Businesses are not bookable through this contract.

Explicitly excluded: any controller, public DTO, security rule, slug lookup, rate
limiting, CORS or CSRF change, frontend, booking, Appointments, migrations,
dependencies, and Business-configurable horizon, notice, grid, or buffers. No
public availability HTTP endpoint exists; a future public-profile/booking phase
adapts the contract.

## Phase 5 scope

Delivered: the Business-owner interface for the four kinds of date-specific
change, consuming the committed private API unchanged (no backend, migration, or
dependency change). "Работно време" keeps one sidebar item, one page heading
("Работно време"), and two tabs: "Седмични графици" (`#/business/schedule`,
unchanged) and "Промени в графика", whose active state names the subsection.
Create and detail pages are headed "Нова промяна в графика" and "Промяна в
графика"; actions are "Добави промяна" (list), "Добави" (create submit), and
"Обратно към графика". The shared unsaved-edit dialog now reads "Имате
незапазени промени." / "Ако напуснете, те ще бъдат загубени." with "Остани"
and "Напусни", and is used by every authenticated create/edit form (the platform
Business create and edit forms and the owner-invitation email were newly guarded).

Routes: `#/business/schedule/exceptions[?from=&to=]`, `.../exceptions/new[?from=&to=]`,
and `.../exceptions/{id}[?from=&to=]`, with strict canonical parsing (an unusable
window is discarded). The optional `from`/`to` on create and detail are the
*return window*: every path back to the list (Back, cancel, create, edit, delete,
conflict reload, browser history) restores it exactly; a URL without one returns
to the canonical default. Opening create or detail is ordinary push navigation,
automatic canonicalization replaces, and a Business switch drops the previous
Business's window from every schedule route.

Kinds (backend enum names never shown), presented in one "Вид промяна" fieldset
with two labelled groups:

- За целия бизнес: "Неработно време" — "Блокира резервациите за всички членове на
  екипа през избрания период."
- За член на екипа: "Отсъствие" — "Блокира резервациите за избрания член на
  екипа."; "Променени работни часове" — "Заменя обичайния седмичен график за
  избраната дата."; "Допълнителни работни часове" — "Добавя часове към обичайния
  график за избраната дата." A StaffMember (active only) is required.

- **Dates** — one shared presentation (`DatePeriodFields`) for the list filter and
  the forms: a range is "От" and "До" with each label above its input (side by
  side when wide, stacked when narrow; "Период" is only an accessible group name),
  a single date is "Дата" above its input, and the list's "Покажи" is aligned
  with the inputs. Validation, bounds, focus and ARIA are unchanged.
- **List** — responsive table (cards below 64rem) of kind, date or inclusive range,
  StaffMember name, and hours ("Цял ден", `09:00–12:00, 14:00–18:00`, "Неработен
  ден"). Every StaffMember page (size 50, one shared `AbortSignal`) is loaded so
  names resolve, including inactive ones. The default window is the Business-local
  today through today + 29; the first request is provisional (browser-local date)
  and the canonical window *replaces* the route, refetching once only if it
  differs. Explicit filter changes *push*.
- **Create** — progressive: choose the kind, then only its fields. Closures and
  time off: whole dates (range) or part of one date with periods; an override is
  "Неработен ден" (zero periods) or "Работни часове"; additional hours always need
  periods. Periods reuse the weekly-schedule editor's row, ✎/× controls, "+ Добави"
  action, and dialog: one period per row, always sorted, strict HH:mm, no
  reversed/duplicate/overlapping periods, adjacency allowed, at most 24.
- **Detail/edit/delete** — kind and StaffMember are read-only context; edit sends
  `expectedVersion`; a conflict offers the guarded "Зареди актуалните данни". Delete
  is a separate hard-delete action behind a confirmation dialog (safe action
  focused, pending state, `expectedVersion`).
- **Lifecycle** — DRAFT/ACTIVE mutate; SUSPENDED shows the shared banner only and
  hides mutations; an inactive StaffMember's records stay readable with mutations
  unavailable and a one-line explanation.
- Every dirty form and period dialog uses the shared unsaved-changes guard,
  including a confirmed discard that stays on the same route (the form is reset).

Terminology and conflict feedback: the Business-owner UI never uses the internal
word "изключение"; the enum, API, and database names are unchanged. What the
owner sees, and what each kind means:

| Kind (UI label) | Explanation (one paragraph per sentence) |
| --- | --- |
| Неработно време | Блокира резервациите за всички членове на екипа през избрания период. |
| Отсъствие | Блокира резервациите за избрания член на екипа през избрания период. |
| Променени работни часове | Заменя обичайните седмични часове за избраната дата. / Неработното време и отсъствията продължават да блокират резервациите. |
| Допълнителни работни часове | Добавя работни часове за избраната дата. / Неработното време и отсъствията продължават да блокират резервациите. |

User-facing interpretation of the unchanged availability semantics:
`available = (date override or recurring schedule) + additional hours − Business
non-working time − StaffMember absence − busy intervals`. There is at most one
Променени and one Допълнителни record per StaffMember and date; a record may
hold several periods, so the owner edits the existing record to change them.
Different kinds may coexist; overlapping records of the same blocking kind are
rejected.

The backend code `SCHEDULE_EXCEPTION_OVERLAP` is mapped, frontend-only and per
kind, to: Неработно време — "Избраният период се застъпва с вече добавено
неработно време."; Отсъствие — "Избраният период се застъпва с вече добавено
отсъствие за този член на екипа."; Променени — "За тази дата вече има променени
работни часове за избрания член на екипа." + "Редактирайте съществуващия запис,
за да промените периодите."; Допълнителни — "За тази дата вече има допълнителни
работни часове за избрания член на екипа." + "Редактирайте съществуващия запис,
за да добавите или промените периодите." Two-sentence messages render as
separate paragraphs; no generic form-level message is shown once the kind is
known. Local period conflicts in the dialog name the hours: overlap —
"Периодът {new} се застъпва със съществуващия период {existing}."; exact
duplicate — "Периодът {period} вече е добавен."; adjacent periods stay valid and
the message clears as soon as the value is corrected.

Table status, sorting and pagination: every record shows a derived badge —
`Предстояща`, `В сила` or `Минала` — computed from the Business-local date
(timezone from the API, never the browser date, first and last date inclusive). It
is not persisted, not accepted from the API, needs no migration, and past records
are never deleted automatically. The table sorts `Вид`, `Дати`, `Член на екипа`,
`Статус`; `Часове` is not sortable. Sorting and pagination are client-side because
the endpoint returns the complete window (≤ 93 dates, no pagination): load the
window, derive statuses, sort everything, then cut the page. The API request is
unchanged. Defaults are page 0, size 10 (options 10/25/50), `dates` ascending. The
complete list state (`from`, `to`, `page`, `size`, `sort`, `direction`) lives in the
route query, so detail, create, edit and delete round trips restore it. Sort, size
and filter changes reset the page; explicit changes push history; canonicalization
and out-of-range recovery (for example after deleting the last record on the last
page) replace it. The shared `ListPagination` renders one labelled region with the
range summary, the single page-size selector and `Предишна`/`Следваща`; cards and
table share one rendered page. The full rules are in
`docs/ui-design-guidelines.md` sections 15.1 and 15.2. Deletion stays detail-only:
no trash icon, no actions column, no delete on cards.

Visual consistency: dialogs and confirmation panels are sized to their content
(shared CSS), period chips are content-sized with 24 px edit/remove targets that
wrap instead of shrinking, and the page header, shell banner, page feedback and
content share one layout-gap stack.

Evidence: Vitest suites under `frontend/src/business/schedule/exceptions/`,
`navigation.test.ts`, and `App.scheduleExceptions.test.tsx`; the real-browser
review checklist was executed twice (initial pass and a UI/UX correction pass)
against a disposable database (no development data touched). Human visual
approval is pending. Phase 6 (documentation and acceptance) is not started.

## Acceptance evidence (Phase 1)

Unit tests in `backend/src/test/java/bg/spotyourslot/scheduling/domain/` cover
the approved matrix. Exact counts and results are in the Phase 1 report.
`ModuleBoundaryTests` verifies the module graph remains acyclic.

## Later-phase constraints

Persistence must not add a generic exclusion constraint prohibiting every
overlap between exception rows of one Business, StaffMember, and date. An
override or additional period legitimately overlaps a closure or time off.
Only semantically conflicting rows of the same effect and scope may be
constrained. Exception administration, owner authorization, versioning, and
lock ordering follow ADR-0012 conventions and are decided in ADR-0015.

## Follow-ups

- Business-configurable booking horizon and minimum notice.
- Configurable Service or StaffMember buffers.
- Owner warning when an exception affects a confirmed future appointment.
- Public availability endpoint, rate limiting, and the StaffMember-exposure
  decision (future public-profile/booking phase).
- Replace `NoBookingBusyIntervalSource` with the real Booking implementation.
- Select a complete date range from one calendar (a dedicated accessible
  range-picker component with its own browser and mobile validation). Phase 5
  keeps two native date inputs.
