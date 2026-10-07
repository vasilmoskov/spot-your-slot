package bg.spotyourslot.publicbooking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The scope, default, and stream behavior of the booking body bound (ADR-0026). */
class BookingBodyLimitFilterTests {
    private static boolean skipped(BookingBodyLimitFilter filter, String method, String path) {
        return filter.shouldNotFilter(new MockHttpServletRequest(method, path));
    }

    @Test
    void theDefaultIsSixteenKibibytes() {
        assertThat(BookingBodyLimitFilter.DEFAULT_MAX_BYTES).isEqualTo(16 * 1024);
    }

    @Test
    void aNonPositiveLimitIsRejected() {
        assertThatThrownBy(() -> new BookingBodyLimitFilter(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BookingBodyLimitFilter(-5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyThePostOfTheExactBookingsRouteIsBounded() {
        BookingBodyLimitFilter filter = new BookingBodyLimitFilter(10);

        assertThat(skipped(filter, "POST", "/api/public/businesses/studio-a/bookings")).isFalse();
        for (String method : List.of("GET", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")) {
            assertThat(skipped(filter, method, "/api/public/businesses/studio-a/bookings")).as(method).isTrue();
        }
        for (String path : List.of(
                "/api/public/businesses/studio-a",
                "/api/public/businesses/studio-a/bookings/",
                "/api/public/businesses/studio-a/bookings/extra",
                "/api/public/businesses/a/b/bookings",
                "/api/public/businesses/studio-a/services/x/availability",
                "/api/business/services",
                "/api/auth/login")) {
            assertThat(skipped(filter, "POST", path)).as(path).isTrue();
        }
    }

    /** A request that, like a chunked one, declares no length at all. */
    private static MockHttpServletRequest chunked(byte[] content) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/public/businesses/s/bookings") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(content);
        return request;
    }

    @Test
    void anUndeclaredLengthIsCountedAndTheStreamStopsAtTheFirstByteOverTheLimit() throws Exception {
        BookingBodyLimitFilter filter = new BookingBodyLimitFilter(8);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(chunked("123456789-and-much-more".getBytes()), new MockHttpServletResponse(), chain);

        var stream = chain.getRequest().getInputStream();
        byte[] buffer = new byte[64];
        assertThat(stream.read(buffer, 0, 8)).isEqualTo(8);
        // One more byte is the first byte over the limit: a large read asks for at most that one byte.
        assertThatThrownBy(() -> stream.read(buffer, 0, 64)).isInstanceOf(IOException.class)
                .hasMessage("Public booking request body is too large").hasNoCause();
    }

    @Test
    void aDeclaredLengthAboveTheLimitFailsOnTheFirstReadBeforeAnyByteIsConsumed() throws Exception {
        BookingBodyLimitFilter filter = new BookingBodyLimitFilter(8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/public/businesses/s/bookings");
        request.setContent("123456789-and-much-more".getBytes());
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        var stream = chain.getRequest().getInputStream();
        assertThatThrownBy(() -> stream.read(new byte[4], 0, 4)).isInstanceOf(RequestBodyTooLarge.class);
        assertThat(request.getInputStream().available()).isEqualTo(23);
    }

    @Test
    void aBodyOfExactlyTheLimitIsReadCompletelyEvenByteByByte() throws Exception {
        BookingBodyLimitFilter filter = new BookingBodyLimitFilter(5);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/public/businesses/s/bookings");
        request.setContent("12345".getBytes());
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(new String(chain.getRequest().getInputStream().readAllBytes())).isEqualTo("12345");
    }
}
