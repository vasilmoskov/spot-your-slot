# SpotYourSlot — Platform-admin Onboarding Frontend

Status: Completed
GitHub subissue: #6 — Build platform-admin onboarding interface
Parent issue: #4 — Create platform admin tools and onboard businesses

## Task purpose

Build the Bulgarian, mobile-first platform-admin interface for the approved
Business onboarding backend. An authenticated `PLATFORM_ADMIN` must be able to
list, create, inspect, edit, invite an owner for, activate, suspend, and
reactivate Businesses without exposing backend or security internals.

This task completes only the issue #6 frontend portion of Phase 3. Preserve all
existing login, forgotten-password, password-reset, invitation-acceptance,
Business-selection, password-change, and logout flows.

## Required reading and inspection

Before proposing or implementing a slice, read completely:

- `AGENTS.md`;
- `README.md`;
- `docs/product-spec.md`;
- `docs/architecture.md`;
- `docs/security.md`;
- `docs/testing-strategy.md`;
- `docs/ui-design-guidelines.md`;
- `docs/implementation-plan.md`;
- `docs/tasks/03a-platform-business-backend.md`;
- this task file;
- the complete current frontend implementation, tests, package manifest, and
  Vite configuration;
- the backend contracts for authentication/session, CSRF, platform Business
  management, and owner invitation creation.

Permanent documentation and the implemented backend contracts are
authoritative. Stop and request approval if implementation reveals a conflict,
missing product decision, unexpected dependency, or required scope expansion.

## Approved UX and architecture decisions

- Use application-owned hash navigation for platform pages:
  - `/#/platform/businesses`;
  - `/#/platform/businesses/new`;
  - `/#/platform/businesses/{businessId}`.
- Support browser back/forward navigation. Do not add React Router or another
  routing dependency.
- Show platform navigation only when the current session reports
  `platformAdmin: true`; backend authorization remains authoritative.
- Keep Business contact email and owner invitation email separate. Do not copy,
  conflate, or persist one as the other automatically.
- Require confirmation before activation, suspension, and reactivation.
- Require confirmation before resending an invitation to the same owner email.
- Invitation success text may say only that the invitation request was
  accepted. Never claim delivery, invitation acceptance, Membership creation,
  or active-owner readiness.
- Invitation acceptance asks for required first name, last name, password, and
  local password confirmation. It sends the trimmed names as the existing
  combined `displayName` and never sends confirmation. A new normalized email
  creates a User with that name; an existing active, unlocked User must supply
  the current password, retains the existing display name, and may receive a
  Membership for another Business. The UI must not reveal which case applies.
- Do not persist authentication tokens, session identifiers, CSRF tokens, or
  other authentication secrets in local storage, session storage, IndexedDB,
  or frontend diagnostic state. Preserve the existing approved invitation and
  password-reset link flows without copying their URL tokens elsewhere.
- Reuse the existing credentialed session and CSRF model. POST and PUT requests
  send the CSRF header; safe structured backend errors drive Bulgarian UI
  feedback without exposing exception or database details.

## Delivery sequence and review gates

Implement slices A–D in order. Each slice is independently reviewable. For
every slice:

1. inspect the current state and propose the exact bounded file change;
2. obtain explicit approval before editing or running state-changing checks;
3. implement only that slice;
4. run its focused checks before broader approved checks;
5. inspect formatting, readability, generated files, secrets, and scope;
6. stop for human review;
7. continue only after the slice is accepted and independently committed by the
   human or after separate explicit approval to commit.

Do not begin the next slice merely because the previous implementation passes
automated checks.

## Slice A — shell, navigation, and Business list

Progress: A1 is implemented, manually reviewed, and human-approved. A2 is
implemented, manually reviewed with the disposable 55-Business environment,
and human-approved. Slice A is complete. That checkpoint did not by itself
complete issue #6; the overall task status remains `Pending` until the final
Slice D browser review and human approval.

