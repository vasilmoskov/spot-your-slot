ALTER TABLE business
    ADD COLUMN description varchar(2000),
    ADD COLUMN address varchar(500),
    ADD COLUMN phone varchar(50),
    ADD COLUMN contact_email varchar(320),
    ADD CONSTRAINT business_description_not_blank
        CHECK (description IS NULL OR description !~ '^[[:space:]]*$'),
    ADD CONSTRAINT business_address_not_blank
        CHECK (address IS NULL OR address !~ '^[[:space:]]*$'),
    ADD CONSTRAINT business_phone_not_blank
        CHECK (phone IS NULL OR phone !~ '^[[:space:]]*$'),
    ADD CONSTRAINT business_contact_email_not_blank
        CHECK (contact_email IS NULL OR contact_email !~ '^[[:space:]]*$'),
    ADD CONSTRAINT business_contact_email_lowercase
        CHECK (contact_email IS NULL OR contact_email = lower(contact_email));
