# SpotYourSlot UI design guidelines

## 1. Purpose and status

This evolving guide defines the authoritative product-wide visual and interaction
direction for SpotYourSlot. Every user-facing UI task follows it unless that task
explicitly approves a change. Human visual review may refine these decisions;
approved refinements must update this document so later work stays consistent.

This guide establishes shared foundations, not a complete component library or
finished design system. No custom logo, illustration, external font, component
library, icon library, or styling dependency is required yet.

## 2. Product-wide visual identity

SpotYourSlot should feel modern, clean, friendly, and professional. Its shared
identity remains generic across appointment-based industries and does not use
industry-specific imagery. A barbershop, nail studio, massage studio, makeup
studio, beauty studio, or other supported Business must feel at home in the same
product.

Design mobile-first and scale deliberately to larger screens. The MVP uses a
light theme only. Favor restrained hierarchy, useful whitespace, and direct
content over decorative complexity. Reuse shared tokens and interaction patterns
instead of inventing a new visual language for each screen.

## 3. Color tokens

Define colors as semantic CSS custom properties. Components consume semantic
roles rather than repeating literal colors. The initial palette is:

```css
:root {
  --color-page-background: #f7f8fa;
  --color-surface: #ffffff;
  --color-text-primary: #172033;
  --color-text-secondary: #667085;
  --color-action-primary: #5b5ce2;
  --color-action-primary-hover: #4747c7;
  --color-success: #168f6b;
  --color-success-subtle: #ecfdf3;
  --color-warning: #d97706;
  --color-danger: #d92d20;
  --color-danger-subtle: #fef3f2;
  --color-border: #e4e7ec;
}
```

Add derived tokens such as subtle state backgrounds, focus rings, disabled
states, or text-on-action colors only when an implemented component needs them.
Accessibility and sufficient contrast override exact palette values when
necessary. Do not communicate meaning through color alone.

Error and success panels use their semantic text color, matching subtle
background, and a balanced full border. Avoid isolated decorative side borders
that make a state panel appear visually incomplete.

## 4. Typography

Use the system font stack without downloading an external font:

```css
font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont,
  "Segoe UI", sans-serif;
```

Use a restrained type scale with clear page headings, section headings, body
text, labels, and supporting text. Keep body text comfortably readable and avoid
very light weights. Bulgarian labels and messages should be natural, concise,
and understandable rather than literal translations of technical terminology.
Avoid redundant headings or generic labels that do not add useful hierarchy.

## 5. Spacing, borders, radii, and shadows

Use a small shared spacing scale based on consistent increments. Prefer layout
gap and padding tokens over isolated one-off values. Keep related controls close
and separate distinct sections clearly.

Use the semantic border color for structure. Corners use a moderate radius:
controls should feel approachable without appearing pill-shaped by default.
Cards and elevated surfaces may use subtle shadows, but borders and spacing
should carry most of the hierarchy. Avoid heavy elevation, ornamental borders,
gradients, and decorative layering.

Distinct semantic blocks within one component — form fields, inline
validation/error feedback, and action groups chief among them — must have
explicit, token-based spacing between them (a layout `gap` on a shared
`display: grid`/`flex` container, or an explicit `margin`/`padding` token),
never an element's own accidental default margin (a bare `<h4>`, `<p>`, or
similar). When a container relies on `gap` for this spacing, zero the outer
margin on its direct children so `gap` remains the single source of spacing
regardless of which element type is present — a default browser margin on a
grid or flex item is not collapsed and silently stacks on top of the
intended `gap`. Review the error-state layout specifically, not only the
success/default state: an inline validation message must not visually touch
the action buttons below it, whether the error is short or wraps across
multiple lines. Prefer a parent grid/flex `gap`; use an explicit token-based
margin only when `gap` is not appropriate, and never rely on browser-default
element margins.

### Content-sized elements and vertical rhythm

- Dialogs, confirmation panels, badges, chips, and compact actions are sized to
  their content (`width: fit-content`, with a readable minimum and a maximum such
  as `min(100%, 26rem)`) and are not stretched by a parent grid or flex column
  without a layout reason. Inputs, tables, cards, and responsive containers may
  remain full-width where that is the intent.
- A content-sized element keeps its accessible interactive target: controls inside
  a chip stay at least 1.5rem (24 px) square and never shrink below it; the chip
  wraps the control instead of squeezing it.
- Logical sibling sections (page header, shell banner, page feedback, page
  content) sit in one layout stack whose `gap` is the single source of spacing
  (`.platform-main`); do not add per-message margins. Feedback, controls, cards,
  and adjacent text must never appear visually stuck together.
- Modal dialogs on narrow screens may use the available viewport width and stack
  their actions, keeping comfortable button heights.
