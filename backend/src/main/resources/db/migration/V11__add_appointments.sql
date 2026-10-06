-- Appointments with database overlap protection (ADR-0022, issue #18 Phase 2).
--
-- One Appointment is one booked time of one StaffMember for one Customer of one Business. The
-- table and its overlap exclusion are created together, so no schema without overlap protection
-- exists. Only CONFIRMED blocks time; CANCELLED rows are retained and never block. Cancellation
-- attribution, COMPLETED and NO_SHOW are deferred and arrive by forward migrations.
--
-- Every reference is a composite same-Business foreign key. There is deliberately no foreign key
-- to staff_member_service: an assignment may be removed later and its history is not stored, so
-- eligibility is a transactional rule, not a schema rule. No Customer name, phone, or email is
-- copied; the Customer record is the only home of contact data.
--
-- start_at, end_at and occupied_until are UTC instants. end_at is start_at plus the snapshotted
-- duration as elapsed time (DST-correct). The current zero-buffer policy requires occupied_until
-- to equal end_at; a later approved buffer relaxes that one constraint by a forward migration.
--
-- Nullability is explicit: every column is NOT NULL except customer_note and the four idempotency
-- columns, which are all present or all absent (and always present for ONLINE rows). CHECK
-- constraints are written NULL-safe and are never the only guard against NULL.
--
-- Constraint and index names are stable on purpose: the persistence layer translates failures
-- from the PostgreSQL SQLState plus the exact constraint name.

CREATE TABLE appointment (
    id uuid PRIMARY KEY,
    business_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    service_id uuid NOT NULL,
    staff_member_id uuid NOT NULL,
    source varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    occupied_until timestamptz NOT NULL,
    timezone varchar(100) NOT NULL,
    duration_minutes integer NOT NULL,
    price_eur numeric(12,2) NOT NULL,
    service_name varchar(200) NOT NULL,
    staff_display_name varchar(200) NOT NULL,
    customer_note varchar(500),
    public_reference varchar(10) NOT NULL,
    booking_attempt_hash bytea,
    request_fingerprint bytea,
    fingerprint_encoding_version smallint,
    fingerprint_key_version smallint,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    CONSTRAINT appointment_business_fk
        FOREIGN KEY (business_id) REFERENCES business(id) ON DELETE RESTRICT,
    CONSTRAINT appointment_customer_fk
        FOREIGN KEY (business_id, customer_id)
        REFERENCES customer(business_id, id) ON DELETE RESTRICT,
    CONSTRAINT appointment_service_fk
        FOREIGN KEY (business_id, service_id)
        REFERENCES service(business_id, id) ON DELETE RESTRICT,
    CONSTRAINT appointment_staff_member_fk
        FOREIGN KEY (business_id, staff_member_id)
        REFERENCES staff_member(business_id, id) ON DELETE RESTRICT,

    CONSTRAINT appointment_source_valid CHECK (source IN ('ONLINE', 'MANUAL')),
    CONSTRAINT appointment_status_valid CHECK (status IN ('CONFIRMED', 'CANCELLED')),

    CONSTRAINT appointment_instants_finite CHECK (
        pg_catalog.isfinite(start_at)
        AND pg_catalog.isfinite(end_at)
        AND pg_catalog.isfinite(occupied_until)
    ),
    CONSTRAINT appointment_duration_minutes_range
        CHECK (duration_minutes BETWEEN 1 AND 480),
    CONSTRAINT appointment_end_matches_duration CHECK (
        end_at = start_at + duration_minutes * INTERVAL '1 minute'
    ),
    CONSTRAINT appointment_occupied_until_equals_end CHECK (occupied_until = end_at),
    CONSTRAINT appointment_price_nonnegative CHECK (price_eur >= 0),

    CONSTRAINT appointment_timezone_not_blank CHECK (
        pg_catalog.char_length(timezone) > 0
        AND timezone = pg_catalog.btrim(timezone)
    ),
    CONSTRAINT appointment_service_name_canonical CHECK (
        pg_catalog.char_length(service_name) > 0
        AND service_name = pg_catalog.btrim(
            pg_catalog.regexp_replace(
                pg_catalog.normalize(service_name, 'NFKC'),
                U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+',
                ' ',
                'g'
            )
        )
    ),
    CONSTRAINT appointment_staff_display_name_canonical CHECK (
        pg_catalog.char_length(staff_display_name) > 0
        AND staff_display_name = pg_catalog.btrim(
            pg_catalog.regexp_replace(
                pg_catalog.normalize(staff_display_name, 'NFKC'),
                U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+',
                ' ',
                'g'
            )
        )
    ),
    -- A plain-text note: non-empty after trimming, trimmed, at most 500 code points (the column
    -- length), and no control characters except a tab and a line feed.
    CONSTRAINT appointment_customer_note_plain_text CHECK (
        customer_note IS NULL OR (
            pg_catalog.char_length(customer_note) > 0
            AND customer_note = pg_catalog.btrim(customer_note, E' \t\r\n')
            AND customer_note !~ '[\x01-\x08\x0B-\x1F\x7F]'
        )
    ),

    -- Crockford base32 (no I, L, O, or U), uppercase, ten characters. Informational only: it is
    -- never an authority and no endpoint reads an Appointment by it.
    CONSTRAINT appointment_public_reference_format
        CHECK (public_reference ~ '^[0-9A-HJKMNP-TV-Z]{10}$'),

    CONSTRAINT appointment_attempt_hash_length CHECK (
        booking_attempt_hash IS NULL OR pg_catalog.octet_length(booking_attempt_hash) = 32
    ),
    CONSTRAINT appointment_fingerprint_length CHECK (
        request_fingerprint IS NULL OR pg_catalog.octet_length(request_fingerprint) = 32
    ),
    CONSTRAINT appointment_fingerprint_versions_positive CHECK (
        (fingerprint_encoding_version IS NULL OR fingerprint_encoding_version >= 1)
        AND (fingerprint_key_version IS NULL OR fingerprint_key_version >= 1)
    ),
    CONSTRAINT appointment_idempotency_all_or_none CHECK (
        (booking_attempt_hash IS NULL
            AND request_fingerprint IS NULL
            AND fingerprint_encoding_version IS NULL
            AND fingerprint_key_version IS NULL)
        OR (booking_attempt_hash IS NOT NULL
            AND request_fingerprint IS NOT NULL
            AND fingerprint_encoding_version IS NOT NULL
            AND fingerprint_key_version IS NOT NULL)
    ),
    CONSTRAINT appointment_online_requires_idempotency CHECK (
        source <> 'ONLINE' OR booking_attempt_hash IS NOT NULL
    ),

    CONSTRAINT appointment_version_nonnegative CHECK (version >= 0),
    CONSTRAINT appointment_timestamps_finite_ordered CHECK (
        pg_catalog.isfinite(created_at)
        AND pg_catalog.isfinite(updated_at)
        AND updated_at >= created_at
    ),

    CONSTRAINT appointment_business_id_id_unique UNIQUE (business_id, id),
    CONSTRAINT appointment_business_public_reference_unique
        UNIQUE (business_id, public_reference),
    -- NULL hashes are distinct, so any number of rows without an attempt may exist.
    CONSTRAINT appointment_business_attempt_hash_unique
        UNIQUE (business_id, booking_attempt_hash),

    -- Only CONFIRMED rows block one StaffMember's time. Half-open bounds allow adjacency.
    CONSTRAINT appointment_staff_no_overlap EXCLUDE USING gist (
        staff_member_id WITH =,
        tstzrange(start_at, occupied_until, '[)') WITH &&
    ) WHERE (status = 'CONFIRMED')
);
