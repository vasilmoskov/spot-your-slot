package bg.spotyourslot.publicprofile.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import bg.spotyourslot.business.PublicBusinessProfileAccess;
import bg.spotyourslot.catalog.PublicServiceAccess;
import bg.spotyourslot.catalog.application.PublicServiceAccessService;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the transaction, statement-count, locking, and snapshot guarantees of the public profile
 * read (ADR-0017). The instrumentation is scoped to this class and records only SQL text handed
 * to {@code Connection.prepareStatement}/{@code prepareCall}, plus the isolation and read-only
 * settings applied to a connection; driver metadata and transaction setup are not counted. There
 * are no sleeps: the concurrent change is committed on another thread and joined.
 */
@AutoConfigureMockMvc
@Import(PublicProfileTransactionIntegrationTests.CountingConfiguration.class)
class PublicProfileTransactionIntegrationTests extends PostgresIntegrationTest {
    private static final List<String> RECORDED = Collections.synchronizedList(new ArrayList<>());
    private static final List<Integer> ISOLATIONS = Collections.synchronizedList(new ArrayList<>());
    private static final List<Boolean> READ_ONLY = Collections.synchronizedList(new ArrayList<>());
    private static final Pattern WRITE_OR_LOCK = Pattern.compile(
            "\\b(insert|update|delete|truncate|merge|for\\s+(no\\s+key\\s+)?update"
                    + "|for\\s+(key\\s+)?share|lock\\s+table)\\b");
    private static volatile boolean recording;
    private static volatile Runnable beforeServices;

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PublicProfileService profiles;
    @Autowired PublicBusinessProfileAccess businessAccess;
    @Autowired PublicServiceAccess serviceAccess;

    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        recording = false;
        beforeServices = null;
        jdbc.sql("TRUNCATE business CASCADE").update();
        now = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @Test
    void theCountedDataSourceIsTheOneTheTransactionManagerUses() {
        assertThat(((JpaTransactionManager) transactions).getDataSource()).isSameAs(dataSource);
    }

    // ---- statement count ----

    @Test
    void anActiveBusinessIssuesExactlyTwoStatementsWhetherItHasOneServiceOrMany() throws Exception {
        UUID one = business("one-service", "ACTIVE");
        service(one, "Only", true);
        UUID many = business("many-services", "ACTIVE");
        for (int index = 0; index < 25; index++) {
            service(many, "Service %02d".formatted(index), true);
        }
        UUID none = business("no-services", "ACTIVE");

        assertThat(statementsForRequest("one-service")).hasSize(2);
        assertThat(statementsForRequest("many-services")).hasSize(2);
        assertThat(statementsForRequest("no-services")).hasSize(2);
        assertThat(none).isNotNull();
    }

    @Test
    void anUnavailableBusinessIssuesExactlyOneStatement() throws Exception {
        business("draft-one", "DRAFT");
        business("paused-one", "SUSPENDED");

        assertThat(statementsForRequest("draft-one")).hasSize(1);
        assertThat(statementsForRequest("paused-one")).hasSize(1);
        assertThat(statementsForRequest("unknown-one")).hasSize(1);
    }

    @Test
    void aMalformedOrReservedSlugIssuesNoStatementAtAll() throws Exception {
        business("booking", "ACTIVE");

        for (String slug : List.of("bad_slug", "-x", "a--b", "x".repeat(101), "login", "booking", "api")) {
            assertThat(statementsForRequest(slug)).as(slug).isEmpty();
        }
    }

    @Test
    void theTwoStatementsAreTheBusinessThenTheServicesWithExplicitPublicColumnsOnly() throws Exception {
        UUID business = business("shape-studio", "ACTIVE");
        service(business, "Alpha", true);

        List<String> statements = statementsForRequest("shape-studio");

        assertThat(statements).hasSize(2);
        String first = normalized(statements.get(0));
        assertThat(first)
                .contains("from business")
                .contains("slug = ?")
                .contains("status = 'active'")
                .doesNotContain("select *")
                .doesNotContain("contact_email")
                .doesNotContain("timezone")
                .doesNotContain("version")
                .doesNotContain("created_at")
                .doesNotContain("updated_at");
        String second = normalized(statements.get(1));
        assertThat(second)
                .contains("from service")
                .contains("business_id = ?")
                .contains("and active")
                .contains("order by normalized_name asc, id asc")
                .doesNotContain("select *")
                .doesNotContain("version")
                .doesNotContain("created_at")
                .doesNotContain("limit")
                .doesNotContain("offset");
    }

    @Test
    void noStatementEverWritesOrTakesAnExplicitLock() throws Exception {
        UUID business = business("readonly-studio", "ACTIVE");
        service(business, "Alpha", true);
        business("draft-studio", "DRAFT");

        List<String> statements = new ArrayList<>();
        for (String slug : List.of("readonly-studio", "draft-studio", "unknown-studio", "bad_slug")) {
            statements.addAll(statementsForRequest(slug));
        }

        assertThat(statements).isNotEmpty();
        for (String statement : statements) {
            assertThat(WRITE_OR_LOCK.matcher(normalized(statement)).find())
                    .as(statement)
                    .isFalse();
        }
    }

    // ---- transaction semantics ----

