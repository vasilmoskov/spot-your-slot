package bg.spotyourslot.customer.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.customer.domain.CustomerSearchCriteria;
import bg.spotyourslot.customer.domain.CustomerSortField;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code EXPLAIN ANALYZE} evidence (ADR-0021) for the real list, search, and count statements over
 * about 10,000 synthetic Customers in one Business, alongside a second Business so tenant scoping is
 * visible in the plan. The statements are the production SQL ({@link CustomerStore#listSql}). The
 * test asserts the structural facts that justify adding no extra index: every statement filters by
 * the Business, and the default first page reads the existing name index without a sort. A deep
 * page may legitimately use a scan and a sort. Timings are printed for the record and are
 * deliberately not asserted.
 */
class CustomerSearchExplainIntegrationTests extends PostgresIntegrationTest {
    private static final int ROWS = 10_000;
    private static final String NAME_INDEX = "customer_business_normalized_display_name_id_idx";

    @Autowired JdbcClient jdbc;

    private UUID businessId;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE business CASCADE").update();
        businessId = business();
        UUID other = business();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (UUID target : List.of(businessId, other)) {
            jdbc.sql("""
                            INSERT INTO customer(
                                id,business_id,display_name,phone,email,version,created_at,updated_at)
                            SELECT gen_random_uuid(), :business,
                                   'Клиент ' || g,
                                   '+359' || (880000000 + g),
                                   'client' || g || '@example.test',
                                   0, :now, :now
                            FROM generate_series(1, :rows) AS g
                            """)
                    .param("business", target)
                    .param("rows", ROWS)
                    .param("now", now)
                    .update();
        }
        jdbc.sql("ANALYZE customer").update();
    }

    @Test
    void theDefaultNameListReadsTheExistingIndexWithoutASortAndIsScopedToTheBusiness() {
        String plan = explainList(CustomerSearchCriteria.none(), CustomerSortField.NAME, true, 0, 10);
        record("default list, page 0", plan);

        assertThat(plan).contains(NAME_INDEX).contains("business_id").doesNotContain("Sort Method");
    }

    @Test
    void aDeepPageIsStillScopedToTheBusinessWhateverPlanThePlannerChooses() {
        String plan = explainList(CustomerSearchCriteria.none(), CustomerSortField.NAME, true, 9_990, 10);
        record("default list, last page (offset 9990)", plan);

        // At the last page the planner may prefer one scan of the Business's rows and a sort; that
        // is acceptable at this size and is recorded rather than forced.
        assertThat(plan).contains("business_id");
    }

    @Test
    void everySortedAndSearchedStatementIsScopedToTheBusiness() {
        for (CustomerSortField sort : CustomerSortField.values()) {
            String plan = explainList(CustomerSearchCriteria.none(), sort, false, 0, 50);
            record("list sorted by " + sort + " desc", plan);
            assertThat(plan).contains("business_id");
        }
        for (String term : List.of("клиент 99", "client99@", "+359880099", "0880099", "+359880009999")) {
            CustomerSearchCriteria criteria = CustomerSearchCriteria.fromTerm(term);
            String list = explainList(criteria, CustomerSortField.NAME, true, 0, 50);
            String count = explainCount(criteria);
            record("search list '" + label(term) + "'", list);
            record("search count '" + label(term) + "'", count);
            assertThat(list).contains("business_id");
            assertThat(count).contains("business_id");
        }
    }

    private String explainList(
            CustomerSearchCriteria criteria, CustomerSortField sort, boolean ascending, long offset, int size) {
        return run(CustomerStore.listSql(criteria, sort, ascending), criteria, true, offset, size);
    }

    private String explainCount(CustomerSearchCriteria criteria) {
        return run(CustomerStore.countSql(criteria), criteria, false, 0, 0);
    }

    private String run(String sql, CustomerSearchCriteria criteria, boolean paged, long offset, int size) {
        var statement = jdbc.sql("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF) " + sql)
                .param("businessId", businessId);
        if (criteria.nameFragment() != null) {
            statement = statement.param("nameFragment", criteria.nameFragment());
        }
        if (criteria.emailFragment() != null) {
            statement = statement.param("emailFragment", criteria.emailFragment());
        }
        if (criteria.phoneExact() != null) {
            statement = statement.param("phoneExact", criteria.phoneExact());
        }
        if (criteria.phonePrefix() != null) {
            statement = statement.param("phonePrefix", criteria.phonePrefix());
        }
        if (paged) {
            statement = statement.param("offset", offset).param("size", size);
        }
        return String.join("\n", statement.query(String.class).list());
    }

    private static String label(String term) {
        return term.length() > 20 ? term.substring(0, 20) : term;
    }

    private static void record(String title, String plan) {
        System.out.println("EXPLAIN-EVIDENCE [" + title + "]\n" + plan + "\n");
    }

    private UUID business() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(id,slug,display_name,business_type,status,timezone,created_at,updated_at)
                        VALUES (:id,:slug,'Explain Business','OTHER','DRAFT','Europe/Sofia',:now,:now)
                        """)
                .param("id", id)
                .param("slug", "customer-explain-" + id)
                .param("now", now)
                .update();
        return id;
    }
}
