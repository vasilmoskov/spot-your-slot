package bg.spotyourslot.publicbooking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.integration.AvailabilityFixtures;
import bg.spotyourslot.integration.MutableTestClock;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.publicbooking.application.PublicBookingViews.Availability;
import bg.spotyourslot.publicbooking.application.PublicBookingViews.BookingOptions;
import bg.spotyourslot.publicbooking.application.PublicBookingViews.Slot;
import bg.spotyourslot.publicbooking.application.PublicBookingViews.Staff;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

/**
 * The internal booking-options and availability read model for a Service selection (ADR-0027).
 * It adds no HTTP route; the HTTP adapter is a later phase. The clock is fixed and every date is
 * explicit.
 */
@Import(PublicAvailabilitySelectionIntegrationTests.ClockConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PublicAvailabilitySelectionIntegrationTests extends PostgresIntegrationTest {
    /** Tuesday 11:00 in Sofia (UTC+3). */
    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 1);

    @Autowired PublicAvailabilityService service;
    @Autowired JdbcClient jdbc;
    @Autowired MutableTestClock clock;

    private AvailabilityFixtures fixtures;
    private UUID business;
    private String slug;
    private UUID first;
    private UUID second;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        fixtures = new AvailabilityFixtures(jdbc);
        business = fixtures.business("ACTIVE", "Europe/Sofia");
        slug = jdbc.sql("SELECT slug FROM business WHERE id = :id")
                .param("id", business).query(String.class).single();
        first = fixtures.service(business, 30, true);
        second = fixtures.service(business, 45, true);
    }

    private UUID member(String name, boolean active, String start, String end, UUID... services) {
        UUID id = fixtures.staffMember(business, active);
        jdbc.sql("UPDATE staff_member SET display_name = :name WHERE id = :id")
                .param("name", name).param("id", id).update();
        for (UUID assigned : services) {
            fixtures.assign(business, id, assigned);
        }
        if (start != null) {
            fixtures.everyDay(business, id, start, end);
        }
        return id;
    }

    @Test
    void theStaffChoicesAreTheIntersectionInNameOrderAndNeverTheUnion() {
        UUID zoe = member("Zoe", true, "09:00", "12:00", first, second);
        UUID ana = member("Ana", true, "09:00", "12:00", first, second);
        member("Boris", true, "09:00", "12:00", first);
        member("Cecil", true, "09:00", "12:00", second);
        member("Dara", false, "09:00", "12:00", first, second);

        BookingOptions options = service.bookingOptions(slug, List.of(first, second));

        assertThat(options.staff()).containsExactly(new Staff(ana, "Ana"), new Staff(zoe, "Zoe"));
        assertThat(options.timezone().getId()).isEqualTo("Europe/Sofia");
        assertThat(options.firstDate()).isEqualTo(TODAY);
        assertThat(options.lastDate()).isEqualTo(TODAY.plusDays(29));
    }

    @Test
    void anEmptyIntersectionReportsNoStaffButStillTheWindow() {
        member("Boris", true, "09:00", "12:00", first);
        member("Cecil", true, "09:00", "12:00", second);

        BookingOptions options = service.bookingOptions(slug, List.of(first, second));

        assertThat(options.staff()).isEmpty();
        assertThat(options.firstDate()).isEqualTo(TODAY);
        assertThat(options.lastDate()).isEqualTo(TODAY.plusDays(29));
    }

    @Test
    void theOneServiceReadModelIsUnchanged() {
        member("Ana", true, "09:00", "12:00", first);

        assertThat(service.bookingOptions(slug, first))
                .isEqualTo(service.bookingOptions(slug, List.of(first)));
        assertThat(service.availability(slug, first, THURSDAY, null))
                .isEqualTo(service.availability(slug, List.of(first), THURSDAY, null));
    }

    @Test
    void theSelectionAvailabilityOffersOnlyIntervalsFittingTheWholeVisit() {
        member("Ana", true, "09:00", "10:30", first, second);

        Availability availability = service.availability(slug, List.of(first, second), THURSDAY, null);

        // 30 plus 45 minutes in a 09:00-10:30 period: the starts 09:00 and 09:15 only.
        assertThat(availability.slots()).extracting(Slot::start).containsExactly(
                THURSDAY.atTime(9, 0).atZone(java.time.ZoneId.of("Europe/Sofia")).toInstant(),
                THURSDAY.atTime(9, 15).atZone(java.time.ZoneId.of("Europe/Sofia")).toInstant());
        assertThat(availability.slots()).allSatisfy(slot ->
                assertThat(Duration.between(slot.start(), slot.end())).isEqualTo(Duration.ofMinutes(75)));
        assertThat(availability.availableDates()).contains(THURSDAY);
    }

    @Test
    void anExplicitStaffMemberMustSupportEverySelectedService() {
        UUID both = member("Ana", true, "09:00", "12:00", first, second);
        UUID firstOnly = member("Boris", true, "09:00", "12:00", first);

        assertThat(service.availability(slug, List.of(first, second), THURSDAY, both).slots())
                .isNotEmpty();
        assertThatThrownBy(() -> service.availability(slug, List.of(first, second), THURSDAY, firstOnly))
                .isInstanceOf(PublicBookingUnavailable.Staff.class);
    }

    @Test
    void missingForeignInactiveAndOversizedSelectionsAreUnavailableForTheCaller() {
        member("Ana", true, "09:00", "12:00", first, second);
        UUID otherBusiness = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID foreign = fixtures.service(otherBusiness, 30, true);
        UUID inactive = fixtures.service(business, 30, false);
        UUID long1 = fixtures.service(business, 300, true);
        UUID long2 = fixtures.service(business, 300, true);

        assertThatThrownBy(() -> service.bookingOptions(slug, List.of(first, UUID.randomUUID())))
                .isInstanceOf(PublicBookingUnavailable.Service.class);
        assertThatThrownBy(() -> service.bookingOptions(slug, List.of(first, foreign)))
                .isInstanceOf(PublicBookingUnavailable.Service.class);
        assertThatThrownBy(() -> service.bookingOptions(slug, List.of(first, inactive)))
                .isInstanceOf(PublicBookingUnavailable.Service.class);
        assertThatThrownBy(() -> service.bookingOptions(slug, List.of(long1, long2)))
                .isInstanceOf(PublicBookingUnavailable.SelectionTooLong.class);
        assertThatThrownBy(() -> service.bookingOptions("no-such-business", List.of(first)))
                .isInstanceOf(PublicBookingUnavailable.Business.class);
    }

    @Test
    void emptyDuplicateAndOversizedSelectionsAreCallerErrors() {
        List<UUID> six = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            six.add(fixtures.service(business, 30, true));
        }

        assertThatThrownBy(() -> service.bookingOptions(slug, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.bookingOptions(slug, List.of(first, first)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.bookingOptions(slug, six))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableTestClock publicAvailabilitySelectionTestClock() {
            return new MutableTestClock(NOW);
        }
    }
}
