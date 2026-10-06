package bg.spotyourslot.scheduling.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import bg.spotyourslot.integration.AvailabilityFixtures;
import bg.spotyourslot.integration.MutableTestClock;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.AvailabilityFailure;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySnapshot;
import bg.spotyourslot.scheduling.domain.NewScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionContent;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the application SQL statement count is constant in the number of
 * eligible StaffMembers. The instrumentation is scoped to this test class and
 * records only SQL text handed to {@code Connection.prepareStatement} or
 * {@code prepareCall}; connection metadata, transaction setup, and other driver
 * calls are not counted.
 */
@Import(AvailabilityQueryStatementCountIntegrationTests.CountingConfiguration.class)
class AvailabilityQueryStatementCountIntegrationTests extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 1);
    private static final List<String> RECORDED = Collections.synchronizedList(new ArrayList<>());
    private static final List<Integer> ISOLATIONS = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean recording;

    @Autowired AvailabilityQuery availability;
    @Autowired ScheduleExceptionStore exceptions;
    @Autowired JdbcClient jdbc;
    @Autowired MutableTestClock clock;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;

    private AvailabilityFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new AvailabilityFixtures(jdbc);
        clock.set(NOW);
    }

    @Test
    void theCountedDataSourceIsTheOneTheTransactionManagerUses() {
        assertThat(((JpaTransactionManager) transactions).getDataSource()).isSameAs(dataSource);
    }

    @Test
    void exactlyFiveApplicationStatementsForOneEligibleStaffMember() {
        // Four availability reads plus the one bulk busy-interval read of the Booking module.
        assertThat(statementsFor(1)).hasSize(5);
    }

    @Test
    void theSameFiveStatementsForTenEligibleStaffMembers() {
        assertThat(statementsFor(10)).hasSize(5);
    }

    @Test
    void theFiveStatementsAreBusinessServiceStaffWithPeriodsExceptionsAndBusyTimeInOrder() {
        List<String> statements = statementsFor(3);

        assertThat(statements).hasSize(5);
        assertThat(normalized(statements.get(0))).contains("from business");
        assertThat(normalized(statements.get(1))).contains("from service");
        assertThat(normalized(statements.get(2)))
                .contains("from staff_member m")
                .contains("staff_working_period");
        assertThat(normalized(statements.get(3))).contains("from schedule_exception e");
        assertThat(normalized(statements.get(4)))
                .contains("from appointment")
                .contains("status = 'confirmed'");
    }

    @Test
    void anEmptyEligibleSetStopsAfterThreeStatements() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID service = fixtures.service(business, 30, true);

        List<String> statements = record(() -> availability.calculate(business, service, null));

        assertThat(statements).hasSize(3);
    }

    // ---- transaction isolation precondition, each case with an explicit outer transaction ----

    @Test
    void aStandaloneCallCreatesAndUsesARepeatableReadTransactionAndSucceeds() {
        Scenario scenario = scenario(2);

        List<String> statements = record(() -> scenario.calculate(availability));

        assertThat(statements).hasSize(5);
        assertThat(ISOLATIONS).contains(Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(scenario.result()[0].slots()).isNotEmpty();
    }

    @Test
    void aCallInsideAReadCommittedTransactionFailsBeforeAnyAvailabilitySql() {
        Scenario scenario = scenario(2);
        Throwable[] failure = new Throwable[1];

        List<String> statements = record(() -> failure[0] = catchThrowable(
                () -> inTransaction(TransactionDefinition.ISOLATION_READ_COMMITTED, scenario)));

        assertThat(statements).isEmpty();
        assertThat(failure[0]).isInstanceOf(AvailabilityFailure.class)
                .hasMessage("Availability calculation failed");
        assertThat(failure[0].getMessage()).doesNotContain(scenario.business().toString());
        assertThat(failure[0].getMessage()).doesNotContainIgnoringCase("isolation");
    }

    @Test
    void aCallInsideADefaultIsolationTransactionFailsBeforeAnyAvailabilitySql() {
        Scenario scenario = scenario(1);
        Throwable[] failure = new Throwable[1];

        List<String> statements = record(() -> failure[0] = catchThrowable(
                () -> inTransaction(TransactionDefinition.ISOLATION_DEFAULT, scenario)));

        assertThat(statements).isEmpty();
        assertThat(failure[0]).isInstanceOf(AvailabilityFailure.class);
    }

    @Test
    void aCallInsideARepeatableReadTransactionJoinsItAndSucceeds() {
        Scenario scenario = scenario(2);

        List<String> statements = record(
                () -> inTransaction(TransactionDefinition.ISOLATION_REPEATABLE_READ, scenario));

        assertThat(statements).hasSize(5);
        assertThat(ISOLATIONS).first().isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(scenario.result()[0].slots()).isNotEmpty();
    }

    @Test
    void aCallInsideASerializableTransactionJoinsItAndSucceeds() {
        Scenario scenario = scenario(2);

        List<String> statements = record(
                () -> inTransaction(TransactionDefinition.ISOLATION_SERIALIZABLE, scenario));

        assertThat(statements).hasSize(5);
        assertThat(ISOLATIONS).first().isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
        assertThat(scenario.result()[0].slots()).isNotEmpty();
    }

    private void inTransaction(int isolation, Scenario scenario) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setIsolationLevel(isolation);
        template.executeWithoutResult(status -> scenario.calculate(availability));
    }

    private Scenario scenario(int staffMembers) {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID service = fixtures.service(business, 30, true);
        for (int index = 0; index < staffMembers; index++) {
            UUID staff = fixtures.staffMember(business, true);
            fixtures.assign(business, staff, service);
            fixtures.everyDay(business, staff, "09:00", "18:00");
        }
        return new Scenario(business, service, new AvailabilitySnapshot[1]);
    }

    private record Scenario(UUID business, UUID service, AvailabilitySnapshot[] result) {
        void calculate(AvailabilityQuery availability) {
            result[0] = availability.calculate(business, service, null);
        }
    }

    private List<String> statementsFor(int staffMembers) {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID service = fixtures.service(business, 30, true);
        for (int index = 0; index < staffMembers; index++) {
            UUID staff = fixtures.staffMember(business, true);
            fixtures.assign(business, staff, service);
            fixtures.everyDay(business, staff, "09:00", "18:00");
            exceptions.insert(new NewScheduleException(
                    UUID.randomUUID(),
                    business,
                    ScheduleExceptionContent.staffTimeOffDays(staff, THURSDAY, THURSDAY),
                    NOW));
        }
        exceptions.insert(new NewScheduleException(
                UUID.randomUUID(),
                business,
                ScheduleExceptionContent.businessClosureDays(THURSDAY.plusDays(1), THURSDAY.plusDays(1)),
                NOW));

        AvailabilitySnapshot[] snapshot = new AvailabilitySnapshot[1];
        List<String> statements = record(
                () -> snapshot[0] = availability.calculate(business, service, null));

        assertThat(snapshot[0].slots()).isNotEmpty();
        return statements;
    }

    private static List<String> record(Runnable action) {
        RECORDED.clear();
        ISOLATIONS.clear();
        recording = true;
        try {
            action.run();
        } finally {
            recording = false;
        }
        return List.copyOf(RECORDED);
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").toLowerCase();
    }

    @TestConfiguration
    static class CountingConfiguration {
        @Bean
        @Primary
        MutableTestClock availabilityTestClock() {
            return new MutableTestClock(NOW);
        }

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
                    if (recording
                            && method.getName().equals("setTransactionIsolation")
                            && args != null
                            && args[0] instanceof Integer level) {
                        ISOLATIONS.add(level);
                    }
                    if (recording
                            && (method.getName().equals("prepareStatement")
                                    || method.getName().equals("prepareCall"))
                            && args != null
                            && args.length > 0
                            && args[0] instanceof String sql) {
                        RECORDED.add(sql);
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
