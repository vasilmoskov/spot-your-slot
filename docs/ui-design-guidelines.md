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
after failed submission when that helps recovery. Preserve entered non-secret
values when safe, but do not retain authentication or sensitive data in browser
storage. Backend rules and safe Bulgarian problem responses remain
authoritative.

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
shrinking. Test at approximately 360 px, representative desktop widths, and
200% browser zoom.

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
or support views. Preserve backend ordering and bounded pagination. Do not
introduce speculative search, filters, or actions.

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

The exact composition of public pages and booking steps will be approved in
their own future task. This guide does not authorize public booking work or
expand the MVP.

## 14. Manual visual review process

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