    @Test
    void aStandaloneCallStartsAReadOnlyRepeatableReadTransaction() throws Exception {
        UUID business = business("tx-studio", "ACTIVE");
        service(business, "Alpha", true);

        List<String> statements = record(() -> profiles.findBySlug("tx-studio"));

        assertThat(statements).hasSize(2);
        assertThat(ISOLATIONS).contains(Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(READ_ONLY).contains(true);
    }

    @Test
    void aCallInsideAReadCommittedTransactionFailsBeforeAnySql() {
        business("tx-studio", "ACTIVE");
        Throwable[] failure = new Throwable[1];

        List<String> statements = record(() -> failure[0] = org.assertj.core.api.Assertions.catchThrowable(
                () -> inTransaction(TransactionDefinition.ISOLATION_READ_COMMITTED)));

        assertThat(statements).isEmpty();
        assertThat(failure[0]).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCallInsideADefaultIsolationTransactionFailsBeforeAnySql() {
        business("tx-studio", "ACTIVE");
        Throwable[] failure = new Throwable[1];

        List<String> statements = record(() -> failure[0] = org.assertj.core.api.Assertions.catchThrowable(
                () -> inTransaction(TransactionDefinition.ISOLATION_DEFAULT)));

        assertThat(statements).isEmpty();
        assertThat(failure[0]).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCallJoiningARepeatableReadOrSerializableTransactionSucceeds() {
        UUID business = business("tx-studio", "ACTIVE");
        service(business, "Alpha", true);

        for (int level : new int[] {
            TransactionDefinition.ISOLATION_REPEATABLE_READ,
            TransactionDefinition.ISOLATION_SERIALIZABLE
        }) {
            List<String> statements = record(() -> {
                var view = inTransaction(level);
                assertThat(view).isPresent();
            });
            assertThat(statements).as("isolation %d", level).hasSize(2);
        }
    }

    @Test
    void thePublishedReadContractsRefuseToRunWithoutACallerTransaction() {
        business("tx-studio", "ACTIVE");

        assertThatThrownBy(() -> businessAccess.findActiveBySlug("tx-studio"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> serviceAccess.findActiveServices(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theBusinessAndServicesComeFromOneSnapshotEvenWhenARowChangesBetweenTheReads() {
        UUID business = business("snapshot-studio", "ACTIVE");
        service(business, "Alpha", true);
        beforeServices = () -> CompletableFuture.runAsync(() -> {
            // Committed on another connection after the Business read and before the Services read.
            jdbc.sql("UPDATE service SET active = false WHERE business_id = :id")
                    .param("id", business)
                    .update();
            jdbc.sql("UPDATE business SET display_name = 'Changed after first read' WHERE id = :id")
                    .param("id", business)
                    .update();
        }).join();

        var during = profiles.findBySlug("snapshot-studio").orElseThrow();
        var after = profiles.findBySlug("snapshot-studio").orElseThrow();

        assertThat(during.displayName()).isEqualTo("Snapshot studio");
        assertThat(during.services()).extracting(PublicProfileView.Service::name).containsExactly("Alpha");
        assertThat(after.displayName()).isEqualTo("Changed after first read");
        assertThat(after.services()).isEmpty();
    }

    // ---- helpers ----

    private java.util.Optional<PublicProfileView> inTransaction(int isolation) {
        var template = new TransactionTemplate(transactions);
        template.setIsolationLevel(isolation);
        return template.execute(status -> profiles.findBySlug("tx-studio"));
    }

    private List<String> statementsForRequest(String slug) throws Exception {
        return record(() -> {
            try {
                mvc.perform(get("/api/public/businesses/{slug}", slug)).andReturn();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private List<String> record(Runnable action) {
        RECORDED.clear();
        ISOLATIONS.clear();
        READ_ONLY.clear();
        recording = true;
        try {
            action.run();
        } finally {
            recording = false;
        }
        return new ArrayList<>(RECORDED);
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private UUID business(String slug, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,created_at,updated_at)
                        VALUES (:id,:slug,:name,'OTHER',:status,'Europe/Sofia',:now,:now)
                        """)
                .param("id", id)
                .param("slug", slug)
                .param("name", slug.substring(0, 1).toUpperCase(Locale.ROOT) + slug.substring(1).replace('-', ' '))
                .param("status", status)
                .param("now", now)
                .update();
        return id;
    }

    private void service(UUID business, String name, boolean active) {
        jdbc.sql("""
                        INSERT INTO service(
                            id,business_id,name,description,duration_minutes,price,
                            active,version,created_at,updated_at)
                        VALUES (:id,:business,:name,NULL,30,10.00,:active,0,:now,:now)
                        """)
                .param("id", UUID.randomUUID())
                .param("business", business)
                .param("name", name)
                .param("active", active)
                .param("now", now)
                .update();
    }

    @TestConfiguration
    static class CountingConfiguration {
        @Bean
        static BeanPostProcessor statementCountingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource dataSource) {
                        return countingDataSource(dataSource);
                    }
                    return bean;
                }
            };
        }

        /** Runs a hook after the Business read and before the Services read, then delegates. */
        @Bean
        @Primary
        PublicServiceAccess hookedPublicServices(PublicServiceAccessService real) {
            return businessId -> {
                Runnable hook = beforeServices;
                if (hook != null) {
                    beforeServices = null;
                    hook.run();
                }
                return real.findActiveServices(businessId);
            };
        }
    }

    private static DataSource countingDataSource(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(target, method, args);
                    if (method.getName().equals("getConnection") && result instanceof Connection) {
                        return countingConnection((Connection) result);
                    }
                    return result;
                });
    }

    private static Connection countingConnection(Connection target) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (recording && args != null && args.length > 0) {
                        String name = method.getName();
                        if (name.equals("setTransactionIsolation") && args[0] instanceof Integer level) {
                            ISOLATIONS.add(level);
                        }
                        if (name.equals("setReadOnly") && args[0] instanceof Boolean readOnly) {
                            READ_ONLY.add(readOnly);
                        }
                        if ((name.equals("prepareStatement") || name.equals("prepareCall"))
                                && args[0] instanceof String sql) {
                            RECORDED.add(sql);
                        }
                    }
                    return invoke(target, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
}
