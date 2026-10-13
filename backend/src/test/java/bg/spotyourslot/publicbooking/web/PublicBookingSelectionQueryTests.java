package bg.spotyourslot.publicbooking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The canonical read-route selection: repeated {@code serviceId}, one to five distinct canonical UUIDs. */
class PublicBookingSelectionQueryTests {
    private static final String A = "00000000-0000-4000-8000-000000000001";
    private static final String B = "7fffffff-ffff-4fff-bfff-ffffffffffff";

    private static InvalidPublicRequest rejected(String... values) {
        return org.junit.jupiter.api.Assertions.assertThrows(
                InvalidPublicRequest.class, () -> PublicBookingSelectionQuery.parse(values));
    }

    @Test
    void oneToFiveDistinctCanonicalIdentifiersAreAcceptedInTheOrderGiven() {
        assertThat(PublicBookingSelectionQuery.parse(new String[] {A}))
                .containsExactly(UUID.fromString(A));
        assertThat(PublicBookingSelectionQuery.parse(new String[] {B, A}))
                .isEqualTo(List.of(UUID.fromString(B), UUID.fromString(A)));
        assertThat(PublicBookingSelectionQuery.parse(new String[] {
            A, B, "80000000-0000-4000-8000-000000000000", "ffffffff-ffff-4fff-bfff-ffffffffffff",
            "22222222-2222-4222-8222-222222222222"})).hasSize(5);
    }

    @Test
    void aMissingEmptyOrOversizedSelectionIsTheServicesError() {
        assertThat(rejected((String[]) null).isServices()).isTrue();
        assertThat(rejected().isServices()).isTrue();
        assertThat(rejected(A, B, "80000000-0000-4000-8000-000000000000",
                "ffffffff-ffff-4fff-bfff-ffffffffffff", "22222222-2222-4222-8222-222222222222",
                "33333333-3333-4333-8333-333333333333").isServices()).isTrue();
    }

    @Test
    void duplicatesMalformedAndNonCanonicalValuesAreTheServicesError() {
        for (String[] values : new String[][] {
            {A, A},
            {A, A.toUpperCase()},
            {""},
            {" " + A},
            {A + " "},
            {A + "," + B},
            {"1-1-1-1-1"},
            {"not-a-uuid"},
            {"00000000-0000-4000-8000-00000000000g"},
            {"+0000000-0000-4000-8000-000000000001"},
            {"0000000-00000-4000-8000-000000000001"},
            {"{00000000-0000-4000-8000-00000000001}"},
            {null},
            {A, null}}) {
            assertThat(rejected(values).isServices()).as(String.join("|", java.util.Arrays.toString(values))).isTrue();
        }
    }

    @Test
    void theFailureEchoesNoValue() {
        assertThatThrownBy(() -> PublicBookingSelectionQuery.parse(new String[] {"SECRET-VALUE"}))
                .hasMessageNotContaining("SECRET");
    }
}
