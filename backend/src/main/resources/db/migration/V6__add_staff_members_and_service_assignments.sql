CREATE TABLE staff_member (
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
    contact_email varchar(320),
    contact_phone varchar(50),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    CONSTRAINT staff_member_business_fk
        FOREIGN KEY (business_id) REFERENCES business(id) ON DELETE RESTRICT,
    CONSTRAINT staff_member_display_name_canonical CHECK (
        display_name = pg_catalog.btrim(
            pg_catalog.regexp_replace(
                pg_catalog.normalize(display_name, 'NFKC'),
                U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+',
                ' ',
                'g'
            )
        )
    ),
    CONSTRAINT staff_member_display_name_not_blank
        CHECK (pg_catalog.char_length(display_name) > 0),
    CONSTRAINT staff_member_contact_email_canonical CHECK (
        contact_email IS NULL OR (
            pg_catalog.char_length(contact_email) > 0
            AND contact_email = pg_catalog.lower(
                pg_catalog.normalize(contact_email, 'NFKC')
            )
            AND contact_email !~ U&'^[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]'
            AND contact_email !~ U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]$'
        )
    ),
    CONSTRAINT staff_member_contact_phone_canonical CHECK (
        contact_phone IS NULL OR (
            contact_phone = pg_catalog.normalize(contact_phone, 'NFKC')
            AND contact_phone ~ '^\+?[0-9 ()./-]+$'
            AND pg_catalog.char_length(
                pg_catalog.regexp_replace(contact_phone, '[^0-9]', '', 'g')
            ) BETWEEN 3 AND 20
            AND contact_phone !~ U&'^[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]'
            AND contact_phone !~ U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]$'
        )
    ),
    CONSTRAINT staff_member_version_nonnegative CHECK (version >= 0),
    CONSTRAINT staff_member_business_id_id_unique UNIQUE (business_id, id)
);

CREATE INDEX staff_member_business_normalized_display_name_id_idx
    ON staff_member (business_id, normalized_display_name, id);

CREATE TABLE staff_member_service (
    business_id uuid NOT NULL,
    staff_member_id uuid NOT NULL,
    service_id uuid NOT NULL,

    CONSTRAINT staff_member_service_pkey
        PRIMARY KEY (business_id, staff_member_id, service_id),
    CONSTRAINT staff_member_service_staff_member_fk
        FOREIGN KEY (business_id, staff_member_id)
        REFERENCES staff_member(business_id, id) ON DELETE RESTRICT,
    CONSTRAINT staff_member_service_service_fk
        FOREIGN KEY (business_id, service_id)
        REFERENCES service(business_id, id) ON DELETE RESTRICT
);

CREATE INDEX staff_member_service_business_service_staff_idx
    ON staff_member_service (business_id, service_id, staff_member_id);