- During visual review, inspect both excessive empty space and missing space
  between adjacent elements.

## 6. Buttons and interactive controls

Use the shared `Button` component and semantic variants; do not create page-specific
color rules. All variants share typography, touch-friendly height, padding,
radius, focus-visible outline, and transition:

- Every non-destructive action uses a purple background and border with white
  text by default; hover/active uses a white background with purple text and
  border. `primary` and `secondary` retain their semantic names but share this
  single CSS rule for login, create, edit, save, invitation, activation,
  reactivation, and confirmation actions. Focus-visible preserves readable
  colors and adds the established focus outline. Ordinary actions remain
  intrinsic-width on desktop and mobile; do not add page-specific overrides.
- `destructive`: red background and border with white text by default;
  hover/active uses a white background with red text and border.
- Password recovery and equivalent identity navigation, including return to login,
  use semantic anchors with the shared `text-link` treatment: intrinsic width,
  purple text, no button border or fill, darker purple/underline on hover, and
  the established keyboard focus outline. Submission and workflow actions remain
  buttons; do not convert them into tertiary links.
- Disabled controls use the shared disabled palette, prevent activation, and do
  not adopt enabled hover/active colors. Busy actions prevent duplicate requests.
- `navigation` is the explicit full-width exception for equal Profile section
  controls. Use restrained light-purple selection and purple text, never a
  saturated block. Sidebar links remain semantic navigation links.
- A popup menu or overflow menu opened from a trigger inside a narrow,
  repeated, or edge-adjacent container (a grid column, a table row, a card
  near the viewport edge) must use collision-aware positioning, measured
  from the real trigger and the real (natural-size) menu at open time, not a
  fixed CSS offset such as `top: 100%; right: 0` tuned for only one
  instance. Prefer a consistent primary side (e.g. opening to the right,
  aligned with the trigger), flip to the opposite side when the viewport
  does not have room, and clamp the final position so the menu never
  overflows the viewport or gets clipped by a narrow ancestor. Extract the
  side/flip decision as a small pure function so it can be unit tested
  without relying on real browser layout in tests. Menu item labels must
  never be truncated or ellipsized; size the menu to its content instead of
  a fixed width that may be narrower than the longest label. A popover or
  overflow menu must not cover the content it was invoked from, must not
  detach from its trigger, and defines its Escape (closes and restores focus
  to the trigger), outside-click, scroll, and resize behavior; every listener
  it registers is removed when it closes or unmounts.

Do not apply the non-destructive action colors to destructive or disabled controls,
selected navigation/tab states, status badges, plain text links, or native
disclosure/accordion controls. These retain their separate semantic treatments.

Verify label/background contrast in default, hover, focus, active, selected, and
disabled states (at least 4.5:1 for ordinary button text), plus visible keyboard
focus. Color alone must not communicate meaning. Ordinary actions always use
intrinsic width, `max-width: 100%`, and explicit flex/grid alignment; never grow or
stretch on desktop or mobile. Adjacent actions wrap with the standard action-row
gap. Full width requires a documented component exception, not a viewport-based
override. Selectors target semantic component classes, never all descendant
buttons, inputs, or status elements. Links navigate; buttons perform actions.
Do not add non-functional or speculative controls.

## 7. Forms and validation

Every control has a visible, programmatically associated label. Mark required
and optional information clearly, provide useful input hints, and keep fields in
a logical keyboard order. Use suitable native input types and autocomplete
attributes without weakening backend validation.

Identity cards are compact and centered in the viewport. Profile forms use the
Profile card beneath the administrative page header and align with the shared
administrative content edge. Wide, data-heavy administrative screens may use
the full administrative content width. Within a compact card, left-align its
headings, explanatory text, labels, inputs, primary and secondary actions,
validation, and feedback. Fields use the full column width while actions remain
content-sized. Do not center individual controls independently or constrain a
form separately from its surrounding heading and feedback.

Show validation near the affected field when possible and provide an accessible
form-level summary when several errors need attention. Move focus deliberately
after failed submission when that helps recovery.

Field problems and form problems are presented differently:

- **A field problem** (a value the user can correct) is an inline message
  directly below that field: a concrete Bulgarian sentence in the
  `.field-error` style (red text plus an invalid-field border) linked with
  `aria-invalid` and `aria-describedby` (`fieldControlProps`/`FieldError`). It
  is never wrapped in a red alert panel and never duplicated in a form-level
  alert.
- **A form or request problem** (network or unavailable server, authentication
  or authorization failure, optimistic-concurrency conflict, unexpected server
  failure, or a `VALIDATION_ERROR` without usable field metadata) uses the
  form-level feedback component.

