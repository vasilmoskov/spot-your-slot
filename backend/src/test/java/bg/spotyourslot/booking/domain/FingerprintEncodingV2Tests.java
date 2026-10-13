package bg.spotyourslot.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.domain.RequestedService.ReviewedTerms;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The frozen version 2 layout (ADR-0027 section 5). Every hexadecimal vector below was produced by an
 * independent Python implementation of the documented layout (sorting by the 16 raw bytes), not by this
 * code, so a change of either side is detected. The identifier-order tests use identifiers across the
 * high-bit boundary, where Java's signed {@link UUID#compareTo} and the unsigned order disagree.
 */
class FingerprintEncodingV2Tests {
    private static final FingerprintEncoding V2 = FingerprintEncodings.CURRENT;
    private static final UUID BUSINESS = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SERVICE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID STAFF = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String ATTEMPT = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final Instant START = Instant.ofEpochSecond(1_790_000_000L);

    /** The four boundary identifiers of ADR-0027: unsigned order A, B, C, D; Java order C, D, A, B. */
    private static final UUID A = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID B = UUID.fromString("7fffffff-ffff-4fff-bfff-ffffffffffff");
    private static final UUID C = UUID.fromString("80000000-0000-4000-8000-000000000000");
    private static final UUID D = UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff");

    private static final String CUSTOMER_TAIL =
            "0300000001000400000008000000006ab13b800500000015d098d0b2d0b0d0bd20d09fd0b5d182d180d0bed0b2060000000e012b3335393838383132333435360700000011016976616e406578616d706c652e636f6d080000001601d09fd18ad180d0b2d0b020d181d180d0b5d189d0b0";
    private static final String HEADER = "535953424650" + "0002" + "0100000010" + "11111111111141118111111111111111";

    private static final String LEGACY_FULL =
            "535953424650000201000000101111111111114111811111111111111102000000120100222222222222422282222222222222220300000001000400000008000000006ab13b800500000015d098d0b2d0b0d0bd20d09fd0b5d182d180d0bed0b2060000000e012b3335393838383132333435360700000011016976616e406578616d706c652e636f6d080000001601d09fd18ad180d0b2d0b020d181d180d0b5d189d0b0";
    private static final String LEGACY_MINIMAL =
            "53595342465000020100000010111111111111411181111111111111110200000012010022222222222242228222222222222222030000001101333333333333433383333333333333330400000008000000006ab13b800500000003416e61060000000e012b333539383838313233343536070000000100080000000100";
    private static final String REVIEWED_ONE =
            "5359534246500002010000001011111111111141118111111111111111020000001e0101222222222222422282222222222222220000002d0000000000000dac0300000001000400000008000000006ab13b800500000015d098d0b2d0b0d0bd20d09fd0b5d182d180d0bed0b2060000000e012b3335393838383132333435360700000011016976616e406578616d706c652e636f6d080000001601d09fd18ad180d0b2d0b020d181d180d0b5d189d0b0";
    private static final String REVIEWED_TWO_BC =
            "5359534246500002010000001011111111111141118111111111111111020000003a02017fffffffffff4fffbfffffffffffffff0000002d00000000000009f6800000000000400080000000000000000000003c00000000000000000300000001000400000008000000006ab13b800500000015d098d0b2d0b0d0bd20d09fd0b5d182d180d0bed0b2060000000e012b3335393838383132333435360700000011016976616e406578616d706c652e636f6d080000001601d09fd18ad180d0b2d0b020d181d180d0b5d189d0b0";
    private static final String BOUNDARY_FOUR_UNSIGNED =
            "535953424650000201000000101111111111114111811111111111111102000000720401000000000000400080000000000000010000001e00000000000005dc7fffffffffff4fffbfffffffffffffff0000002d00000000000009f6800000000000400080000000000000000000003c0000000000000000ffffffffffff4fffbfffffffffffffff0000005a000000e8d4a50fff0300000001000400000008000000006ab13b800500000015d098d0b2d0b0d0bd20d09fd0b5d182d180d0bed0b2060000000e012b3335393838383132333435360700000011016976616e406578616d706c652e636f6d080000001601d09fd18ad180d0b2d0b020d181d180d0b5d189d0b0";
    /** What an encoder using Java's signed order would produce for the same four services (C, D, A, B). */
    private static final String BOUNDARY_FOUR_SIGNED =
            "535953424650000201000000101111111111114111811111111111111102000000720401800000000000400080000000000000000000003c0000000000000000ffffffffffff4fffbfffffffffffffff0000005a000000e8d4a50fff000000000000400080000000000000010000001e00000000000005dc7fffffffffff4fffbfffffffffffffff0000002d00000000000009f60300000001000400000008000000006ab13b800500000015d098d0b2d0b0d0bd20d09fd0b5d182d180d0bed0b2060000000e012b3335393838383132333435360700000011016976616e406578616d706c652e636f6d080000001601d09fd18ad180d0b2d0b020d181d180d0b5d189d0b0";

