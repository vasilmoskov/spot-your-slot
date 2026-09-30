package bg.spotyourslot.business.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReservedBusinessSlugsTests {
    /**
     * The approved list of ADR-0018. The frontend mirror is pinned to the same values in
     * {@code reservedSlugs.test.ts}; change all three together.
     */
    private static final Set<String> APPROVED = Set.of(
            "forgot-password",
            "password-reset",
            "invitation",
            "login",
            "logout",
            "profile",
            "platform",
            "business",
            "api",
            "actuator",
            "assets",
            "admin",
            "b",
            "book",
            "booking",
            "cancel",
            "cancellation",
            "confirmation",
            "appointments");

    @Test
    void containsExactlyTheApprovedValues() {
        assertThat(ReservedBusinessSlugs.values()).containsExactlyInAnyOrderElementsOf(APPROVED);
        assertThat(ReservedBusinessSlugs.values()).hasSize(19);
    }

    @Test
    void everyApprovedValueIsReserved() {
        for (String value : APPROVED) {
            assertThat(ReservedBusinessSlugs.isReserved(new BusinessSlug(value)))
                    .as(value)
                    .isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOGIN", "  Booking  ", "Forgot-Password", "API", "B"})
    void matchesTheCanonicalLowercaseSlug(String submitted) {
        assertThat(ReservedBusinessSlugs.isReserved(new BusinessSlug(submitted))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "booking-studio",
                "my-book",
                "appointments-bg",
                "salon-invitation",
                "login-2",
                "ba",
                "a",
                "apis",
                "admin1",
                "business-1"
            })
    void doesNotReserveNearMisses(String slug) {
        assertThat(ReservedBusinessSlugs.isReserved(new BusinessSlug(slug))).isFalse();
    }

    @Test
    void exposesAnUnmodifiableSet() {
        var values = ReservedBusinessSlugs.values();

        assertThatThrownBy(() -> values.add("new-root"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