Business-owner configuration forms (Services and StaffMembers, and the
schedule period dialog for its own fields) validate every locally knowable rule
before sending a request, using `noValidate` and the shared
`ui/formValidation` `useFieldValidation` policy:

- An untouched form shows no required-field errors.
- Leaving a field (blur) validates it. Once touched, a field is revalidated on
  every change and its error disappears the moment the value is valid.
- A non-empty value that is already invalid (a negative duration or price, a
  malformed email or telephone) shows its error immediately, without waiting
  for blur or submit.
- Submit validates every field, shows all errors together, preserves entered
  values, and focuses the first invalid control in DOM order.
- Trim before judging required values (whitespace-only is empty); optional
  fields accept blank and validate only when non-blank.
- Mirror only rules that are deterministic in the browser. The telephone is
  checked with `libphonenumber-js` (full metadata; Bulgaria as the default
  region for a leading `0`, `+` and `00` as explicit international numbers),
  never with a hand-written prefix list; letters and extensions are rejected, not
  stripped. The shared `src/contact/contactPolicy.ts` module holds the phone and email
  rules and is kept aligned with the backend by the golden vectors in
  `shared-test-data/contact-policy-vectors.json`. The backend stays authoritative.
- The backend names an invalid body field in an optional `fieldErrors` object
  of the `VALIDATION_ERROR` problem response (public field name to fixed
  Bulgarian message). Show each known field's message inline under its
  control, focus the first, drop it as soon as that field changes, and do not
  also show the generic alert. Unknown field names are ignored. A
  `VALIDATION_ERROR` with no usable `fieldErrors` shows a safe form-level
  message; never guess a field.

An inclusive date range always uses one shared presentation
(`DatePeriodFields`): a labelled "От" control and a labelled "До" control, each
label above its input, side by side when there is room and stacked when narrow
(the group is named "Период" for assistive technology only); a single date shows
only "Дата" above its input. A trailing action such as "Покажи" is bottom-aligned
with the inputs. Do not invent per-screen wording such as "Първа дата".

Browser constraint messages shown to Bulgarian users must use natural Bulgarian
while preserving native validation, focus behavior, and keyboard submission
where practical. New and replacement passwords use the authoritative minimum
of eight Unicode code points, and frontend length checks count code points
consistently with the backend. Login-password and current-password verification
fields must not impose the current new-password minimum on an existing
credential.

Use confirmation for new passwords where a mistype could lock the user out.
Validate confirmation locally and never send it to the backend. Safe failed
submissions preserve entered values; successful password replacement clears all
password fields. Editing the relevant form or starting a new submission clears
stale success and error feedback as appropriate.

## 8. Loading, empty, success, warning, and error states

Every data-driven view defines useful loading, empty, success, warning, and
error behavior. Announce asynchronous status changes through appropriate live
regions without excessive interruption.

Every user-triggered mutation provides an explicit success or safe failure
result. Feedback must be contextual to the operation: do not reuse a login
failure message for password change, invitation, Business mutation, or another
unrelated flow merely because the message is safe.

- Loading states explain what is loading and prevent duplicate actions.
- Empty states explain the absence of data and offer only an available next
  action.
- Success states confirm what completed without overstating external outcomes.
- Warning states explain a recoverable risk or required attention.
- Error states use safe natural Bulgarian text and an actionable recovery when
  one exists.

Lifecycle feedback must describe the current domain operation: the shared SUSPENDED notice takes its wording
from the screen (Customers: “…Можете да преглеждате и редактирате клиентите, но не можете да добавяте нови.”), and
no screen calls its records “configuration” when they are not. It appears once: the shared banner is the only
lifecycle statement on a screen, so a page must not repeat it in its own sentence or leave an empty action row (blank
space) where the blocked action used to be. Action-specific explanations that do not restate the lifecycle stay.

Classify feedback before implementing it. Use the shared `useFeedback` hook for
local action feedback instead of separate page timers:

| Category | Examples | Lifetime |
|---|---|---|
| Transient action feedback | Saved/sent confirmation, request/network failure, failed activation | Auto-dismiss after 5 seconds; immediately clear on a new attempt, replacement, route/tab/entity/operation change |
| Field/form validation | Required/invalid input, rejected credentials, duplicate slug | Retain until the relevant form is edited/reset or its context is left; no timer |
| Loading/blocking failure | Page-load failure, unusable invitation link, stale version requiring reload | No timer; preserve retry/navigation/reload recovery |

Clear feedback when its originating context changes, including browser navigation.
Invalidate late responses from abandoned/replaced operations. Clean up timers on
replacement and unmount. Never persist feedback or timer state in browser storage.
Maintain accessible status/alert roles, live regions, deliberate error focus, and
field associations. Keep notices compact with uniform padding, intrinsic width,
`max-width: 100%`, `align-self: flex-start`, and grid start alignment. Text is
left-aligned and safely wraps. Feedback and related controls always occupy separate
rows with a standard gap; never place controls inside a notice or inline beside it.

