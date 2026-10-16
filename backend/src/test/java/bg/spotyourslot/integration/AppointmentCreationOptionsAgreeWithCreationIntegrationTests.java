package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.AppointmentAdministration;
import bg.spotyourslot.booking.AppointmentAdministrationException;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreateAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.ReviewedService;
import bg.spotyourslot.booking.AppointmentCreationOptions;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.OfferedStart;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The read side and the write side of a manual visit agree: a start the options offer is accepted by
 * the creation, one it does not offer is rejected as an unavailable slot, and the summed duration the
 * options report is the one the creation stores. Real PostgreSQL, the real orchestration.
 */
class AppointmentCreationOptionsAgreeWithCreationIntegrationTests extends BusinessCalendarApiIntegrationTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @Autowired AppointmentCreationOptions creation;
    @Autowired AppointmentAdministration administration;

    private AuthenticatedBusinessContext context;
    private Tenant tenant;
    private UUID second;

    @BeforeEach
    void seed() {
        Actor owner = api.owner("ACTIVE");
        UUID user = owner.userId();
        UUID business = owner.businessId();
        context = new AuthenticatedBusinessContext() {
            @Override
            public UUID userId() {
                return user;
            }

            @Override
            public Optional<UUID> selectedBusinessId() {
                return Optional.of(business);
            }
        };
        tenant = tenant(business, "Мария Петрова", "Анна Иванова");
        second = availability.service(business, 45, true);
        availability.assign(business, tenant.staff(), second);
        availability.everyDay(business, tenant.staff(), "09:00", "14:00");
    }

    private ReviewedService claim(UUID service) {
        return jdbc.sql("SELECT name, duration_minutes, price FROM service WHERE id = :id")
                .param("id", service)
                .query((rs, row) -> new ReviewedService(service, rs.getString("name"),
                        rs.getInt("duration_minutes"), rs.getBigDecimal("price").movePointRight(2).longValueExact()))
                .single();
    }

    private void create(Instant start, UUID... services) {
        List<ReviewedService> claims = new ArrayList<>();
        for (UUID service : services) {
            claims.add(claim(service));
        }
        administration.create(context, new CreateAppointmentCommand(
                tenant.customer(), tenant.staff(), start, claims, null));
    }

    @Test
    void everyStartTheOptionsOfferIsAcceptedAndTheDurationIsTheStoredOne() {
        List<UUID> selection = List.of(tenant.service(), second);
        int minutes = creation.options(context, selection).durationMinutes();
        assertThat(minutes).isEqualTo(75);

        List<OfferedStart> offered = creation.availability(context, selection, tenant.staff(), DAY).starts();
        assertThat(offered).isNotEmpty();
        // The first, a middle and the last offered start: non-overlapping visits of 75 minutes.
        OfferedStart first = offered.getFirst();
        OfferedStart last = offered.getLast();
        create(first.start(), tenant.service(), second);
        create(last.start(), second, tenant.service());

        List<Integer> stored = jdbc.sql("SELECT duration_minutes FROM appointment ORDER BY start_at")
                .query(Integer.class).list();
        assertThat(stored).containsExactly(75, 75);
        // Each created visit now blocks exactly what the options stop offering.
        List<Instant> after = creation.availability(context, selection, tenant.staff(), DAY).starts().stream()
                .map(OfferedStart::start).toList();
        assertThat(after).doesNotContain(first.start(), last.start());
        assertThat(after).hasSizeLessThan(offered.size());
    }

    @Test
    void aStartTheOptionsDoNotOfferIsRejectedAsAnUnavailableSlot() {
        List<UUID> selection = List.of(tenant.service(), second);
        List<Instant> offered = creation.availability(context, selection, tenant.staff(), DAY).starts().stream()
                .map(OfferedStart::start).toList();
        // Off the 15-minute grid and a start whose 75 minutes would pass the end of the working day.
        Instant offGrid = Instant.parse("2026-11-10T07:07:00Z");
        Instant tooLate = Instant.parse("2026-11-10T11:30:00Z");
        assertThat(offered).doesNotContain(offGrid, tooLate);

        for (Instant start : List.of(offGrid, tooLate)) {
            assertThatThrownBy(() -> create(start, tenant.service(), second))
                    .isInstanceOf(AppointmentAdministrationException.SlotUnavailable.class);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single()).isZero();
    }

    @Test
    void theOptionsFollowTheCreationWhenAnAppointmentOccupiesTheOfferedTime() {
        List<UUID> selection = List.of(tenant.service());
        Instant start = creation.availability(context, selection, tenant.staff(), DAY).starts().getFirst().start();
        create(start, tenant.service());

        assertThatThrownBy(() -> create(start, tenant.service()))
                .isInstanceOf(AppointmentAdministrationException.SlotUnavailable.class);
        assertThat(creation.availability(context, selection, tenant.staff(), DAY).starts().stream()
                .map(OfferedStart::start)).doesNotContain(start);
    }
}
