-- Read indexes for the Business calendar and the Customer Appointment history (ADR-0028
-- sections 11 and 12, issue #21 Phase 3).
--
-- Both indexes were justified by EXPLAIN (ANALYZE, BUFFERS) on the committed query shapes over
-- about 10,000 and 50,000 synthetic Appointments (the evidence is in the Phase 3 record of task
-- 09a); a third candidate, (business_id, staff_member_id, start_at, id), was measured and rejected.
--
-- appointment_business_start_idx serves the calendar and agenda window reads, which filter by
-- Business and the half-open overlap window and are ordered by (start_at, id). Without it every
-- calendar navigation reads every Appointment the Business has ever had, because the existing
-- Business-leading unique indexes order by neither start nor window; with it the read is bounded by
-- the window (the start_at range is bounded below by the checked maximum duration of 480 minutes).
--
-- appointment_business_customer_start_idx serves the Customer history, ordered newest first. Without
-- it the planner scans the whole appointment table across every Business for each history page,
-- so the cost grows with the total number of Appointments of all tenants.
--
-- Indexes only: no table is rewritten and no data changes. Cancelled and confirmed rows are both
-- indexed on purpose (the reads show both). The statements run inside the migration's transaction,
-- so they take a SHARE lock on appointment (writes wait) for the build; the table is small enough
-- for the MVP, and CREATE INDEX CONCURRENTLY cannot run in a transaction.
CREATE INDEX appointment_business_start_idx
    ON appointment (business_id, start_at, id);

CREATE INDEX appointment_business_customer_start_idx
    ON appointment (business_id, customer_id, start_at DESC, id DESC);
