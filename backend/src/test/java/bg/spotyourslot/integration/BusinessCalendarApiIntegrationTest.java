package bg.spotyourslot.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.domain.AppointmentServiceLine;
import bg.spotyourslot.booking.domain.AppointmentSource;
import bg.spotyourslot.booking.domain.NewAppointment;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.identity.domain.TokenCodec;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Common setup of the private calendar API tests: owners and other actors of the Customer API test
 * support, synthetic data written straight to the Testcontainers database, the four read requests,
 * and the SQL recording of {@link CalendarSqlRecording}. Every value is fictional.
 */
@AutoConfigureMockMvc
@Import(CalendarSqlRecording.class)
abstract class BusinessCalendarApiIntegrationTest extends PostgresIntegrationTest {
    static final Instant NOW = CalendarSqlRecording.NOW;
    /** A Tuesday in November 2026: Sofia is on UTC+02:00. */
    static final Instant NOV_10 = Instant.parse("2026-11-10T07:00:00Z");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired TokenCodec tokens;
    @Autowired MutableTestClock clock;
    @Autowired AppointmentStore store;
    @Autowired PlatformTransactionManager transactionManager;

    BusinessCustomerApiTestSupport api;
    AppointmentFixtures fixtures;
    AvailabilityFixtures availability;

    @BeforeEach
    void resetCalendarFixtures() {
        jdbc.sql("TRUNCATE business,app_user CASCADE").update();
        clock.set(NOW);
        api = new BusinessCustomerApiTestSupport(mvc, jdbc, tokens, NOW);
        fixtures = new AppointmentFixtures(jdbc);
        availability = new AvailabilityFixtures(jdbc);
        CalendarSqlRecording.clear();
    }

    // ---- data ------------------------------------------------------------------------------------

    /** One Service, one active StaffMember named by {@code staffName}, and one named Customer. */
    Tenant tenant(UUID business, String staffName, String customerName) {
        UUID service = availability.service(business, 30, true);
        UUID staff = staff(business, staffName, true);
        availability.assign(business, staff, service);
        return new Tenant(business, service, staff, customer(business, customerName));
    }

    UUID staff(UUID business, String name, boolean active) {
        UUID staff = availability.staffMember(business, active);
        jdbc.sql("UPDATE staff_member SET display_name = :name WHERE id = :id")
                .param("name", name).param("id", staff).update();
        return staff;
    }

    UUID customer(UUID business, String name) {
        int number = SEQUENCE.incrementAndGet();
        return api.insertCustomer(business, name, "+359888" + String.format("%06d", number), "c" + number + "@example.test");
    }

    Tenant withStaff(Tenant tenant, UUID staff) {
        return new Tenant(tenant.business(), tenant.service(), staff, tenant.customer());
    }

    Tenant withCustomer(Tenant tenant, UUID customer) {
        return new Tenant(tenant.business(), tenant.service(), tenant.staff(), customer);
    }

    /** A single-Service Appointment inserted by SQL, customized before it is written. */
    UUID appointment(Tenant tenant, Instant start, int minutes, Consumer<Map<String, Object>> customize) {
        Map<String, Object> row = AppointmentFixtures.row(tenant, start, minutes);
        customize.accept(row);
        fixtures.insertRow(row);
        return (UUID) row.get("id");
    }

    UUID appointment(Tenant tenant, Instant start, int minutes) {
        return appointment(tenant, start, minutes, BusinessCalendarApiIntegrationTest::unchanged);
    }

    /** A multi-Service visit through the real store: one line per name, 30 minutes each, price 10.00. */
    UUID visit(Tenant tenant, Instant start, String... serviceNames) {
        List<AppointmentServiceLine> lines = new ArrayList<>();
        for (int index = 0; index < serviceNames.length; index++) {
            UUID service = index == 0 ? tenant.service() : availability.service(tenant.business(), 30, true);
            lines.add(new AppointmentServiceLine(index + 1, service, serviceNames[index], 30, new BigDecimal("10.00")));
        }
        NewAppointment visit = new NewAppointment(
                UUID.randomUUID(), tenant.business(), tenant.customer(), tenant.service(), tenant.staff(),
                AppointmentSource.MANUAL, start, 30 * lines.size(), new BigDecimal("10.00").multiply(BigDecimal.valueOf(lines.size())),
                "Europe/Sofia", serviceNames[0], AppointmentFixtures.STAFF_NAME, null,
                AppointmentFixtures.reference(), null, AppointmentFixtures.CREATED_AT, lines);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> store.insert(visit));
        return visit.id();
    }

    private static void unchanged(Map<String, Object> row) {
    }

    OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // ---- requests --------------------------------------------------------------------------------

    ResultActions calendar(Actor actor, String query) throws Exception {
        return mvc.perform(get("/api/business/calendar" + (query == null ? "" : "?" + query))
                .cookie(actor.session()));
    }

    ResultActions agenda(Actor actor, String query) throws Exception {
        return mvc.perform(get("/api/business/appointments" + (query == null ? "" : "?" + query))
                .cookie(actor.session()));
    }

    ResultActions detail(Actor actor, Object appointmentId) throws Exception {
        return mvc.perform(get("/api/business/appointments/{id}", appointmentId).cookie(actor.session()));
    }

    ResultActions history(Actor actor, Object customerId, String query) throws Exception {
        return mvc.perform(get("/api/business/customers/{id}/appointments" + (query == null ? "" : "?" + query), customerId)
                .cookie(actor.session()));
    }

    static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    static JsonNode json(ResultActions result) throws Exception {
        return BusinessCustomerApiTestSupport.parse(body(result));
    }

    static List<String> keys(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }
}
