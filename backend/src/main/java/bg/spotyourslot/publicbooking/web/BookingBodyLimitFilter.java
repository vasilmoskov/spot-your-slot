package bg.spotyourslot.publicbooking.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Bounds the body of exactly {@code POST /api/public/businesses/{slug}/bookings} (ADR-0026). The
 * request is wrapped so that its stream counts the bytes the application <em>actually reads</em> and
 * fails at the first byte past the maximum, so the JSON tree is never built from an oversized body
 * and memory is bounded by the maximum plus one read buffer. The check does not trust
 * {@code Content-Length}: a chunked body or an absent or misleading length is counted like any other,
 * and a declared length above the maximum fails on the first read without reading more.
 *
 * <p>The filter reads nothing itself, so it runs after the per-address limiter has charged the
 * request (the interceptor precedes argument resolution): an oversized request is therefore charged to
 * the address budgets exactly once and never to the contact budget, and it reaches no booking,
 * Customer, or database work. Every other route and verb is untouched.
 */
@Component
public class BookingBodyLimitFilter extends OncePerRequestFilter {
    public static final int DEFAULT_MAX_BYTES = 16 * 1024;

    private static final PathPattern BOOKINGS =
            PathPatternParser.defaultInstance.parse("/api/public/businesses/{slug}/bookings");

    private final long maxBytes;

    public BookingBodyLimitFilter(
            @Value("${spotyourslot.booking.max-request-body-bytes:16384}") long maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("The booking request body limit must be positive");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return !BOOKINGS.matches(PathContainer.parsePath(path));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new BoundedRequest(request, maxBytes), response);
    }

    private static final class BoundedRequest extends HttpServletRequestWrapper {
        private final long maxBytes;
        private ServletInputStream stream;

        private BoundedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new BoundedStream(super.getInputStream(), getContentLengthLong(), maxBytes);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class BoundedStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long declared;
        private final long maxBytes;
        private long total;

        private BoundedStream(ServletInputStream delegate, long declared, long maxBytes) {
            this.delegate = delegate;
            this.declared = declared;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (declared > maxBytes) {
                throw new RequestBodyTooLarge();
            }
            if (length == 0) {
                return 0;
            }
            // Never ask for more than one byte past the limit, so the count is exact.
            int allowed = (int) Math.min(length, maxBytes - total + 1);
            int count = delegate.read(buffer, offset, allowed);
            if (count > 0) {
                total += count;
                if (total > maxBytes) {
                    throw new RequestBodyTooLarge();
                }
            }
            return count;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new IllegalStateException("Asynchronous reads are not supported");
        }
    }
}
