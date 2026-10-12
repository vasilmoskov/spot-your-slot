-- Appointment Service lines for a multi-Service visit (ADR-0027, issue #18, phase M1).
--
-- An Appointment becomes a visit of one to five distinct Services performed consecutively by one
-- StaffMember. The Appointment row keeps its meaning of one booked time, one idempotency key, one
-- overlap range and one status; its duration_minutes and price_eur become the visit totals, its
-- service_id and service_name stay as the headline copy of line 1 (the first Service in the
-- public-profile order), and service_count says how many lines it has. The immutable per-Service
-- snapshots live in appointment_service.
--
-- The visit total can exceed one Service price: five Services of the largest valid price
-- (9 999 999 999.99) total 49 999 999 999.95, which does not fit numeric(12,2). Only the
-- visit-total column is widened (numeric(13,2)); service.price and the line prices stay
-- numeric(12,2). Widening the precision at the same scale leaves every stored value unchanged.
--
-- Every existing Appointment is backfilled with exactly one line copied from its own snapshot, so
-- no existing row changes meaning. The backfill is verified at the end of this migration, and only
-- then are the deferred whole-visit consistency trigger and the immutability trigger created, so the
-- backfill itself is never judged by a rule it could not yet satisfy.
--
-- Constraint and trigger names are stable on purpose: the persistence layer translates failures
-- from the PostgreSQL SQLState plus the exact constraint name.

ALTER TABLE appointment
    ALTER COLUMN price_eur TYPE numeric(13,2);

ALTER TABLE appointment
    ADD COLUMN service_count smallint NOT NULL DEFAULT 1;

ALTER TABLE appointment
    ADD CONSTRAINT appointment_service_count_range CHECK (service_count BETWEEN 1 AND 5);

CREATE TABLE appointment_service (
    appointment_id uuid NOT NULL,
    position smallint NOT NULL,
    business_id uuid NOT NULL,
    service_id uuid NOT NULL,
    service_name varchar(200) NOT NULL,
    duration_minutes integer NOT NULL,
    price_eur numeric(12,2) NOT NULL,

    CONSTRAINT appointment_service_pkey PRIMARY KEY (appointment_id, position),
    -- Same-Business references: a line cannot point at another Business's Appointment or Service.
    CONSTRAINT appointment_service_appointment_fk
        FOREIGN KEY (business_id, appointment_id)
        REFERENCES appointment(business_id, id) ON DELETE RESTRICT,
    CONSTRAINT appointment_service_service_fk
        FOREIGN KEY (business_id, service_id)
        REFERENCES service(business_id, id) ON DELETE RESTRICT,
    CONSTRAINT appointment_service_position_range CHECK (position BETWEEN 1 AND 5),
    -- The Services of one visit are distinct.
    CONSTRAINT appointment_service_distinct UNIQUE (appointment_id, service_id),
    CONSTRAINT appointment_service_duration_range CHECK (duration_minutes BETWEEN 1 AND 480),
    CONSTRAINT appointment_service_price_nonnegative CHECK (price_eur >= 0),
    -- The same canonical-name rule as appointment.service_name (V11).
    CONSTRAINT appointment_service_line_name_canonical CHECK (
        pg_catalog.char_length(service_name) > 0
        AND service_name = pg_catalog.btrim(
            pg_catalog.regexp_replace(
                pg_catalog.normalize(service_name, 'NFKC'),
                U&'[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+',
                ' ',
                'g'
            )
        )
    )
);

-- Supports the restrictive Service foreign key and Service-based lookups of later issues.
CREATE INDEX appointment_service_service_idx ON appointment_service (business_id, service_id);

-- Deterministic backfill: one line per existing Appointment, copied from its own snapshot.
INSERT INTO appointment_service(
    appointment_id, position, business_id, service_id, service_name, duration_minutes, price_eur)
SELECT id, 1, business_id, service_id, service_name, duration_minutes, price_eur
FROM appointment;

-- Verification: every Appointment has exactly its one backfilled line and the totals equal it. A
-- wrong backfill fails the migration (and rolls it back) instead of shipping.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM appointment a
        WHERE a.service_count <> 1
           OR (SELECT count(*) FROM appointment_service l WHERE l.appointment_id = a.id) <> 1
           OR NOT EXISTS (
                SELECT 1
                FROM appointment_service l
                WHERE l.appointment_id = a.id
                  AND l.position = 1
                  AND l.business_id = a.business_id
                  AND l.service_id = a.service_id
                  AND l.service_name = a.service_name
                  AND l.duration_minutes = a.duration_minutes
                  AND l.price_eur = a.price_eur)
    ) THEN
        RAISE EXCEPTION 'appointment_service backfill is inconsistent'
            USING ERRCODE = '23514';
    END IF;
