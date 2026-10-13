package bg.spotyourslot.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The frozen version 1 layout (kept for historical replays), proven against hexadecimal vectors produced by an independent
 * implementation of the documented layout (so a change of either side is detected), and the
 * unambiguity of the encoding.
 */
class FingerprintEncodingTests {
    private static final UUID BUSINESS = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SERVICE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID STAFF = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String ATTEMPT = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final Instant START = Instant.ofEpochSecond(1_790_000_000L);

    private static final String FULL_VECTOR =
            "53595342465000010100000010111111111111411181111111111111110200000010222222222222422282222222222222220300000001000400000008000000006ab13b800500000015d098d0b2d0b0d0bd20d09fd0b5d182d180d0bed0b2060000000e012b3335393838383132333435360700000011016976616e406578616d706c652e636f6d080000001601d09fd18ad180d0b2d0b020d181d180d0b5d189d0b0";
    private static final String MINIMAL_VECTOR =
            "5359534246500001010000001011111111111141118111111111111111020000001022222222222242228222222222222222030000001101333333333333433383333333333333330400000008000000006ab13b800500000003416e61060000000e012b333539383838313233343536070000000100080000000100";

    /** The historical layout stays registered for ever; it is addressed directly, not as the current one. */
    private static final FingerprintEncoding V1 = FingerprintEncodingV1.INSTANCE;

    private static NormalizedBookingRequest request(
            UUID staff, Instant start, String name, String phone, String email, String note) {
        return NormalizedBookingRequest.normalize(ATTEMPT, SERVICE, staff, start, name, phone, email, note);
    }

