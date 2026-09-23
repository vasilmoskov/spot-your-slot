package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
class WorkingScheduleSchemaIntegrationTests extends PostgresIntegrationTest {
    @Autowired
    JdbcClient jdbc;

    @Test
    void postgresProvidesTheApprovedRangeAndGistSupport() {
        String extension = jdbc.sql("""
                        SELECT extname
                        FROM pg_catalog.pg_extension
                        WHERE extname = 'btree_gist'
                        """)
                .query(String.class)
                .single();
        List<String> scalarTypes = jdbc.sql("""
                        SELECT type.typname
                        FROM pg_catalog.pg_opclass operator_class
                        JOIN pg_catalog.pg_am access_method
                          ON access_method.oid = operator_class.opcmethod
                        JOIN pg_catalog.pg_type type
                          ON type.oid = operator_class.opcintype
                        WHERE access_method.amname = 'gist'
                          AND operator_class.opcdefault = true
                          AND type.typname IN ('int2', 'uuid')
                        ORDER BY type.typname
                        """)
                .query(String.class)
                .list();
        String indexMethod = jdbc.sql("""
                        SELECT access_method.amname
                        FROM pg_catalog.pg_class index_class
                        JOIN pg_catalog.pg_namespace namespace
                          ON namespace.oid = index_class.relnamespace
                        JOIN pg_catalog.pg_am access_method
                          ON access_method.oid = index_class.relam
                        WHERE namespace.nspname = 'public'
                          AND index_class.relname = 'staff_working_period_no_overlap'
                        """)
                .query(String.class)
                .single();

        assertThat(extension).isEqualTo("btree_gist");
        assertThat(scalarTypes).containsExactly("int2", "uuid");
        assertThat(indexMethod).isEqualTo("gist");
    }

    @Test
    void generatedMinuteRangeUsesOnlyImmutablePostgresFunctions() {
        var generated = jdbc.sql("""
                        SELECT attribute.attgenerated,
                               pg_catalog.pg_get_expr(definition.adbin, definition.adrelid)
                                   AS expression
                        FROM pg_catalog.pg_attribute attribute
                        JOIN pg_catalog.pg_class table_class
                          ON table_class.oid = attribute.attrelid
                        JOIN pg_catalog.pg_namespace namespace
                          ON namespace.oid = table_class.relnamespace
                        JOIN pg_catalog.pg_attrdef definition
                          ON definition.adrelid = attribute.attrelid
                         AND definition.adnum = attribute.attnum
                        WHERE namespace.nspname = 'public'
                          AND table_class.relname = 'staff_working_period'
                          AND attribute.attname = 'minute_range'
                        """)
                .query((resultSet, rowNumber) -> new GeneratedColumn(
                        resultSet.getString("attgenerated"),
                        resultSet.getString("expression")))
                .single();
        List<FunctionVolatility> functions = jdbc.sql("""
                        SELECT candidate.signature, function.provolatile
                        FROM (VALUES
                            ('date_part(text,time without time zone)'),
                            ('int4range(integer,integer,text)')
                        ) AS candidate(signature)
                        JOIN pg_catalog.pg_proc function
                          ON function.oid = pg_catalog.to_regprocedure(
                              'pg_catalog.' || candidate.signature)
                        ORDER BY candidate.signature
                        """)
                .query((resultSet, rowNumber) -> new FunctionVolatility(
                        resultSet.getString("signature"),
                        resultSet.getString("provolatile")))
                .list();

        assertThat(generated.generatedKind()).isEqualTo("s");
        assertThat(generated.expression())
                .contains("int4range(")
                .contains("date_part('hour'::text")
                .contains("date_part('minute'::text")
                .contains("* 60")
                .contains("'[)'::text");
        assertThat(functions)
                .containsExactly(
                        new FunctionVolatility(
                                "date_part(text,time without time zone)", "i"),
                        new FunctionVolatility(
                                "int4range(integer,integer,text)", "i"));
    }

