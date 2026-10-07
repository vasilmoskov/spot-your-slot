package bg.spotyourslot.publicbooking.web;

import bg.spotyourslot.booking.PublicBookingRateLimiter;
import bg.spotyourslot.booking.PublicBookingRateLimiter.Admission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Charges the per-address budgets of one public request before the handler, so an invalid or
 * malformed body is charged exactly like a valid one and a rejected request never reaches the
 * body parser, the orchestration, or the database. The address is the servlet remote address; no
 * forwarded header is read here (the container decides, and the production default is none).
 */
final class PublicBookingRateLimitInterceptor implements HandlerInterceptor {
    private final BiFunction<String, String, Admission> admission;

    private PublicBookingRateLimitInterceptor(BiFunction<String, String, Admission> admission) {
        this.admission = admission;
    }

    static PublicBookingRateLimitInterceptor forBooking(PublicBookingRateLimiter limiter) {
        return new PublicBookingRateLimitInterceptor(limiter::admitBookingRequest);
    }

    static PublicBookingRateLimitInterceptor forReads(PublicBookingRateLimiter limiter) {
        return new PublicBookingRateLimitInterceptor(limiter::admitAvailabilityRequest);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String slug = variables instanceof Map<?, ?> map && map.get("slug") instanceof String text
                ? text
                : null;
        Admission decision = admission.apply(request.getRemoteAddr(), slug);
        if (!decision.allowed()) {
            throw new PublicRequestRateLimited(decision.retryAfterSeconds());
        }
        return true;
    }
}
