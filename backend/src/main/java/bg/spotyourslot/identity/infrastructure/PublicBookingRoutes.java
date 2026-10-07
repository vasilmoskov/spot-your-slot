package bg.spotyourslot.identity.infrastructure;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The exact unauthenticated, session-independent public booking routes (ADR-0026) and the one
 * state-changing route among them. One definition feeds the authorization rules, the CSRF
 * exemption, and the session filter's bypass, so the three can never disagree.
 *
 * <p>Every matcher is a method plus a full path with one single-segment variable per placeholder;
 * none is a prefix or a wildcard, so a deeper path, another verb, or a sibling route never matches.
 */
public final class PublicBookingRoutes {
    public static final String PROFILE = "/api/public/businesses/{slug}";
    public static final String BOOKING_OPTIONS =
            "/api/public/businesses/{slug}/services/{serviceId}/booking-options";
    public static final String AVAILABILITY =
            "/api/public/businesses/{slug}/services/{serviceId}/availability";
    public static final String BOOKINGS = "/api/public/businesses/{slug}/bookings";

    /** The only public mutation, and therefore the only CSRF exemption. */
    public static final RequestMatcher BOOKING_MUTATION = route(HttpMethod.POST, BOOKINGS);

    /** Every route that must ignore the session cookie entirely. */
    public static final RequestMatcher SESSION_INDEPENDENT = new OrRequestMatcher(
            route(HttpMethod.GET, PROFILE),
            route(HttpMethod.GET, BOOKING_OPTIONS),
            route(HttpMethod.GET, AVAILABILITY),
            BOOKING_MUTATION);

    private PublicBookingRoutes() {
    }

    private static RequestMatcher route(HttpMethod method, String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(method, pattern);
    }
}
