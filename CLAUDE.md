# Claude repository instructions

## Repository authority

- Read and follow `AGENTS.md` completely before taking task actions.
- Treat committed code, tests, permanent documentation, accepted ADRs, task
  records, current GitHub issues, and explicitly approved user decisions as
  authoritative sources.
- Inspect the repository directly. Do not substitute summaries or assumptions
  for current evidence.
- Identify and report conflicts between authoritative sources instead of
  silently choosing an interpretation.

## Risk-based workflow

Use the lightest workflow that still provides credible evidence for the
change's risk level; when a change spans categories, use the highest
applicable level.

- **Fast**: documentation-only changes, isolated test changes, and low-risk
  local refactoring. Inspection, editing, and verification may happen in one
  run.
- **Standard**: ordinary domain, application, persistence, and internal
  adapter work. Bounded inspection and implementation may combine when the
  task document and approved scope are already precise.
- **Strict**: migrations, authentication, authorization, tenant isolation,
  transaction boundaries, concurrency, locking, cross-module contracts, and
  public API contracts. Requires read-only inspection, a concrete
  implementation plan, and explicit approval before persistent changes.

Any required scope expansion, unresolved contract decision, destructive
action, or unexpected repository state requires stopping for approval at
every risk level. Explicit phase-approval requirements in task documents
remain authoritative.

## Discovering the current state

At the start of a new issue, perform full orientation:

- Inspect `git status`, the current branch, recent history, and relevant diffs.
- Read the roadmap, implementation plan, applicable task records, permanent
  documentation, and relevant ADRs.
- Inspect the current GitHub issue hierarchy and SpotYourSlot Project board with
  read-only `gh` commands when authentication and network access are available.
- Distinguish completed and committed behavior from planned, open, or partially
  implemented work.
- Never infer implementation completion from Project board status alone.
- Report unavailable GitHub access instead of inventing issue or board state.

For a later phase of an already-oriented issue, start narrower: the current
task document, applicable ADRs, recent relevant commits, the current working
tree, and the directly affected production and test code. Expand into
permanent documentation, broader history, GitHub Project state, or unrelated
modules only to resolve a real ambiguity or requirement, and do not
re-discover already committed and documented facts without a concrete
reason. Prefer a fresh agent session per major implementation phase, relying
on committed task documents and ADRs for durable context. Ask for missing
context only when repository and available evidence cannot resolve a
material ambiguity.

## Approval and scope

- Scale inspection and approval to risk level, per Risk-based workflow above.
  For Strict work, perform read-only inspection first, report the exact
  intended files, behavior, verification commands, assumptions, and
  identified conflicts, and wait for explicit approval before persistent
  edits or state-changing verification. Standard and Fast work may proceed
  directly once scope is clear.
- Modify only the approved scope. Stop and request direction if a required
  change would expand it, or if a destructive action or unexpected
  repository state is encountered.
- Do not stage, commit, push, create or switch branches, open pull requests, or
  edit issues or the Project board unless explicitly authorized. Creating a
  review archive is never such authorization.
- Preserve unrelated user changes and untracked files.

## Engineering expectations

- Follow the existing modular-monolith boundaries and repository conventions.
- Preserve tenant isolation, authorization, Business lifecycle rules, safe
  public errors, optimistic concurrency, and deterministic behavior.
- Never expose SQL diagnostics, credentials, secrets, internal security state,
  or cross-tenant record existence.
- Use the pinned real PostgreSQL environment for PostgreSQL-specific persistence
  and concurrency claims.
- Coordinate concurrency tests deterministically without sleeps.
- Never edit an existing Flyway migration.
- Prefer the smallest implementation that fully satisfies the approved
  contract.
- Do not begin a later phase or adjacent capability without separate approval.

## Verification and reporting

- Run the narrowest relevant checks first, then the approved broader
  verification, scaled to risk level: Fast work normally uses relevant
  text/static checks and does not run unrelated backend or frontend suites;
  Standard work uses focused tests and one complete verification once the
  implementation is stable; Strict work adds the relevant real-database,
  security, module-boundary, race, migration, or contract verification. Do
  not repeatedly run the complete suite during normal iteration; rerun the
  affected focused tests and complete verification when a correction changes
  production behavior, transaction semantics, security, concurrency, or
  schema behavior. A purely archival or metadata operation must not rerun
  tests.
- Distinguish implementation failures from environmental or sandbox failures.
- Never weaken a valid test merely to obtain a passing build.
- Verify the exact changed-file scope, formatting, migration integrity,
  generated artifacts, staged state, secret exclusions, and final Git status.
- Report limitations and remaining risks directly.
- Never claim that a command or test passed unless it ran successfully.

## Visual and manual validation

- Explicitly notify the user when functionality becomes visually testable.
- Provide concise manual verification steps for frontend, responsive,
  accessibility, browser, email-rendering, and other user-visible behavior.
- Do not treat automated tests as a substitute for requested human visual
  validation.

## Review archives

- After successful implementation and required verification, create the
  review archive in the same run unless the user explicitly excludes it; do
  not wait for a separate archive prompt. Skip archive creation if required
  verification fails. A correction request creates a refreshed final archive
  in the same run once its required verification succeeds.
- Write SpotYourSlot review ZIP archives directly beside the repository under
  `/Users/vasilmoskov/dev/projects/`.
- Never place review archives inside the repository or under `/tmp`.
- Build each archive from an explicit allowlist.
- Include the changed files, necessary committed context, complete patch, exact
  Git status, HEAD identifier, and inventory. Scale evidence and context to
  risk level: add migration hashes, expanded committed context, and
  exhaustive member checksums only for Strict work or when the protected
  scope requires them.
- Exclude `.git`, environment files, credentials, build output, caches, logs,
  reports, screenshots unless specifically needed, database data, and previous
  archives.
- Validate archive integrity, patch scope, inventory, checksum, secret
  exclusions, and unchanged repository status.
- Do not rerun tests merely to create an archive; archive creation and
  validation must never modify repository content or staging.

## Collaboration model

- Claude may inspect, reason about, and propose architecture. Material
  architectural or product decisions require explicit user approval before
  implementation.
- Implementation receives independent review outside the implementing agent.
- Evaluate review findings against repository evidence and correct them through
  the same inspect–propose–approve workflow.
- Keep explanations and handoffs concise, evidence-based, and suitable for
  another engineer to verify.
