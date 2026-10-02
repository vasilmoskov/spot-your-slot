# ADR-0019: Model Business-scoped Customers with canonical contact identifiers, per-Business uniqueness, and no lifecycle

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-10-01
- **Recorded date:** 2026-10-01
- **Related issues:** #20 (future consumers: #18, #21)
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

Issue #20 introduces the first Customer record. No `customer` table or module
exists. The permanent documents described a Customer with a private staff note, an
active/blocked state and "original/normalized" contact values; the issue itself
places notes on the Appointment, defines no lifecycle, and requires phone and
email to be unique per Business. A Customer is also the key that a future guest
booking is matched against, so the contact canonicalization is an identity
decision, not only validation.

Inspection of the existing StaffMember contact policy found three gaps:

- backend telephone parsing (libphonenumber 9.0.40) accepts a trailing extension
  (`+359888123456 ext 5`, `x5`) and silently discards it, while the frontend
  (`^\+?\d+$` before parsing) rejects it;
- the email local-part dot rules (no leading or trailing `.` and no `..`) exist in
  the frontend and in the generic `@Email` check, but not in the central backend
  email policy;
- the policy classes live in `workforce.domain`, and a `customer` module must not
  depend on `workforce` for contact rules.

## Constraints

- A Customer belongs to exactly one Business; the same person may exist at another
  Business; nothing is global (issue #20, ADR-0003).
- A Customer never creates an account, credentials, Membership, platform access, or
  public profile.
- Names must never take part in automatic matching or uniqueness.
- Migrations are forward-only and immutable (ADR-0004); PostgreSQL is the source of
  truth (ADR-0002); optimistic concurrency follows ADR-0007 and ADR-0010.
- No second, incompatible telephone or email policy.
- No Appointment table or Appointment column is created by issue #20.

## Options considered

### Contact requirement: phone only, email only, both, or at least one

Requiring both blocks walk-in and phone bookings. Requiring none leaves a record
that can never be recognized and is duplicated on every booking. Phone-only or
email-only excludes legitimate Customers. **At least one is selected.**

### Name: one `displayName` or first and last name

Two fields invent structure that does not fit every naming convention and adds no
matching value. **One `displayName` is selected.**

### Contact storage: original plus normalized, or one canonical value

Keeping the original text duplicates personal data and creates a second value that
can disagree with the matching key. The only information lost by canonicalization
is formatting and, for email, case. **One canonical value is selected.**

### Contact policy location: copy into `customer`, depend on `workforce`, or share

A copy creates two policies that drift. Depending on `workforce` inverts a
sensible dependency. **A small shared contact-policy package is selected.**

### Lifecycle: none, deactivation, archive, or hard deletion

No Appointment exists yet, and no requirement needs a Customer state. Deletion
would break the future Appointment relationship and belongs with retention work.
**No lifecycle is selected.** A mistaken record is corrected by editing it, which
releases its identifiers.

## Decision

**Fields.** A Customer has an application-generated UUID, an immutable
`business_id`, one required `display_name`, an optional canonical `phone`, an
optional canonical `email`, a nonnegative `version`, and UTC `created_at` and
`updated_at`. It has no status, note, account link, or Appointment-derived column.

**Minimum contact.** A Customer requires a non-blank name and at least one valid
phone or email. It may be created with one identifier and gain the other later. One
identifier may be removed only while the other remains. A database check enforces
`phone IS NOT NULL OR email IS NOT NULL`.

**Name.** Unicode NFKC, approved whitespace collapsed to one space, trimmed, 1 to
200 code points, case preserved. A generated `normalized_display_name` (the
StaffMember expression: NFKC, whitespace collapse, trim, casefold under
`pg_unicode_fast`, NFKC) is used only for ordering and search. Names are not unique
and never participate in matching.

**Phone.** One shared, country-aware policy: separators (whitespace, `(`, `)`, `-`,
`.`) are ignored; a leading `+` is international; a leading `00` becomes `+`; a
leading `0` is Bulgaria; any other prefix is rejected, never guessed.
libphonenumber's full `isValidNumber` is required. After separator removal only
`^\+?\d+$` is accepted, and a parsed extension is rejected, so letters and
extensions are never silently dropped. The stored value is compact E.164
(`^\+[1-9][0-9]{7,14}$`, `varchar(16)`). Blank or null input is SQL NULL. The
backend is authoritative; the frontend mirrors the policy with libphonenumber-js,
and a shared golden-vector file (accepted, rejected, canonical output) is run by
both test suites so a library upgrade that changes behavior fails a test.

**Email.** One shared, ASCII-only policy: trim, NFKC, lowercase the entire address,
exactly one `@`, a non-empty local part without whitespace, a local part that does
not start or end with `.` or contain `..`, and a dotted domain of ASCII labels
that neither start nor end with `-`. Maximum 320 code points. The stored lowercase
value is both the display and the matching value. SMTP local parts are technically
case-sensitive; treating `A@x` and `a@x` as one identity is the accepted MVP
trade-off. No provider-specific folding (Gmail dots, `+tag`) is applied.
Internationalized addresses remain deferred.

**Shared package.** The text canonicalizer, phone policy, and email policy move to
a small `bg.spotyourslot.shared.contact` package. StaffMember delegates to it
during Phase 2 of issue #20, with its existing tests unchanged. The package stays
limited to these contact primitives so `shared` does not become a dumping ground.
This extraction is recorded here and in the task record; it needs no separate ADR.
Rejecting phone extensions and letters is a behavior change for API-only StaffMember
input that the frontend already rejects.

**Uniqueness.** `UNIQUE (business_id, phone)` and `UNIQUE (business_id, email)`.
PostgreSQL treats NULLs as distinct, so any number of Customers may lack either
value. The same value may exist at another Business. A shared phone therefore
identifies exactly one Customer per Business.

**Constraint translation.** Translation uses SQLState `23505` and the exact
constraint name reported by the PostgreSQL driver, never a pattern match on the
message, because the server message includes the offending value. The Customer
persistence layer rethrows a sanitized exception without the original cause.

**No lifecycle.** No active, inactive, blocked, archived, or deleted state exists,
and there is no hard delete, merge, or Customer note. The future Appointment
foreign key will be `(business_id, customer_id)` with `ON DELETE RESTRICT`, which
requires `UNIQUE (business_id, id)` on `customer`. Retention, export, anonymization,
merge, and legal deletion are explicitly deferred; the model stays compatible with
them because they can add nullable columns or a new decision without changing the
identity of a Customer.

**Versioning.** An accepted update increments `version` exactly once and sets
`updated_at` from the injected clock, including an update with unchanged values,
consistent with StaffMember. Customer matching never writes to an existing
Customer.

## Rationale

One canonical identifier gives a single deterministic matching key and minimizes
stored personal data. Database uniqueness is the authority; application checks only
improve error messages. Keeping names out of matching removes the most common
source of merging different people. Omitting a lifecycle keeps the smallest model
that satisfies the issue.

## Tradeoffs and disadvantages

- Original phone formatting and original email case are not preserved.
- A phone shared by a family can be held by only one Customer per Business; other
  members need a different phone or an email-only record.
- A mistaken Customer cannot be deleted, only corrected, and a Customer with no
  identifier cannot exist.
- Rejecting phone extensions rejects some legitimate business numbers. Extensions
  cannot be part of a matching key without a separate decision.
- The shared package changes where StaffMember rules live, and its extension
  rejection changes API-only StaffMember behavior.
- Frontend and backend libraries ship different metadata releases; the golden
  vectors detect drift but the backend can still reject a value the browser accepts.

## Risks and mitigations

- **Silent identifier collapse:** extensions and letters are rejected before parsing.
- **Policy drift:** one backend package, one frontend module, shared golden vectors.
- **Personal data in database diagnostics:** constraint translation by name and
  sanitized exceptions without a cause; normal paths avoid raising unique
  violations (ADR-0020).
- **Phone-policy change invalidating rows:** the database check validates only the
  E.164 shape, not libphonenumber metadata.
- **Cross-Business reference:** `UNIQUE (business_id, id)` and composite foreign
  keys, enforced by tests.

## Consequences

Phase 2 of issue #20 first extracts the shared contact policy and verifies
StaffMember behavior, then adds `V10__add_customers.sql` and the Customer
persistence. `V10` creates one table, changes no existing table, and needs no
backfill or extension. Documentation replaces the earlier Customer description
(staff note, blocked state, original/normalized values). ADR-0020 builds matching
on these identifiers, and ADR-0021 administers them.

## Evidence

Direct evidence: issue #20; `StaffMemberPhoneNumbers`, `StaffMemberEmailPolicy`,
`StaffMemberTextCanonicalizer`, `StaffMemberInputValidator`;
`frontend/src/business/staff/validation.ts`; V6 and V8; ADR-0002, ADR-0003,
ADR-0004, ADR-0007, ADR-0010; `docs/data-model.md`; `docs/product-spec.md`.

A libphonenumber 9.0.40 probe on 2026-10-01 returned `+359888123456` for
`+359888123456 ext 5` and `+359888123456x5`, and `+35929811234` for
`+359 2 981 1234 ext. 12`, confirming that extensions are accepted and discarded.

Inference: a frontend mirror without shared test vectors would drift at the next
metadata release; that is a judgment, not an observed failure.

## Conditions for revisiting

Revisit for verified or international Customer identity, internationalized email,
extensions as part of an identifier, a retention, anonymization, merge, or deletion
capability, Customer accounts, or a shared phone that must identify several
Customers.

## Phase 2 implementation notes

Clarifications from implementation (2026-10-01), with no decision changed:

- **Package and boundary.** The shared policy lives in `bg.spotyourslot.shared.contact`
  (`ContactTextCanonicalizer`, `ContactPhoneNumbers`, `ContactEmailPolicy`), declared a
  `@NamedInterface("contact")` of the `shared` module. The build adds the BOM-managed
  `spring-modulith-api` artifact (no version pinned) so the annotation compiles. Only
  `shared::contact` is exposed; `shared.web` and the other sub-packages stay internal, and
  `customer` and `workforce` reach `shared` only through it. Boundary tests pin this.
- **Email rules, final.** In addition to the rules above, the local part is at most 64 characters and
  each domain label at most 63. The approved local-part characters are the RFC 5322 `atext` set
  `A-Z a-z 0-9 ! # $ % & ' * + / = ? ^ _ ` { | } ~ -`, separated by single dots. Quoted-string local
  parts are not supported (the removed Jakarta `@Email` check allowed them); this is the only
  intentional narrowing. The Jakarta check is no longer applied to StaffMember: the shared policy is
  authoritative and sufficient.
- **Persistence classification.** The PostgreSQL driver (already a dependency, now compile scope)
  is used directly: `PSQLException`, `ServerErrorMessage`, `getConstraint()`, `getTable()`, and
  `getColumn()`, with the standard SQLState. Message text is never read, missing structured data is
  unexpected, and no translated exception retains the original throwable. Expected classifications
  are lightweight; `UnexpectedFailure` keeps its own normal stack trace (application frames only).
- **Application versus database email checks.** Email syntax (the rules above) is enforced only by
  the authoritative application policy `ContactEmailPolicy`. The database `customer_email_canonical`
  check enforces only the approved stored representation: non-blank, equal to its own lower-case NFKC
  form, and without leading or trailing approved whitespace. It does not validate address syntax.
