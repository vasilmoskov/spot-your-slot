-- Business-scoped Customer records (ADR-0019, issue #20).
--
-- A Customer is the Business's private contact record for a person: one display name and a
-- canonical phone and/or email. It has no lifecycle, note, account or Membership link, original
-- (raw) contact text, or Appointment reference. The same normalized phone or email may exist at
-- different Businesses but at most once within one Business; NULL values are distinct, so any
-- number of Customers may lack either identifier, while a Customer must have at least one.
--
-- phone: compact E.164 only. email: the canonical lowercase address. The email checks mirror the
-- accepted StaffMember contact_email checks (V6); address syntax is enforced by the application's
-- shared contact policy, not by the database.
--
-- Constraint and index names are stable on purpose: the persistence layer translates failures
-- from the PostgreSQL SQLState plus the exact constraint name.

CREATE TABLE customer (
    id uuid PRIMARY KEY,
    business_id uuid NOT NULL,
    display_name varchar(200) NOT NULL,
    normalized_display_name text COLLATE pg_catalog.pg_unicode_fast
        GENERATED ALWAYS AS (
            pg_catalog.normalize(
                pg_catalog.casefold(
                    pg_catalog.btrim(
                        pg_catalog.regexp_replace(
                            pg_catalog.normalize(display_name, 'NFKC'),
                            U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+',
                            ' ',
                            'g'
                        )
                    ) COLLATE pg_catalog.pg_unicode_fast
                ),
                'NFKC'
            )
        ) STORED,
    phone varchar(16),
    email varchar(320),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    CONSTRAINT customer_business_fk
        FOREIGN KEY (business_id) REFERENCES business(id) ON DELETE RESTRICT,
    CONSTRAINT customer_display_name_canonical CHECK (
        display_name = pg_catalog.btrim(
            pg_catalog.regexp_replace(
                pg_catalog.normalize(display_name, 'NFKC'),
                U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+',
                ' ',
                'g'
            )
        )
    ),
    CONSTRAINT customer_display_name_not_blank
        CHECK (pg_catalog.char_length(display_name) > 0),
    CONSTRAINT customer_phone_canonical CHECK (
        phone IS NULL OR phone ~ '^\+[1-9][0-9]{7,14}$'
    ),
    CONSTRAINT customer_email_canonical CHECK (
        email IS NULL OR (
            pg_catalog.char_length(email) > 0
            AND email = pg_catalog.lower(pg_catalog.normalize(email, 'NFKC'))
            AND email !~ U&'^[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]'
            AND email !~ U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]$'
        )
    ),
    CONSTRAINT customer_contact_present CHECK (phone IS NOT NULL OR email IS NOT NULL),
    CONSTRAINT customer_version_nonnegative CHECK (version >= 0),
    CONSTRAINT customer_timestamps_finite_ordered CHECK (
        pg_catalog.isfinite(created_at)
        AND pg_catalog.isfinite(updated_at)
        AND updated_at >= created_at
    ),
    CONSTRAINT customer_business_phone_unique UNIQUE (business_id, phone),
    CONSTRAINT customer_business_email_unique UNIQUE (business_id, email),
    CONSTRAINT customer_business_id_id_unique UNIQUE (business_id, id)
);

CREATE INDEX customer_business_normalized_display_name_id_idx
    ON customer (business_id, normalized_display_name, id);