END
$$;

-- The whole-visit consistency rule, evaluated at commit (the Appointment and its lines are written
-- in one transaction, in any order): the number of lines equals service_count, the positions are
-- exactly 1..service_count, the sums of the line durations and prices equal the visit totals, and
-- the headline columns equal line 1. The search_path is fixed at creation so the trigger resolves
-- its tables whatever the session uses.
CREATE FUNCTION appointment_visit_is_consistent() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path FROM CURRENT
AS $$
DECLARE
    target uuid;
    found_count integer;
    visit_count smallint;
    visit_duration integer;
    visit_price numeric;
    headline_service uuid;
    headline_name varchar;
    line_count bigint;
    line_duration bigint;
    line_price numeric;
    min_position smallint;
    max_position smallint;
    headline_matches boolean;
BEGIN
    IF TG_TABLE_NAME = 'appointment' THEN
        target := NEW.id;
    ELSE
        target := NEW.appointment_id;
    END IF;

    SELECT a.service_count, a.duration_minutes, a.price_eur, a.service_id, a.service_name
    INTO visit_count, visit_duration, visit_price, headline_service, headline_name
    FROM appointment a
    WHERE a.id = target;
    GET DIAGNOSTICS found_count = ROW_COUNT;
    IF found_count = 0 THEN
        -- The Appointment no longer exists in this transaction; nothing remains to be consistent.
        RETURN NULL;
    END IF;

    SELECT count(*),
           coalesce(sum(l.duration_minutes), 0),
           coalesce(sum(l.price_eur), 0),
           min(l.position),
           max(l.position),
           coalesce(bool_or(
               l.position = 1
               AND l.service_id = headline_service
               AND l.service_name = headline_name), false)
    INTO line_count, line_duration, line_price, min_position, max_position, headline_matches
    FROM appointment_service l
    WHERE l.appointment_id = target;

    IF line_count <> visit_count
       OR min_position IS DISTINCT FROM 1
       OR max_position IS DISTINCT FROM visit_count
       OR line_duration <> visit_duration
       OR line_price <> visit_price
       OR NOT headline_matches THEN
        RAISE EXCEPTION 'Appointment visit is inconsistent'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'appointment_visit_consistent',
                  TABLE = 'appointment';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER appointment_visit_consistent_on_appointment
    AFTER INSERT OR UPDATE ON appointment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION appointment_visit_is_consistent();

CREATE CONSTRAINT TRIGGER appointment_visit_consistent_on_line
    AFTER INSERT ON appointment_service
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION appointment_visit_is_consistent();

-- Lines are immutable snapshots: nothing updates or deletes one (nothing is ever hard-deleted).
CREATE FUNCTION appointment_service_is_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Appointment service lines are immutable'
        USING ERRCODE = '23000',
              CONSTRAINT = 'appointment_service_immutable',
              TABLE = 'appointment_service';
END;
$$;

CREATE TRIGGER appointment_service_immutable
    BEFORE UPDATE OR DELETE ON appointment_service
    FOR EACH ROW
    EXECUTE FUNCTION appointment_service_is_immutable();
