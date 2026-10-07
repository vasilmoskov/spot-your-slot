package bg.spotyourslot.booking;

import bg.spotyourslot.booking.BookingTestHooks.Point;
import bg.spotyourslot.business.ScheduleRevisionGuard;
import bg.spotyourslot.business.application.ScheduleRevisionService;
import bg.spotyourslot.catalog.ServiceBookingAccess;
import bg.spotyourslot.catalog.application.ServiceBookingAccessService;
import bg.spotyourslot.customer.CustomerIdentification;
import bg.spotyourslot.customer.CustomerMatchOutcome;
import bg.spotyourslot.customer.application.CustomerIdentificationService;
import bg.spotyourslot.integration.MutableTestClock;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.application.AvailabilityQueryService;
import bg.spotyourslot.workforce.StaffBookingAccess;
import bg.spotyourslot.workforce.application.StaffBookingAccessService;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test wiring for the booking integration tests: a controllable clock, the deterministic hooks
 * around the booking collaborators, and the recording transaction manager. Every wrapper delegates
 * to the real, transactional implementation unchanged, so the collaborators still join the booking
 * transaction and nothing about the production behavior is replaced.
 */
@TestConfiguration
public class BookingHookConfiguration {
    public static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");

    @Bean
    @Primary
    MutableTestClock bookingTestClock() {
        return new MutableTestClock(NOW);
    }

    @Bean
    BookingTestHooks bookingTestHooks() {
        return new BookingTestHooks();
    }

    /** Replaces Boot's identical manager, which backs off when a transaction manager exists. */
    @Bean(name = "transactionManager")
    RecordingJpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new RecordingJpaTransactionManager(entityManagerFactory);
    }

    @Bean
    @Primary
    StaffBookingAccess hookedStaffBookingAccess(StaffBookingAccessService delegate, BookingTestHooks hooks) {
        return (businessId, serviceId, requestedStaffMemberId) -> {
            hooks.fire(Point.BEFORE_STAFF_LOCK);
            return delegate.lockEligibleForBooking(businessId, serviceId, requestedStaffMemberId);
        };
    }

    @Bean
    @Primary
    ScheduleRevisionGuard hookedScheduleRevisionGuard(ScheduleRevisionService delegate, BookingTestHooks hooks) {
        return businessId -> {
            hooks.fire(Point.BEFORE_GUARD);
            if (hooks.scheduleGuardBypassed()) {
                return -1L;
            }
            return delegate.lockShared(businessId);
        };
    }

    @Bean
    @Primary
    ServiceBookingAccess hookedServiceBookingAccess(ServiceBookingAccessService delegate, BookingTestHooks hooks) {
        return (businessId, serviceId) -> {
            hooks.fire(Point.BEFORE_SERVICE_LOCK);
            return delegate.lockForBooking(businessId, serviceId);
        };
    }

    @Bean
    @Primary
    AvailabilityQuery hookedAvailabilityQuery(AvailabilityQueryService delegate, BookingTestHooks hooks) {
        return (businessId, serviceId, staffMemberIdOrNull) -> {
            hooks.fire(Point.BEFORE_AVAILABILITY);
            return delegate.calculate(businessId, serviceId, staffMemberIdOrNull);
        };
    }

    @Bean
    @Primary
    CustomerIdentification hookedCustomerIdentification(
            CustomerIdentificationService delegate, BookingTestHooks hooks) {
        return (businessId, identity) -> {
            hooks.fire(Point.BEFORE_CUSTOMER);
            CustomerMatchOutcome outcome = delegate.findOrCreate(businessId, identity);
            hooks.fire(Point.AFTER_CUSTOMER);
            return outcome;
        };
    }
}
