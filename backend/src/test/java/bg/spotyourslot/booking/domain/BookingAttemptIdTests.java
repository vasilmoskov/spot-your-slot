package bg.spotyourslot.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BookingAttemptIdTests {
    private static final String VALID = "0f8fad5b-d9cb-469f-a165-70867728950e";

    @Test
    void aCanonicalLowercaseVersionFourUuidIsAccepted() {
        assertThat(BookingAttemptId.parse(VALID)).isPresent();
        for (int index = 0; index < 50; index++) {
            assertThat(BookingAttemptId.parse(UUID.randomUUID().toString())).isPresent();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "0F8FAD5B-D9CB-469F-A165-70867728950E",
        "0f8fad5bd9cb469fa16570867728950e",
        "{0f8fad5b-d9cb-469f-a165-70867728950e}",
        " 0f8fad5b-d9cb-469f-a165-70867728950e",
        "0f8fad5b-d9cb-469f-a165-70867728950e ",
        "0f8fad5b-d9cb-469f-a165-70867728950e\n",
        "0f8fad5b-d9cb-169f-a165-70867728950e",
        "0f8fad5b-d9cb-569f-a165-70867728950e",
        "0f8fad5b-d9cb-469f-c165-70867728950e",
        "0f8fad5b-d9cb-469f-0165-70867728950e",
        "0f8fad5b-d9cb-469f-a165-70867728950",
        "0f8fad5b-d9cb-469f-a165-70867728950ef",
        "0f8fad5b-d9cb-469f-a165-7086772895gz",
        "00000000-0000-0000-0000-000000000000"
    })
    void anythingButTheCanonicalVersionFourFormIsRejected(String text) {
        assertThat(BookingAttemptId.parse(text)).isEmpty();
    }

    @Test
    void theStoredHashIsTheSha256OfTheCanonicalTextAndMatchesAnIndependentVector() {
        byte[] hash = BookingAttemptId.parse(VALID).orElseThrow().hash();

        assertThat(HexFormat.of().formatHex(hash))
                .isEqualTo("c812e1edb64417d6090dcfbaf16c21cd8e8665c04396e1edb472fecfb2797c6a");
        assertThat(hash).hasSize(32);
    }

    @Test
    void theHashIsFreshOnEveryCallSoACallerCannotMutateTheIdentity() {
        BookingAttemptId id = BookingAttemptId.parse(VALID).orElseThrow();
        byte[] first = id.hash();
        first[0] ^= 0x7F;

        assertThat(id.hash()).isNotEqualTo(first);
    }

    @Test
    void equalIdentifiersAreEqualAndTheTextIsNeverPrinted() {
        BookingAttemptId first = BookingAttemptId.parse(VALID).orElseThrow();
        BookingAttemptId second = BookingAttemptId.parse(VALID).orElseThrow();

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first.toString()).doesNotContain(VALID).contains("redacted");
    }
}
