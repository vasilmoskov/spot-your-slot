package bg.spotyourslot.publicbooking.web;

import bg.spotyourslot.publicbooking.application.PublicBookingUnavailable;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps every failure of the public booking routes to a sanitized RFC 7807 body with a fixed
 * {@code instance}. It also handles the generic Spring failures, so the shared handler (which
 * leaves the request path in {@code instance}) is never used for these routes. Nothing here reads
 * the exception message: a message could hold a submitted value.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = PublicBookingController.class)
public class PublicBookingExceptionHandler {
    @ExceptionHandler(PublicBookingUnavailable.Business.class)
    ProblemDetail businessUnavailable() {
        return PublicProblems.businessUnavailable();
    }

    @ExceptionHandler(PublicBookingUnavailable.Service.class)
    ProblemDetail serviceUnavailable() {
        return PublicProblems.serviceUnavailable();
    }

    @ExceptionHandler(PublicBookingUnavailable.Staff.class)
    ProblemDetail staffUnavailable() {
        return PublicProblems.staffUnavailable();
    }

    @ExceptionHandler({
        InvalidPublicRequest.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        ConstraintViolationException.class,
        HandlerMethodValidationException.class
    })
    ProblemDetail invalid() {
        return PublicProblems.validation(Map.of());
    }

    /** An unreadable body is a validation error, unless the bounded stream stopped it for its size. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof RequestBodyTooLarge) {
                return PublicProblems.requestTooLarge();
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return PublicProblems.validation(Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail unsupportedMediaType() {
        return PublicProblems.unsupportedMediaType();
    }

    @ExceptionHandler(PublicRequestRateLimited.class)
    ResponseEntity<ProblemDetail> rateLimited(PublicRequestRateLimited exception) {
        return ResponseEntity.status(429)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(PublicProblems.rateLimited());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected() {
        return PublicProblems.internal();
    }
}
