package bg.spotyourslot.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The exact route set of the public booking surface (ADR-0026). One definition feeds the
 * authorization rules, the one CSRF exemption, and the session filter's bypass, so these tests pin
 * the whole set: nothing outside it matches, and only the booking POST is a mutation.
 */
class PublicBookingRoutesTests {
    private static final String SLUG = "studio-a";
    private static final String SERVICE = UUID.randomUUID().toString();

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }

    private static boolean sessionIndependent(String method, String path) {
        return PublicBookingRoutes.SESSION_INDEPENDENT.matches(request(method, path));
    }

    private static boolean mutation(String method, String path) {
        return PublicBookingRoutes.BOOKING_MUTATION.matches(request(method, path));
    }

    @Test
    void exactlyFourRoutesWithTheirOneVerbAreSessionIndependent() {
        assertThat(sessionIndependent("GET", "/api/public/businesses/" + SLUG)).isTrue();
        assertThat(sessionIndependent("GET",
                "/api/public/businesses/" + SLUG + "/services/" + SERVICE + "/booking-options")).isTrue();
        assertThat(sessionIndependent("GET",
                "/api/public/businesses/" + SLUG + "/services/" + SERVICE + "/availability")).isTrue();
        assertThat(sessionIndependent("POST", "/api/public/businesses/" + SLUG + "/bookings")).isTrue();
    }

    @Test
    void onlyTheBookingPostIsTheMutationAndTheCsrfExemption() {
        assertThat(mutation("POST", "/api/public/businesses/" + SLUG + "/bookings")).isTrue();

        for (String method : List.of("GET", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")) {
            assertThat(mutation(method, "/api/public/businesses/" + SLUG + "/bookings"))
                    .as(method).isFalse();
        }
        for (String path : List.of(
                "/api/public/businesses/" + SLUG,
                "/api/public/businesses/" + SLUG + "/services/" + SERVICE + "/availability",
                "/api/public/businesses/" + SLUG + "/services/" + SERVICE + "/booking-options")) {
            assertThat(mutation("POST", path)).as(path).isFalse();
        }
    }

    @Test
    void noOtherVerbPathOrDeeperRouteMatchesAnything() {
        List<String> paths = List.of(
                "/api/public/businesses",
                "/api/public/businesses/",
                "/api/public/businesses/" + SLUG + "/",
                "/api/public/businesses/" + SLUG + "/bookings/",
                "/api/public/businesses/" + SLUG + "/bookings/ABCDEFGHJK",
                "/api/public/businesses/" + SLUG + "/bookings/extra/deeper",
                "/api/public/businesses/" + SLUG + "/services",
                "/api/public/businesses/" + SLUG + "/services/" + SERVICE,
                "/api/public/businesses/" + SLUG + "/services/" + SERVICE + "/availability/extra",
                "/api/public/businesses/" + SLUG + "/services/" + SERVICE + "/booking-options/",
                "/api/public/businesses/" + SLUG + "/services/" + SERVICE + "/slots",
                "/api/public/businesses/" + SLUG + "/staff",
                "/api/public/businesses/" + SLUG + "/appointments",
                "/api/public/businesses/a/b/bookings",
                "/api/public/other",
                "/api/public",
                "/api/auth/login",
                "/api/auth/csrf",
                "/api/business/services",
                "/api/platform/businesses",
                "/api/dev/mailbox",
                "/actuator/health");

        for (String path : paths) {
            for (String method : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")) {
                assertThat(sessionIndependent(method, path)).as("%s %s", method, path).isFalse();
            }
        }
    }

    @Test
    void theOtherVerbsOfTheDocumentedPathsAreNotSessionIndependent() {
        List<String> documented = List.of(
                "/api/public/businesses/" + SLUG,
                "/api/public/businesses/" + SLUG + "/services/" + SERVICE + "/booking-options",
                "/api/public/businesses/" + SLUG + "/services/" + SERVICE + "/availability",
                "/api/public/businesses/" + SLUG + "/bookings");
        for (String path : documented) {
            int matching = 0;
            for (String method : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")) {
                if (sessionIndependent(method, path)) {
                    matching++;
                }
            }
            assertThat(matching).as(path).isEqualTo(1);
        }
    }

    @Test
    void theRouteConstantsHoldNoWildcard() {
        for (String pattern : List.of(
                PublicBookingRoutes.PROFILE,
                PublicBookingRoutes.BOOKING_OPTIONS,
                PublicBookingRoutes.AVAILABILITY,
                PublicBookingRoutes.BOOKINGS)) {
            assertThat(pattern).doesNotContain("*").startsWith("/api/public/businesses/{slug}");
        }
    }
}
