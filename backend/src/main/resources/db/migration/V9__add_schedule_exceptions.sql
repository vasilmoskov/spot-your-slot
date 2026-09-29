-- Schedule exceptions (ADR-0013, ADR-0014): one versioned aggregate per
-- owner-visible exception, with its local periods as composed child rows.
-- btree_gist is installed by V7 and is not re-created here.
--
-- The database does not count child rows. Full-day aggregates having no
-- periods, partial closures/time off and additional working periods having at
-- least one, and an override having zero or more are enforced by the
-- scheduling domain content record and the store, not by this schema.

CREATE TABLE schedule_exception (
    id uuid PRIMARY KEY,
    business_id uuid NOT NULL,
    staff_member_id uuid,
    kind varchar(32) NOT NULL,
    first_date date NOT NULL,
    last_date date NOT NULL,
    all_day boolean NOT NULL,
    date_range daterange GENERATED ALWAYS AS (
        pg_catalog.daterange(first_date, last_date, '[]')
    ) STORED,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    CONSTRAINT schedule_exception_business_fk
        FOREIGN KEY (business_id) REFERENCES business(id) ON DELETE RESTRICT,
    -- A NULL staff_member_id skips this MATCH SIMPLE foreign key, which is
    -- correct for Business-wide closures; the scope check below requires it
    -- to be NULL only for BUSINESS_CLOSURE.
    CONSTRAINT schedule_exception_staff_member_fk
        FOREIGN KEY (business_id, staff_member_id)
        REFERENCES staff_member(business_id, id) ON DELETE RESTRICT,
    CONSTRAINT schedule_exception_business_id_id_unique UNIQUE (business_id, id),
    CONSTRAINT schedule_exception_kind_valid CHECK (kind IN (
        'BUSINESS_CLOSURE',
        'STAFF_TIME_OFF',
        'WORKING_DAY_OVERRIDE',
        'ADDITIONAL_WORKING_PERIODS')),
    CONSTRAINT schedule_exception_scope_matches_kind CHECK (
        (kind = 'BUSINESS_CLOSURE') = (staff_member_id IS NULL)),
    CONSTRAINT schedule_exception_dates_finite CHECK (
        pg_catalog.isfinite(first_date) AND pg_catalog.isfinite(last_date)),
    CONSTRAINT schedule_exception_date_order CHECK (first_date <= last_date),
    CONSTRAINT schedule_exception_range_needs_all_day CHECK (
        all_day OR first_date = last_date),
    CONSTRAINT schedule_exception_working_kinds_single_date CHECK (
        kind IN ('BUSINESS_CLOSURE', 'STAFF_TIME_OFF')
        OR (NOT all_day AND first_date = last_date)),
    CONSTRAINT schedule_exception_version_nonnegative CHECK (version >= 0),
    CONSTRAINT schedule_exception_timestamps_ordered CHECK (updated_at >= created_at),

    -- Only aggregates of the same kind and scope may conflict; cross-kind
    -- overlaps are legitimate and are resolved by the availability engine.
    CONSTRAINT schedule_exception_closure_no_overlap
        EXCLUDE USING gist (business_id WITH =, date_range WITH &&)
        WHERE (kind = 'BUSINESS_CLOSURE'),
    CONSTRAINT schedule_exception_staff_kind_no_overlap
        EXCLUDE USING gist (
            business_id WITH =,
            staff_member_id WITH =,
            kind WITH =,
            date_range WITH &&)
        WHERE (kind <> 'BUSINESS_CLOSURE')
);

CREATE INDEX schedule_exception_business_first_date_id_idx
    ON schedule_exception (business_id, first_date, last_date, id);

CREATE INDEX schedule_exception_business_date_range_idx
    ON schedule_exception USING gist (business_id, date_range);

-- Aggregate composition: a period has no lifecycle outside its exception, so
-- deleting the aggregate removes its periods in the same statement.
CREATE TABLE schedule_exception_period (
    business_id uuid NOT NULL,
    exception_id uuid NOT NULL,
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    minute_range int4range GENERATED ALWAYS AS (
        pg_catalog.int4range(
            (
                pg_catalog.date_part('hour', start_time)::integer * 60
                + pg_catalog.date_part('minute', start_time)::integer
            ),
            (
                pg_catalog.date_part('hour', end_time)::integer * 60
                + pg_catalog.date_part('minute', end_time)::integer
            ),
            '[)'
        )
    ) STORED,

    CONSTRAINT schedule_exception_period_pkey
        PRIMARY KEY (business_id, exception_id, start_time, end_time),
    CONSTRAINT schedule_exception_period_exception_fk
        FOREIGN KEY (business_id, exception_id)
        REFERENCES schedule_exception(business_id, id) ON DELETE CASCADE,
    CONSTRAINT schedule_exception_period_minute_precision CHECK (
        pg_catalog.date_part('second', start_time) = 0
        AND pg_catalog.date_part('second', end_time) = 0
    ),
    CONSTRAINT schedule_exception_period_clock_time_range CHECK (
        start_time < TIME '24:00:00'
        AND end_time < TIME '24:00:00'
    ),
    CONSTRAINT schedule_exception_period_valid_range CHECK (start_time < end_time),
    CONSTRAINT schedule_exception_period_no_overlap
        EXCLUDE USING gist (exception_id WITH =, minute_range WITH &&)
);