    private static NormalizedBookingRequest base() {
        return request(null, START, "Иван Петров", "+359888123456", "ivan@example.com", "Първа среща");
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    @Test
    void versionOneStaysRegisteredAlongsideTheCurrentVersionTwoAndNothingElseIsKnown() {
        assertThat(FingerprintEncodings.CURRENT.version()).isEqualTo(2);
        assertThat(FingerprintEncodings.forVersion(1)).contains(V1);
        assertThat(FingerprintEncodings.forVersion(2)).contains(FingerprintEncodings.CURRENT);
        assertThat(FingerprintEncodings.forVersion(0)).isEmpty();
        assertThat(FingerprintEncodings.forVersion(3)).isEmpty();
        assertThat(FingerprintEncodings.forVersion(Short.MAX_VALUE)).isEmpty();
    }

    @Test
    void versionOneDescribesOnlyOneServiceWithoutReviewedTerms() {
        UUID other = UUID.fromString("55555555-5555-4555-8555-555555555555");
        NormalizedBookingRequest several = NormalizedBookingRequest.normalize(
                ATTEMPT,
                java.util.List.of(new RequestedService(SERVICE, null), new RequestedService(other, null)),
                null, START, "Ана", "+359888123456", null, null);
        NormalizedBookingRequest reviewed = NormalizedBookingRequest.normalize(
                ATTEMPT,
                java.util.List.of(new RequestedService(SERVICE, new RequestedService.ReviewedTerms(30, 1000))),
                null, START, "Ана", "+359888123456", null, null);

        assertThat(V1.supports(base())).isTrue();
        assertThat(V1.supports(several)).isFalse();
        assertThat(V1.supports(reviewed)).isFalse();
        assertThat(FingerprintEncodings.CURRENT.supports(several)).isTrue();
        assertThat(FingerprintEncodings.CURRENT.supports(reviewed)).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> V1.encode(BUSINESS, several))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFullRequestMatchesTheIndependentGoldenVector() {
        assertThat(hex(V1.encode(BUSINESS, base()))).isEqualTo(FULL_VECTOR);
    }

    @Test
    void aSpecificPreferenceAndAbsentOptionalFieldsMatchTheIndependentGoldenVector() {
        NormalizedBookingRequest minimal = request(STAFF, START, "Ana", "+359888123456", null, null);

        assertThat(hex(V1.encode(BUSINESS, minimal))).isEqualTo(MINIMAL_VECTOR);
    }

    @Test
    void theAttemptIdentifierIsNotPartOfTheEncoding() {
        NormalizedBookingRequest other = NormalizedBookingRequest.normalize(
                "9b2e6f4c-1d3a-4c5b-8e7f-0a1b2c3d4e5f", SERVICE, null, START,
                "Иван Петров", "+359888123456", "ivan@example.com", "Първа среща");

        assertThat(V1.encode(BUSINESS, other))
                .isEqualTo(V1.encode(BUSINESS, base()));
    }

    @Test
    void normalizedEquivalentRequestsEncodeIdentically() {
        NormalizedBookingRequest messy = request(
                null, START, "  Иван   Петров ", "0888 123 456", " IVAN@EXAMPLE.COM ", "\r\n Първа среща \r\n");

        assertThat(V1.encode(BUSINESS, messy))
                .isEqualTo(V1.encode(BUSINESS, base()));
    }

    record Variation(String field, Function<NormalizedBookingRequest, byte[]> encoded) {
        @Override
        public String toString() {
            return field;
        }
    }

    static java.util.stream.Stream<Variation> changedFields() {
        UUID otherBusiness = UUID.fromString("44444444-4444-4444-8444-444444444444");
        return java.util.stream.Stream.of(
                new Variation("business", unused -> V1.encode(otherBusiness, base())),
                new Variation("service", unused -> V1.encode(BUSINESS,
                        NormalizedBookingRequest.normalize(ATTEMPT, STAFF, null, START,
                                "Иван Петров", "+359888123456", "ivan@example.com", "Първа среща"))),
                new Variation("specific preference", unused -> V1.encode(BUSINESS,
                        request(STAFF, START, "Иван Петров", "+359888123456", "ivan@example.com", "Първа среща"))),
                new Variation("start by one second", unused -> V1.encode(BUSINESS,
                        request(null, START.plusSeconds(1), "Иван Петров", "+359888123456",
                                "ivan@example.com", "Първа среща"))),
                new Variation("name", unused -> V1.encode(BUSINESS,
                        request(null, START, "Иван Петрова", "+359888123456", "ivan@example.com", "Първа среща"))),
                new Variation("phone", unused -> V1.encode(BUSINESS,
                        request(null, START, "Иван Петров", "+359888123457", "ivan@example.com", "Първа среща"))),
                new Variation("phone removed", unused -> V1.encode(BUSINESS,
                        request(null, START, "Иван Петров", null, "ivan@example.com", "Първа среща"))),
                new Variation("email", unused -> V1.encode(BUSINESS,
                        request(null, START, "Иван Петров", "+359888123456", "ivan2@example.com", "Първа среща"))),
                new Variation("email removed", unused -> V1.encode(BUSINESS,
                        request(null, START, "Иван Петров", "+359888123456", null, "Първа среща"))),
                new Variation("note", unused -> V1.encode(BUSINESS,
                        request(null, START, "Иван Петров", "+359888123456", "ivan@example.com", "Втора среща"))),
                new Variation("note removed", unused -> V1.encode(BUSINESS,
                        request(null, START, "Иван Петров", "+359888123456", "ivan@example.com", null))));
    }

    @ParameterizedTest
    @MethodSource("changedFields")
    void changingAnySingleFieldChangesTheEncoding(Variation variation) {
        assertThat(variation.encoded().apply(base()))
                .isNotEqualTo(V1.encode(BUSINESS, base()));
    }

    @Test
    void aNoPreferenceRequestDiffersFromEverySpecificPreferenceSoTheAssignedMemberNeverSubstitutes() {
        byte[] anyone = V1.encode(BUSINESS, request(
                null, START, "Ана", "+359888123456", null, null));
        byte[] assignedLater = V1.encode(BUSINESS, request(
                STAFF, START, "Ана", "+359888123456", null, null));

        assertThat(anyone).isNotEqualTo(assignedLater);
    }

    @Test
    void anAbsentOptionalFieldDiffersFromAPresentEmptyAndValuesCannotShiftIntoNeighbours() {
        // The note cannot be present and empty (it normalizes to absent), so compare the shapes
        // that the length prefix and presence marker must keep apart.
        byte[] noEmailWithNote = V1.encode(BUSINESS, request(
                null, START, "Ана", "+359888123456", null, "x@y.z"));
        byte[] emailWithoutNote = V1.encode(BUSINESS, request(
                null, START, "Ана", "+359888123456", "x@y.z", null));
        byte[] splitName = V1.encode(BUSINESS, request(
                null, START, "Ана Б", "+359888123456", null, null));
        byte[] shiftedName = V1.encode(BUSINESS, request(
                null, START, "Ана", "+359888123456", null, "Б"));

        assertThat(noEmailWithNote).isNotEqualTo(emailWithoutNote);
        assertThat(splitName).isNotEqualTo(shiftedName);
    }

    @Test
    void theEncodingIsDeterministicAndStartsWithTheMagicAndVersion() {
        byte[] first = V1.encode(BUSINESS, base());

        assertThat(V1.encode(BUSINESS, base())).isEqualTo(first);
        assertThat(hex(first)).startsWith("535953424650" + "0001");
    }
}