Slice A is the first implementation of the permanent
`docs/ui-design-guidelines.md`. Apply its product-wide tokens, typography,
controls, feedback, accessibility, responsive behavior, and platform-admin
layout conventions. Do not invent a separate visual specification in this task.

### User-visible behavior

- Preserve the existing unauthenticated and authenticated identity experience.
- Give authenticated platform administrators clear navigation between their
  account view and “Бизнеси”.
- Never show platform navigation solely because a user has a
  `BUSINESS_OWNER`, `MANAGER`, or `STAFF` Membership.
- Render a responsive Business list containing only approved presentation
  metadata: display name, raw slug presented as “Идентификатор в уеб адреса”,
  BusinessType presented as “Дейност”, and translated status. The typed response
  retains
  `updatedAt`, `timezone`, `version`, `createdAt`, and its other authoritative
  application data for later operations and detail, audit, or support views,
  but these fields are intentionally omitted from list presentation as a UX and
  metadata-minimization decision, not as a security boundary.
- Request page 0 with size 50 by default. Provide accessible previous/next
  controls, the current page, and total count without adding search, filters, or
  client-selected sorting.
- Provide Bulgarian loading, empty, retry, authentication-required,
  access-denied, and generic safe-error states.
- Keep all interactive controls keyboard-operable and prevent duplicate
  submissions or requests while the relevant action is busy.

The permanent UI guide owns the complete visual specification. Slice A must use
its desktop sidebar, mobile header/navigation, desktop table, mobile cards,
translated status badges, and shared state patterns.

### Expected implementation area

Expected new focused modules include typed navigation, platform API/types, a
platform shell, a Business list, and their component tests. Expected existing
changes are limited to the root application/tests, the shared frontend request
helper, and global styles. Exact filenames must be confirmed in the slice
proposal before editing.

The root application continues to own the authenticated session. A small typed
hash-navigation helper parses the approved locations and handles hash changes
and browser history. The shared request helper may preserve safe HTTP status,
problem code, and Bulgarian detail so the UI can handle 401, 403, validation,
and unexpected failures without parsing message prose. Platform data and
security values remain out of browser storage.

### Focused tests

- platform shell/navigation visibility for `platformAdmin: true`;
- absence of platform navigation for ordinary authenticated users and every
  Business Membership role;
- default list request, approved metadata, and deterministic server order;
- loading, empty, error, retry, and pagination boundary behavior;
- hash navigation plus browser back/forward behavior;
- safe structured API-error extraction;
- duplicate-request protection;
- existing identity component tests and proof that no authentication secret is
  written to browser storage.

### Slice exclusions

Do not add create, detail/edit, lifecycle, or invitation behavior. Do not add a
router, search, filters, advanced pagination, backend changes, or dependencies.

### Mandatory manual visual checkpoint

Slice A must stop after its focused automated verification for manual visual
review. Do not start Slice B until the human has reviewed the live UI, supplied
UX feedback, and explicitly approved continuation.

Use this exact local procedure from the repository root:

```bash
test -e .env || cp .env.example .env
docker compose up -d postgres
docker compose ps

cd backend
export POSTGRES_USER=spotyourslot
export POSTGRES_PASSWORD='the-value-from-the-repository-root-.env-file'
export BOOTSTRAP_ADMIN_ENABLED=true
export BOOTSTRAP_ADMIN_EMAIL='admin@example.invalid'
export BOOTSTRAP_ADMIN_DISPLAY_NAME='Local Administrator'
export BOOTSTRAP_ADMIN_PASSWORD='replace-with-a-long-local-only-password'
./mvnw spring-boot:run
```

In a second terminal:

```bash
cd frontend
npm ci
npm run dev
```

In a browser:

1. open `http://localhost:5173`;
2. sign in with the explicitly configured local-only platform administrator;
3. open `http://localhost:5173/#/platform/businesses`;
4. review at approximately 360 px mobile width and at desktop width;
5. use keyboard-only navigation and browser Back/Forward;
6. inspect loading, populated or empty, pagination, denied, and retry states
   available in the prepared local fixtures;
