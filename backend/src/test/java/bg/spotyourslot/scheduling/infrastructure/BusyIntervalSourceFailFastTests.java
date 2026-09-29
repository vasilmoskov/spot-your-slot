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
 * The placeholder is a required ordinary bean, never a silent fallback. When a
 * real implementation appears, startup must fail until the placeholder is
 * deleted (ADR-0016).
 */
class BusyIntervalSourceFailFastTests {
    @Test
    void exactlyOnePlaceholderStartsTheOrchestrator() {
        try (AnnotationConfigApplicationContext context = context()) {
            context.register(NoBookingBusyIntervalSource.class);
            context.refresh();

            assertThat(context.getBeansOfType(BusyIntervalSource.class)).hasSize(1);
            assertThat(context.getBean(AvailabilityQueryService.class)).isNotNull();
        }
    }

    @Test
    void aRealImplementationAlongsideThePlaceholderFailsStartup() {
        try (AnnotationConfigApplicationContext context = context()) {
            context.register(NoBookingBusyIntervalSource.class);
            context.registerBean(
                    "futureBookingBusyIntervalSource",
                    BusyIntervalSource.class,
                    () -> (businessId, staffMemberIds, from, to) -> Map.of());

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
