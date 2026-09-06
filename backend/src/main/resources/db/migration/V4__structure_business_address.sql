ALTER TABLE business
    RENAME COLUMN address TO address_details;

ALTER TABLE business
    RENAME CONSTRAINT business_address_not_blank TO business_address_details_not_blank;

ALTER TABLE business
    ADD COLUMN city VARCHAR(100),
    ADD COLUMN postal_code VARCHAR(20),
    ADD COLUMN street VARCHAR(200),
    ADD COLUMN street_number VARCHAR(50);

ALTER TABLE business
    ADD CONSTRAINT business_city_nonblank
        CHECK (city IS NULL OR city !~ '^[[:space:]]*$'),
    ADD CONSTRAINT business_postal_code_nonblank
        CHECK (postal_code IS NULL OR postal_code !~ '^[[:space:]]*$'),
    ADD CONSTRAINT business_street_nonblank
        CHECK (street IS NULL OR street !~ '^[[:space:]]*$'),
    ADD CONSTRAINT business_street_number_nonblank
        CHECK (street_number IS NULL OR street_number !~ '^[[:space:]]*$');
