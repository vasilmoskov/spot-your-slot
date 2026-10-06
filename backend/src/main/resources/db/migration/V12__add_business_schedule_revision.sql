-- One revision row per Business (ADR-0025). Every availability-affecting schedule mutation advances
-- it with an UPDATE; every booking locks it FOR SHARE. The table is owned by the business module.
CREATE TABLE business_schedule_revision (
    business_id uuid NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL,

    CONSTRAINT business_schedule_revision_pkey
        PRIMARY KEY (business_id),
    CONSTRAINT business_schedule_revision_business_fk
        FOREIGN KEY (business_id)
        REFERENCES business(id) ON DELETE RESTRICT,
    CONSTRAINT business_schedule_revision_nonnegative CHECK (revision >= 0)
);

-- Existing Businesses start at revision 0, stamped with their creation time.
INSERT INTO business_schedule_revision(business_id, revision, updated_at)
SELECT id, 0, created_at
FROM business;

-- A new Business receives its row in the same statement and transaction that creates it, whichever
-- code path inserts it, so a Business without a revision row cannot be committed.
CREATE FUNCTION create_business_schedule_revision() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO business_schedule_revision(business_id, revision, updated_at)
    VALUES (NEW.id, 0, NEW.created_at);
    RETURN NEW;
END;
$$;

CREATE TRIGGER business_create_schedule_revision
    AFTER INSERT ON business
    FOR EACH ROW
    EXECUTE FUNCTION create_business_schedule_revision();