Never expose exception messages, SQL details, stack traces, security values, or
other internal diagnostics. Do not claim email delivery, invitation acceptance,
or another external outcome when only request acceptance is known.

## 9. Status badges

Business status badges use Bulgarian text and both text and visual treatment:

| Technical status | Bulgarian label | Treatment |
|---|---|---|
| `DRAFT` | „Предстои активиране“ | Neutral |
| `ACTIVE` | „Активен“ | Success |
| `SUSPENDED` | „Временно спрян“ | Warning |

Badges supplement, rather than replace, surrounding status information. They
must remain legible at narrow widths and under high zoom. Danger is not used for
`SUSPENDED`; it is a preserved, recoverable state.

## 10. Responsive behavior

Start with a usable single-column mobile layout and enhance it at content-driven
breakpoints. Avoid horizontal page scrolling. Controls remain touch-friendly,
content retains a readable line length, and actions wrap or stack without losing
priority.

Do not hide essential information only because the viewport is narrow. Dense
desktop tables must transform into a readable mobile pattern rather than merely
shrinking. Review every screen at ordinary desktop width, an intermediate/tablet
width (about 1024 px), mobile width (about 375 px), and 200% browser zoom. Fix
overflow without degrading the preferred information hierarchy: keep a
recognizable layout (for example the weekly schedule) whenever enough room
exists and collapse it only where it genuinely does not fit.

## 11. Accessibility requirements

Accessibility is part of completion, not a later polish step. User-facing UI
requires:

- semantic landmarks, headings, lists, tables, and form controls;
- full keyboard operation with logical focus order and visible focus;
- sufficient text, control, focus, and state contrast;
- accessible names and descriptions;
- status and loading announcements that work with assistive technology;
- no essential hover-only, color-only, pointer-only, or motion-only behavior;
- comfortably sized touch targets;
- error identification and recovery that do not rely on visual position alone;
- layouts that remain usable with text expansion and at 200% zoom.

Native HTML behavior is preferred where it satisfies the interaction. Custom
controls must reproduce the expected keyboard and assistive-technology behavior.

## 12. Platform-admin layout conventions

### Desktop

Use a left sidebar with the SpotYourSlot text wordmark, “Бизнеси” and “Профил”
navigation, and the current user plus logout at the bottom. The adjacent area
contains a clear page header and primary content region.

#### Global and Business-scoped navigation (durable rules)

- Global account navigation (“Бизнеси”, “Профил”) and Business-scoped navigation (“Услуги”, “Екип”, “Работно
  време”, “Клиенти”) stay visibly separate: the global links first, then a separated group headed by the selected
  Business's name. The signed-in user and “Изход” close the sidebar; the Business name belongs only to its group.
- Contextual navigation appears only while its context exists: no Business-scoped link is rendered without a
  selected Business, and a user who loses the Business (it became unavailable) returns to “Бизнеси”.
- Business selection never lives inside personal settings. “Профил” holds only personal data and the password.
  “Бизнеси” lists the user's Businesses as cards, each named by its own heading, with the lifecycle in words and a
  plain “Покажи” button (the card, not a longer button label, supplies the context), which selects the Business and
  opens “Услуги”. The selected card is pale green with a matching border and a small checkmark plus screen-reader text
  (never colour alone, and separate from the lifecycle badge, because a draft or suspended Business can be selected).
  No technical role or status value is ever shown.
- A lost Business context is recovered the same way on every Business-scoped screen: the backend's
  `403 ACTIVE_BUSINESS_REQUIRED` (and only that code) refreshes the session once; if no valid Business remains the
  route is replaced with “Бизнеси”, otherwise the screen keeps its safe error. A missing record, a suspended Business,
  an expired login, and a network failure are never treated as a lost context. A dirty form is never discarded
  silently: the shared guard asks first.
- Switching Business, sidebar navigation, browser Back/Forward, and logout use the shared unsaved-changes guard.
  Cancelling the dialog returns focus to the invoker, or to the control marked `data-focus-fallback` (the mobile
  menu button) when the invoker is hidden. Naming is an approved, intentional exception: an ordinary owner's Business
  selection is “Бизнеси”; a Platform Administrator's “Бизнеси” is the platform-wide list; one who also manages
  Businesses reaches their own selection through “Моите бизнеси”. The destinations stay distinct.

### Shared layout and feedback

- Primary page content, cards, forms, tables, and page actions share the same
  left content edge unless a centered layout is explicitly required. Page-level
  primary actions normally appear on the left near the content they affect.
- Use the shared spacing scale between distinct components. Adjacent buttons
  use the established action-row gap.
