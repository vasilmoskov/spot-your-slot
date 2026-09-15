CREATE TABLE service (
    id uuid PRIMARY KEY,
    business_id uuid NOT NULL,
    name varchar(200) NOT NULL,
    normalized_name text COLLATE pg_catalog.pg_unicode_fast
        GENERATED ALWAYS AS (
            pg_catalog.normalize(
                pg_catalog.casefold(
                    pg_catalog.btrim(
                        pg_catalog.regexp_replace(
                            pg_catalog.normalize(name, 'NFKC'),
                            U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+',
                            ' ',
                            'g'
                        )
                    ) COLLATE pg_catalog.pg_unicode_fast
                ),
                'NFKC'
            )
        ) STORED,
    description varchar(2000),
    duration_minutes integer NOT NULL,
    price numeric(12,2) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    CONSTRAINT service_business_fk
        FOREIGN KEY (business_id) REFERENCES business(id) ON DELETE RESTRICT,
    CONSTRAINT service_name_canonical CHECK (
        name = pg_catalog.btrim(
            pg_catalog.regexp_replace(
                pg_catalog.normalize(name, 'NFKC'),
                U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+',
                ' ',
                'g'
            )
        )
    ),
    CONSTRAINT service_name_not_blank CHECK (pg_catalog.char_length(name) > 0),
    CONSTRAINT service_description_canonical CHECK (
        description IS NULL OR (
            pg_catalog.char_length(description) > 0
            AND description = pg_catalog.normalize(description, 'NFKC')
            AND description !~ U&'^[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]'
            AND description !~ U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]$'
        )
    ),
    CONSTRAINT service_duration_minutes_range
        CHECK (duration_minutes BETWEEN 1 AND 480),
    CONSTRAINT service_price_nonnegative CHECK (price >= 0),
    CONSTRAINT service_version_nonnegative CHECK (version >= 0),
    CONSTRAINT service_business_id_id_unique UNIQUE (business_id, id),
    CONSTRAINT service_business_normalized_name_unique
        UNIQUE (business_id, normalized_name)
);
