package bg.spotyourslot.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code EXPLAIN (ANALYZE, BUFFERS)} evidence behind the {@code V15} decision (issue #21
 * Phase 3), run on the production statements of {@link AppointmentReadStore} over deterministic
 * synthetic data of about 10,000 and 50,000 Appointments in 8 Businesses with a skewed Business
 * size, StaffMember load, and Customer load, about 15 percent cancelled (a third of them with the
 * legacy unknown actor). The measurements are printed (prefix {@code V15-EVIDENCE}) and recorded in
 * the task record. Timings are never asserted. The structural assertions are the ones that must
 * hold whatever the index decision is: every plan filters by the Business, and the lines read uses
 * an existing index of {@code appointment_service} led by {@code appointment_id} (the primary key or
 * the distinct-Service unique index), so no index is added for it.
 *
 * <p>The first execution of each statement is reported separately from the median of seven repeated
 * executions. A truly cold cache cannot be forced inside a container, so "first" is reported as
 * the first execution after the data was loaded and analyzed, with the operating-system cache
 * possibly warm.
 */
class AppointmentReadExplainIntegrationTests extends PostgresIntegrationTest {
    private static final Pattern EXECUTION = Pattern.compile("Execution Time: ([\\d.]+) ms");
    private static final Pattern HIT = Pattern.compile("shared hit=(\\d+)");
    private static final Pattern READ = Pattern.compile("read=(\\d+)");
    private static final Pattern ROWS = Pattern.compile("actual time=[\\d.]+\\.\\.[\\d.]+ rows=(\\d+)");
    private static final Pattern NODE = Pattern.compile(
            "(Seq Scan|Index Scan|Index Only Scan|Bitmap Heap Scan|Bitmap Index Scan|Sort|Limit|Aggregate)[^\\n(]*");
    private static final String I1 = "CREATE INDEX appointment_business_start_idx ON appointment (business_id, start_at, id)";
    private static final String I2 = "CREATE INDEX appointment_business_staff_start_idx ON appointment (business_id, staff_member_id, start_at, id)";
    private static final String I3 = "CREATE INDEX appointment_business_customer_start_idx ON appointment (business_id, customer_id, start_at DESC, id DESC)";
    private static final double[] SHARE = {0.60, 0.10, 0.08, 0.06, 0.06, 0.04, 0.03, 0.03};

    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;