- Ordinary action buttons use intrinsic content width and never stretch because
  a parent uses grid or flex. Only explicitly documented controls, such as
  compact navigation items, may fill their available width.
- Feedback notices require intrinsic content width, `max-width: 100%`, and
  `align-self: flex-start` inside stretching layouts. Render feedback and its
  related controls in separate rows with the standard visible gap; they must
  never be accidentally laid out inline.
- Apply the three feedback categories and five-second transient lifetime in
  section 8; validation and blocking errors do not auto-dismiss.
  Feedback is scoped to its route, tab, entity, or operation and is cleared
  when that context changes.
- Form controls use consistent heights and box sizing; textareas may be taller.
  Desktop forms and details use established balanced columns and collapse
  predictably on mobile.
- Active navigation uses the restrained application selected treatment, never a
  saturated arbitrary block. Before changing UI, inspect this guide and the
  nearest approved screen; do not create a parallel visual system. Selectors
  target semantic component classes and never broadly style all descendant
  buttons, inputs, or status elements.
- Frontend tests verify structure and behavior. Every meaningful visual change
  also requires explicit human desktop and mobile review; automated DOM tests
  do not replace that review.

### Frontend pre-completion checklist

- Confirm left-edge alignment, component spacing, intrinsic button and notice
  width, and separate feedback/action rows.
- Audit every affected shared-component consumer on desktop and narrow/mobile
  layouts: width, alignment, spacing, wrapping, responsive behavior, all button
  interaction-state contrasts, keyboard focus, and stale-feedback lifecycle.
- Record explicit human visual review separately from automated tests.

### Mobile

Use a compact top header, an accessible navigation control, single-column
content, and touch-friendly actions. Opening and closing navigation must work by
keyboard, expose its state, manage focus appropriately, and not obscure the
current page without a clear dismissal method.

### Business list

Use a table on desktop and readable cards on mobile. Show the display name as
the normal accessible detail link, slug as “Идентификатор в уеб адреса” without
constructing a full public URL, BusinessType as “Дейност”, and translated
status. Do not display timestamps,
timezone, IDs, version, or other technical metadata in the overview; returned
metadata remains available for later application operations and detail, audit,
or support views. Follow the shared paginated-table standard in section 15 for
sorting, page size, and URL state. Do not introduce speculative search, filters,
or actions beyond the approved sortable columns.

### Business detail

The header shows the Business name, slug, and translated status. Details begin
as read-only information and expose an explicit “Редактирай” action with
save/cancel only in edit mode. Use accessible collapsible sections titled
“Данни за бизнеса”, “Покана”, and “Активиране”; keep operation feedback inside
its corresponding section and reopen that section for an active error. Keep
Business contact email distinct from owner invitation email, and show only
lifecycle actions valid for the current status. The current UI hides timezone
and technical version while retaining them in application state for correct
updates.

## 13. Public/customer-facing UI boundary

Public booking screens reuse the product-wide tokens, typography, controls,
accessibility rules, and feedback patterns. They must not automatically copy the
platform-admin sidebar or administrative page composition. Customer journeys
should remain simpler and focused on selecting and booking a suitable time.

