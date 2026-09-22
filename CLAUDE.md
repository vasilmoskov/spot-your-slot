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

## Discovering the current state

At the start of a new working conversation:

- Inspect `git status`, the current branch, recent history, and relevant diffs.
- Read the roadmap, implementation plan, applicable task records, permanent
  documentation, and relevant ADRs.
- Inspect the current GitHub issue hierarchy and SpotYourSlot Project board with
  read-only `gh` commands when authentication and network access are available.
- Distinguish completed and committed behavior from planned, open, or partially
  implemented work.
- Never infer implementation completion from Project board status alone.
- Report unavailable GitHub access instead of inventing issue or board state.
- Ask for missing context only when repository and GitHub evidence cannot
  resolve a material ambiguity.

## Approval and scope

- Perform read-only inspection before proposing changes.
- Before modifying state, report the exact intended files, behavior,
  verification commands, assumptions, and identified conflicts.
- Wait for explicit approval before persistent edits or state-changing
  verification.
- Modify only the approved scope. Stop and request direction if a required
  change would expand it.
- Do not stage, commit, push, create or switch branches, open pull requests, or
  edit issues or the Project board unless explicitly authorized.
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
  verification.
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

- Create a review archive only when explicitly requested.
- Write SpotYourSlot review ZIP archives directly beside the repository under
  `/Users/vasilmoskov/dev/projects/`.
- Never place review archives inside the repository or under `/tmp`.
- Build each archive from an explicit allowlist.
- Include the changed files, necessary committed context, complete patch, exact
  Git status, HEAD identifier, and inventory.
- Exclude `.git`, environment files, credentials, build output, caches, logs,
  reports, screenshots unless specifically needed, database data, and previous
  archives.
- Validate archive integrity, patch scope, inventory, checksum, secret
  exclusions, and unchanged repository status.
- Do not rerun tests merely to create an archive.

## Collaboration model

- Claude may inspect, reason about, and propose architecture. Material
  architectural or product decisions require explicit user approval before
  implementation.
- Implementation receives independent review outside the implementing agent.
- Evaluate review findings against repository evidence and correct them through
  the same inspect–propose–approve workflow.
- Keep explanations and handoffs concise, evidence-based, and suitable for
  another engineer to verify.
