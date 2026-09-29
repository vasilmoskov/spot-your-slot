# Task 04e: Business configuration journey — end-to-end verification (issue #15)

Status: implemented (Phases 1–3); awaiting final review. The GitHub issue is not closed or edited by this work.

## Goal

Add deterministic Playwright verification of the Business-owner configuration
journey delivered by issues #11–#14, through rendered React, the real Spring
Boot HTTP boundary, and a PostgreSQL database migrated from empty by Flyway
(V1–V8, unchanged). Detailed validation, concurrency, and tenant-isolation
matrices remain in the lower-level suites.

## Reused infrastructure (unchanged)

Playwright `@playwright/test` 1.63.0 (Chromium), `scripts/run-e2e.sh` with the
fixed `spotyourslot-e2e` Compose project and ports `55432`/`18080`/`15173`,
`frontend/playwright.config.ts` (serial, one worker, no retries, screenshots/
traces/videos off), the redacting reporter, and the CI `e2e` job. No dependency,
runner, config, or production change is made by this issue.

## Accepted product decisions superseding stale issue #15 wording

Reviewed issue #14 decisions take precedence over these statements in the
GitHub issue text. The GitHub issue itself is not modified.

1. **Schedule timezone** — the authoritative Business timezone remains in the
   backend schedule contract but its visible text was intentionally removed
   from the schedule UI. E2E does not assert visible `Europe/Sofia` copy; the
   backend/API tests own the timezone contract.
2. **StaffMember account explanation** — the paragraph stating that a
   StaffMember is not a login account was intentionally removed. E2E does not
   assert it. StaffMember/account separation is not asserted through the
   browser; see "StaffMember and account separation" below.
3. **DRAFT/ACTIVE badge** — the lifecycle badge beside `УПРАВЛЕНИЕ НА БИЗНЕСА`
   was intentionally removed. DRAFT and ACTIVE are proven by successful
   mutations; SUSPENDED is proven by the shared read-only banner and
   unavailable mutation controls.

## Test architecture

- `frontend/e2e/support/` — shared helpers: `environment.ts` (required
  environment and API origin), `browser.ts` (sign-in, public session, cookie,
  storage, and overflow assertions), `mailbox.ts` (protected development
  mailbox and invitation acceptance), `provisioning.ts` (deterministic Business
  and owner provisioning through the administrator UI and invitation flow).
  `business-onboarding-lifecycle.spec.ts` now imports these helpers; its
  scenario is unchanged.
- `frontend/e2e/business-configuration-journey.spec.ts` — a file-level
  `beforeAll` provisions Business A and its owner in three isolated contexts
  (administrator, owner A, signed-out), all through legitimate UI/API paths.
  Steps that genuinely build on prior state run in a `serial` describe; focused
  negative tests use fresh pages of the owner context and their own uniquely
  named records. Credentials and invitation URLs remain in memory only.
- Fictional data uses distinct names, slugs, and `example.invalid` addresses so
  it cannot collide with the onboarding spec sharing the same disposable
  database.

## Phase plan

1. **Phase 1 (implemented)** — support module, provisioning, sole-Business
   auto-selection and Services landing, Service create/edit/deactivate/
   reactivate with duration and EUR presentation and reload persistence,
   StaffMember create/edit/deactivate/reactivate with optional-contact
   validation, assignment add/remove/restore with inactive Services not
   offered, focused Service negatives (local validation,
   backend duplicate-name rejection), administrator/owner context independence.
2. **Phase 2 (implemented)** — working schedule (weekdays, split periods, ordering,
   persistence, edit/remove, invalid/overlap, inactive StaffMember), unsaved-
   changes guard, ACTIVE → SUSPENDED → ACTIVE lifecycle, storage (including
   IndexedDB), authorization and Business B/owner B isolation, stale feedback.
3. **Phase 3 (implemented)** — Pixel 7 smoke, overflow and keyboard checks, CI only if
   required, documentation, and complete verification.

## StaffMember and account separation

An earlier Phase 1 draft attempted a sign-in with a StaffMember's contact
email and the owner's password. A 401 is equally expected when an account
exists with a different password, because the authentication contract is
intentionally enumeration-safe, so that test proved nothing and was removed.
No account-enumeration endpoint is added and the browser never inspects the
database.

