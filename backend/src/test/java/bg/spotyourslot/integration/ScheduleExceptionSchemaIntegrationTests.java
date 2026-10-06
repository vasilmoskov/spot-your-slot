package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ScheduleExceptionSchemaIntegrationTests extends PostgresIntegrationTest {
    private static final String BUSINESS_CLOSURE = "BUSINESS_CLOSURE";
    private static final String STAFF_TIME_OFF = "STAFF_TIME_OFF";
    private static final String WORKING_DAY_OVERRIDE = "WORKING_DAY_OVERRIDE";
    private static final String ADDITIONAL_WORKING_PERIODS = "ADDITIONAL_WORKING_PERIODS";

    private static final String V1_SHA_256 =
            "68cb25d6ccfd4e5aca12ec0b0199f13f3d0dd35418e45e1d7d2ef74a6832dc49";
    private static final String V2_SHA_256 =
            "c1b62d1fed08138a937f281d4e3952942cc0a5d5cbaf7ed4803dc030a60527f8";
    private static final String V3_SHA_256 =
            "655d22a52c06eb41996a75c100c1bfab853c907b9e0e076f128ba2ab8be674a2";
    private static final String V4_SHA_256 =
            "aa48255701e3ce6999073801ca0ed37e292b9ada545e3fe4a50221ada596cb98";
    private static final String V5_SHA_256 =
            "e2221627ed52ceb951881648d9738d213544ad4c8d79aa35e1e79c0896e958d3";
    private static final String V6_SHA_256 =
            "73e89120c0163d6ea79ee28f0d59ae02c055df85066234a3688f525ada16ff1b";
    private static final String V7_SHA_256 =
            "d9a8184b7c7c856064426d79dd375e7b4fb7f55a1fec389b7c391512286339b2";
    private static final String V8_SHA_256 =
            "2f9fb21a06d4f42e5a3f9b4f479a2bbae2470e4fad0800247aed816d624668e8";

    @Autowired
    JdbcClient jdbc;

    // ---- migration integrity -------------------------------------------------

    @Test
    void migrationsFromEmptyAreExactlyV1ThroughV12() {
        List<String> versions = jdbc.sql("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE type = 'SQL' AND success = true
                        ORDER BY installed_rank
                        """)
                .query(String.class)
                .list();

        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
    }

    @Test
    void previousMigrationsRemainByteForByteUnchanged() {
        assertThat(sha256("V1__identity_and_tenancy.sql")).isEqualTo(V1_SHA_256);
        assertThat(sha256("V2__enforce_single_active_identity_tokens.sql")).isEqualTo(V2_SHA_256);
        assertThat(sha256("V3__add_business_profile_fields.sql")).isEqualTo(V3_SHA_256);
        assertThat(sha256("V4__structure_business_address.sql")).isEqualTo(V4_SHA_256);
        assertThat(sha256("V5__add_business_services.sql")).isEqualTo(V5_SHA_256);
        assertThat(sha256("V6__add_staff_members_and_service_assignments.sql"))
                .isEqualTo(V6_SHA_256);
        assertThat(sha256("V7__add_recurring_staff_working_schedules.sql")).isEqualTo(V7_SHA_256);
        assertThat(sha256("V8__canonicalize_staff_member_contact_phone.sql"))
                .isEqualTo(V8_SHA_256);
    }

    @Test
    void upgradingV8PreservesExistingDataAndAddsEmptyExceptionTables() throws Exception {
        String schema = "v9_schedule_exception_upgrade";
        var dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .target("8")
                .load()
                .migrate();

        UUID businessId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-01T08:00:00Z");
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var business = connection.prepareStatement("""
                    INSERT INTO v9_schedule_exception_upgrade.business(
                        id, slug, display_name, business_type, status, timezone,
                        created_at, updated_at)
                    VALUES (?, ?, 'Upgrade Business', 'OTHER', 'DRAFT',
                            'Europe/Sofia', ?, ?)
                    """)) {
                business.setObject(1, businessId);
                business.setString(2, "upgrade-" + businessId);
                business.setObject(3, createdAt);
                business.setObject(4, createdAt);
                business.executeUpdate();
            }
            try (var staff = connection.prepareStatement("""
                    INSERT INTO v9_schedule_exception_upgrade.staff_member(
                        id, business_id, display_name, active, version,
                        created_at, updated_at)
                    VALUES (?, ?, 'Съществуващ', true, 0, ?, ?)
                    """)) {
                staff.setObject(1, staffId);
                staff.setObject(2, businessId);
                staff.setObject(3, createdAt);
                staff.setObject(4, createdAt);
                staff.executeUpdate();
            }
        }

        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement("""
                        SELECT (SELECT count(*) FROM v9_schedule_exception_upgrade.business),
                               (SELECT count(*) FROM v9_schedule_exception_upgrade.staff_member),
                               (SELECT count(*)
                                FROM v9_schedule_exception_upgrade.schedule_exception),
                               (SELECT count(*)
                                FROM v9_schedule_exception_upgrade.schedule_exception_period),
                               (SELECT max(version::integer)
                                FROM v9_schedule_exception_upgrade.flyway_schema_history
                                WHERE type = 'SQL')
                        """);
                var result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(1);
            assertThat(result.getLong(2)).isEqualTo(1);
            assertThat(result.getLong(3)).isZero();
            assertThat(result.getLong(4)).isZero();
            assertThat(result.getInt(5)).isEqualTo(12);
        }
    }

    // ---- schema shape --------------------------------------------------------

    @Test
    void schemaCreatesTheApprovedConstraintsAndIndexes() {
        List<String> constraints = jdbc.sql("""
                        SELECT conname
                        FROM pg_catalog.pg_constraint
                        WHERE conrelid IN (
                            'public.schedule_exception'::regclass,
                            'public.schedule_exception_period'::regclass)
                        ORDER BY conname
                        """)
                .query(String.class)
                .list();
        List<String> indexes = jdbc.sql("""
                        SELECT indexname
                        FROM pg_catalog.pg_indexes
                        WHERE schemaname = 'public'
                          AND tablename IN ('schedule_exception', 'schedule_exception_period')
                        ORDER BY indexname
                        """)
                .query(String.class)
                .list();

        assertThat(constraints)
                .contains(
                        "schedule_exception_pkey",
                        "schedule_exception_business_fk",
                        "schedule_exception_staff_member_fk",
                        "schedule_exception_business_id_id_unique",
                        "schedule_exception_kind_valid",
                        "schedule_exception_scope_matches_kind",
                        "schedule_exception_dates_finite",
                        "schedule_exception_date_order",
                        "schedule_exception_range_needs_all_day",
                        "schedule_exception_working_kinds_single_date",
                        "schedule_exception_version_nonnegative",
                        "schedule_exception_timestamps_ordered",
                        "schedule_exception_closure_no_overlap",
                        "schedule_exception_staff_kind_no_overlap",
                        "schedule_exception_period_pkey",
                        "schedule_exception_period_exception_fk",
                        "schedule_exception_period_minute_precision",
                        "schedule_exception_period_clock_time_range",
                        "schedule_exception_period_valid_range",
                        "schedule_exception_period_no_overlap");
        assertThat(constraints).doesNotContain("schedule_exception_dates_bounded");
        assertThat(indexes)
                .contains(
                        "schedule_exception_business_first_date_id_idx",
                        "schedule_exception_business_date_range_idx",
                        "schedule_exception_closure_no_overlap",
                        "schedule_exception_staff_kind_no_overlap",
                        "schedule_exception_period_no_overlap");
    }

    @Test
    void periodForeignKeyCascadesAndAllOtherForeignKeysRestrict() {
        List<String> deleteActions = jdbc.sql("""
                        SELECT conname || ':' || confdeltype::text
                        FROM pg_catalog.pg_constraint
                        WHERE contype = 'f'
                          AND conrelid IN (
                            'public.schedule_exception'::regclass,
                            'public.schedule_exception_period'::regclass)
                        ORDER BY conname
                        """)
                .query(String.class)
                .list();

        assertThat(deleteActions)
                .containsExactly(
                        "schedule_exception_business_fk:r",
                        "schedule_exception_period_exception_fk:c",
                        "schedule_exception_staff_member_fk:r");
    }

    @Test
    void generatedRangesUseOnlyImmutableFunctionsAndExactBounds() {
        List<String> volatility = jdbc.sql("""
                        SELECT candidate.signature || ':' || function.provolatile::text
                        FROM (VALUES
                            ('daterange(date,date,text)'),
                            ('isfinite(date)'),
                            ('date_part(text,time without time zone)'),
                            ('int4range(integer,integer,text)')
                        ) AS candidate(signature)
                        JOIN pg_catalog.pg_proc function
                          ON function.oid = pg_catalog.to_regprocedure(
                              'pg_catalog.' || candidate.signature)
                        ORDER BY candidate.signature
                        """)
                .query(String.class)
                .list();
        List<String> generated = jdbc.sql("""
                        SELECT table_class.relname || '.' || attribute.attname
                               || ':' || attribute.attgenerated::text
                        FROM pg_catalog.pg_attribute attribute
                        JOIN pg_catalog.pg_class table_class
                          ON table_class.oid = attribute.attrelid
                        WHERE table_class.relname IN (
                            'schedule_exception', 'schedule_exception_period')
                          AND attribute.attgenerated <> ''
                        ORDER BY 1
                        """)
                .query(String.class)
                .list();

        assertThat(volatility)
                .containsExactly(
                        "date_part(text,time without time zone):i",
                        "daterange(date,date,text):i",
                        "int4range(integer,integer,text):i",
                        "isfinite(date):i");
        assertThat(generated)
                .containsExactly(
                        "schedule_exception.date_range:s",
                        "schedule_exception_period.minute_range:s");
    }

    @Test
    void generatedDateRangeIsInclusiveOfTheLastDate() {
        UUID businessId = createBusiness();
        UUID id = insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-12-24", "2026-12-26", true);

        var bounds = jdbc.sql("""
                        SELECT lower(date_range)::text || '|' || upper(date_range)::text
                        FROM schedule_exception WHERE id = :id
                        """)
                .param("id", id)
                .query(String.class)
                .single();

        assertThat(bounds).isEqualTo("2026-12-24|2026-12-27");
    }

    // ---- kinds, scope and tenancy --------------------------------------------

    @Test
    void allFourKindsAreAcceptedWithTheirApprovedShapes() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);

        insertException(businessId, null, BUSINESS_CLOSURE, "2026-11-02", "2026-11-04", true);
        insertException(businessId, staffId, STAFF_TIME_OFF, "2026-11-02", "2026-11-04", true);
        UUID override = insertException(
                businessId, staffId, WORKING_DAY_OVERRIDE, "2026-11-05", "2026-11-05", false);
        insertException(
                businessId, staffId, ADDITIONAL_WORKING_PERIODS,
                "2026-11-05", "2026-11-05", false);

        assertThat(count("schedule_exception")).isEqualTo(4);
        assertThat(count("schedule_exception_period WHERE exception_id = '" + override + "'"))
                .isZero();
    }

    @Test
    void unknownKindIsRejected() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);

        assertRejectedBy("schedule_exception_kind_valid", () -> insertException(
                businessId, staffId, "HOLIDAY", "2026-11-02", "2026-11-02", true));
    }

    @Test
    void businessScopeRequiresNoStaffMemberAndStaffScopesRequireOne() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);

        assertRejectedBy("schedule_exception_scope_matches_kind", () -> insertException(
                businessId, staffId, BUSINESS_CLOSURE, "2026-11-02", "2026-11-02", true));
        for (String kind : List.of(
                STAFF_TIME_OFF, WORKING_DAY_OVERRIDE, ADDITIONAL_WORKING_PERIODS)) {
            assertRejectedBy("schedule_exception_scope_matches_kind", () -> insertException(
                    businessId, null, kind, "2026-11-02", "2026-11-02", false));
        }
    }

    @Test
    void staffMemberFromAnotherBusinessIsRejectedByTheCompositeTenantForeignKey() {
        UUID owner = createBusiness();
        UUID other = createBusiness();
        UUID foreignStaff = createStaff(other);

        assertRejectedBy("schedule_exception_staff_member_fk", () -> insertException(
                owner, foreignStaff, STAFF_TIME_OFF, "2026-11-02", "2026-11-02", true));
        assertThat(count("schedule_exception")).isZero();
    }

    @Test
    void periodCannotReferenceAnExceptionThroughAnotherBusiness() {
        UUID owner = createBusiness();
        UUID other = createBusiness();
        UUID exception = insertException(
                owner, null, BUSINESS_CLOSURE, "2026-11-02", "2026-11-02", false);

        assertRejectedBy("schedule_exception_period_exception_fk", () -> insertPeriod(
                other, exception, "09:00", "10:00"));
    }

    // ---- dates and shape -----------------------------------------------------

    @Test
    void fullDaySingleDateAndInclusiveRangeAreAcceptedForBlocks() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);

        insertException(businessId, null, BUSINESS_CLOSURE, "2026-11-02", "2026-11-02", true);
        insertException(businessId, null, BUSINESS_CLOSURE, "2026-11-10", "2026-11-12", true);
        insertException(businessId, staffId, STAFF_TIME_OFF, "2026-11-02", "2026-11-02", true);
        insertException(businessId, staffId, STAFF_TIME_OFF, "2026-11-10", "2026-11-12", true);

        assertThat(count("schedule_exception")).isEqualTo(4);
    }

    @Test
    void reversedDateRangeIsRejected() {
        UUID businessId = createBusiness();

        // The generated date_range is evaluated before CHECK constraints, so PostgreSQL
        // rejects a reversed range with its own range error rather than date_order.
        assertThatThrownBy(() -> insertException(
                        businessId, null, BUSINESS_CLOSURE, "2026-11-03", "2026-11-02", true))
                .isInstanceOf(DataAccessException.class);
        assertThat(count("schedule_exception")).isZero();
    }

    @Test
    void partialBlocksMustCoverExactlyOneDate() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);

        assertRejectedBy("schedule_exception_range_needs_all_day", () -> insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-02", "2026-11-03", false));
        assertRejectedBy("schedule_exception_range_needs_all_day", () -> insertException(
                businessId, staffId, STAFF_TIME_OFF, "2026-11-02", "2026-11-03", false));
        insertException(businessId, null, BUSINESS_CLOSURE, "2026-11-04", "2026-11-04", false);
        insertException(businessId, staffId, STAFF_TIME_OFF, "2026-11-04", "2026-11-04", false);
    }

    @Test
    void workingExceptionsMustBePartialAndCoverExactlyOneDate() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);

        for (String kind : List.of(WORKING_DAY_OVERRIDE, ADDITIONAL_WORKING_PERIODS)) {
            assertRejectedBy("schedule_exception_working_kinds_single_date", () ->
                    insertException(businessId, staffId, kind, "2026-11-02", "2026-11-02", true));
            assertRejectedBy("schedule_exception_range_needs_all_day", () ->
                    insertException(businessId, staffId, kind, "2026-11-02", "2026-11-03", false));
            insertException(businessId, staffId, kind, "2026-11-09", "2026-11-09", false);
        }
    }

    @Test
    void infiniteDatesAreRejectedButNoArbitraryCalendarBoundsApply() {
        UUID businessId = createBusiness();

        assertRejectedBy("schedule_exception_dates_finite", () -> insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-02", "infinity", true));
        assertRejectedBy("schedule_exception_dates_finite", () -> insertException(
                businessId, null, BUSINESS_CLOSURE, "-infinity", "2026-11-02", true));
        insertException(businessId, null, BUSINESS_CLOSURE, "1999-12-31", "1999-12-31", true);
        insertException(businessId, null, BUSINESS_CLOSURE, "3000-01-01", "3000-01-01", true);
    }

    @Test
    void versionDefaultsToZeroIsNonNegativeAndTimestampsAreOrdered() {
        UUID businessId = createBusiness();
        UUID id = insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-02", "2026-11-02", true);

        assertThat(jdbc.sql("SELECT version FROM schedule_exception WHERE id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isZero();
        assertRejectedBy("schedule_exception_version_nonnegative", () -> jdbc.sql(
                        "UPDATE schedule_exception SET version = -1 WHERE id = :id")
                .param("id", id)
                .update());
        assertRejectedBy("schedule_exception_timestamps_ordered", () -> jdbc.sql(
                        "UPDATE schedule_exception SET updated_at = created_at - interval '1 second'"
                                + " WHERE id = :id")
                .param("id", id)
                .update());
    }

    // ---- periods -------------------------------------------------------------

    @Test
    void periodsAcceptWholeMinutesThroughTwentyThreeFiftyNineWithExactRanges() {
        UUID businessId = createBusiness();
        UUID exception = insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-02", "2026-11-02", false);

        insertPeriod(businessId, exception, "09:07", "13:53");
        insertPeriod(businessId, exception, "23:58", "23:59");

        List<String> ranges = jdbc.sql("""
                        SELECT lower(minute_range) || '-' || upper(minute_range)
                               || lower_inc(minute_range) || upper_inc(minute_range)
                        FROM schedule_exception_period
                        WHERE exception_id = :id
                        ORDER BY start_time
                        """)
                .param("id", exception)
                .query(String.class)
                .list();
        assertThat(ranges).containsExactly("547-833truefalse", "1438-1439truefalse");
    }

    @Test
    void periodsRejectSecondsAndTwentyFourHundred() {
        UUID businessId = createBusiness();
        UUID exception = insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-02", "2026-11-02", false);

        assertRejectedBy("schedule_exception_period_minute_precision", () -> insertPeriod(
                businessId, exception, "09:00:30", "10:00"));
        assertRejectedBy("schedule_exception_period_minute_precision", () -> insertPeriod(
                businessId, exception, "09:00", "10:00:01"));
        assertRejectedBy("schedule_exception_period_clock_time_range", () -> insertPeriod(
                businessId, exception, "23:00", "24:00"));
        assertRejectedBy("schedule_exception_period_clock_time_range", () -> insertPeriod(
                businessId, exception, "24:00", "24:00"));
    }

    @Test
    void periodsRejectReversedAndEmptyRanges() {
        UUID businessId = createBusiness();
        UUID exception = insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-02", "2026-11-02", false);

        // A reversed period fails while generating minute_range, before the CHECK runs.
        assertThatThrownBy(() -> insertPeriod(businessId, exception, "10:00", "09:00"))
                .isInstanceOf(DataAccessException.class);
        assertRejectedBy("schedule_exception_period_valid_range", () -> insertPeriod(
                businessId, exception, "10:00", "10:00"));
    }

    @Test
    void duplicateOverlappingAndContainedPeriodsInOneAggregateAreRejected() {
        UUID businessId = createBusiness();
        UUID exception = insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-02", "2026-11-02", false);
        insertPeriod(businessId, exception, "09:00", "12:00");

        assertRejectedBy("schedule_exception_period_pkey", () -> insertPeriod(
                businessId, exception, "09:00", "12:00"));
        assertRejectedBy("schedule_exception_period_no_overlap", () -> insertPeriod(
                businessId, exception, "11:00", "13:00"));
        assertRejectedBy("schedule_exception_period_no_overlap", () -> insertPeriod(
                businessId, exception, "08:00", "09:01"));
        assertRejectedBy("schedule_exception_period_no_overlap", () -> insertPeriod(
                businessId, exception, "10:00", "11:00"));
        assertRejectedBy("schedule_exception_period_no_overlap", () -> insertPeriod(
                businessId, exception, "08:00", "13:00"));
    }

    @Test
    void adjacentPeriodsInOneAggregateAreAllowedAndStaySeparateRows() {
        UUID businessId = createBusiness();
        UUID exception = insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-02", "2026-11-02", false);

        insertPeriod(businessId, exception, "09:00", "12:00");
        insertPeriod(businessId, exception, "12:00", "15:00");

        assertThat(count("schedule_exception_period WHERE exception_id = '" + exception + "'"))
                .isEqualTo(2);
    }

    @Test
    void samePeriodsInDifferentAggregatesDoNotConflictThroughTheChildConstraint() {
        UUID businessId = createBusiness();
        UUID first = insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-02", "2026-11-02", false);
        UUID second = insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-03", "2026-11-03", false);

        insertPeriod(businessId, first, "09:00", "12:00");
        insertPeriod(businessId, second, "09:00", "12:00");

        assertThat(count("schedule_exception_period")).isEqualTo(2);
    }

    @Test
    void deletingAnAggregateCascadesToItsPeriodsOnly() {
        UUID businessId = createBusiness();
        UUID doomed = insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-02", "2026-11-02", false);
        UUID kept = insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-03", "2026-11-03", false);
        insertPeriod(businessId, doomed, "09:00", "10:00");
        insertPeriod(businessId, doomed, "11:00", "12:00");
        insertPeriod(businessId, kept, "09:00", "10:00");

        jdbc.sql("DELETE FROM schedule_exception WHERE id = :id").param("id", doomed).update();

        assertThat(count("schedule_exception_period")).isEqualTo(1);
        assertThat(count("schedule_exception_period WHERE exception_id = '" + kept + "'"))
                .isEqualTo(1);
    }

    @Test
    void staffMemberReferencedByAnExceptionCannotBeDeleted() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);
        insertException(businessId, staffId, STAFF_TIME_OFF, "2026-11-02", "2026-11-02", true);

        assertRejectedBy("schedule_exception_staff_member_fk", () -> jdbc.sql(
                        "DELETE FROM staff_member WHERE id = :id")
                .param("id", staffId)
                .update());
    }

    // ---- cross-aggregate overlap matrix --------------------------------------

    @Test
    void closuresOfOneBusinessRejectOverlappingDateRangesButAllowAdjacentRanges() {
        UUID businessId = createBusiness();
        insertException(businessId, null, BUSINESS_CLOSURE, "2026-11-10", "2026-11-12", true);

        assertRejectedBy("schedule_exception_closure_no_overlap", () -> insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-12", "2026-11-14", true));
        assertRejectedBy("schedule_exception_closure_no_overlap", () -> insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-11", "2026-11-11", false));
        assertRejectedBy("schedule_exception_closure_no_overlap", () -> insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-08", "2026-11-20", true));
        insertException(businessId, null, BUSINESS_CLOSURE, "2026-11-13", "2026-11-13", true);
        insertException(businessId, null, BUSINESS_CLOSURE, "2026-11-09", "2026-11-09", false);
    }

    @Test
    void timeOffOfOneStaffMemberRejectsOverlappingDateRanges() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);
        insertException(businessId, staffId, STAFF_TIME_OFF, "2026-11-10", "2026-11-12", true);

        assertRejectedBy("schedule_exception_staff_kind_no_overlap", () -> insertException(
                businessId, staffId, STAFF_TIME_OFF, "2026-11-12", "2026-11-13", true));
        assertRejectedBy("schedule_exception_staff_kind_no_overlap", () -> insertException(
                businessId, staffId, STAFF_TIME_OFF, "2026-11-11", "2026-11-11", false));
        insertException(businessId, staffId, STAFF_TIME_OFF, "2026-11-13", "2026-11-13", false);
    }

    @Test
    void overridesAndAdditionalPeriodsForTheSameStaffMemberAndDateAreRejected() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);
        for (String kind : List.of(WORKING_DAY_OVERRIDE, ADDITIONAL_WORKING_PERIODS)) {
            insertException(businessId, staffId, kind, "2026-11-10", "2026-11-10", false);

            assertRejectedBy("schedule_exception_staff_kind_no_overlap", () -> insertException(
                    businessId, staffId, kind, "2026-11-10", "2026-11-10", false));
            insertException(businessId, staffId, kind, "2026-11-11", "2026-11-11", false);
        }
    }

    @Test
    void sameKindForAnotherStaffMemberOrBusinessIsAllowed() {
        UUID businessId = createBusiness();
        UUID otherBusiness = createBusiness();
        UUID staffId = createStaff(businessId);
        UUID otherStaff = createStaff(businessId);
        UUID foreignStaff = createStaff(otherBusiness);

        insertException(businessId, null, BUSINESS_CLOSURE, "2026-11-10", "2026-11-12", true);
        insertException(otherBusiness, null, BUSINESS_CLOSURE, "2026-11-10", "2026-11-12", true);
        for (String kind : List.of(STAFF_TIME_OFF, WORKING_DAY_OVERRIDE,
                ADDITIONAL_WORKING_PERIODS)) {
            boolean allDay = kind.equals(STAFF_TIME_OFF);
            insertException(businessId, staffId, kind, "2026-11-10", "2026-11-10", allDay);
            insertException(businessId, otherStaff, kind, "2026-11-10", "2026-11-10", allDay);
            insertException(otherBusiness, foreignStaff, kind, "2026-11-10", "2026-11-10", allDay);
        }

        assertThat(count("schedule_exception")).isEqualTo(11);
    }

    @Test
    void differentKindsMayOverlapForTheSameBusinessStaffMemberAndDate() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);

        insertException(businessId, null, BUSINESS_CLOSURE, "2026-11-10", "2026-11-10", true);
        insertException(businessId, staffId, STAFF_TIME_OFF, "2026-11-10", "2026-11-10", true);
        insertException(
                businessId, staffId, WORKING_DAY_OVERRIDE, "2026-11-10", "2026-11-10", false);
        insertException(
                businessId, staffId, ADDITIONAL_WORKING_PERIODS,
                "2026-11-10", "2026-11-10", false);

        assertThat(count("schedule_exception")).isEqualTo(4);
    }

    @Test
    void exceptionDoesNotConflictWithRecurringPeriodsOnTheSameStaffMember() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);
        jdbc.sql("""
                        INSERT INTO staff_working_schedule(
                            business_id, staff_member_id, created_at, updated_at)
                        VALUES (:businessId, :staffId, now(), now())
                        """)
                .param("businessId", businessId)
                .param("staffId", staffId)
                .update();
        jdbc.sql("""
                        INSERT INTO staff_working_period(
                            business_id, staff_member_id, weekday, start_time, end_time)
                        VALUES (:businessId, :staffId, 1, TIME '09:00', TIME '17:00')
                        """)
                .param("businessId", businessId)
                .param("staffId", staffId)
                .update();
        // 2026-11-09 is a Monday.
        UUID override = insertException(
                businessId, staffId, WORKING_DAY_OVERRIDE, "2026-11-09", "2026-11-09", false);
        insertPeriod(businessId, override, "10:00", "12:00");
        UUID additional = insertException(
                businessId, staffId, ADDITIONAL_WORKING_PERIODS,
                "2026-11-09", "2026-11-09", false);
        insertPeriod(businessId, additional, "09:00", "17:00");

        assertThat(count("schedule_exception_period")).isEqualTo(2);
    }

    @Test
    void anAggregateMayBeMovedWithoutConflictingWithItself() {
        UUID businessId = createBusiness();
        UUID id = insertException(
                businessId, null, BUSINESS_CLOSURE, "2026-11-10", "2026-11-12", true);

        jdbc.sql("""
                        UPDATE schedule_exception
                        SET first_date = DATE '2026-11-11', last_date = DATE '2026-11-13'
                        WHERE id = :id
                        """)
                .param("id", id)
                .update();

        assertThat(jdbc.sql("SELECT last_date::text FROM schedule_exception WHERE id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single())
                .isEqualTo("2026-11-13");
    }

    // ---- helpers -------------------------------------------------------------

    private UUID createBusiness() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, 'Schedule Exception Schema Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "exception-schema-" + id)
                .param("now", now)
                .update();
        return id;
    }

    private UUID createStaff(UUID businessId) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO staff_member(
                            id, business_id, display_name, active, version, created_at, updated_at)
                        VALUES (:id, :businessId, 'Exception Staff', true, 0, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("now", now)
                .update();
        return id;
    }

    private UUID insertException(
            UUID businessId,
            UUID staffId,
            String kind,
            String firstDate,
            String lastDate,
            boolean allDay) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO schedule_exception(
                            id, business_id, staff_member_id, kind, first_date, last_date,
                            all_day, created_at, updated_at)
                        VALUES (
                            :id, :businessId, :staffId, :kind,
                            CAST(:firstDate AS date), CAST(:lastDate AS date),
                            :allDay, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("staffId", staffId)
                .param("kind", kind)
                .param("firstDate", firstDate)
                .param("lastDate", lastDate)
                .param("allDay", allDay)
                .param("now", now)
                .update();
        return id;
    }

    private void insertPeriod(UUID businessId, UUID exceptionId, String start, String end) {
        jdbc.sql("""
                        INSERT INTO schedule_exception_period(
                            business_id, exception_id, start_time, end_time)
                        VALUES (:businessId, :exceptionId,
                                CAST(:start AS time), CAST(:end AS time))
                        """)
                .param("businessId", businessId)
                .param("exceptionId", exceptionId)
                .param("start", start)
                .param("end", end)
                .update();
    }

    private long count(String tableAndPredicate) {
        return jdbc.sql("SELECT count(*) FROM " + tableAndPredicate)
                .query(Long.class)
                .single();
    }

    private static void assertRejectedBy(String constraint, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(failure -> assertThat(
                                ((DataAccessException) failure).getMostSpecificCause().getMessage())
                        .contains("\"" + constraint + "\""));
    }

    private String sha256(String migrationFile) {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("db/migration/" + migrationFile)) {
            if (input == null) {
                throw new IllegalStateException("Migration resource is missing: " + migrationFile);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Migration resource could not be verified", exception);
        }
    }
}
