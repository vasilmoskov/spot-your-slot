package bg.spotyourslot.scheduling.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.catalog.ServiceAvailabilityAccess;
import bg.spotyourslot.scheduling.BusyIntervalSource;
import bg.spotyourslot.scheduling.application.AvailabilityQueryService;
import bg.spotyourslot.workforce.StaffAvailabilityAccess;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The busy-interval source is a required ordinary bean, never an optional or
 * defaulted one. Exactly one implementation must exist: none or two fail
 * startup, so booked time can never be silently ignored (ADR-0016). The
 * Booking-owned implementation is wired by the booking module's own tests.
 */
class BusyIntervalSourceFailFastTests {
    @Test
    void exactlyOneImplementationStartsTheOrchestrator() {
        try (AnnotationConfigApplicationContext context = context()) {
            context.registerBean(
                    "busySource",
                    BusyIntervalSource.class,
                    BusyIntervalSourceFailFastTests::noBusyTime);
            context.refresh();

            assertThat(context.getBeansOfType(BusyIntervalSource.class)).hasSize(1);
            assertThat(context.getBean(AvailabilityQueryService.class)).isNotNull();
        }
    }

    @Test
    void twoImplementationsFailStartup() {
        try (AnnotationConfigApplicationContext context = context()) {
            context.registerBean(
                    "firstBusySource",
                    BusyIntervalSource.class,
                    BusyIntervalSourceFailFastTests::noBusyTime);
            context.registerBean(
                    "secondBusySource",
                    BusyIntervalSource.class,
                    BusyIntervalSourceFailFastTests::noBusyTime);

            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class);
        }
    }

    @Test
    void noImplementationAtAllAlsoFailsStartup() {
        try (AnnotationConfigApplicationContext context = context()) {
            assertThatThrownBy(context::refresh).isInstanceOf(RuntimeException.class);
        }
    }

    private static BusyIntervalSource noBusyTime() {
        return (businessId, staffMemberIds, from, to) -> Map.of();
    }

    private static AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(
                BusinessScheduleContextAccess.class,
                () -> mock(BusinessScheduleContextAccess.class));
        context.registerBean(
                ServiceAvailabilityAccess.class, () -> mock(ServiceAvailabilityAccess.class));
        context.registerBean(
                StaffAvailabilityAccess.class, () -> mock(StaffAvailabilityAccess.class));
        context.registerBean(ScheduleExceptionStore.class, () -> mock(ScheduleExceptionStore.class));
        context.registerBean(Clock.class, Clock::systemUTC);
        context.register(AvailabilityQueryService.class);
        return context;
    }
}