7. confirm existing account, password-change, Business-selection, and logout
   behavior remains reachable.

Remove the bootstrap environment variables after the bootstrap startup. Keep
`.env` untracked and never use a production credential. Stop the backend with
Ctrl-C and stop local containers from the repository root with:

```bash
docker compose down
```

This preserves the named PostgreSQL volume. Wait for human UX feedback and
approval before any Slice B work.

## Slice B — create, view, and edit Businesses

Progress: Implemented, manually reviewed as part of the final combined
rendered-browser checkpoint, and human-approved.

### User-visible behavior

- Add a Bulgarian “Нов бизнес” flow for slug, display name, BusinessType,
  description, structured optional Bulgarian address (city, postal code,
  street, street number, and additional details), phone, and contact email.
- Omit timezone from the UI; creation relies on the backend `Europe/Sofia`
  default and editing preserves the authoritative stored timezone.
- After HTTP 201, show the created Business detail with status `DRAFT` without
  displaying technical version metadata.
- Show only approved Business detail metadata.
- Start with read-only profile details and expose explicit “Редактирай”,
  “Запази промените”, and “Отказ” actions. Prepopulate edit mode and send the
  current internal `expectedVersion`; never allow the client to select status,
  replace persisted version, or supply creation/update timestamps.
- Present safe Bulgarian validation and slug-conflict feedback. A concurrency
  conflict must offer a clear reload action and must not silently overwrite.
- Use natural Bulgarian labels, visible validation, accessible focus handling,
  keyboard submission, responsive layout, and duplicate-submission protection.

### State and API handling

- Use the existing POST, GET, and PUT platform Business contracts exactly.
- Treat BusinessType as the seven approved fixed values and descriptive only.
- Apply useful HTML/client validation without duplicating or replacing
  Business-owned backend validation.
- Keep owner invitation email out of the Business form and state.
- Keep returned version/status authoritative in application state and refresh
  displayed state after successful mutations; version is not presentation
  content.

### Focused tests

- exact create and update requests;
- 201 navigation and DRAFT rendering without visible version metadata;
- all approved BusinessType options and backend-defaulted timezone behavior;
- approved detail metadata and editable-field boundary;
- inability to submit status, replacement version, or timestamps;
- required fields, documented length boundaries, and optional fields;
- safe validation, duplicate-slug, and stale-version recovery;
- accessible labels, focus/error behavior, keyboard submission, responsive
  structure, and duplicate-submission protection;
- all Slice A and existing identity regressions.

### Slice exclusions

Do not add lifecycle actions, invitations, public previews, Business settings
beyond the existing profile contract, backend changes, or dependencies.

### Manual checkpoint

After Slice B checks pass, repeat the exact Slice A local startup procedure.
Open `http://localhost:5173/#/platform/businesses`, then review list → create →
detail → edit at approximately 360 px and desktop widths. Use keyboard-only
operation, submit representative valid and invalid Bulgarian content, confirm
the Business remains DRAFT after creation, and review safe conflict
recovery. Stop services with the exact Slice A shutdown procedure. Stop for
human review and wait for UX feedback and explicit approval before Slice C.

## Slice C — lifecycle actions and owner invitation

Progress: Implemented, manually reviewed as part of the final combined
rendered-browser checkpoint, and human-approved. The invitation flows were
manually verified.

### User-visible behavior

- A DRAFT Business offers a separate owner-invitation form and activation.
- An ACTIVE Business offers suspension.
- A SUSPENDED Business offers reactivation.
- Show only the lifecycle action valid for the current status.
- Require explicit confirmation for activation, suspension, and reactivation.
- Send the displayed `expectedVersion`, update from the successful response,
  and provide reload recovery for concurrent changes.
- Explain a missing-owner activation safely without claiming that the UI can
  inspect owner readiness.
- Label owner invitation email separately from Business contact email.
- Before resending to the same owner email in the current detail-page session,
  explain that the previous active invitation will be replaced and require
  confirmation.