The composition of the read-only public Business profile was approved in issue #17
([task 06a](tasks/06a-public-business-profile.md)): one coherent Business hero with the
public identity and the optional approved contact data, an honest booking-unavailable
state, standalone Service cards without redundant outer card nesting, and a responsive,
accessible presentation on desktop and mobile. The booking journey (issue #18, Phase 6) is
implemented inside that page: native radio groups for the Service, the StaffMember preference, the
date, and the time, a labelled details form that follows section 7, a review with «Промени» actions,
the shared «Остани» / «Напусни» dialog (section 19), and the shared status messages; the public
page never copies the administration shell. **Its composition is implemented but not yet visually
approved:** it becomes an enduring decision of this guide only after the Phase 7 rendered review and
explicit human approval. This guide does not authorize public booking work or expand the MVP.

## 15. Paginated tables

Section 15.1 makes sorting and pagination mandatory for every data-list table.
Every user-facing server-paginated table — the Business-owner Services table, the
Platform-admin Businesses table, and any later table — follows this shared
standard rather than a page-specific pagination design:

- Sorting and pagination are resolved server-side; never sort only the
  currently loaded page on the client.
- Default page size is 10. The user-selectable sizes are exactly 10, 25, and
  50; the backend rejects every other value with the standard safe
  validation response, not merely values above 50 (for example 1, 7, 20, and
  37 are all rejected, not only values past the maximum).
- Changing the sort field, changing sort direction, or changing the page size
  returns to page 0. Ordinary Previous/Next navigation changes only the page.
- Every backend ordering is deterministic: a documented, explicit
  allowlist maps a sort field to database columns, and a stable final
  tie-breaker (typically case-insensitive name, then id) makes ties
  reproducible. A status column uses a documented semantic lifecycle order,
  never accidental enum or alphabetical order.
- Desktop sortable column headers are one semantic, keyboard-operable button
  per header cell with an accessible name. Every sortable column always shows
  both direction arrows (`▲▼`); inactive columns show both in a neutral,
  muted style. The active column is visually distinguishable through a
  restrained active treatment (stronger header text plus a subtle background
  or accent) and, within it, only the arrow matching the current direction is
  emphasized. Color is never the only signal. The active column exposes
  `aria-sort` (`ascending`/`descending`); inactive columns use
  `aria-sort="none"`. Clicking the active column toggles direction; clicking
  a different column selects it ascending and resets page to 0. Non-sortable
  decorative header space is never made interactive.
- At the breakpoint where a table becomes cards, its column headers stop
  being a practical interaction surface, so a compact, labeled responsive
  sort control (a single combined field-and-direction select) replaces them.
  It exposes the same sort choices as the desktop headers and never
  duplicates or conflicts with that state.
- Table state (page, size, sort field, direction) is represented in the URL
  wherever the current router supports it, using canonical query parameters
  such as `?page=0&size=10&sort=name&direction=asc`. Refresh and browser
  Back/Forward restore the exact prior configuration. Missing values use the
  documented defaults; invalid or unsupported values normalize safely to
  those defaults rather than erroring.
- Every explicit user interaction (sorting a column, changing the responsive
  sort control, changing page size, Previous/Next) creates a restorable
  history entry (a push). Automatic canonicalization or recovery — normalizing
  an invalid URL on load, resetting page on a Business switch, or recovering
  from an out-of-range page — replaces the current history entry instead, so
  the corrected state is never followed by a Back-navigation trap that
  returns to the invalid/obsolete one.
- Switching the active Business resets a Business-scoped table's page to 0
  while preserving its size, sort, and direction, and never renders a
  response that belongs to the previous Business.
- An empty result caused by a page beyond the data (including after the
  underlying data shrinks) recovers to the last valid page, or page 0 when
  there is no valid page, using the smallest coherent correction.
- The page-size selector lives in the pagination region beside Previous/Next,
  not duplicated above the table. The preferred desktop order in that region
  is: result range / current page summary, page-size selector, then
  Previous/Next. On mobile and at 200% zoom the pagination region wraps or
  stacks cleanly without introducing horizontal scrolling.
- Show a results summary (for example “1–10 от 24 услуги”, “Страница 1 от
  3”) using the correct noun for the entity being listed.

### 15.1 Mandatory sorting and pagination for every data-list table

Every user-facing application data-list table — paginated by the server or not —
must be designed with both sorting and pagination before implementation. Small
semantic or layout tables that are not data lists (for example a weekly grid or a
definition table) may document, in their task record, why pagination and sorting
do not apply; that exception is deliberate and recorded, never assumed silently.
Example: the Staff service-assignment checkbox table (`StaffServiceAssignments`),
recorded in `docs/tasks/04d-business-owner-configuration-frontend.md`.

**Pre-implementation checklist.** Before implementing or modifying a data-list
table, the task must state:

- its deterministic default ordering;
- its sortable columns, and why any data column is not sortable;
- the deterministic tie-breakers, ending in a stable record ID;
- its pagination ownership (server or client) and why;
- its default page size and allowed page sizes;
- how filtering, sorting and pagination are represented in the route;
- its responsive desktop-table/mobile-card behaviour;
- its empty-page recovery after a mutation.

**Sorting.**

- Meaningful data columns are sortable unless documented otherwise. Action
  columns, where they genuinely exist, are never sortable.
- Headers use the shared `SortableColumnHeader` two-arrow indicator (`▲▼`); the
  active column and direction are visibly identifiable, sorting is keyboard
  accessible, and `aria-sort` is correct. Do not create another implementation.
- Tie-breakers are mandatory. Entities that may lack a value (for example
  Business-wide records with no StaffMember) take one documented position that
  holds in both directions.
- Responsive card layouts use the same ordering as the desktop table and expose
  it through the shared responsive sort select.

**Pagination (approved standard, unless a task records an approved exception).**

- Default size is `10`; allowed sizes are `10`, `25`, `50`. Internal page numbers
  are zero-based, visible ones one-based.
- The page-size selector is inside the pagination region beside `Предишна` /
  `Следваща`, never duplicated above the table. The shared `ListPagination`
  component provides the labelled region, the range summary
  (`Показани 1–10 от 37`), the selector and the buttons.
- Sorting happens before pagination. A paginated API sorts on the server before
  paginating. Client-side pagination and sorting are permitted only when the
  complete relevant dataset is loaded, and the task record says why.
- Filter, sort and size changes reset to page zero; a page change keeps the rest.
- Explicit user actions push history. Automatic canonicalization and empty-page
  recovery (including after deleting the last item on the last page) replace it,
  never leaving a broken intermediate entry.
- Route-backed lists keep the complete state (filter, page, size, sort,
  direction) in the URL through refresh and Back/Forward, and carry it through
  related create/detail routes so a return lands on the same state. Invalid
  values normalize field by field to the defaults.
  **Recorded exception (ADR-0021, issue #20):** a filter whose value is personal data, such as
  the Customer search term, is not stored in the URL, history, or browser storage. It lives in
  component state, is sent in a POST body, resets the page to 0 when it changes, and is
  cleared on Business switch and logout. Page, size, sort, and direction remain in the URL.
  A Business switch also clears that search and returns the Customer list to its defaults.
- A filter whose input is free text and which can update safely as the user types is a live search: no
  redundant Search/Clear buttons, a short debounce (about 300 ms), Enter applies at once, Escape clears, removing
  characters searches again, an empty field restores the unfiltered list, superseded requests are aborted, and the
  previous result stays visible (dimmed, `aria-busy`) until the newer one arrives. The Customer search is the
  first.
- The Customer list (implemented, issue #20 Phase 5) has Name, Phone, and Email sortable columns with
  default `name` ascending and the tie-breakers normalized name, then id, empty phone and
  email last in both directions, server-side pagination, no actions column, and mobile cards. Its detail
  shows the name as the page header and only the phone and email that exist; `version`, timestamps, and IDs are
  response fields, never page content. An empty page above 0 (including a result that became empty) is replaced
  by the last valid page or page 0 before it is rendered.
  Its fields use `autocomplete="off"` because they describe other people.
- Mobile cards render exactly the same sorted, paginated result as the desktop
  table.
- Do not add destructive table actions (trash icons, an actions column) when the
  approved flow intentionally requires opening the detail view first.

### 15.2 Schedule changes table

The Business-owner "Промени в графика" table sorts `Вид`, `Дати`, `Член на екипа`
and `Статус`; `Часове` is not sortable because one record may hold several
periods, `Цял ден` or `Неработен ден`. The endpoint returns the complete selected
window (at most 93 dates, no pagination), so sorting and pagination are
client-side and never see a partial result: the whole window is loaded, statuses
are derived, the result is sorted, and only then is the current page cut.

Default order (`dates` ascending): first date, last date, kind, StaffMember name,
record ID. `Дати` sorts by first date, last date and then those same tie-breakers;
`Вид` by the Bulgarian display label; `Член на екипа` by Bulgarian locale order
with Business-wide records always last in both directions; `Статус` orders
`В сила`, `Предстояща`, `Минала` (descending reverses that primary order).

Canonical route: `?from=…&to=…&page=0&size=10&sort=dates&direction=asc`. Invalid
`page` (negative, non-numeric) becomes `0`, unsupported `size` becomes `10`,
invalid `sort` becomes `dates`, invalid `direction` becomes `asc`. A page beyond the
last valid page is rendered as the last page at once and the route is corrected by
replacing history.

The status is derived, never stored or read from the API: `Предстояща` when the
first date is after the Business-local date, `В сила` when that date lies within
first and last date inclusive, `Минала` when the last date is before it. The
Business-local date comes from the timezone returned by the API. It appears as a
compact badge in the table, the mobile cards and the detail view. Records are
never deleted automatically; past ones stay reachable through the date filter.
Deletion exists only in the detail view.

## 16. Authenticated mutable forms

Every authenticated, user-editable form (profile, Business profile, Service
create/edit, and later forms) uses the shared `UnsavedChangesGuardProvider`/
`useGuardedFormState` mechanism rather than a page-specific confirmation:

- Dirty detection compares actual current values against the loaded/saved
  values, not merely "has an input been touched."
- A successful asynchronous save clears the dirty state before navigating
  away; a failed save preserves the entered (safe, non-secret) input and
  keeps the guard active.
- Only the first pending guarded navigation is authoritative: a repeated
  guarded action (Back/Forward, another link, a second logout) while a
  confirmation is already showing is dropped, never silently replacing the
  original request.
- The confirmation dialog says exactly “Имате незапазени промени.” and “Ако
  напуснете, те ще бъдат загубени.” on two separate lines, with no visible title.
  Its safe action (“Остани”, shown first) receives initial focus, closes the
  dialog, and Escape does the same, restoring focus to the control that triggered
  the attempt; the destructive action (“Напусни”, red, shown second) is never the
  default focus.
- The browser's `beforeunload` prompt is armed only while the form is dirty.
- Every transition that would discard a dirty editor goes through the guard:
  sidebar navigation, browser Back/Forward, Business switching, logout,
  switching between mutually exclusive editors, and reloading fresh data after
  an optimistic-concurrency conflict.
- A confirmed discard that leaves the form mounted (for example Back/Forward to
  the same route) really resets the form's values, so the guard's cleared dirty
  flag never disagrees with a still-dirty form.
- A dirty state that lives outside the form's own values (for example an open
  dialog with typed input) is included in the registered dirty state, and the
  registered discard callback resets all of it. Registration is removed on
  unmount.

## 17. Identity and page hierarchy

- The signed-in user's own profile identity (name, email) and the active
  Business identity are distinct concepts and must never be conflated in a
  heading, label, or summary.
- The active Business name shown in navigation and page headers always comes
  from the active-Business session context, never from a form draft or
  fixture value.
- Page eyebrows (the small label above a page heading, such as “УПРАВЛЕНИЕ НА
  БИЗНЕСА”) use stable, product-defined copy. Never derive an eyebrow from
  test/fixture data or from the entity being displayed.
- Avoid duplicated headings and duplicated summary content: a page heading,
  section heading, and any status/summary line should each add distinct
  information rather than repeating the same fact in adjacent elements.

## 18. Manual visual review process

Include a manual visual checkpoint as soon as meaningful UI is available. The
task must provide exact local startup, fixture, URL, viewport, interaction, and
shutdown instructions. Review at mobile and desktop widths, at 200% zoom where
appropriate, and with keyboard-only navigation.

Review visual hierarchy, Bulgarian wording, spacing, responsive transformations,
focus, contrast, loading and feedback states, error recovery, and duplicate-
submission protection. Automated checks support but do not replace this review.
Completion reports distinguish automated tests, DOM assertions, and CSS
inspection from rendered browser review and explicit human visual approval.
Never describe a layout as visually verified when it has not been reviewed in a
rendered browser. Stop for human feedback at the task's review gate. When
feedback establishes or changes an enduring design decision, update this guide
as part of the approved change so future screens remain consistent.

## 19. Dialogs and confirmations

- A dialog heading describes the operation precisely; avoid misleading generic
  headings such as „Отказ“ when the user initiated navigation or another
  action.
- Action labels say what the action does. A safe cancellation is „Отказ“, never
  „Запази…“ when nothing is saved.
- When copy contains several complete sentences with separate purposes, render
  each as its own paragraph and let the container's standard gap space them;
  do not insert `<br>` merely for visual layout.
- Confirmation dialogs focus the safe action first. A destructive confirmation
  never receives default focus, whether it is a modal or an inline
  `alertdialog`. Modal dialogs set `aria-modal="true"`, Escape behaves like the
  safe action, and focus returns to the invoking control (or the nearest
  meaningful control when the invoker disappeared).
- Use the destructive treatment only for actions that modify or remove
  persisted or draft data. Non-destructive selection resets stay neutral.
- While a destructive request is in flight, disable duplicate submission, keep
  the dialog open, and ignore Escape and cancellation so the operation never
  appears cancelled while it continues in the background. Controls behind the
  dialog that could start a competing operation are disabled as well.
- A separate destructive operation with its own meaning (for example clearing
  a whole schedule) is not hidden inside ordinary editing; read-only and edit
  modes expose only the actions that belong to that mode.
- A dialog with an unsaved typed value ignores Escape; only the explicit safe
  action discards it.

## 20. Wording and hierarchy

- Do not repeat headings, add explanatory copy that does not help the user
  finish a task, duplicate metadata, or show status labels without actionable
  information.
- Button labels communicate an action („Добави нова услуга“,
  „Редактирай графика“) rather than merely naming an entity.
- Bulgarian weekday names are capitalized as standalone headings and labels and
  lowercase when embedded in a sentence; use one shared mapping for each form.

## 21. Business-scoped asynchronous data

- Business-scoped data and feedback must never survive a change of Business,
  route, entity, or operation. Key Business-owner sections by the active
  Business (and entity id), abort obsolete reads with `AbortController`, ignore
  responses that belong to a superseded request, and invalidate operation-scoped
  feedback when its context changes (`useFeedback` generations).
- Prevent duplicate mutations while one is in flight with a synchronous ref
  guard, not only a disabled attribute; a response that arrives after the
  originating view unmounted must not navigate or render.
- A successful create/save clears the dirty state synchronously before
  navigating; a failed operation preserves safe input and keeps the guard
  active.

## 22. Fixtures for manual review

Manual visual review uses only supported APIs and normal application flows, or
a throwaway mock that never touches the development database. Never write to
the database directly, hand-edit password hashes, or delete volumes, and report
any fixture data created or mutated together with cleanup instructions.
