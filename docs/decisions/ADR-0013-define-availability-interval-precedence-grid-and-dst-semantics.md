# ADR-0013: Define availability interval, precedence, grid, and DST semantics

## Metadata

- **Status:** Accepted
- **Decision date:** 2026-09-29
- **Recorded date:** 2026-09-29
- **Related issues:** #16
- **Supersedes:** None
- **Superseded by:** None

## Context and problem

Issue #16 must calculate the appointment start times a Business can genuinely
offer. Correctness depends on interval boundaries, how schedule exceptions
combine with recurring weekly periods, the slot grid, minimum notice, the
booking horizon, and daylight-saving transitions in the authoritative Business
timezone. Recurring periods (ADR-0012) are local wall-clock values without an
offset. Later phases will persist exceptions and expose orchestration, so the
semantics must be fixed before either is designed.

## Constraints

- The Business `ZoneId` is authoritative; instants are UTC.
- Recurring periods use one-minute precision and `00:00` through `23:59`
  (ADR-0012); an exact-midnight end is not representable.
- Appointments will occupy half-open `[start_at, occupied_until)` ranges
  (`architecture.md`), so adjacency is not a conflict.
- The MVP has a fixed 15-minute slot grid, a two-hour minimum notice, a 30-day
  booking window, and zero buffers (`product-spec.md`, issue #16 decisions).
- The engine must be pure and deterministic: no clock, repository, or security
  access. Availability reserves nothing.
- Only JDK `java.time` is used.

## Options considered

### Merge overlapping or adjacent working periods

Merging simplifies later subtraction, but a Service could then bridge the
boundary between two originally separate periods, including two periods that
merely touch. The owner defined them separately, so this is rejected.

### Anchor the slot grid to each working period

A period starting at 09:10 would offer 09:10, 09:25, and so on. Customers would
see different minute patterns per StaffMember. Anchoring to the Business-local
wall clock keeps one predictable pattern.

### Represent DST time by shifting local times

`ZonedDateTime.ofLocal` shifts a gap time forward by the gap length and picks
the earlier offset in an overlap. A period ending inside a gap would then
extend working time, and a wall-clock range in an overlap would silently become
one contiguous range that includes unrelated minutes. This is rejected.

### Split the timeline at offset transitions

Convert each local period through constant-offset timeline pieces. A gap
belongs to no piece; an overlap belongs to two. This is selected.

## Decision

### Effective working periods

For one StaffMember and one Business-local date:

```
base      = override for the date when present; otherwise recurring periods
            for the date's weekday
working   = base plus additional working periods for the date
available = working minus Business closures, StaffMember time off, and busy
            intervals
```

- An override is StaffMember-scoped and replaces every recurring period for its
  date. A present override with no periods removes the day's recurring periods.
- Additional working periods are StaffMember-scoped and augment the base.
- Business closures are Business-wide; time off is StaffMember-scoped. Both are
  full-day (a date range, inclusive) or a partial range on one local date.
  Working exceptions and partial blocks apply to one local date. Date ranges
  exist only conceptually for full-day closures and time off.
- Blocking intervals always win over working periods, overrides, and additional
  periods. A full-day block removes the entire local date.
- A blocking exception may overlap a busy interval. It never edits or cancels
  the appointment that interval represents; the busy interval still blocks.

### Interval rules

- All ranges are half-open `[start, end)`.
- `LocalPeriod` itself enforces whole-minute precision (no seconds or
  nanoseconds) and `start < end`, so every working, override, additional, and
  partial-block period is `00:00` through `23:59`; `24:00` is not representable.
- A slot occupies `[start, start + occupied duration)`. It must fit wholly inside
  one working segment and must not overlap any block or busy interval.
  Adjacency to a block or busy interval is allowed.
- Every recurring, override, and additional period stays a separate segment.
  Adjacent and overlapping periods are never merged, so a slot never crosses an
  original segment boundary. Overlapping segments may both yield the same start
  instant, which is deduplicated by instant.
- Buffers are zero in the MVP. The engine calls the elapsed span a slot occupies
  its *occupied duration* so a later approved buffer only changes that input.

### Slot grid

Candidate starts are aligned to the Business-local wall clock at `:00`, `:15`,
`:30`, and `:45`, never to a period start. A period starting off the grid
produces candidates from the next grid boundary; for example `09:10–10:30` with a
30-minute Service yields `09:15` through `10:00`. Durations are minute-precise.

### Minimum notice and horizon

- Earliest candidate start: `start >= now + 2 hours` (inclusive).
- Permitted dates: the Business-local date of `now` through that date plus 29
  days, exactly 30 calendar dates. The 31st date is excluded.
- `now` is a parameter. Business-configurable horizon and notice are a
  follow-up; no configuration is added.

The existing documentation states the horizon as "days in advance" without an
inclusive or exclusive boundary, so it does not contradict this reading.

### Timezone and DST

- The timeline is split into constant-offset pieces at every offset transition
  known to the zone rules. Local times inside a gap belong to no piece and never
  produce a candidate. A period edge inside a gap therefore resolves to the
  transition instant.
- A wall-clock range inside an overlap produces one part per occurrence. Both
  occurrences may yield slots, with distinct instants and offsets.
- Parts of the same original period that touch across a transition are joined
  into one segment, because elapsed time continues. Parts separated by other
  wall-clock minutes (a range inside a repeated hour) are not joined.
- Service duration is elapsed time between instants. A slot may therefore
  straddle a transition when it fits the segment in real elapsed time.
- A partial block is a wall-clock range and uses the same conversion, so it
  blocks every occurrence in an overlap.
- Slot identity and ordering use the start `Instant`; the start offset is kept
  so a repeated wall-clock time stays distinguishable.
- Transition dates are never hard-coded. Tests derive them from `ZoneRules`.

### Specific and any StaffMember

Calculation is per StaffMember. A small aggregation combines results by
distinct start instant and retains the eligible StaffMember IDs, ordered by
`UUID` natural order, for later deterministic assignment. Which identifiers a
public response reveals is not decided here.

### Determinism

Outputs are ordered by start instant, IDs by `UUID` order, and inputs are copied
defensively; input order never affects the result.

### Eligibility and appointments

The engine receives only eligible StaffMembers. Later orchestration enforces
ACTIVE Business, active Service, active StaffMember, and an active assignment.
Busy intervals are explicit inputs. The future application seam is a
`BusyIntervalSource` interface owned by `scheduling` and implemented by the
future `booking` module, so `scheduling` never depends on `booking`. It is not
implemented by this decision.

## Rationale

Keeping segments separate matches the owner's definition of split working time
and prevents bridging by construction. Wall-clock grid alignment gives
Customers one predictable pattern. Splitting the timeline at transitions gives
correct results for gaps, overlaps, and periods spanning a transition from one
rule instead of special cases, and reflects that working hours are wall-clock
commitments while Service duration is elapsed time.

## Tradeoffs and disadvantages

- Overlapping working inputs are accepted and evaluated separately, so
  redundant candidates are computed and deduplicated.
- A wall-clock partial block affects both occurrences in an overlap; an owner
  cannot block only one of them.
- The fixed policy values cannot be tuned per Business until the follow-up.
- Working periods cannot end at exact midnight (ADR-0012).
- Full-day semantics ignore the local date's real length (23 or 25 hours).

## Risks and mitigations

- **Boundary errors.** Deterministic tests cover exact fit, one minute short,
  the exact notice instant and the next nanosecond, and the first, last, and
  first-excluded horizon dates.
- **DST regressions.** Tests derive the Sofia gap and overlap from `ZoneRules`
  and assert local times, offsets, and instants.
- **Persistence overreach.** Later exception storage must not forbid every
  overlap between exception rows of a Business, StaffMember, and date. An
  override or additional period legitimately overlaps a closure or time off
  that subtracts from it. Only conflicting rows of the same effect and scope
  may be constrained. Database design requires separate approval.

## Consequences

Later phases store exceptions that map onto the engine's explicit inputs and
must not change these semantics without a superseding ADR. Availability remains
a current view; final conflict protection stays with appointment creation.

## Evidence

Direct evidence: issue #16; `product-spec.md`; `architecture.md`; ADR-0012;
`AvailabilityEngineTests` and `AvailabilityDstTests` in
`backend/src/test/java/bg/spotyourslot/scheduling/domain/`. The DST behavior of
`ZonedDateTime.ofLocal` is documented JDK behavior and was not re-verified
against primary documentation for this record (inference).

## Conditions for revisiting

Revisit for Business-configurable horizon, notice, or grid; approved buffers;
exact-midnight boundaries; per-occurrence blocking in an overlap; or a
requirement to merge working periods.