- After HTTP 202, state only that the invitation request was accepted. Do not
  claim delivery, acceptance, Membership creation, or readiness.
- Treat the successful empty HTTP 202 body as success rather than attempting to
  decode JSON. The earlier false invitation failure was caused by the shared
  request helper parsing that empty success body; it was not a backend rejection.
- Keep confirmation, status, and error feedback accessible, keyboard-operable,
  responsive, and protected from duplicate submission.

### State and API handling

- Lifecycle POST bodies contain only `expectedVersion`.
- Owner invitation POST bodies contain only `email` and expect no response body.
- Do not persist owner emails, invitation status, invitation tokens, Membership
  data, or security data in browser storage.
- Treat backend lifecycle, owner, concurrency, authentication, and authorization
  responses as authoritative safe problem responses.

### Focused tests

- status-specific action visibility;
- confirmation and cancellation for all three lifecycle actions;
- exact endpoints and expected-version payloads;
- successful returned status/internal-version updates;
- missing-owner, invalid-lifecycle, and stale-version recovery;
- separate contact and owner email behavior;
- exact owner-invitation request and 202 success wording;
- resend confirmation for the same email;
- no claim of delivery, acceptance, Membership creation, or readiness;
- keyboard operation, focus/status announcements, and duplicate-submission
  protection;
- all earlier frontend and identity regressions.

### Slice exclusions

Do not add invitation history/status, owner-readiness preflight, Membership
management, real email, Business-user setup, backend changes, or dependencies.

### Manual checkpoint

After Slice C checks pass, repeat the exact Slice A local startup procedure.
Use `http://localhost:5173/#/platform/businesses` to review create → invite →
activation attempt → suspend → reactivate. Where prepared local/test delivery
allows it, open the accepted invitation link in a separate signed-out browser
context and return to the administrator flow; do not treat mailbox appearance as
production delivery. Review confirmations, same-email resend warning,
missing-owner and stale-version recovery, keyboard operation, and 360 px/desktop
layouts. Stop services with the exact Slice A shutdown procedure. Stop for
human review and wait for UX feedback and explicit approval before Slice D.

## Slice D — feedback, accessibility, verification, and documentation

Progress: Completed. The corrected visual presentation and invitation flows
were manually verified and human-approved. Issue #7 end-to-end verification is
separate.

### Scope

- Apply only approved feedback from the A–C visual checkpoints.
- Review natural Bulgarian text, information hierarchy, responsive behavior,
  keyboard order, visible focus, semantic headings/landmarks, form labels,
  confirmation semantics, loading/status announcements, error focus, contrast,
  and long-content behavior.
- Preserve every existing identity flow and every accepted platform flow.
- Update only permanent documentation that would otherwise be inaccurate, and
  record concrete completion evidence in this task following repository
  convention.

### Tests and completion verification

- run the narrowest affected component tests after each approved correction;
- run the complete frontend lint, test, and production build checks;
- review every acceptance criterion against a named automated test or manual
  verification;
- inspect the final diff and status for unrelated files, dependencies,
  generated output, secrets, unsafe error text, browser-storage secrets, and
  later-phase work;
- apply the AGENTS.md formatting/readability rules to every changed TypeScript,
  TSX, CSS, and documentation file;
- if Java is unexpectedly changed, stop because it is outside this task; any
  separately approved Java change must also pass the mandatory compressed-body
  search from AGENTS.md;
- run `git diff --check` before handoff.

### Final manual checkpoint

Repeat the exact Slice A local startup procedure. In the browser, exercise the
complete issue #6 journey from `http://localhost:5173`, including identity
regressions and `http://localhost:5173/#/platform/businesses`. Review at 360 px
and desktop widths, zoom to 200%, use keyboard-only navigation and browser
Back/Forward, and verify safe loading, empty, error, confirmation, conflict, and
success states. Stop services with the exact Slice A shutdown procedure. Wait
for final human UX acceptance before marking this task complete.

