-- Tightens the StaffMember telephone contract to a compact, E.164-compatible
-- canonical form (for example "+359895555777"), matching the application's
-- StaffMemberTextCanonicalizer/StaffMemberInputValidator contract.
--
-- Existing rows may still hold the previous, looser canonical form, which
-- allowed internal separators such as "+359 888 123 456" or a bare Bulgarian
-- national number such as "0895555777". Both forms are safely and
-- unambiguously convertible under the application's own rules (a leading "0"
-- is the Bulgarian national prefix, the product's default region; a leading
-- "+" already carries an explicit country code), so they are normalized
-- in place below before the stricter CHECK constraint is added.
--
-- A legacy value that starts with neither "0" nor "+" would be ambiguous
-- (no recoverable country code) and is intentionally left untouched by the
-- UPDATE below; if such a value exists, it will fail the new CHECK
-- constraint and abort this migration rather than being silently guessed at,
-- reset, or discarded.

UPDATE staff_member sm
SET contact_phone = CASE
        WHEN norm.stripped LIKE '00%' THEN '+' || substring(norm.stripped from 3)
        WHEN norm.stripped LIKE '0%' THEN '+359' || substring(norm.stripped from 2)
        ELSE norm.stripped
    END
FROM (
    SELECT id,
           pg_catalog.regexp_replace(contact_phone, '[[:space:]()./-]', '', 'g') AS stripped
    FROM staff_member
    WHERE contact_phone IS NOT NULL
) norm
WHERE sm.id = norm.id
  AND sm.contact_phone IS DISTINCT FROM (
        CASE
            WHEN norm.stripped LIKE '00%' THEN '+' || substring(norm.stripped from 3)
            WHEN norm.stripped LIKE '0%' THEN '+359' || substring(norm.stripped from 2)
            ELSE norm.stripped
        END
  );

ALTER TABLE staff_member
    DROP CONSTRAINT staff_member_contact_phone_canonical;

ALTER TABLE staff_member
    ADD CONSTRAINT staff_member_contact_phone_canonical CHECK (
        contact_phone IS NULL
        OR contact_phone ~ '^\+[1-9][0-9]{7,14}$'
    );
