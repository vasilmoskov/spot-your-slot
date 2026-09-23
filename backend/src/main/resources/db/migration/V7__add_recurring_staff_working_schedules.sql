CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE staff_working_schedule (
    business_id uuid NOT NULL,
    staff_member_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    CONSTRAINT staff_working_schedule_pkey
        PRIMARY KEY (business_id, staff_member_id),
    CONSTRAINT staff_working_schedule_staff_member_fk
        FOREIGN KEY (business_id, staff_member_id)
        REFERENCES staff_member(business_id, id) ON DELETE RESTRICT,
    CONSTRAINT staff_working_schedule_version_nonnegative CHECK (version >= 0)
);

INSERT INTO staff_working_schedule(
    business_id, staff_member_id, version, created_at, updated_at)
SELECT business_id, id, 0, created_at, created_at
FROM staff_member;

CREATE TABLE staff_working_period (
    business_id uuid NOT NULL,
    staff_member_id uuid NOT NULL,
    weekday smallint NOT NULL,
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

    CONSTRAINT staff_working_period_pkey
        PRIMARY KEY (
            business_id, staff_member_id, weekday, start_time, end_time),
    CONSTRAINT staff_working_period_schedule_fk
        FOREIGN KEY (business_id, staff_member_id)
        REFERENCES staff_working_schedule(business_id, staff_member_id)
        ON DELETE RESTRICT,
    CONSTRAINT staff_working_period_weekday_range CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT staff_working_period_minute_precision CHECK (
        pg_catalog.date_part('second', start_time) = 0
        AND pg_catalog.date_part('second', end_time) = 0
    ),
    CONSTRAINT staff_working_period_clock_time_range CHECK (
        start_time < TIME '24:00:00'
        AND end_time < TIME '24:00:00'
    ),
    CONSTRAINT staff_working_period_valid_range CHECK (start_time < end_time),
    CONSTRAINT staff_working_period_no_overlap
        EXCLUDE USING gist (
            business_id WITH =,
            staff_member_id WITH =,
            weekday WITH =,
            minute_range WITH &&
        )
);