    private final List<UUID> businesses = new ArrayList<>();

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE business CASCADE").update();
        // The database starts in the V15 state; the measurement drops and recreates the candidates.
        jdbc.sql("DROP INDEX IF EXISTS appointment_business_staff_start_idx").update();
        jdbc.sql(I1.replace("CREATE INDEX", "CREATE INDEX IF NOT EXISTS")).update();
        jdbc.sql(I3.replace("CREATE INDEX", "CREATE INDEX IF NOT EXISTS")).update();
        businesses.clear();
    }

    @Test
    void aboutTenThousandAppointments() {
        measure(10_000);
    }

    @Test
    void aboutFiftyThousandAppointments() {
        measure(50_000);
    }

    @Test
    void theLinesReadUsesAnExistingIndexLedByAppointmentIdForAPageAndForAWholeCalendar() {
        UUID id = business(0);
        seed(id, 5_000, 6, 300);
        // Other tenants dilute the table, as in production: the requested lines are a small share of it.
        seed(business(1), 25_000, 8, 800);
        jdbc.sql("ANALYZE appointment_service").update();
        for (int size : List.of(50, 1_500)) {
            UUID[] ids = jdbc.sql("SELECT id FROM appointment WHERE business_id = :b ORDER BY start_at, id LIMIT :n")
                    .param("b", id).param("n", size).query(UUID.class).list().toArray(UUID[]::new);
            String plan = String.join("\n", jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + AppointmentReadStore.linesSql())
                    .param("businessId", id).param("appointmentIds", ids).query(String.class).list());
            System.out.println("V15-EVIDENCE lines read for " + size + " Appointments: "
                    + NODE.matcher(plan).results().map(match -> match.group().trim()).toList());
            Matcher time = EXECUTION.matcher(plan);
            Matcher buffers = HIT.matcher(plan);
            System.out.println("V15-EVIDENCE lines read for " + size + " Appointments: "
                    + (time.find() ? time.group(1) : "?") + " ms, buffers hit="
                    + (buffers.find() ? buffers.group(1) : "?"));
            // Always an index of appointment_service, never a sequential scan; the page-sized read
            // is driven by the appointment_id index, while a very large id list may be answered by
            // the Business-led index when the Business holds most of the lines.
            assertThat(plan).containsAnyOf("appointment_service_pkey", "appointment_service_distinct",
                    "appointment_service_service_idx").doesNotContain("Seq Scan");
            if (size == 50) {
                assertThat(plan).contains("appointment_service_pkey");
            }
            assertThat(plan).contains("business_id");
        }
    }

    // ---- data -------------------------------------------------------------------------------------

    private UUID business(int number) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(id,slug,display_name,business_type,status,timezone,created_at,updated_at)
                        VALUES (:id,:slug,'Explain Business','OTHER','ACTIVE','Europe/Sofia',now(),now())
                        """)
                .param("id", id).param("slug", "read-explain-" + number + "-" + id).update();
        return id;
    }

    private record Seeded(UUID business, UUID heavyStaff, UUID smallStaff, UUID heavyCustomer, UUID typicalCustomer) {
    }

    private Seeded seed(UUID business, int appointments, int staff, int customers) {
        UUID service = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO service(id,business_id,name,description,duration_minutes,price,active,version,created_at,updated_at)
                        VALUES (:id,:b,'Услуга',NULL,15,25.00,true,0,now(),now())
                        """).param("id", service).param("b", business).update();
        jdbc.sql("""
                        INSERT INTO staff_member(id,business_id,display_name,contact_email,contact_phone,active,version,created_at,updated_at)
                        SELECT gen_random_uuid(), :b, 'Служител ' || g, NULL, NULL, true, 0, now(), now()
                        FROM generate_series(1, :staff) g
                        """).param("b", business).param("staff", staff).update();
        jdbc.sql("""
                        INSERT INTO customer(id,business_id,display_name,phone,email,version,created_at,updated_at)
                        SELECT gen_random_uuid(), :b, 'Клиент ' || g, '+359' || (880000000 + g), NULL, 0, now(), now()
                        FROM generate_series(1, :customers) g
                        """).param("b", business).param("customers", customers)
                .update();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
        jdbc.sql("""
                        WITH staff AS (SELECT id, row_number() OVER (ORDER BY id) AS rn FROM staff_member WHERE business_id = :b),
                             cust AS (SELECT id, row_number() OVER (ORDER BY id) AS rn FROM customer WHERE business_id = :b),
                             gen AS (
                                 SELECT g,
                                        1 + floor(:staff * power(random(), 2))::int AS srank,
                                        1 + floor(:customers * power(random(), 3))::int AS crank,
                                        random() AS r
                                 FROM generate_series(1, :n) g),
                             numbered AS (
                                 SELECT gen.*,
                                        row_number() OVER (PARTITION BY srank ORDER BY g) - 1 AS k,
                                        count(*) OVER (PARTITION BY srank) AS c
                                 FROM gen),
                             rows AS (
                                 SELECT numbered.*, staff.id AS staff_id, cust.id AS cust_id,
                                        timestamptz '2025-01-06 06:00+00'
                                          + floor(k * 730.0 / c)::int * interval '1 day'
                                          + (k % 24) * interval '20 minutes' AS start_at,
                                        CASE WHEN r < 0.15 THEN 'CANCELLED' ELSE 'CONFIRMED' END AS status
                                 FROM numbered
                                 JOIN staff ON staff.rn = numbered.srank
                                 JOIN cust ON cust.rn = numbered.crank)
                        INSERT INTO appointment(
                            id,business_id,customer_id,service_id,staff_member_id,source,status,start_at,end_at,
                            occupied_until,timezone,duration_minutes,price_eur,service_name,staff_display_name,
                            customer_note,public_reference,version,created_at,updated_at,service_count,
                            cancelled_at,cancelled_by)
                        SELECT gen_random_uuid(), :b, cust_id, :service, staff_id, 'MANUAL', status, start_at,
                               start_at + interval '15 minutes', start_at + interval '15 minutes', 'Europe/Sofia', 15,
                               25.00, 'Услуга', 'Служител', NULL,
                               upper(substr(md5(g::text || :b::text), 1, 10)), 0,
                               timestamptz '2024-12-01 00:00+00', timestamptz '2024-12-01 00:00+00', 1,
                               CASE WHEN status = 'CANCELLED' AND r < 0.10 THEN timestamptz '2024-12-01 00:00+00' END,
                               CASE WHEN status = 'CANCELLED' THEN CASE WHEN r < 0.10 THEN 'BUSINESS' ELSE 'UNKNOWN' END END
                        FROM rows
                        """).param("b", business).param("staff", staff).param("customers", customers)
                .param("n", appointments).param("service", service).update();
        jdbc.sql("""
                        INSERT INTO appointment_service(appointment_id,position,business_id,service_id,service_name,duration_minutes,price_eur)
                        SELECT id,1,business_id,service_id,service_name,duration_minutes,price_eur
                        FROM appointment WHERE business_id = :b
                        """).param("b", business).update();
        });
        UUID heavyStaff = jdbc.sql("""
                        SELECT staff_member_id FROM appointment WHERE business_id = :b
                        GROUP BY staff_member_id ORDER BY count(*) DESC, staff_member_id LIMIT 1
                        """).param("b", business).query(UUID.class).single();
        UUID smallStaff = jdbc.sql("""
                        SELECT staff_member_id FROM appointment WHERE business_id = :b
                        GROUP BY staff_member_id ORDER BY count(*) ASC, staff_member_id LIMIT 1
                        """).param("b", business).query(UUID.class).single();
        UUID heavyCustomer = jdbc.sql("""
                        SELECT customer_id FROM appointment WHERE business_id = :b
                        GROUP BY customer_id ORDER BY count(*) DESC, customer_id LIMIT 1
                        """).param("b", business).query(UUID.class).single();
        UUID typicalCustomer = jdbc.sql("""
                        SELECT customer_id FROM (
                            SELECT customer_id, count(*) AS c FROM appointment WHERE business_id = :b GROUP BY customer_id
                        ) t ORDER BY abs(c - 20), customer_id LIMIT 1
                        """).param("b", business).query(UUID.class).single();
        return new Seeded(business, heavyStaff, smallStaff, heavyCustomer, typicalCustomer);
    }

    // ---- measurement ---------------------------------------------------------------------------------

    private record Query(String name, String sql, Function<JdbcClient.StatementSpec, JdbcClient.StatementSpec> bind) {
    }

    private record Result(String name, long rows, double first, double median, long hit, long read, String nodes) {
    }

    private void measure(int total) {
        Seeded large = null;
        Seeded small = null;
        for (int index = 0; index < SHARE.length; index++) {
            UUID id = business(index);
            businesses.add(id);
            int count = (int) Math.round(total * SHARE[index]);
            int staff = index == 0 ? 12 : 3 + index % 4;
            int customers = index == 0 ? 3000 : 300 + index * 80;
            Seeded seeded = seed(id, count, staff, customers);
            if (index == 0) {
                large = seeded;
            }
            if (index == 5) {
                small = seeded;
            }
        }
        // The baseline is the schema without the V15 indexes.
        jdbc.sql("DROP INDEX appointment_business_start_idx, appointment_business_customer_start_idx").update();
        jdbc.sql("ANALYZE appointment").update();
        jdbc.sql("ANALYZE appointment_service").update();
        System.out.println("V15-EVIDENCE dataset total=" + jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single()
                + " largeBusiness=" + jdbc.sql("SELECT count(*) FROM appointment WHERE business_id=:b").param("b", large.business()).query(Long.class).single()
                + " cancelled=" + jdbc.sql("SELECT count(*) FROM appointment WHERE status='CANCELLED'").query(Long.class).single()
                + " heavyStaff=" + jdbc.sql("SELECT count(*) FROM appointment WHERE staff_member_id=:s").param("s", large.heavyStaff()).query(Long.class).single()
                + " heavyCustomer=" + jdbc.sql("SELECT count(*) FROM appointment WHERE customer_id=:s").param("s", large.heavyCustomer()).query(Long.class).single()
                + " tableBytes=" + jdbc.sql("SELECT pg_table_size('appointment')").query(Long.class).single()
                + " existingIndexBytes=" + jdbc.sql("SELECT pg_indexes_size('appointment')").query(Long.class).single());

        List<Query> queries = queries(large, small);
        record(total, "baseline (existing indexes only)", queries);
        jdbc.sql(I1).update();
        jdbc.sql("ANALYZE appointment").update();
        record(total, "I1 (business_id, start_at, id)", queries);
        jdbc.sql(I2).update();
        jdbc.sql("ANALYZE appointment").update();
        record(total, "I1 + I2 (business_id, staff_member_id, start_at, id)", queries);
        jdbc.sql("DROP INDEX appointment_business_staff_start_idx").update();
        jdbc.sql(I3).update();
        jdbc.sql("ANALYZE appointment").update();
        record(total, "I1 + I3 (business_id, customer_id, start_at DESC, id DESC)", queries);
        jdbc.sql("DROP INDEX appointment_business_start_idx").update();
        jdbc.sql("ANALYZE appointment").update();
        record(total, "I3 only", queries);
        jdbc.sql("DROP INDEX appointment_business_customer_start_idx").update();
        jdbc.sql("ANALYZE appointment").update();

        System.out.println("V15-EVIDENCE sizes " + indexSizes());
        writeCost(large.business());
        // The schema ends in the V15 state, and the shipped indexes must be the ones the plans use.
        jdbc.sql("DROP INDEX IF EXISTS appointment_business_start_idx, appointment_business_staff_start_idx, "
                + "appointment_business_customer_start_idx").update();
        jdbc.sql(I1).update();
        jdbc.sql(I3).update();
        jdbc.sql("ANALYZE appointment").update();
        assertShippedIndexesAreUsed(queries);
    }

    private void assertShippedIndexesAreUsed(List<Query> queries) {
        for (Query query : queries) {
            String plan = String.join("\n", query.bind().apply(
                    jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + query.sql())).query(String.class).list());
            System.out.println("V15-EVIDENCE final " + query.name() + " | uses "
                    + (plan.contains("appointment_business_start_idx") ? "I1 " : "")
                    + (plan.contains("appointment_business_customer_start_idx") ? "I3 " : "")
                    + (plan.contains("Seq Scan") ? "SEQ-SCAN" : ""));
            assertThat(plan).as(query.name()).doesNotContain("Seq Scan");
            if (query.name().startsWith("history")) {
                assertThat(plan).as(query.name()).containsAnyOf(
                        "appointment_business_customer_start_idx", "appointment_business_start_idx");
            }
        }
    }

    private String indexSizes() {
        return jdbc.sql("SELECT pg_indexes_size('appointment')").query(Long.class).single() + " bytes of indexes";
    }

    private List<Query> queries(Seeded large, Seeded small) {
        Instant weekFrom = Instant.parse("2026-06-07T21:00:00Z");
        Instant weekTo = weekFrom.plusSeconds(7 * 86_400L);
        Instant monthTo = weekFrom.plusSeconds(31 * 86_400L);
        List<Query> list = new ArrayList<>();
        list.add(window("calendar week, large Business, all staff", large.business(), weekFrom, weekTo, null, false, 0, 0));
        list.add(window("calendar 31 dates, large Business, all staff", large.business(), weekFrom, monthTo, null, false, 0, 0));
        list.add(window("calendar week, small Business, all staff", small.business(), weekFrom, weekTo, null, false, 0, 0));
        list.add(window("calendar week, large Business, heavy staff", large.business(), weekFrom, weekTo, large.heavyStaff(), false, 0, 0));
        list.add(window("calendar 31 dates, large Business, small staff", large.business(), weekFrom, monthTo, large.smallStaff(), false, 0, 0));
        list.add(window("agenda page 0 size 10, 31 dates", large.business(), weekFrom, monthTo, null, true, 0, 10));
        list.add(window("agenda page 20 size 50, 31 dates", large.business(), weekFrom, monthTo, null, true, 1000, 50));
        list.add(window("agenda page 0 size 10, 31 dates, heavy staff", large.business(), weekFrom, monthTo, large.heavyStaff(), true, 0, 10));
        list.add(count("agenda count, 31 dates", large.business(), weekFrom, monthTo, null));
        list.add(count("agenda count, 31 dates, small staff", large.business(), weekFrom, monthTo, large.smallStaff()));
        list.add(history("history page 0, typical Customer", large.business(), large.typicalCustomer(), 0, 10));
        list.add(history("history page 0, heavy Customer", large.business(), large.heavyCustomer(), 0, 10));
        list.add(history("history deep page (offset 1000), heavy Customer", large.business(), large.heavyCustomer(), 1000, 50));
        list.add(historyCount("history count, heavy Customer", large.business(), large.heavyCustomer()));
        return list;
    }

    private Query window(String name, UUID business, Instant from, Instant to, UUID staff, boolean paged, long offset, int size) {
        return new Query(name, AppointmentReadStore.windowListSql(staff != null, paged), spec -> {
            spec = spec.param("businessId", business)
                    .param("earliestStart", OffsetDateTime.ofInstant(from.minusSeconds(480 * 60L), ZoneOffset.UTC))
                    .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                    .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
            if (staff != null) {
                spec = spec.param("staffMemberId", staff);
            }
            return paged ? spec.param("offset", offset).param("size", size) : spec;
        });
    }

    private Query count(String name, UUID business, Instant from, Instant to, UUID staff) {
        return new Query(name, AppointmentReadStore.windowCountSql(staff != null), spec -> {
            spec = spec.param("businessId", business)
                    .param("earliestStart", OffsetDateTime.ofInstant(from.minusSeconds(480 * 60L), ZoneOffset.UTC))
                    .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                    .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
            return staff != null ? spec.param("staffMemberId", staff) : spec;
        });
    }

    private Query history(String name, UUID business, UUID customer, long offset, int size) {
        return new Query(name, AppointmentReadStore.historyListSql(false, false), spec -> spec
                .param("businessId", business).param("customerId", customer)
                .param("offset", offset).param("size", size));
    }

    private Query historyCount(String name, UUID business, UUID customer) {
        return new Query(name, AppointmentReadStore.historyCountSql(false), spec -> spec
                .param("businessId", business).param("customerId", customer));
    }

    private void record(int total, String phase, List<Query> queries) {
        System.out.println("V15-EVIDENCE ==== " + total + " | " + phase + " | indexBytes=" + indexSizes());
        for (Query query : queries) {
            Result result = run(query);
            System.out.printf("V15-EVIDENCE %s | rows=%d | first=%.3f ms | median=%.3f ms | buffers hit=%d read=%d | %s%n",
                    result.name(), result.rows(), result.first(), result.median(), result.hit(), result.read(), result.nodes());
        }
    }

    private Result run(Query query) {
        List<Double> times = new ArrayList<>();
        double first = 0;
        long hit = 0;
        long read = 0;
        long rows = 0;
        String nodes = "";
        for (int attempt = 0; attempt < 8; attempt++) {
            String plan = String.join("\n", query.bind().apply(
                    jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + query.sql())).query(String.class).list());
            Matcher time = EXECUTION.matcher(plan);
            assertThat(time.find()).isTrue();
            double millis = Double.parseDouble(time.group(1));
            if (attempt == 0) {
                first = millis;
                Matcher hits = HIT.matcher(plan);
                hit = hits.find() ? Long.parseLong(hits.group(1)) : 0;
                Matcher reads = READ.matcher(plan);
                read = reads.find() ? Long.parseLong(reads.group(1)) : 0;
                Matcher counted = ROWS.matcher(plan);
                rows = counted.find() ? Long.parseLong(counted.group(1)) : 0;
                Matcher node = NODE.matcher(plan);
                List<String> found = new ArrayList<>();
                while (node.find()) {
                    found.add(node.group().trim());
                }
                nodes = String.join(" > ", found);
                // The structural facts that hold whatever the index decision is.
                assertThat(plan).contains("business_id");
            } else {
                times.add(millis);
            }
        }
        Collections.sort(times);
        return new Result(query.name(), rows, first, times.get(times.size() / 2), hit, read, nodes);
    }

    private void writeCost(UUID large) {
        UUID[] extra = new UUID[2];
        long[] bulk = new long[2];
        jdbc.sql("DROP INDEX IF EXISTS appointment_business_start_idx, appointment_business_staff_start_idx, "
                + "appointment_business_customer_start_idx").update();
        for (int round = 0; round < 2; round++) {
            if (round == 1) {
                jdbc.sql(I1).update();
                jdbc.sql(I2).update();
                jdbc.sql(I3).update();
            }
            extra[round] = business(100 + round);
            long started = System.nanoTime();
            seed(extra[round], 10_000, 8, 800);
            bulk[round] = (System.nanoTime() - started) / 1_000_000;
        }
        System.out.println("V15-EVIDENCE write cost: seeding 10,000 Appointments (insert only) without the candidates = "
                + bulk[0] + " ms, with all three candidates = " + bulk[1] + " ms");
        UUID staff = jdbc.sql("SELECT id FROM staff_member WHERE business_id=:b LIMIT 1").param("b", extra[1]).query(UUID.class).single();
        UUID customer = jdbc.sql("SELECT id FROM customer WHERE business_id=:b LIMIT 1").param("b", extra[1]).query(UUID.class).single();
        UUID service = jdbc.sql("SELECT id FROM service WHERE business_id=:b LIMIT 1").param("b", extra[1]).query(UUID.class).single();
        for (int round = 0; round < 2; round++) {
            final int currentRound = round;
            if (round == 1) {
                jdbc.sql("DROP INDEX appointment_business_start_idx, appointment_business_staff_start_idx, appointment_business_customer_start_idx").update();
            }
            long started = System.nanoTime();
            for (int index = 0; index < 300; index++) {
                UUID id = UUID.randomUUID();
                final int at = index;
                new TransactionTemplate(transactions).executeWithoutResult(status -> jdbc.sql("""
                                INSERT INTO appointment(id,business_id,customer_id,service_id,staff_member_id,source,status,
                                    start_at,end_at,occupied_until,timezone,duration_minutes,price_eur,service_name,
                                    staff_display_name,public_reference,version,created_at,updated_at,service_count)
                                VALUES (:id,:b,:c,:s,:st,'MANUAL','CONFIRMED',:start,:end,:end,'Europe/Sofia',15,25.00,'Услуга','Служител',
                                    upper(substr(md5(:id::text),1,10)),0,now(),now(),1);
                                INSERT INTO appointment_service(appointment_id,position,business_id,service_id,service_name,duration_minutes,price_eur)
                                VALUES (:id,1,:b,:s,'Услуга',15,25.00)
                                """)
                        .param("id", id).param("b", extra[1]).param("c", customer).param("s", service).param("st", staff)
                        .param("start", OffsetDateTime.parse("2031-01-01T06:00:00Z").plusMinutes(20L * at + 20L * 300 * currentRound))
                        .param("end", OffsetDateTime.parse("2031-01-01T06:15:00Z").plusMinutes(20L * at + 20L * 300 * currentRound))
                        .update());
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            System.out.println("V15-EVIDENCE write cost: 300 single-row visit inserts "
                    + (round == 0 ? "with all three candidates" : "without them") + " = " + elapsed + " ms");
        }
        System.out.println("V15-EVIDENCE candidate sizes were measured per phase above (indexBytes); large Business id suppressed");
    }
}
