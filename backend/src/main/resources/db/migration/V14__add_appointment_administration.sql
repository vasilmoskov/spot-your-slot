-- Appointment administration columns (ADR-0028 section 8 and 9, ADR-0029, issue #19 Phase 2).
--
-- Adds the cancellation attribution (cancelled_at, cancelled_by) and the private internal_note.
-- All three columns are nullable with no default, so adding them is a metadata-only change that
-- does not rewrite the table file.
--
-- Rows that were CANCELLED before this migration have no recorded actor or time, because no
-- cancellation capability existed. They are backfilled with the explicit actor 'UNKNOWN' and a
-- null cancelled_at; the time is not derived from updated_at because it was never maintained for
-- them. This backfill physically updates those pre-existing CANCELLED tuples (new row versions);
-- it does not touch version, updated_at, or any other column, and CONFIRMED rows are not touched.
-- Application code never writes 'UNKNOWN'.
--
-- The cancellation rule is written NULL-safe with IS NOT DISTINCT FROM: a CHECK passes when its
-- expression is NULL, so a plain comparison against a nullable actor would let a CANCELLED row
-- without an actor through. The internal note is independent of the lifecycle: it may be present
-- on CONFIRMED and CANCELLED rows alike, so no constraint ties it to the status.
--
-- Constraint names are stable on purpose: the persistence layer translates failures from the
-- PostgreSQL SQLState plus the exact constraint name.

ALTER TABLE appointment
    ADD COLUMN cancelled_at timestamptz,
    ADD COLUMN cancelled_by varchar(16),
    ADD COLUMN internal_note varchar(500);

UPDATE appointment
SET cancelled_by = 'UNKNOWN'
WHERE status = 'CANCELLED';

-- The backfill queued the deferred whole-visit trigger of V13 for each updated row, and PostgreSQL
-- refuses ALTER TABLE while trigger events are pending. Judging them now, inside this same
-- transaction, is the only effect of this statement; the checks are the V13 rules and must hold.
SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE appointment
    ADD CONSTRAINT appointment_cancellation_consistent CHECK (
           (status = 'CONFIRMED'
               AND cancelled_at IS NULL
               AND cancelled_by IS NULL)
        OR (status = 'CANCELLED'
               AND cancelled_by IS NOT DISTINCT FROM 'BUSINESS'
               AND cancelled_at IS NOT NULL)
        OR (status = 'CANCELLED'
               AND cancelled_by IS NOT DISTINCT FROM 'UNKNOWN'
               AND cancelled_at IS NULL)
    ),
    ADD CONSTRAINT appointment_cancelled_at_valid CHECK (
        cancelled_at IS NULL
        OR (pg_catalog.isfinite(cancelled_at) AND cancelled_at >= created_at)
    ),
    -- The plain-text rule of customer_note (V11): non-empty after trimming, trimmed, at most 500
    -- code points (the column length), and no control characters except a tab and a line feed.
    ADD CONSTRAINT appointment_internal_note_plain_text CHECK (
        internal_note IS NULL OR (
            pg_catalog.char_length(internal_note) > 0
            AND internal_note = pg_catalog.btrim(internal_note, E' \t\r\n')
            AND internal_note !~ '[\x01-\x08\x0B-\x1F\x7F]'
        )
    );

-- Verification: every CANCELLED row is a legacy row with the explicit unknown actor and no time,
-- and every CONFIRMED row carries no attribution. The internal note is deliberately not judged
-- here: it is valid on any status. A wrong backfill fails the migration (and rolls it back).
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM appointment a
        WHERE (a.status = 'CONFIRMED'
                AND (a.cancelled_at IS NOT NULL OR a.cancelled_by IS NOT NULL))
           OR (a.status = 'CANCELLED'
                AND (a.cancelled_by IS DISTINCT FROM 'UNKNOWN' OR a.cancelled_at IS NOT NULL))
    ) THEN
        RAISE EXCEPTION 'appointment cancellation backfill is inconsistent'
            USING ERRCODE = '23514';
    END IF;
END
$$;
