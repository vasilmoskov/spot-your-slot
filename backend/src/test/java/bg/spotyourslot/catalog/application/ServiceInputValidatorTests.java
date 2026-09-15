package bg.spotyourslot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.catalog.ServiceApplicationException.InputField;
import bg.spotyourslot.catalog.ServiceApplicationException.InvalidInput;
import bg.spotyourslot.catalog.ServiceRecords.CreateServiceCommand;
import bg.spotyourslot.catalog.ServiceRecords.ServiceDetails;
import bg.spotyourslot.catalog.ServiceRecords.ServicePage;
import bg.spotyourslot.catalog.ServiceRecords.ServiceVersionCommand;
import bg.spotyourslot.catalog.ServiceRecords.UpdateServiceCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ServiceInputValidatorTests {
    private final ServiceInputValidator validator = new ServiceInputValidator();

    @Test
    void canonicalizesBeforeValidatingCreateAndUpdateCommands() {
        var create = new CreateServiceCommand(
                "\u2003Мъжко\u00A0\u00A0подстригване\n",
                "\u3000Първи  ред\n\tВтори ред\u2009",
                60,
                new BigDecimal("25.50"));

        assertThat(validator.validateCreate(create))
                .isEqualTo(new CreateServiceCommand(
                        "Мъжко подстригване",
                        "Първи  ред\n\tВтори ред",
                        60,
                        new BigDecimal("25.50")));

        var update = new UpdateServiceCommand(
                " \uFB03 e\u0301 ", "\u2003\n", 1, BigDecimal.ZERO, 0L);
        assertThat(validator.validateUpdate(update))
                .isEqualTo(new UpdateServiceCommand("ffi é", null, 1, BigDecimal.ZERO, 0L));
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void rejectsNamesThatAreInvalidAfterCanonicalization(String name) {
        assertInvalid(InputField.NAME, () -> validator.validateCreate(
                new CreateServiceCommand(name, null, 30, BigDecimal.ONE)));
    }

    @Test
    void countsCanonicalNameBoundsByUnicodeCodePoint() {
        String twoHundredSupplementaryCharacters = "😀".repeat(200);
        String twoHundredOneSupplementaryCharacters = "😀".repeat(201);

        assertThat(validator.validateCreate(new CreateServiceCommand(
                                twoHundredSupplementaryCharacters,
                                null,
                                30,
                                BigDecimal.ONE))
                        .name())
                .hasSize(400)
                .hasSameSizeAs(twoHundredSupplementaryCharacters);
        assertInvalid(InputField.NAME, () -> validator.validateCreate(
                new CreateServiceCommand(
                        twoHundredOneSupplementaryCharacters,
                        null,
                        30,
                        BigDecimal.ONE)));
    }

    @Test
    void validatesCanonicalDescriptionCodePointBoundariesAndBlankToNull() {
        String twoThousandSupplementaryCharacters = "😀".repeat(2_000);
        String twoThousandOneSupplementaryCharacters = "😀".repeat(2_001);

        assertThat(validator.validateCreate(new CreateServiceCommand(
                                "Услуга",
                                twoThousandSupplementaryCharacters,
                                30,
                                BigDecimal.ONE))
                        .description())
                .hasSize(4_000);
        assertThat(validator.validateCreate(new CreateServiceCommand(
                                "Услуга", "\u2003\n\u3000", 30, BigDecimal.ONE))
                        .description())
                .isNull();
        assertInvalid(InputField.DESCRIPTION, () -> validator.validateCreate(
                new CreateServiceCommand(
                        "Услуга",
                        twoThousandOneSupplementaryCharacters,
                        30,
                        BigDecimal.ONE)));
    }

    @ParameterizedTest
    @MethodSource("validDurations")
    void acceptsDurationBoundaries(int duration) {
        assertThat(validator.validateCreate(command(duration, new BigDecimal("1.00")))
                        .durationMinutes())
                .isEqualTo(duration);
    }

    @ParameterizedTest
    @MethodSource("invalidDurations")
    void rejectsMissingOrOutOfRangeDurations(Integer duration) {
        assertInvalid(InputField.DURATION_MINUTES, () -> validator.validateCreate(
                command(duration, new BigDecimal("1.00"))));
    }

    @ParameterizedTest
    @MethodSource("validPrices")
    void acceptsApprovedPrices(BigDecimal price) {
        assertThat(validator.validateCreate(command(30, price)).price()).isSameAs(price);
    }

    @ParameterizedTest
    @MethodSource("invalidPrices")
    void rejectsInvalidPricesWithoutRounding(BigDecimal price) {
        assertInvalid(InputField.PRICE, () -> validator.validateCreate(command(30, price)));
    }

    @Test
    void validatesIdentifiersVersionsAndPagination() {
        UUID id = UUID.randomUUID();

        assertThat(validator.validateBusinessId(id)).isEqualTo(id);
        assertThat(validator.validateServiceId(id)).isEqualTo(id);
        assertThat(validator.validateVersion(new ServiceVersionCommand(0L))).isZero();
        assertThat(validator.validatePage(0, 1)).isEqualTo(new ServiceInputValidator.PageInput(0, 1));
        assertThat(validator.validatePage(4, 100)).isEqualTo(new ServiceInputValidator.PageInput(4, 100));

        assertInvalid(InputField.BUSINESS_ID, () -> validator.validateBusinessId(null));
        assertInvalid(InputField.SERVICE_ID, () -> validator.validateServiceId(null));
        assertInvalid(InputField.EXPECTED_VERSION,
                () -> validator.validateVersion(new ServiceVersionCommand(null)));
        assertInvalid(InputField.EXPECTED_VERSION,
                () -> validator.validateVersion(new ServiceVersionCommand(-1L)));
        assertInvalid(InputField.PAGE, () -> validator.validatePage(-1, 50));
        assertInvalid(InputField.SIZE, () -> validator.validatePage(0, 0));
        assertInvalid(InputField.SIZE, () -> validator.validatePage(0, 101));
    }

    @Test
    void rejectsNullCommandsWithoutRetainingInput() {
        assertInvalid(InputField.COMMAND, () -> validator.validateCreate(null));
        assertInvalid(InputField.COMMAND, () -> validator.validateUpdate(null));
        assertInvalid(InputField.COMMAND, () -> validator.validateVersion(null));

        InvalidInput failure = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidInput.class,
                () -> validator.validateCreate(new CreateServiceCommand(
                        "", "private description", 30, BigDecimal.ONE)));
        assertThat(failure.getMessage()).isEqualTo("Service input is invalid");
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage()).doesNotContain("private description");
    }

    @Test
    void servicePageDefensivelyCopiesItsContents() {
        var mutable = new ArrayList<ServiceDetails>();
        mutable.add(details());

        ServicePage page = new ServicePage(mutable, 0, 50, 1);
        mutable.clear();

        assertThat(page.services()).containsExactly(details());
        assertThatThrownBy(() -> page.services().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static CreateServiceCommand command(Integer duration, BigDecimal price) {
        return new CreateServiceCommand("Услуга", null, duration, price);
    }

    private static void assertInvalid(InputField field, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(InvalidInput.class,
                        failure -> assertThat(failure.field()).isEqualTo(field));
    }

    private static ServiceDetails details() {
        Instant timestamp = Instant.parse("2026-09-15T10:00:00Z");
        return new ServiceDetails(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Услуга",
                null,
                30,
                BigDecimal.TEN,
                true,
                0,
                timestamp,
                timestamp);
    }

    private static Stream<String> invalidNames() {
        return Stream.of(null, "", "\u2003\n\u3000", "a".repeat(201), "😀".repeat(201));
    }

    private static Stream<Integer> validDurations() {
        return Stream.of(1, 480);
    }

    private static Stream<Integer> invalidDurations() {
        return Stream.of(null, 0, -1, 481);
    }

    private static Stream<Arguments> validPrices() {
        return Stream.of(
                Arguments.of(BigDecimal.ZERO),
                Arguments.of(new BigDecimal("0E+10")),
                Arguments.of(new BigDecimal("0E+100")),
                Arguments.of(new BigDecimal("0.01")),
                Arguments.of(new BigDecimal("9999999999.99")),
                Arguments.of(new BigDecimal("1E+9")));
    }

    private static Stream<Arguments> invalidPrices() {
        return Stream.of(
                Arguments.of((BigDecimal) null),
                Arguments.of(new BigDecimal("-0.01")),
                Arguments.of(new BigDecimal("0.000")),
                Arguments.of(new BigDecimal("12.345")),
                Arguments.of(new BigDecimal("12.340")),
                Arguments.of(new BigDecimal("9999999999.991")),
                Arguments.of(new BigDecimal("10000000000")),
                Arguments.of(new BigDecimal("10000000000.00")),
                Arguments.of(new BigDecimal("1E+10")));
    }
}
