package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.await;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreateAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.ReviewedService;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shared base of the owner-created Appointment integration tests: real PostgreSQL, the real
 * {@link AppointmentAdministration}, a controllable clock (Tuesday 2026-09-29 11:00 in Sofia), the
 * guest hooks plus {@link ManualBookingHooks}, and helpers for named attempt threads. The default
 * slot is Thursday 2026-10-01 10:00 Sofia with the StaffMember working 09:00 to 12:00.
 */
public abstract class ManualAppointmentTest extends BookingIntegrationTest {
    @Autowired protected AppointmentAdministration administration;
    @Autowired protected ManualBookingHooks manualHooks;

    protected AuthenticatedBusinessContext ownerContext;
    /** The Business's Customer (the shared booking fixture deletes the Tenant's own). */
    protected UUID customerId;

    @BeforeEach
    void setUpManualFixtures() {
        manualHooks.reset();
        ownerContext = owner(tenant.business());
        customerId = fixtures.customer(tenant.business());
    }

    // ---- commands ---------------------------------------------------------------

    /** The reviewed terms of a Service exactly as the database holds them now. */
    protected ReviewedService reviewed(UUID service) {
        return jdbc.sql("SELECT name, duration_minutes, price FROM service WHERE id = :id")
                .param("id", service)
                .query((resultSet, row) -> new ReviewedService(
                        service,
                        resultSet.getString("name"),
                        resultSet.getInt("duration_minutes"),
                        resultSet.getBigDecimal("price").movePointRight(2).longValueExact()))
                .single();
    }

    /** An additional active Service of the Tenant's Business assigned to its StaffMember. */
    protected UUID addService(int minutes, String name) {
        UUID service = fixtures.service(tenant.business(), minutes);
        availabilityFixtures.assign(tenant.business(), tenant.staff(), service);
        jdbc.sql("UPDATE service SET name = :name WHERE id = :id").param("name", name).param("id", service).update();
        return service;
    }

    protected final class Cmd {
        UUID customer = customerId;
        UUID staff = tenant.staff();
        Instant start = at("10:00");
        List<ReviewedService> services = new ArrayList<>(List.of(reviewed(tenant.service())));
        String note;

        public Cmd customer(UUID value) {
            customer = value;
            return this;
        }

        public Cmd staff(UUID value) {
            staff = value;
            return this;
        }

        public Cmd start(String time) {
            start = at(time);
            return this;
        }

        public Cmd start(Instant value) {
            start = value;
            return this;
        }

        public Cmd noServices() {
            services = new ArrayList<>();
            return this;
        }

        public Cmd services(UUID... ids) {
            services = new ArrayList<>();
            for (UUID id : ids) {
                services.add(reviewed(id));
            }
            return this;
        }

        public Cmd services(ReviewedService... values) {
            services = new ArrayList<>(List.of(values));
            return this;
        }

        public Cmd note(String value) {
            note = value;
            return this;
        }

        public CreateAppointmentCommand build() {
            return new CreateAppointmentCommand(customer, staff, start, services, note);
        }
    }

    protected Cmd cmd() {
        return new Cmd();
    }

    // ---- execution --------------------------------------------------------------

    protected CompletableFuture<CreatedAppointment> submitCreate(String label, Cmd command) {
        return submitCreate(label, ownerContext, command.build());
    }

    protected CompletableFuture<CreatedAppointment> submitCreate(
            String label, AuthenticatedBusinessContext context, CreateAppointmentCommand command) {
        return onBookingThread(label, () -> administration.create(context, command));
    }

    protected CreatedAppointment create(Cmd command) {
        return completed(submitCreate("main", command));
    }

    /** The typed failure a submitted creation ended with, or an assertion error if it succeeded. */
    protected static Throwable failureOf(CompletableFuture<?> future) {
        try {
            future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            return exception.getCause();
        } catch (Exception exception) {
            throw new AssertionError("the creation did not complete", exception);
        }
        throw new AssertionError("the creation succeeded but a failure was expected");
    }

    /** Holds the named attempt at a guest point until released, after telling the test it arrived. */
    protected void holdAt(
            BookingTestHooks.Point point, String label, CountDownLatch reached, CountDownLatch release) {
        hooks.on(point, invocation -> {
            if (invocation.thread().equals(threadOf(label)) && invocation.ordinal() == 1) {
                reached.countDown();
                await(release, "attempt " + label + " was never released at " + point);
            }
        });
    }

    /** Holds the named attempt at a Business point until released. */
    protected void holdAt(
            ManualBookingHooks.Point point, String label, CountDownLatch reached, CountDownLatch release) {
        manualHooks.on(point, invocation -> {
            if (invocation.thread().equals(threadOf(label)) && invocation.ordinal() == 1) {
                reached.countDown();
                await(release, "attempt " + label + " was never released at " + point);
            }
        });
    }

    // ---- observation ------------------------------------------------------------

    protected Map<String, Object> appointmentById(UUID id) {
        return jdbc.sql("SELECT * FROM appointment WHERE id = :id").param("id", id).query().singleRow();
    }

    protected List<Map<String, Object>> linesOf(UUID id) {
        return jdbc.sql("""
                        SELECT position, service_id, service_name, duration_minutes, price_eur
                        FROM appointment_service WHERE appointment_id = :id ORDER BY position
                        """)
                .param("id", id)
                .query()
                .listOfRows();
    }

    protected long linesCount(UUID business) {
        return jdbc.sql("SELECT count(*) FROM appointment_service WHERE business_id = :id")
                .param("id", business).query(Long.class).single();
    }

    protected String customerSnapshot() {
        return jdbc.sql("SELECT string_agg(c::text, '|' ORDER BY id) FROM customer c WHERE business_id = :id")
                .param("id", tenant.business()).query(String.class).single();
    }
}