    @Test
    void migrationFromEmptyAppliesV7AsTheOnlyVersionAfterV6() {
        List<String> versions = jdbc.sql("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE type = 'SQL' AND success = true
                        ORDER BY installed_rank
                        """)
                .query(String.class)
                .list();

        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7");
    }

    @Test
    void upgradingV6BackfillsOneEmptySchedulePerExistingStaffMember() throws Exception {
        String schema = "v7_working_schedule_upgrade";
        var dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .target("6")
                .load()
                .migrate();

        UUID businessId = UUID.randomUUID();
        UUID firstStaffId = UUID.randomUUID();
        UUID secondStaffId = UUID.randomUUID();
        OffsetDateTime firstCreatedAt = OffsetDateTime.parse("2026-09-01T08:00:00Z");
        OffsetDateTime secondCreatedAt = OffsetDateTime.parse("2026-09-02T09:30:00Z");
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var business = connection.prepareStatement("""
                    INSERT INTO v7_working_schedule_upgrade.business(
                        id, slug, display_name, business_type, status, timezone,
                        created_at, updated_at)
                    VALUES (?, ?, 'Upgrade Business', 'OTHER', 'DRAFT',
                            'Europe/Sofia', ?, ?)
                    """)) {
                business.setObject(1, businessId);
                business.setString(2, "upgrade-" + businessId);
                business.setObject(3, firstCreatedAt);
                business.setObject(4, firstCreatedAt);
                business.executeUpdate();
            }
            insertUpgradeStaff(
                    connection, schema, firstStaffId, businessId, "Първи", firstCreatedAt);
            insertUpgradeStaff(
                    connection, schema, secondStaffId, businessId, "Втори", secondCreatedAt);
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
                        SELECT staff_member_id, version, created_at, updated_at,
                               (SELECT count(*)
                                FROM v7_working_schedule_upgrade.staff_working_period period
                                WHERE period.business_id = schedule.business_id
                                  AND period.staff_member_id = schedule.staff_member_id)
                                   AS period_count
                        FROM v7_working_schedule_upgrade.staff_working_schedule schedule
                        ORDER BY staff_member_id
                        """)) {
            try (var result = statement.executeQuery()) {
                var schedules = new java.util.ArrayList<BackfilledSchedule>();
                while (result.next()) {
                    schedules.add(new BackfilledSchedule(
                            result.getObject("staff_member_id", UUID.class),
                            result.getLong("version"),
                            result.getObject("created_at", OffsetDateTime.class),
                            result.getObject("updated_at", OffsetDateTime.class),
                            result.getLong("period_count")));
                }
                assertThat(schedules)
                        .containsExactlyInAnyOrder(
                                new BackfilledSchedule(
                                        firstStaffId,
                                        0,
                                        firstCreatedAt,
                                        firstCreatedAt,
                                        0),
                                new BackfilledSchedule(
                                        secondStaffId,
                                        0,
                                        secondCreatedAt,
                                        secondCreatedAt,
                                        0));
            }
        }
    }

    @Test
    void emptyScheduleDefaultsToVersionZeroAndRequiresTimestamps() {
        UUID businessId = createBusiness();
        UUID staffMemberId = insertStaff(businessId, "Празен график");
        OffsetDateTime createdAt = now();

        insertSchedule(businessId, staffMemberId, 0, createdAt, createdAt);

        var stored = jdbc.sql("""
                        SELECT version, created_at, updated_at
                        FROM staff_working_schedule
                        WHERE business_id = :businessId
                          AND staff_member_id = :staffMemberId
                        """)
                .param("businessId", businessId)
                .param("staffMemberId", staffMemberId)
                .query((resultSet, rowNumber) -> new ScheduleDefaults(
                        resultSet.getLong("version"),
                        resultSet.getObject("created_at", OffsetDateTime.class),
                        resultSet.getObject("updated_at", OffsetDateTime.class)))
                .single();

        assertThat(stored).isEqualTo(new ScheduleDefaults(0, createdAt, createdAt));
        assertThat(periodCount(businessId, staffMemberId)).isZero();
        UUID missingTimestampStaffId = insertStaff(businessId, "Без време");
        assertRejected(() -> jdbc.sql("""
                        INSERT INTO staff_working_schedule(
                            business_id, staff_member_id, created_at, updated_at)
                        VALUES (:businessId, :staffMemberId, NULL, NULL)
                        """)
                .param("businessId", businessId)
                .param("staffMemberId", missingTimestampStaffId)
                .update());
    }

    @Test
    void splitPeriodsAndMultipleWeekdaysSupportDeterministicOrdering() {
        ScheduledStaff staff = createScheduledStaff("Подреден график");
        insertPeriod(staff, 5, "14:00", "18:00");
        insertPeriod(staff, 1, "14:00", "18:00");
        insertPeriod(staff, 1, "09:00", "13:00");
        insertPeriod(staff, 3, "10:00", "16:00");

        List<PeriodValue> periods = jdbc.sql("""
                        SELECT weekday, start_time, end_time
                        FROM staff_working_period
                        WHERE business_id = :businessId
                          AND staff_member_id = :staffMemberId
                        ORDER BY weekday, start_time, end_time
                        """)
                .param("businessId", staff.businessId())
                .param("staffMemberId", staff.staffMemberId())
                .query((resultSet, rowNumber) -> new PeriodValue(
                        resultSet.getInt("weekday"),
                        resultSet.getObject("start_time", LocalTime.class),
                        resultSet.getObject("end_time", LocalTime.class)))
                .list();

        assertThat(periods)
                .containsExactly(
                        period(1, "09:00", "13:00"),
                        period(1, "14:00", "18:00"),
                        period(3, "10:00", "16:00"),
                        period(5, "14:00", "18:00"));
    }

    @Test
    void generatedRangePreservesExactMinuteBounds() {
        ScheduledStaff staff = createScheduledStaff("Минутна точност");
        insertPeriod(staff, 2, "09:07", "13:53");

        var bounds = jdbc.sql("""
                        SELECT pg_catalog.lower(minute_range),
                               pg_catalog.upper(minute_range),
                               pg_catalog.lower_inc(minute_range),
                               pg_catalog.upper_inc(minute_range)
                        FROM staff_working_period
                        WHERE business_id = :businessId
                          AND staff_member_id = :staffMemberId
                        """)
                .param("businessId", staff.businessId())
                .param("staffMemberId", staff.staffMemberId())
                .query((resultSet, rowNumber) -> new RangeBounds(
                        resultSet.getInt(1),
                        resultSet.getInt(2),
                        resultSet.getBoolean(3),
                        resultSet.getBoolean(4)))
                .single();

        assertThat(bounds).isEqualTo(new RangeBounds(547, 833, true, false));
    }

    @Test
    void latestRepresentableMinuteBoundaryIsAcceptedWithExactRangeBounds() {
        ScheduledStaff staff = createScheduledStaff("Късен период");
        insertPeriod(staff, 1, "23:58", "23:59");

        var bounds = jdbc.sql("""
                        SELECT pg_catalog.lower(minute_range),
                               pg_catalog.upper(minute_range),
                               pg_catalog.lower_inc(minute_range),
                               pg_catalog.upper_inc(minute_range)
                        FROM staff_working_period
                        WHERE business_id = :businessId
                          AND staff_member_id = :staffMemberId
                        """)
                .param("businessId", staff.businessId())
                .param("staffMemberId", staff.staffMemberId())
                .query((resultSet, rowNumber) -> new RangeBounds(
                        resultSet.getInt(1),
                        resultSet.getInt(2),
                        resultSet.getBoolean(3),
                        resultSet.getBoolean(4)))
                .single();

        assertThat(bounds).isEqualTo(new RangeBounds(1438, 1439, true, false));
    }

    @Test
    void adjacentPeriodsAreAccepted() {
        ScheduledStaff staff = createScheduledStaff("Съседни периоди");

        insertPeriod(staff, 1, "09:00", "13:00");
        insertPeriod(staff, 1, "13:00", "18:00");

        assertThat(periodCount(staff.businessId(), staff.staffMemberId())).isEqualTo(2);
    }

    @Test
    void duplicateAndOverlappingPeriodsAreRejected() {
        ScheduledStaff staff = createScheduledStaff("Застъпване");
        insertPeriod(staff, 1, "09:00", "13:00");

        assertRejected(() -> insertPeriod(staff, 1, "09:00", "13:00"));
        assertRejected(() -> insertPeriod(staff, 1, "10:00", "12:00"));
        assertRejected(() -> insertPeriod(staff, 1, "08:00", "10:00"));
        assertRejected(() -> insertPeriod(staff, 1, "12:00", "14:00"));
        assertRejected(() -> insertPeriod(staff, 1, "08:00", "14:00"));

        assertThat(periodCount(staff.businessId(), staff.staffMemberId())).isEqualTo(1);
    }

    @Test
    void overlapConstraintIsScopedByBusinessStaffMemberAndWeekday() {
        UUID firstBusinessId = createBusiness();
        ScheduledStaff first = createScheduledStaff(firstBusinessId, "Първи");
        ScheduledStaff second = createScheduledStaff(firstBusinessId, "Втори");
        UUID otherBusinessId = createBusiness();
        ScheduledStaff otherBusiness = createScheduledStaff(otherBusinessId, "Друг бизнес");

        insertPeriod(first, 1, "09:00", "13:00");
        insertPeriod(first, 2, "09:00", "13:00");
        insertPeriod(second, 1, "09:00", "13:00");
        insertPeriod(otherBusiness, 1, "09:00", "13:00");

        assertThat(totalPeriodCount()).isEqualTo(4);
    }

    @Test
    void weekdayRangeMinutePrecisionAndDaytimeBoundsAreEnforced() {
        ScheduledStaff staff = createScheduledStaff("Невалидни периоди");

        assertRejected(() -> insertPeriod(staff, 0, "09:00", "10:00"));
        assertRejected(() -> insertPeriod(staff, 8, "09:00", "10:00"));
        assertRejected(() -> insertPeriod(staff, 1, "09:00", "09:00"));
        assertRejected(() -> insertPeriod(staff, 1, "18:00", "09:00"));
        assertRejected(() -> insertPeriod(staff, 1, "09:00:30", "10:00"));
        assertRejected(() -> insertPeriod(staff, 1, "09:00", "10:00:00.001"));
        assertRejected(() -> insertPeriod(staff, 1, "23:00", "24:00"));
        assertRejected(() -> insertPeriod(staff, 1, "24:00", "00:01"));

        assertThat(periodCount(staff.businessId(), staff.staffMemberId())).isZero();
    }

    @Test
    void compositeForeignKeysRejectMissingAndCrossBusinessOwnership() {
        UUID firstBusinessId = createBusiness();
        UUID secondBusinessId = createBusiness();
        UUID firstStaffId = insertStaff(firstBusinessId, "Първи бизнес");
        UUID secondStaffId = insertStaff(secondBusinessId, "Втори бизнес");
        insertSchedule(firstBusinessId, firstStaffId, 0, now(), now());

        assertRejected(() -> insertSchedule(
                firstBusinessId, secondStaffId, 0, now(), now()));
        assertRejected(() -> insertSchedule(
                firstBusinessId, UUID.randomUUID(), 0, now(), now()));
        assertRejected(() -> insertPeriod(
                new ScheduledStaff(firstBusinessId, secondStaffId),
                1,
                "09:00",
                "10:00"));
    }

    @Test
    void negativeVersionsAndDestructiveParentDeletesAreRejected() {
        UUID businessId = createBusiness();
        UUID negativeVersionStaffId = insertStaff(businessId, "Невалидна версия");
        assertRejected(() -> insertSchedule(
                businessId, negativeVersionStaffId, -1, now(), now()));

        ScheduledStaff staff = createScheduledStaff(businessId, "Защитен график");
        insertPeriod(staff, 1, "09:00", "10:00");

        assertRejected(() -> jdbc.sql("""
                        DELETE FROM staff_working_schedule
                        WHERE business_id = :businessId
                          AND staff_member_id = :staffMemberId
                        """)
                .param("businessId", staff.businessId())
                .param("staffMemberId", staff.staffMemberId())
                .update());
        assertRejected(() -> jdbc.sql("DELETE FROM staff_member WHERE id = :staffMemberId")
                .param("staffMemberId", staff.staffMemberId())
                .update());
        assertRejected(() -> jdbc.sql("DELETE FROM business WHERE id = :businessId")
                .param("businessId", businessId)
                .update());
    }

    @Test
    void columnsConstraintsAndIndexesMatchTheApprovedSchema() {
        List<String> scheduleColumns = columns("staff_working_schedule");
        List<String> periodColumns = columns("staff_working_period");
        List<ConstraintMetadata> scheduleConstraints = constraints("staff_working_schedule");
        List<ConstraintMetadata> periodConstraints = constraints("staff_working_period");
        List<IndexMetadata> scheduleIndexes = indexes("staff_working_schedule");
        List<IndexMetadata> periodIndexes = indexes("staff_working_period");

        assertThat(scheduleColumns)
                .containsExactly(
                        "business_id:uuid:NO:NEVER",
                        "staff_member_id:uuid:NO:NEVER",
                        "version:int8:NO:NEVER",
                        "created_at:timestamptz:NO:NEVER",
                        "updated_at:timestamptz:NO:NEVER");
        assertThat(periodColumns)
                .containsExactly(
                        "business_id:uuid:NO:NEVER",
                        "staff_member_id:uuid:NO:NEVER",
                        "weekday:int2:NO:NEVER",
                        "start_time:time:NO:NEVER",
                        "end_time:time:NO:NEVER",
                        "minute_range:int4range:YES:ALWAYS");
        assertThat(scheduleConstraints)
                .containsExactly(
                        new ConstraintMetadata(
                                "staff_working_schedule_business_id_not_null", "n"),
                        new ConstraintMetadata(
                                "staff_working_schedule_created_at_not_null", "n"),
                        new ConstraintMetadata("staff_working_schedule_pkey", "p"),
                        new ConstraintMetadata("staff_working_schedule_staff_member_fk", "f"),
                        new ConstraintMetadata(
                                "staff_working_schedule_staff_member_id_not_null", "n"),
                        new ConstraintMetadata(
                                "staff_working_schedule_updated_at_not_null", "n"),
                        new ConstraintMetadata("staff_working_schedule_version_nonnegative", "c"),
                        new ConstraintMetadata(
                                "staff_working_schedule_version_not_null", "n"));
        assertThat(periodConstraints)
                .containsExactly(
                        new ConstraintMetadata(
                                "staff_working_period_business_id_not_null", "n"),
                        new ConstraintMetadata(
                                "staff_working_period_clock_time_range", "c"),
                        new ConstraintMetadata(
                                "staff_working_period_end_time_not_null", "n"),
                        new ConstraintMetadata("staff_working_period_minute_precision", "c"),
                        new ConstraintMetadata("staff_working_period_no_overlap", "x"),
                        new ConstraintMetadata("staff_working_period_pkey", "p"),
                        new ConstraintMetadata("staff_working_period_schedule_fk", "f"),
                        new ConstraintMetadata(
                                "staff_working_period_staff_member_id_not_null", "n"),
                        new ConstraintMetadata(
                                "staff_working_period_start_time_not_null", "n"),
                        new ConstraintMetadata("staff_working_period_valid_range", "c"),
                        new ConstraintMetadata("staff_working_period_weekday_not_null", "n"),
                        new ConstraintMetadata("staff_working_period_weekday_range", "c"));
        assertThat(scheduleIndexes)
                .containsExactly(new IndexMetadata("staff_working_schedule_pkey", "btree", true));
        assertThat(periodIndexes)
                .containsExactly(
                        new IndexMetadata("staff_working_period_no_overlap", "gist", false),
                        new IndexMetadata("staff_working_period_pkey", "btree", true));
    }

    @Test
    void v7IntroducesOnlyRecurringScheduleTables() {
        List<String> tables = jdbc.sql("""
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                        ORDER BY table_name
                        """)
                .query(String.class)
                .list();
        List<String> staffColumns = columns("staff_member");

        assertThat(tables)
                .contains("staff_working_schedule", "staff_working_period")
                .doesNotContain(
                        "schedule_break",
                        "time_off",
                        "working_override",
                        "availability",
                        "appointment",
                        "customer");
        assertThat(staffColumns)
                .noneMatch(column -> column.startsWith("membership_id:"));
    }

    private UUID createBusiness() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, 'Working Schedule Schema Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "working-schedule-schema-" + id)
                .param("now", now)
                .update();
        return id;
    }

    private UUID insertStaff(UUID businessId, String displayName) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        jdbc.sql("""
                        INSERT INTO staff_member(
                            id, business_id, display_name, created_at, updated_at)
                        VALUES (:id, :businessId, :displayName, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("displayName", displayName)
                .param("now", now)
                .update();
        return id;
    }

    private ScheduledStaff createScheduledStaff(String displayName) {
        return createScheduledStaff(createBusiness(), displayName);
    }

    private ScheduledStaff createScheduledStaff(UUID businessId, String displayName) {
        UUID staffMemberId = insertStaff(businessId, displayName);
        OffsetDateTime createdAt = now();
        insertSchedule(businessId, staffMemberId, 0, createdAt, createdAt);
        return new ScheduledStaff(businessId, staffMemberId);
    }

    private void insertSchedule(
            UUID businessId,
            UUID staffMemberId,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        jdbc.sql("""
                        INSERT INTO staff_working_schedule(
                            business_id, staff_member_id, version, created_at, updated_at)
                        VALUES (
                            :businessId, :staffMemberId, :version, :createdAt, :updatedAt)
                        """)
                .param("businessId", businessId)
                .param("staffMemberId", staffMemberId)
                .param("version", version)
                .param("createdAt", createdAt)
                .param("updatedAt", updatedAt)
                .update();
    }

    private void insertPeriod(
            ScheduledStaff staff, int weekday, String startTime, String endTime) {
        jdbc.sql("""
                        INSERT INTO staff_working_period(
                            business_id, staff_member_id, weekday, start_time, end_time)
                        VALUES (
                            :businessId, :staffMemberId, :weekday,
                            CAST(:startTime AS time without time zone),
                            CAST(:endTime AS time without time zone))
                        """)
                .param("businessId", staff.businessId())
                .param("staffMemberId", staff.staffMemberId())
                .param("weekday", weekday)
                .param("startTime", startTime)
                .param("endTime", endTime)
                .update();
    }

    private long periodCount(UUID businessId, UUID staffMemberId) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM staff_working_period
                        WHERE business_id = :businessId
                          AND staff_member_id = :staffMemberId
                        """)
                .param("businessId", businessId)
                .param("staffMemberId", staffMemberId)
                .query(Long.class)
                .single();
    }

    private long totalPeriodCount() {
        return jdbc.sql("SELECT count(*) FROM staff_working_period")
                .query(Long.class)
                .single();
    }

    private List<String> columns(String tableName) {
        return jdbc.sql("""
                        SELECT column_name || ':' || udt_name || ':'
                               || is_nullable || ':' || is_generated
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = :tableName
                        ORDER BY ordinal_position
                        """)
                .param("tableName", tableName)
                .query(String.class)
                .list();
    }

    private List<ConstraintMetadata> constraints(String tableName) {
        return jdbc.sql("""
                        SELECT constraint_metadata.conname, constraint_metadata.contype
                        FROM pg_catalog.pg_constraint constraint_metadata
                        JOIN pg_catalog.pg_class table_class
                          ON table_class.oid = constraint_metadata.conrelid
                        JOIN pg_catalog.pg_namespace namespace
                          ON namespace.oid = table_class.relnamespace
                        WHERE namespace.nspname = 'public'
                          AND table_class.relname = :tableName
                        ORDER BY constraint_metadata.conname
                        """)
                .param("tableName", tableName)
                .query((resultSet, rowNumber) -> new ConstraintMetadata(
                        resultSet.getString("conname"), resultSet.getString("contype")))
                .list();
    }

    private List<IndexMetadata> indexes(String tableName) {
        return jdbc.sql("""
                        SELECT index_class.relname, access_method.amname,
                               index_metadata.indisunique
                        FROM pg_catalog.pg_index index_metadata
                        JOIN pg_catalog.pg_class index_class
                          ON index_class.oid = index_metadata.indexrelid
                        JOIN pg_catalog.pg_class table_class
                          ON table_class.oid = index_metadata.indrelid
                        JOIN pg_catalog.pg_namespace namespace
                          ON namespace.oid = table_class.relnamespace
                        JOIN pg_catalog.pg_am access_method
                          ON access_method.oid = index_class.relam
                        WHERE namespace.nspname = 'public'
                          AND table_class.relname = :tableName
                        ORDER BY index_class.relname
                        """)
                .param("tableName", tableName)
                .query((resultSet, rowNumber) -> new IndexMetadata(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getBoolean(3)))
                .list();
    }

    private static void insertUpgradeStaff(
            java.sql.Connection connection,
            String schema,
            UUID staffMemberId,
            UUID businessId,
            String displayName,
            OffsetDateTime createdAt)
            throws Exception {
        try (var staff = connection.prepareStatement("""
                INSERT INTO %s.staff_member(
                    id, business_id, display_name, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """.formatted(schema))) {
            staff.setObject(1, staffMemberId);
            staff.setObject(2, businessId);
            staff.setString(3, displayName);
            staff.setObject(4, createdAt);
            staff.setObject(5, createdAt.plusHours(2));
            staff.executeUpdate();
        }
    }

    private static PeriodValue period(int weekday, String startTime, String endTime) {
        return new PeriodValue(
                weekday, LocalTime.parse(startTime), LocalTime.parse(endTime));
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private void assertRejected(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(DataAccessException.class);
    }

    private record GeneratedColumn(String generatedKind, String expression) {
    }

    private record FunctionVolatility(String signature, String volatility) {
    }

    private record BackfilledSchedule(
            UUID staffMemberId,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long periodCount) {
    }

    private record ScheduleDefaults(
            long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    private record ScheduledStaff(UUID businessId, UUID staffMemberId) {
    }

    private record PeriodValue(int weekday, LocalTime startTime, LocalTime endTime) {
    }

    private record RangeBounds(
            int lower, int upper, boolean lowerInclusive, boolean upperInclusive) {
    }

    private record ConstraintMetadata(String name, String type) {
    }

    private record IndexMetadata(String name, String method, boolean unique) {
    }
}