## Complete issue #6 acceptance criteria

1. Existing identity flows remain functional and tested.
2. Only a session reporting `platformAdmin: true` is offered platform
   navigation; backend authorization remains authoritative.
3. Hash navigation supports the approved URLs and browser Back/Forward without
   a routing dependency.
4. PLATFORM_ADMIN can list Businesses with bounded pagination and approved
   metadata.
5. PLATFORM_ADMIN can create a DRAFT Business and view its read-only details;
   technical version remains internal for mutations.
6. PLATFORM_ADMIN can edit only approved profile fields with `expectedVersion`.
7. Concurrency, validation, slug conflict, authentication, and authorization
   failures produce safe actionable Bulgarian UI without internal detail.
8. Only the status-appropriate activation, suspension, or reactivation action
   is offered, and each requires confirmation.
9. Owner invitation email remains separate from contact email; requests use the
   approved API and same-email resend requires confirmation.
10. Invitation success wording claims only request acceptance.
11. No authentication secret or sensitive platform state is stored in browser
    persistence or exposed in diagnostics.
12. Forms and actions use natural Bulgarian, accessible semantics, keyboard
    operation, responsive layouts, and duplicate-submission protection.
13. Focused tests and the complete frontend lint/test/build suite pass.
14. Mandatory live visual checkpoints are completed and human feedback is
    accepted before subsequent work and final completion.
15. Each accepted slice is independently reviewed and committed; no slice is
    silently combined with later work.
16. Formatting/readability and `git diff --check` pass.
17. No unapproved backend, migration, dependency, public-booking, workforce,
    deployment, issue #7, or later-phase change is introduced.
18. No credential, generated output, or unrelated file is committed.

## Completion evidence

The corrected issue #6 implementation was completed and human-approved on
2026-09-06.
Slice A was previously reviewed, committed, and human-approved. The remaining
implementation adds typed create/detail routes, exact Business and owner-
invitation API clients, create and profile-edit forms, authoritative
`expectedVersion` handling, explicit conflict reload, status-specific confirmed
lifecycle actions, and same-email invitation resend confirmation.

Automated evidence:

- `BusinessForm.test.tsx`, `BusinessCreate.test.tsx`, and
  `BusinessDetail.test.tsx` cover
  structured-address field boundaries, safe local feedback, DRAFT creation,
  read-only/edit/cancel behavior, profile updates, conflict recovery, lifecycle
  confirmations, returned status/internal-version updates,
  owner/contact email separation, invitation wording, error focus, and
  duplicate-submission protection;
- `BusinessList.test.tsx`, `PlatformAdminShell.test.tsx`,
  `navigation.test.ts`, and `App.test.tsx` cover platform-only navigation,
  approved hash routes,
  list/create/detail integration, pagination, request cancellation, identity
  regressions, and absence of browser-persistence writes;
- `api.test.ts` verifies exact list, create, retrieval, update, lifecycle, and
  invitation requests through the credentialed CSRF-aware request helper;
- complete-suite counts and build evidence are refreshed in the final handoff
  after this correction.

The corrected visual layout and invitation flows were manually verified in a
live browser and human-approved. Automated tests and CSS inspection remain
separate from that rendered visual evidence. Issue #7 retains the final full
end-to-end Business onboarding and lifecycle verification.

## Explicit exclusions

Apart from the explicitly approved structured-address V4 correction, do not
modify backend code, APIs, security, migrations, dependencies, lockfiles, CI,
Compose, deployment, hosting, or production email. Do not add React Router, a
component library, Playwright, or another dependency.

Do not implement public Business pages, public booking, Services, StaffMembers,
schedules, availability, Customers, Appointments, Business-user configuration,
Membership management, invitation status/history, owner-readiness preflight,
issue #7, or Phase 4+ functionality.

Do not commit or push unless the human explicitly approves the exact Git
operation. Stop after every slice and every mandatory checkpoint for review.