    private static RequestedService reviewed(UUID id, int minutes, long cents) {
        return new RequestedService(id, new ReviewedTerms(minutes, cents));
    }

    private static NormalizedBookingRequest request(UUID staff, List<RequestedService> services) {
        return NormalizedBookingRequest.normalize(
                ATTEMPT, services, staff, START,
                "Иван Петров", "+359888123456", "ivan@example.com", "Първа среща");
    }

    private static List<RequestedService> four() {
        return List.of(reviewed(A, 30, 1500), reviewed(B, 45, 2550), reviewed(C, 60, 0),
                reviewed(D, 90, 999_999_999_999L));
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    @Test
    void theLegacyShapeOfOneServiceMatchesTheIndependentVectorWithFlagZero() {
        assertThat(hex(V2.encode(BUSINESS, request(null, List.of(new RequestedService(SERVICE, null))))))
                .isEqualTo(LEGACY_FULL);
        NormalizedBookingRequest minimal = NormalizedBookingRequest.normalize(
                ATTEMPT, List.of(new RequestedService(SERVICE, null)), STAFF, START,
                "Ana", "+359888123456", null, null);
        assertThat(hex(V2.encode(BUSINESS, minimal))).isEqualTo(LEGACY_MINIMAL);
    }

    @Test
    void aLegacyServiceIdOverloadEncodesLikeTheListForm() {
        NormalizedBookingRequest viaId = NormalizedBookingRequest.normalize(
                ATTEMPT, SERVICE, null, START,
                "Иван Петров", "+359888123456", "ivan@example.com", "Първа среща");

        assertThat(hex(V2.encode(BUSINESS, viaId))).isEqualTo(LEGACY_FULL);
    }

    @Test
    void oneReviewedServiceMatchesTheIndependentVector() {
        assertThat(hex(V2.encode(BUSINESS, request(null, List.of(reviewed(SERVICE, 45, 3500))))))
                .isEqualTo(REVIEWED_ONE);
    }

    @Test
    void servicesAcrossTheHighBitBoundaryAreEncodedInUnsignedOrder() {
        assertThat(hex(V2.encode(BUSINESS, request(null, four())))).isEqualTo(BOUNDARY_FOUR_UNSIGNED);
        assertThat(hex(V2.encode(BUSINESS, request(null, List.of(reviewed(B, 45, 2550), reviewed(C, 60, 0))))))
                .isEqualTo(REVIEWED_TWO_BC);
        // The bytes a signed-order encoder would write are different, and this code never writes them.
        assertThat(BOUNDARY_FOUR_SIGNED).isNotEqualTo(BOUNDARY_FOUR_UNSIGNED);
        assertThat(hex(V2.encode(BUSINESS, request(null, four())))).isNotEqualTo(BOUNDARY_FOUR_SIGNED);
        List<UUID> javaOrder = new ArrayList<>(List.of(A, B, C, D));
        javaOrder.sort(Comparator.naturalOrder());
        assertThat(javaOrder).containsExactly(C, D, A, B);
        List<UUID> unsigned = new ArrayList<>(List.of(C, D, A, B));
        unsigned.sort(UnsignedUuidOrder.INSTANCE);
        assertThat(unsigned).containsExactly(A, B, C, D);
    }

    @Test
    void everyPermutationOfTheSameSelectionEncodesIdentically() {
        byte[] expected = V2.encode(BUSINESS, request(null, four()));
        List<RequestedService> services = new ArrayList<>(four());
        int checked = 0;
        for (int first = 0; first < 4; first++) {
            for (int second = 0; second < 4; second++) {
                for (int third = 0; third < 4; third++) {
                    for (int fourth = 0; fourth < 4; fourth++) {
                        if (first + second + third + fourth != 6
                                || first == second || first == third || first == fourth
                                || second == third || second == fourth || third == fourth) {
                            continue;
                        }
                        List<RequestedService> permuted = List.of(
                                services.get(first), services.get(second),
                                services.get(third), services.get(fourth));
                        assertThat(V2.encode(BUSINESS, request(null, permuted))).isEqualTo(expected);
                        checked++;
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(24);
        List<RequestedService> reversed = new ArrayList<>(four());
        Collections.reverse(reversed);
        assertThat(V2.encode(BUSINESS, request(null, reversed))).isEqualTo(expected);
    }

    @Test
    void anyChangedReviewedFactOrServiceSetChangesTheEncoding() {
        byte[] base = V2.encode(BUSINESS, request(null, four()));
        List<List<RequestedService>> variations = List.of(
                List.of(reviewed(A, 31, 1500), reviewed(B, 45, 2550), reviewed(C, 60, 0), reviewed(D, 90, 999_999_999_999L)),
                List.of(reviewed(A, 30, 1501), reviewed(B, 45, 2550), reviewed(C, 60, 0), reviewed(D, 90, 999_999_999_999L)),
                List.of(reviewed(A, 30, 1500), reviewed(B, 45, 2550), reviewed(C, 60, 1), reviewed(D, 90, 999_999_999_999L)),
                List.of(reviewed(A, 30, 1500), reviewed(B, 45, 2550), reviewed(C, 60, 0), reviewed(D, 90, 999_999_999_998L)),
                List.of(reviewed(A, 30, 1500), reviewed(B, 45, 2550), reviewed(C, 60, 0)),
                List.of(reviewed(A, 30, 1500), reviewed(B, 45, 2550), reviewed(C, 60, 0), reviewed(SERVICE, 90, 999_999_999_999L)),
                // the same facts attached to a different Service (a swap between two Services)
                List.of(reviewed(A, 45, 2550), reviewed(B, 30, 1500), reviewed(C, 60, 0), reviewed(D, 90, 999_999_999_999L)));
        for (List<RequestedService> variation : variations) {
            assertThat(V2.encode(BUSINESS, request(null, variation))).isNotEqualTo(base);
        }
    }

    @Test
    void theReviewedFlagSeparatesTheTwoShapesAndTheCountIsEncoded() {
        byte[] legacy = V2.encode(BUSINESS, request(null, List.of(new RequestedService(SERVICE, null))));
        byte[] reviewedOne = V2.encode(BUSINESS, request(null, List.of(reviewed(SERVICE, 45, 3500))));
        byte[] legacyTwo = V2.encode(BUSINESS, request(null,
                List.of(new RequestedService(B, null), new RequestedService(C, null))));
        byte[] reviewedTwo = V2.encode(BUSINESS, request(null, List.of(reviewed(B, 45, 2550), reviewed(C, 60, 0))));

        assertThat(legacy).isNotEqualTo(reviewedOne);
        assertThat(legacyTwo).isNotEqualTo(reviewedTwo);
        assertThat(hex(legacyTwo)).startsWith(HEADER + "02" + "00000022" + "0200");
        assertThat(hex(reviewedTwo)).startsWith(HEADER + "02" + "0000003a" + "0201");
    }

    @Test
    void otherIdentityFieldsStillChangeTheEncoding() {
        NormalizedBookingRequest base = request(null, four());
        byte[] expected = V2.encode(BUSINESS, base);
        UUID otherBusiness = UUID.fromString("44444444-4444-4444-8444-444444444444");

        assertThat(V2.encode(otherBusiness, base)).isNotEqualTo(expected);
        assertThat(V2.encode(BUSINESS, request(STAFF, four()))).isNotEqualTo(expected);
        assertThat(V2.encode(BUSINESS, NormalizedBookingRequest.normalize(
                ATTEMPT, four(), null, START.plusSeconds(1),
                "Иван Петров", "+359888123456", "ivan@example.com", "Първа среща"))).isNotEqualTo(expected);
        assertThat(V2.encode(BUSINESS, NormalizedBookingRequest.normalize(
                ATTEMPT, four(), null, START,
                "Иван Петрова", "+359888123456", "ivan@example.com", "Първа среща"))).isNotEqualTo(expected);
        assertThat(V2.encode(BUSINESS, NormalizedBookingRequest.normalize(
                ATTEMPT, four(), null, START,
                "Иван Петров", "+359888123457", "ivan@example.com", "Първа среща"))).isNotEqualTo(expected);
        assertThat(V2.encode(BUSINESS, NormalizedBookingRequest.normalize(
                ATTEMPT, four(), null, START,
                "Иван Петров", "+359888123456", "ivan2@example.com", "Първа среща"))).isNotEqualTo(expected);
        assertThat(V2.encode(BUSINESS, NormalizedBookingRequest.normalize(
                ATTEMPT, four(), null, START,
                "Иван Петров", "+359888123456", "ivan@example.com", "Втора среща"))).isNotEqualTo(expected);
    }

    @Test
    void theAttemptIdentifierIsNotPartOfTheEncoding() {
        NormalizedBookingRequest other = NormalizedBookingRequest.normalize(
                "9b2e6f4c-1d3a-4c5b-8e7f-0a1b2c3d4e5f", four(), null, START,
                "Иван Петров", "+359888123456", "ivan@example.com", "Първа среща");

        assertThat(V2.encode(BUSINESS, other)).isEqualTo(V2.encode(BUSINESS, request(null, four())));
    }

    @Test
    void theBoundaryVectorEndsWithTheUnchangedCustomerFields() {
        assertThat(BOUNDARY_FOUR_UNSIGNED).endsWith(CUSTOMER_TAIL);
        assertThat(LEGACY_FULL).endsWith(CUSTOMER_TAIL);
    }
}
