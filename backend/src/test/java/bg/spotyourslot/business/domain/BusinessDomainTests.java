package bg.spotyourslot.business.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BusinessDomainTests {
    @ParameterizedTest
    @MethodSource("validSlugs")
    void normalizesAndAcceptsValidSlugs(String input, String expected) {
        assertThat(new BusinessSlug(input).value()).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "",
                "   ",
                "business slug",
                "business_slug",
                "business--slug",
                "-business",
                "business-",
                "бизнес",
                "business.slug",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            })
    void rejectsInvalidSlugs(String input) {
        assertThatThrownBy(() -> new BusinessSlug(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Europe/Sofia", "Europe/London", "America/New_York", "Etc/UTC"})
    void acceptsNamedIanaTimezones(String zoneId) {
        var timezone = new BusinessTimezone(zoneId);

        assertThat(timezone.toZoneId()).isEqualTo(ZoneId.of(zoneId));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "Europe/Not_A_Zone", "+02:00", "UTC+02:00", " Europe/Sofia "})
    void rejectsValuesThatAreNotNamedIanaTimezones(String zoneId) {
        assertThatThrownBy(() -> new BusinessTimezone(zoneId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsToEuropeSofia() {
        assertThat(BusinessTimezone.defaultTimezone().value()).isEqualTo("Europe/Sofia");
    }

    @ParameterizedTest
    @MethodSource("allowedTransitions")
    void allowsApprovedLifecycleTransitions(BusinessStatus current, BusinessStatus target) {
        assertThat(current.canTransitionTo(target)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("rejectedTransitions")
    void rejectsEveryOtherLifecycleTransition(BusinessStatus current, BusinessStatus target) {
        assertThat(current.canTransitionTo(target)).isFalse();
    }

    private static Stream<Arguments> validSlugs() {
        return Stream.of(
                Arguments.of("business", "business"),
                Arguments.of("business-42", "business-42"),
                Arguments.of("  My-Business-42  ", "my-business-42"),
                Arguments.of("a", "a"));
    }

    private static Stream<Arguments> allowedTransitions() {
        return Stream.of(
                Arguments.of(BusinessStatus.DRAFT, BusinessStatus.ACTIVE),
                Arguments.of(BusinessStatus.ACTIVE, BusinessStatus.SUSPENDED),
                Arguments.of(BusinessStatus.SUSPENDED, BusinessStatus.ACTIVE));
    }

    private static Stream<Arguments> rejectedTransitions() {
        return Stream.of(BusinessStatus.values())
                .flatMap(current -> Stream.of(BusinessStatus.values())
                        .filter(target -> !isAllowed(current, target))
                        .map(target -> Arguments.of(current, target)));
    }

    private static boolean isAllowed(BusinessStatus current, BusinessStatus target) {
        return (current == BusinessStatus.DRAFT && target == BusinessStatus.ACTIVE)
                || (current == BusinessStatus.ACTIVE && target == BusinessStatus.SUSPENDED)
                || (current == BusinessStatus.SUSPENDED && target == BusinessStatus.ACTIVE);
    }
}