Separation is a backend/domain invariant covered at the persistence level by
`StaffSchemaIntegrationTests.staffMemberColumnsMatchApprovedSchema` and
`StaffSchemaIntegrationTests.recurringSchedulesAddNoMembershipLinkOrLaterWorkforceTables`
(`backend/src/test/java/bg/spotyourslot/integration/StaffSchemaIntegrationTests.java`),
which assert that `staff_member` has the approved columns and no
`membership_id` (or user) link. (`ModuleBoundaryTests` only verifies that the
module graph is acyclic and is not cited as proof of this invariant.)

**Coverage gap (reported, no backend work added under issue #15):** no test
counts `app_user` or `membership` rows before and after StaffMember creation
through the application service or API. The structural guarantee exists; a
behavioral row-count assertion does not. Adding one would be a small
lower-level backend test and needs separate approval.

## Phase 1 verification

- `./scripts/run-e2e.sh` (run twice): 13 tests passed both times; the disposable
  Compose project, network, and volume were removed; the development container
  and volume were untouched.
- ESLint over `frontend/e2e` is clean.

## Phase 2 (implemented)

`business-configuration-journey.spec.ts` gained two describes and an isolation
describe; `support/browser.ts` gained `apiRequest` (a same-session, CSRF-aware
request sent from the page that returns only status, public problem code, and
public response text) and a stricter storage assertion that also covers
IndexedDB and confirms the HttpOnly session cookie is not script-visible.

Serial journey (continues the Phase 1 state):

- **Schedule** — Monday split day (entered out of order), Wednesday, Friday;
  the local overlap rejection in the period dialog; save; weekday/start-time
  order asserted from the rendered chips; persistence after route navigation
  and a full reload; editing one period and removing another; an inactive
  StaffMember's schedule is read-only in the UI and a forced PUT is rejected
  with `STAFF_MEMBER_INACTIVE`. No timezone text is asserted.
- **Unsaved changes** — the dirty Service edit form: sidebar navigation opens
  the shared dialog, `Продължи редактирането` is initially focused, continuing
  preserves the value and route, and discarding completes the original
  navigation without saving.
- **Lifecycle** — DRAFT edits succeed (Phase 1); the admin context activates
  and the owner still edits; the admin suspends and the owner sees the shared
  banner, readable data on every surface, no mutation controls, forced
  create/deactivate/schedule-replace requests rejected with
  `BUSINESS_SUSPENDED`, and Profile, the password section, and logout still
  available; the admin reactivates and editing and all data are restored.
  No status badge is asserted.
- **Isolation** — Business B and owner B are provisioned through the same
  invitation flow. Owner B sees only Business B; guessed Business A service and
  StaffMember identifiers return the safe 404s in the UI and API and leave
  Business A unchanged; the platform administrator without a Membership gets
  403 from private configuration endpoints and has no owner navigation; the
  owner shell has exactly four links and no platform controls (and
  `/api/platform/businesses` returns 403); `localStorage`, `sessionStorage`,
  and IndexedDB are empty in all three contexts; the three session cookies
  differ (compared in memory only).

Test-architecture note: a Playwright timeout in a serial describe restarts the
worker and re-runs the file-level setup. Fixture provisioning is therefore
collision-safe across worker restarts: `uniqueBusinessFixture` (in
`support/provisioning.ts`) is called inside each setup hook and returns a copy of
the fixture template with a random 8-hex suffix appended to the lowercase slug
and to the owner email's local part, while the display name stays stable. The
generated fixture object is the only source of the slug and email used for
creation, invitation, mailbox lookup, and sign-in; no literal is duplicated. A
re-run therefore creates a fresh Business and owner instead of failing with a
duplicate-slug 409 that would mask the original failure. No database deletion,
direct SQL, or volume removal is involved; superseded fixtures simply remain in
the disposable database until the runner removes it.

### Phase 2 verification

`./scripts/run-e2e.sh` was run after the final edit to the spec; the final two
consecutive runs after the fixture-uniqueness correction each executed 25 tests and passed, and each removed the
disposable Compose project, network, and volume. The development container and
volume were untouched. `npm run lint` and `git diff --check` are clean.

## Phase 3 (implemented)

- **Mobile smoke** — the last describe of `business-configuration-journey.spec.ts`
  signs in as owner A in a Pixel 7 context and reuses the data created by the
  desktop journey. It opens the navigation by keyboard, checks initial focus,
  Escape and focus return, identifies the active Business in the open menu,
  reaches Services, Staff, and Working Schedule by keyboard, checks list rows
  and the schedule for readable content and viewport visibility, and asserts no
  horizontal overflow on every page. The administrator context then suspends
  the Business, the mobile page shows the shared banner with no schedule edit
  control and unchanged data, and a `finally` block reactivates the Business.
- **CI** — no change. The existing `e2e` job already runs the same script after
  the backend and frontend jobs with pinned Playwright, always-run validated
  cleanup, no artifact upload, and `contents: read`; the suite runs in well under
  its 20-minute timeout. (The job's step label still says "onboarding and
  lifecycle"; it was left unchanged because a rename is not required.)
- **Documentation** — `README.md` (E2E coverage, V1–V8, cleanup), `docs/testing-strategy.md`
  (new browser-configuration section and reconciled "no browser coverage yet"
  statements), and `docs/implementation-plan.md` were updated.

### Acceptance reconciliation

| Issue #15 criterion | Evidence |
|---|---|
| Complete owner configuration journey through the rendered app | Serial describes in `business-configuration-journey.spec.ts` |
| Services create/edit/deactivate/reactivate | "owner creates, edits, deactivates and reactivates a Service…" |
| Staff create/edit/deactivate/reactivate | Staff tests (create, edit, deactivate/reactivate) |
| Active Services assignable to active staff; inactive not offered | "owner assigns, removes and restores Service assignments" |
| Multiple weekdays, split days, ordering | Schedule tests; ordering asserted from rendered chips |
| Persistence after navigation and reload | Service, staff, assignment, and schedule tests |
| DRAFT and ACTIVE editable | DRAFT tests; "activating the Business keeps configuration editable" |
| SUSPENDED read-only with preserved data; forced mutations rejected | "a SUSPENDED Business is readable but rejects every configuration mutation" |
| Reactivation restores editing without data loss | "reactivating the Business restores editing…" |
| Owner/admin/unauthorized context isolation; cross-Business reads/writes rejected | Isolation describe (owner B, admin without Membership, owner shell, storage, cookies) |
| Critical negatives with safe Bulgarian feedback | Service validation, duplicate name, schedule overlap, inactive-staff schedule |
| Real PostgreSQL and all Flyway migrations | Runner: empty database migrated by Spring Boot startup (V1–V8) |
| Desktop journey plus mobile smoke | Desktop describes; "Pixel 7 owner reaches every destination…" |
| Existing suites pass | Backend `verify`, frontend test/lint/build (below) |
| Local and CI execution/cleanup documented | `README.md`, `docs/testing-strategy.md` |
| No secrets or generated artifacts committed | Redacting reporter; artifacts disabled; archive secret scan |

Discrepancies and partial coverage (all reported, none silently dropped):

- Visible schedule timezone, the StaffMember account explanation, and the
  DRAFT/ACTIVE badge are intentionally not asserted (accepted issue #14 decisions).
- Issue text "identify the correct Business context" is verified through the
  session response and the Business name in the open mobile menu / shell.
- "Stale feedback does not survive Business, route, entity, or operation
  changes" is covered by the unit tests of `useFeedback` and the Business-scoped
  components (issue #14); the browser suite has no dedicated stale-feedback
  test, so this criterion is not verified at browser level.
- "Profile, password change, Business switching, and logout remain available
  where authorized" — Profile, the password section, and the logout control are
  asserted present and enabled while SUSPENDED; logout, a password change, and
  Business switching are not exercised (a single-Business owner has no switch;
  logout would end the shared session).
- "Another Business context cannot render or mutate" is covered with owner B
  against Business A entities; PLATFORM_ADMIN-with-owner-Membership behavior is
  backend-tested only.
- StaffMember/account separation: the documented backend row-count evidence gap
  above is unchanged.

### Phase 3 verification

Results are reported in the Phase 3 handoff (backend 1033 tests, frontend 494
tests in 34 files, two consecutive E2E runs of 26 tests). Commands: `cd backend && ./mvnw --batch-mode verify`;
`cd frontend && npm ci && npm run test && npm run lint && npm run build`;
`./scripts/run-e2e.sh`; `git diff --check`.
