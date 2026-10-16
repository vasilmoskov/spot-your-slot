package bg.spotyourslot.calendar.web;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentAdministrationException;
import bg.spotyourslot.booking.AppointmentAdministrationException.AccessDenied;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessNotActive;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessSuspended;
import bg.spotyourslot.booking.AppointmentAdministrationException.CustomerNotFound;
import bg.spotyourslot.booking.AppointmentAdministrationException.Failure;
import bg.spotyourslot.booking.AppointmentAdministrationException.InvalidInput;
import bg.spotyourslot.booking.AppointmentAdministrationException.OutcomeUncertain;
import bg.spotyourslot.booking.AppointmentAdministrationException.ReviewChanged;
import bg.spotyourslot.booking.AppointmentAdministrationException.ServiceUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.SlotUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.StaffUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.TemporarilyUnavailable;
import bg.spotyourslot.booking.BookingOrchestrationFailure;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * The exact mapping of every Appointment creation failure to its status, code, Bulgarian message,
 * and fixed problem instance. The 503 results cannot be produced deterministically over HTTP, so
 * the mapping is proven here; their orchestration is proven in the service tests.
 */
class BusinessAppointmentExceptionHandlerTests {
    private final BusinessAppointmentExceptionHandler handler = new BusinessAppointmentExceptionHandler();

    private ProblemDetail handle(Class<? extends Throwable> type) throws Exception {
        for (Method method : BusinessAppointmentExceptionHandler.class.getDeclaredMethods()) {
            ExceptionHandler annotation = method.getAnnotation(ExceptionHandler.class);
            if (annotation != null && List.of(annotation.value()).contains(type)) {
                method.setAccessible(true);
                return (ProblemDetail) method.invoke(handler);
            }
        }
        throw new AssertionError("no handler for " + type);
    }

    private record Expected(Class<? extends Throwable> type, int status, String code, String detail) {
    }

    @Test
    void everyFailureHasItsExactStatusCodeMessageAndTheFixedInstance() throws Exception {
        List<Expected> expected = new ArrayList<>(List.of(
                new Expected(InvalidInput.class, 400, "VALIDATION_ERROR", "Проверете въведените данни."),
                new Expected(AccessDenied.class, 403, "ACCESS_DENIED", "Нямате достъп до тази операция."),
                new Expected(BusinessNotActive.class, 409, "BUSINESS_NOT_ACTIVE",
                        "Бизнесът не е активен. Часове могат да се създават само в активен бизнес."),
                new Expected(BusinessSuspended.class, 409, "BUSINESS_SUSPENDED",
                        "Спрян бизнес може само да преглежда данните си."),
                new Expected(CustomerNotFound.class, 404, "CUSTOMER_NOT_FOUND", "Клиентът не е намерен."),
                new Expected(ServiceUnavailable.class, 409, "APPOINTMENT_SERVICE_UNAVAILABLE",
                        "Някоя от избраните услуги вече не е налична."),
                new Expected(StaffUnavailable.class, 409, "APPOINTMENT_STAFF_UNAVAILABLE",
                        "Избраният член на екипа не е наличен за тези услуги."),
                new Expected(ReviewChanged.class, 409, "APPOINTMENT_REVIEW_CHANGED",
                        "Услугите са променени. Прегледайте ги отново и опитайте пак."),
                new Expected(SlotUnavailable.class, 409, "APPOINTMENT_SLOT_UNAVAILABLE",
                        "Избраният час вече не е свободен."),
                new Expected(TemporarilyUnavailable.class, 503, "APPOINTMENT_TEMPORARILY_UNAVAILABLE",
                        "Часът не беше запазен. Опитайте отново след малко."),
                new Expected(OutcomeUncertain.class, 503, "APPOINTMENT_OUTCOME_UNCERTAIN",
                        "Не е ясно дали часът е запазен. Проверете графика, преди да опитате отново."),
                new Expected(Failure.class, 500, "INTERNAL_ERROR", "Възникна неочаквана грешка."),
                new Expected(BookingOrchestrationFailure.class, 500, "INTERNAL_ERROR", "Възникна неочаквана грешка."),
                new Expected(HttpMediaTypeNotSupportedException.class, 415, "UNSUPPORTED_MEDIA_TYPE",
                        "Заявката не може да бъде изпълнена.")));

        for (Expected current : expected) {
            ProblemDetail problem = handle(current.type());
            assertThat(problem.getStatus()).as(current.type().getSimpleName()).isEqualTo(current.status());
            assertThat(problem.getProperties()).containsEntry("code", current.code());
            assertThat(problem.getDetail()).isEqualTo(current.detail());
            assertThat(problem.getTitle()).isEqualTo("Заявката не може да бъде изпълнена.");
            assertThat(problem.getInstance()).hasToString("/api/business/appointments");
        }
    }

    @Test
    void theOrchestrationGuardMessageIsNeverExposedByTheProblem() throws Exception {
        ProblemDetail problem = handle(BookingOrchestrationFailure.class);
        String message = new BookingOrchestrationFailure().getMessage();

        assertThat(message).isEqualTo("Booking orchestration requires no active caller transaction");
        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(problem.getDetail()).isEqualTo("Възникна неочаквана грешка.");
        assertThat(String.valueOf(problem)).doesNotContain(message).doesNotContain("orchestration");
    }

    @Test
    void everyPermittedExceptionTypeIsMapped() throws Exception {
        for (Class<?> type : AppointmentAdministrationException.class.getPermittedSubclasses()) {
            @SuppressWarnings("unchecked")
            Class<? extends Throwable> throwable = (Class<? extends Throwable>) type;
            assertThat(handle(throwable)).as(type.getSimpleName()).isNotNull();
        }
    }
}
