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
  --color-warning: #d97706;
  --color-danger: #d92d20;
  --color-border: #e4e7ec;
}
```

Add derived tokens such as subtle state backgrounds, focus rings, disabled
states, or text-on-action colors only when an implemented component needs them.
Accessibility and sufficient contrast override exact palette values when
necessary. Do not communicate meaning through color alone.

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

Define clear action hierarchy:

- primary actions use the brand/action treatment;
- secondary actions are quieter but remain visibly interactive;
- danger actions use danger color only for genuinely risky or destructive
  operations.

Controls must be touch-friendly, keyboard-operable, and have a visible focus
state. Hover may reinforce interaction but must never be the only way to reveal
essential information or functionality. Disabled and busy controls must remain
understandable and prevent duplicate submission. Do not render non-functional
buttons or speculative controls for unimplemented features.

Links look and behave like links. Buttons perform actions. Icon-only controls,
if later justified, require an accessible name and must not depend on an icon
library.

## 7. Forms and validation

Every control has a visible, programmatically associated label. Mark required
and optional information clearly, provide useful input hints, and keep fields in
a logical keyboard order. Use suitable native input types and autocomplete
attributes without weakening backend validation.

Show validation near the affected field when possible and provide an accessible
form-level summary when several errors need attention. Move focus deliberately
after failed submission when that helps recovery. Preserve entered non-secret
values when safe, but do not retain authentication or sensitive data in browser
storage. Backend rules and safe Bulgarian problem responses remain
authoritative.

## 8. Loading, empty, success, warning, and error states

Every data-driven view defines useful loading, empty, success, warning, and
error behavior. Announce asynchronous status changes through appropriate live
regions without excessive interruption.

- Loading states explain what is loading and prevent duplicate actions.
- Empty states explain the absence of data and offer only an available next
  action.
- Success states confirm what completed without overstating external outcomes.
- Warning states explain a recoverable risk or required attention.
- Error states use safe natural Bulgarian text and an actionable recovery when
  one exists.

Never expose exception messages, SQL details, stack traces, security values, or
other internal diagnostics. Do not claim email delivery, invitation acceptance,
or another external outcome when only request acceptance is known.

## 9. Status badges

Business status badges use Bulgarian text and both text and visual treatment:

| Technical status | Bulgarian label | Treatment |
|---|---|---|
| `DRAFT` | „Чернова“ | Neutral |
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

### Mobile

Use a compact top header, an accessible navigation control, single-column
content, and touch-friendly actions. Opening and closing navigation must work by
keyboard, expose its state, manage focus appropriately, and not obscure the
current page without a clear dismissal method.

### Business list

Use a table on desktop and readable cards on mobile. Show display name, slug,
BusinessType, translated status, timezone, updated time, and an explicit open
action. Preserve backend ordering and bounded pagination. Do not introduce
speculative search, filters, or actions.

### Business detail

The header shows the Business name, slug, translated status, and the relevant
primary action. Use clear sections for basic information, owner invitation, and
lifecycle. Prefer sections over multiple tabs for the MVP. Keep Business contact
email distinct from owner invitation email, and show only lifecycle actions
valid for the current status.

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
Stop for human feedback at the task's review gate. When feedback establishes or
changes an enduring design decision, update this guide as part of the approved
change so future screens remain consistent.
