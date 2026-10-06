package bg.spotyourslot.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.domain.NewAppointment;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.scheduling.BusyIntervalSource;
import bg.spotyourslot.scheduling.BusyIntervalSource.BusyWindow;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Booking-owned {@link BusyIntervalSource} against real PostgreSQL: {@code CONFIRMED} rows
 * only, Business and requested-StaffMember filtering, half-open overlap, ordering, immutability,
 * mandatory transaction participation, and one bulk statement.
 */
@Import(BookingBusyIntervalSourceIntegrationTests.CountingConfiguration.class)
class BookingBusyIntervalSourceIntegrationTests extends PostgresIntegrationTest {
    private static final Instant FROM = Instant.parse("2026-11-10T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-11T00:00:00Z");
    private static final Instant NOON = Instant.parse("2026-11-10T12:00:00Z");
    private static final List<String> RECORDED = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean recording;

    @Autowired
    BusyIntervalSource source;

    @Autowired
    AppointmentStore store;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private AppointmentFixtures fixtures;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        // The counting DataSource proxy is not the transaction manager's DataSource, so the
        // declarative SQL scripts of the other classes cannot be used here.
        jdbc.sql("TRUNCATE business CASCADE").update();
        fixtures = new AppointmentFixtures(jdbc);
        tenant = fixtures.tenant();
    }

    private Map<UUID, List<BusyWindow>> busy(UUID business, List<UUID> staff, Instant from, Instant to) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> source.findBusyWindows(business, staff, from, to));
    }

    /** Another StaffMember of the same Business, the same Service and the same Customer. */
    private Tenant another() {
        return new Tenant(
                tenant.business(), tenant.service(),
                fixtures.staffMember(tenant.business(), tenant.service()), tenant.customer());
    }

    private void insert(Tenant owner, Instant start, int minutes) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> store.insert(AppointmentFixtures.online(owner, start, minutes)));
    }

    @Test
    void onlyConfirmedRowsAreBusyAndTheWindowIsTheOccupiedRange() {
        insert(tenant, NOON, 60);
        var cancelled = AppointmentFixtures.row(tenant, NOON.plusSeconds(7200), 30);
        cancelled.put("status", "CANCELLED");
        fixtures.insertRow(cancelled);

        Map<UUID, List<BusyWindow>> windows = busy(tenant.business(), List.of(tenant.staff()), FROM, TO);

        assertThat(windows).containsOnlyKeys(tenant.staff());
        assertThat(windows.get(tenant.staff())).containsExactly(
                new BusyWindow(NOON, NOON.plusSeconds(3600)));
    }

    @Test
    void windowsAreHalfOpenAndNeverClipped() {
        Tenant touchesFrom = another();
        Tenant straddlesFrom = another();
        Tenant inside = another();
        Tenant straddlesTo = another();
        Tenant touchesTo = another();
        Tenant after = another();
        insert(touchesFrom, FROM.minusSeconds(1800), 30);   // ends exactly at FROM: touches only
        insert(straddlesFrom, FROM.minusSeconds(900), 30);  // overlaps the start of the range
        insert(inside, NOON, 30);
        insert(straddlesTo, TO.minusSeconds(900), 30);      // overlaps the end of the range
        insert(touchesTo, TO, 30);                          // starts exactly at TO: touches only
        insert(after, TO.plusSeconds(3600), 30);

        Map<UUID, List<BusyWindow>> windows = busy(
                tenant.business(),
                List.of(touchesFrom.staff(), straddlesFrom.staff(), inside.staff(),
                        straddlesTo.staff(), touchesTo.staff(), after.staff()),
                FROM, TO);

        assertThat(windows).containsOnlyKeys(
                straddlesFrom.staff(), inside.staff(), straddlesTo.staff());
        assertThat(windows.get(straddlesFrom.staff())).containsExactly(
                new BusyWindow(FROM.minusSeconds(900), FROM.plusSeconds(900)));
        assertThat(windows.get(inside.staff())).containsExactly(
                new BusyWindow(NOON, NOON.plusSeconds(1800)));
        assertThat(windows.get(straddlesTo.staff())).containsExactly(
                new BusyWindow(TO.minusSeconds(900), TO.plusSeconds(900)));
    }

    @Test
    void aWindowSpanningTheWholeQueryRangeIsReturnedUnclipped() {
        Instant start = FROM.minusSeconds(3600);
        insert(tenant, start, 480);
        insert(tenant, TO, 30);

        assertThat(busy(tenant.business(), List.of(tenant.staff()),
                FROM.plusSeconds(3600), FROM.plusSeconds(7200)).get(tenant.staff()))
                .containsExactly(new BusyWindow(start, start.plusSeconds(480 * 60L)));
    }

    @Test
    void windowsAreOrderedByStartPerStaffMemberAndAbsentKeysMeanNoBusyTime() {
        UUID secondStaff = fixtures.staffMember(tenant.business(), tenant.service());
        UUID idleStaff = fixtures.staffMember(tenant.business(), tenant.service());
        Tenant second = new Tenant(tenant.business(), tenant.service(), secondStaff, tenant.customer());
        insert(tenant, NOON.plusSeconds(7200), 30);
        insert(tenant, NOON, 30);
        insert(tenant, NOON.plusSeconds(3600), 30);
        insert(second, NOON.plusSeconds(60), 30);

        Map<UUID, List<BusyWindow>> windows = busy(
                tenant.business(), List.of(tenant.staff(), secondStaff, idleStaff), FROM, TO);

        assertThat(windows).containsOnlyKeys(tenant.staff(), secondStaff);
        assertThat(windows.get(tenant.staff())).extracting(BusyWindow::start)
                .containsExactly(NOON, NOON.plusSeconds(3600), NOON.plusSeconds(7200));
        assertThat(windows.get(secondStaff)).extracting(BusyWindow::start)
                .containsExactly(NOON.plusSeconds(60));
    }

    @Test
    void onlyTheRequestedStaffMembersOfTheRequestedBusinessAppear() {
        UUID secondStaff = fixtures.staffMember(tenant.business(), tenant.service());
        Tenant second = new Tenant(tenant.business(), tenant.service(), secondStaff, tenant.customer());
        Tenant otherBusiness = fixtures.tenant();
        insert(tenant, NOON, 30);
        insert(second, NOON, 30);
        insert(otherBusiness, NOON, 30);

        assertThat(busy(tenant.business(), List.of(tenant.staff()), FROM, TO))
                .containsOnlyKeys(tenant.staff());
        // Another Business's StaffMember is never reported, even when named explicitly.
        assertThat(busy(tenant.business(), List.of(otherBusiness.staff()), FROM, TO)).isEmpty();
        assertThat(busy(otherBusiness.business(), List.of(tenant.staff()), FROM, TO)).isEmpty();
        assertThat(busy(otherBusiness.business(), List.of(otherBusiness.staff()), FROM, TO))
                .containsOnlyKeys(otherBusiness.staff());
    }

    @Test
    void theResultIsDeeplyImmutable() {
        insert(tenant, NOON, 30);

        Map<UUID, List<BusyWindow>> windows = busy(tenant.business(), List.of(tenant.staff()), FROM, TO);

        assertThatThrownBy(() -> windows.put(UUID.randomUUID(), List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> windows.get(tenant.staff()).add(new BusyWindow(FROM, TO)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(busy(tenant.business(), List.of(), FROM, TO)).isEmpty();
    }

    @Test
    void aCallWithoutATransactionIsRejected() {
        assertThatThrownBy(() -> source.findBusyWindows(
                tenant.business(), List.of(tenant.staff()), FROM, TO))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void invalidArgumentsAreRejected() {
        UUID business = tenant.business();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.execute(status ->
                source.findBusyWindows(null, List.of(), FROM, TO)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> transaction.execute(status ->
                source.findBusyWindows(business, null, FROM, TO)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> transaction.execute(status ->
                source.findBusyWindows(business, List.of(), null, TO)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> transaction.execute(status ->
                source.findBusyWindows(business, List.of(), FROM, null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> transaction.execute(status ->
                source.findBusyWindows(business, List.of(tenant.staff()), TO, FROM)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manyStaffMembersAreAnsweredByExactlyOneAppointmentStatement() {
        List<UUID> staff = new ArrayList<>();
        staff.add(tenant.staff());
        for (int index = 0; index < 9; index++) {
            staff.add(fixtures.staffMember(tenant.business(), tenant.service()));
        }
        insert(tenant, NOON, 30);

        List<String> statements = record(() -> busy(tenant.business(), staff, FROM, TO));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).contains("from appointment").contains("status = 'confirmed'");
    }

    @Test
    void duplicateRequestedIdsAndAnEmptyRequestStaySingleOrNoStatement() {
        insert(tenant, NOON, 30);

        assertThat(record(() -> busy(
                tenant.business(), List.of(tenant.staff(), tenant.staff()), FROM, TO))).hasSize(1);
        assertThat(record(() -> busy(tenant.business(), List.of(), FROM, TO))).isEmpty();
    }

    private static List<String> record(Runnable action) {
        RECORDED.clear();
        recording = true;
        try {
            action.run();
        } finally {
            recording = false;
        }
        return RECORDED.stream().filter(sql -> sql.contains("from appointment")).toList();
    }

    @TestConfiguration
    static class CountingConfiguration {
        @Bean
        static BeanPostProcessor statementCountingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource dataSource ? countingDataSource(dataSource) : bean;
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
                            && (method.getName().equals("prepareStatement")
                                    || method.getName().equals("prepareCall"))
                            && args != null
                            && args.length > 0
                            && args[0] instanceof String sql) {
                        RECORDED.add(sql.replaceAll("\\s+", " ").toLowerCase());
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
