package bg.spotyourslot.publicbooking.web;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Builds every error body of the public booking surface (ADR-0026). The {@code instance} is one
 * fixed value, because Spring would otherwise fill it with the request path, which holds the slug
 * and the Service identifier. No problem carries a submitted value, an identifier, or a diagnostic.
 */
final class PublicProblems {
    /** The fixed, non-sensitive instance of every public problem (as the profile's 404). */
    static final URI INSTANCE = URI.create("/api/public/businesses");

    private static final String TITLE = "Заявката не може да бъде изпълнена.";

    private PublicProblems() {
    }

    static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(TITLE);
        problem.setProperty("code", code);
        problem.setInstance(INSTANCE);
        return problem;
    }

    static ProblemDetail validation(Map<String, String> fieldErrors) {
        var problem = problem(
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Проверете въведените данни.");
        if (!fieldErrors.isEmpty()) {
            problem.setProperty("fieldErrors", fieldErrors);
        }
        return problem;
    }

    static ProblemDetail businessUnavailable() {
        return problem(
                HttpStatus.NOT_FOUND, "BUSINESS_PAGE_UNAVAILABLE", "Страницата не е налична.");
    }

    static ProblemDetail serviceUnavailable() {
        return problem(
                HttpStatus.CONFLICT,
                "BOOKING_SERVICE_UNAVAILABLE",
                "Избраната услуга вече не е налична. Изберете друга услуга.");
    }

    static ProblemDetail staffUnavailable() {
        return problem(
                HttpStatus.CONFLICT,
                "BOOKING_STAFF_UNAVAILABLE",
                "Избраният служител вече не е наличен. Изберете друг или „Без предпочитание“.");
    }

    static ProblemDetail slotUnavailable() {
        return problem(
                HttpStatus.CONFLICT,
                "BOOKING_SLOT_UNAVAILABLE",
                "Избраният час вече не е свободен. Изберете друг час.");
    }

    static ProblemDetail notCompletedOnline() {
        return problem(
                HttpStatus.CONFLICT,
                "BOOKING_NOT_COMPLETED_ONLINE",
                "Не можем да завършим резервацията онлайн. Моля, свържете се с бизнеса.");
    }

    static ProblemDetail attemptMismatch() {
        return problem(
                HttpStatus.CONFLICT,
                "BOOKING_ATTEMPT_MISMATCH",
                "Тази заявка вече е използвана с други данни. Започнете резервацията отново.");
    }

    /** A proven rollback: nothing was committed. */
    static ProblemDetail temporarilyUnavailable() {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "BOOKING_TEMPORARILY_UNAVAILABLE",
                "Резервацията не беше направена. Опитайте отново.");
    }

    /** A commit may have succeeded; the guest must repeat the same attempt. */
    static ProblemDetail outcomeUncertain() {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "BOOKING_OUTCOME_UNCERTAIN",
                "Не получихме потвърждение за резервацията. Опитайте отново.");
    }

    static ProblemDetail rateLimited() {
        return problem(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                "Твърде много опити. Опитайте по-късно.");
    }

    static ProblemDetail unsupportedMediaType() {
        return problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "Заявката не може да бъде изпълнена.");
    }

    static ProblemDetail requestTooLarge() {
        return problem(
                HttpStatus.CONTENT_TOO_LARGE,
                "REQUEST_TOO_LARGE",
                "Заявката е твърде голяма.");
    }

    static ProblemDetail internal() {
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Възникна неочаквана грешка.");
    }
}
