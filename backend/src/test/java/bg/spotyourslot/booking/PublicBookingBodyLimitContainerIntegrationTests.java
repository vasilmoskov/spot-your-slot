package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * The booking body bound against a REAL embedded Tomcat on a real socket: a chunked body (no
 * {@code Content-Length}), a body larger than a declared length, and a declared length that is far
 * larger than the body. This is the container evidence that MockMvc cannot give; the exact byte
 * boundary and the 413 contract are in {@code PublicBookingBodyLimitApiIntegrationTests}.
 *
 * <p>The socket reads carry a safety timeout so a defect fails the test instead of hanging; it is never
 * a way to wait for a result.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spotyourslot.booking.max-request-body-bytes=2048")
class PublicBookingBodyLimitContainerIntegrationTests extends PublicBookingApiIntegrationTest {
    private static final int LIMIT = 2048;

    @LocalServerPort int port;

    private HttpResponse<String> chunked(String slug, byte[] content) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + bookingsUrl(slug)))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(content)))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private byte[] padded(Req request, int bytes) {
        byte[] json = body(request).toString().getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[bytes];
        System.arraycopy(json, 0, padded, 0, json.length);
        java.util.Arrays.fill(padded, json.length, bytes, (byte) ' ');
        return padded;
    }

    /**
     * Sends raw HTTP with an explicit Content-Length header and returns the status line and headers
     * (the response body may be chunked and is checked in the HttpClient tests).
     */
    private String raw(String slug, long declared, byte[] content) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST " + bookingsUrl(slug) + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: application/json\r\nConnection: close\r\n"
                    + "Content-Length: " + declared + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(content);
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            // The server may keep the connection open to swallow the declared body, so stop at the end
            // of the headers instead of waiting for EOF.
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line).append('\n');
                if (line.isEmpty()) {
                    break;
                }
            }
            return response.toString();
        }
    }

    @Test
    void aChunkedBodyWithinTheLimitIsBookedOverARealConnection() throws Exception {
        Req request = req().name("Мария Йорданова").note("бележка с кирилица");

        HttpResponse<String> response = chunked(request.slug, padded(request, 1500));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void anOversizedChunkedBodyWithNoContentLengthIsRefusedAs413() throws Exception {
        Req request = req();

        HttpResponse<String> response = chunked(request.slug, padded(request, LIMIT + 1));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(response.body()).contains("REQUEST_TOO_LARGE").contains("\"instance\":\"/api/public/businesses\"")
                .doesNotContain(request.slug);
        assertThat(totalAppointments()).isZero();
        assertThat(totalCustomers()).isZero();
    }

    @Test
    void aChunkedBodyOfExactlyTheLimitIsAccepted() throws Exception {
        Req request = req();

        assertThat(chunked(request.slug, padded(request, LIMIT)).statusCode()).isEqualTo(201);
    }

    @Test
    void aDeclaredLengthFarAboveTheBodyIsRefusedWithoutWaitingForTheBytes() throws Exception {
        Req request = req();
        byte[] small = body(request).toString().getBytes(StandardCharsets.UTF_8);

        String response = raw(request.slug, 50_000_000L, small);

        assertThat(response).startsWith("HTTP/1.1 413");
        assertThat(response).containsIgnoringCase("Content-Type: application/problem+json");
        assertThat(totalAppointments()).isZero();
    }

    @Test
    void aDeclaredLengthBelowTheBodyIsTruncatedByTheContainerSoTheApplicationNeverSeesMore() throws Exception {
        Req request = req();

        // Tomcat delivers only the declared 10 bytes to the application: an unreadable JSON prefix.
        String response = raw(request.slug, 10, padded(request, LIMIT * 4));

        assertThat(response).startsWith("HTTP/1.1 400");
        assertThat(response).containsIgnoringCase("Content-Type: application/problem+json");
        assertThat(totalAppointments()).isZero();
    }
}
