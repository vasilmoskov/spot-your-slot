package bg.spotyourslot.business.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.business.BusinessAdministration;
import bg.spotyourslot.business.BusinessApplicationException;
import bg.spotyourslot.business.BusinessApplicationException.InputField;
import bg.spotyourslot.business.BusinessApplicationException.InvalidInput;
import bg.spotyourslot.business.BusinessRecords;
import bg.spotyourslot.business.BusinessRecords.BusinessPage;
import bg.spotyourslot.business.BusinessRecords.BusinessSummary;
import bg.spotyourslot.business.BusinessRecords.CreateBusinessCommand;
import bg.spotyourslot.business.BusinessRecords.UpdateBusinessCommand;
import bg.spotyourslot.business.domain.BusinessStatus;
import bg.spotyourslot.business.domain.BusinessType;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BusinessInputValidatorTests {
    private static final ValidatorFactory VALIDATOR_FACTORY =
            Validation.buildDefaultValidatorFactory();
    private static final BusinessInputValidator VALIDATOR =
            new BusinessInputValidator(VALIDATOR_FACTORY.getValidator());

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void normalizesAValidCreateCommand() {
        var normalized = VALIDATOR.validateCreate(new CreateBusinessCommand(
                "  MY-BUSINESS  ",
                "  Моят бизнес  ",
                BusinessType.BEAUTY_STUDIO,
                null,
                "  Описание  ",
                "  София  ",
                "  1000  ",
                "  Примерна  ",
                "  1  ",
                "  вход А  ",
                "  +359 2 000 0000  ",
                "  OFFICE@EXAMPLE.INVALID  "));

        assertThat(normalized)
                .isEqualTo(new CreateBusinessCommand(
                        "my-business",
                        "Моят бизнес",
                        BusinessType.BEAUTY_STUDIO,
                        "Europe/Sofia",
                        "Описание",
                        "София",
                        "1000",
                        "Примерна",
                        "1",
                        "вход А",
                        "+359 2 000 0000",
                        "office@example.invalid"));
    }

    @Test
    void normalizesAValidUpdateCommandWithoutReplacingVersion() {
        var normalized = VALIDATOR.validateUpdate(new UpdateBusinessCommand(
                "  UPDATED-BUSINESS  ",
                "  Обновен бизнес  ",
                BusinessType.MASSAGE_STUDIO,
                "Europe/London",
                null,
                "  Пловдив  ",
                null,
                "  Главна  ",
                "  2  ",
                "  етаж 1  ",
                null,
                "  CONTACT@EXAMPLE.INVALID  ",
                7));

        assertThat(normalized.slug()).isEqualTo("updated-business");
        assertThat(normalized.displayName()).isEqualTo("Обновен бизнес");
        assertThat(normalized.timezone()).isEqualTo("Europe/London");
        assertThat(normalized.city()).isEqualTo("Пловдив");
        assertThat(normalized.street()).isEqualTo("Главна");
        assertThat(normalized.streetNumber()).isEqualTo("2");
        assertThat(normalized.addressDetails()).isEqualTo("етаж 1");
        assertThat(normalized.contactEmail()).isEqualTo("contact@example.invalid");
        assertThat(normalized.expectedVersion()).isEqualTo(7);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "business slug", "business--slug", "бизнес"})
    void rejectsInvalidSlugsWithoutRepairingThem(String slug) {
        assertInvalid(InputField.SLUG, () -> VALIDATOR.validateCreate(createWithSlug(slug)));
    }

    @Test
    void acceptsDisplayNameAtExactCodePointLimitIncludingNonBmpText() {
        String displayName = codePointText(BusinessInputValidator.DISPLAY_NAME_MAX_LENGTH);

        assertThat(VALIDATOR.validateCreate(createWithDisplayName(displayName)).displayName())
                .isEqualTo(displayName);
        assertThat(displayName.codePointCount(0, displayName.length())).isEqualTo(200);
        assertThat(displayName.length()).isGreaterThan(200);
    }

    @Test
    void rejectsDisplayNameOneCodePointOverLimit() {
        String displayName = codePointText(BusinessInputValidator.DISPLAY_NAME_MAX_LENGTH + 1);

        assertInvalid(
                InputField.DISPLAY_NAME,
                () -> VALIDATOR.validateCreate(createWithDisplayName(displayName)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t", "\n", " \t\n "})
    void rejectsMissingOrBlankDisplayNames(String displayName) {
        assertInvalid(
                InputField.DISPLAY_NAME,
                () -> VALIDATOR.validateCreate(createWithDisplayName(displayName)));
    }

    @Test
    void requiresBusinessType() {
        var command = new CreateBusinessCommand(
                "valid-business",
                "Valid Business",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertInvalid(InputField.BUSINESS_TYPE, () -> VALIDATOR.validateCreate(command));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Europe/Sofia", "Europe/London", "America/New_York", "Etc/UTC"})
    void acceptsNamedIanaTimezones(String timezone) {
        assertThat(VALIDATOR.validateCreate(createWithTimezone(timezone)).timezone())
                .isEqualTo(timezone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " Europe/Sofia ", "+02:00", "UTC+02:00", "Unknown/Zone"})
    void rejectsInvalidOrNonNamedTimezones(String timezone) {
        assertInvalid(
                InputField.TIMEZONE,
                () -> VALIDATOR.validateCreate(createWithTimezone(timezone)));
    }

    @Test
    void requiresExplicitTimezoneForUpdate() {
        assertInvalid(
                InputField.TIMEZONE,
                () -> VALIDATOR.validateUpdate(updateWithTimezone(null)));
    }

    @ParameterizedTest
    @MethodSource("blankOptionalValues")
    void normalizesBlankOptionalValuesToNull(OptionalField field, String value) {
        CreateBusinessCommand normalized =
                VALIDATOR.validateCreate(createWithOptionalField(field, value));

        assertThat(optionalFieldValue(normalized, field)).isNull();
    }

    @ParameterizedTest
    @MethodSource("optionalFieldBoundaries")
    void acceptsOptionalTextAtExactCodePointLimit(OptionalField field, int maximumLength) {
        String value = codePointText(maximumLength);

        CreateBusinessCommand normalized =
                VALIDATOR.validateCreate(createWithOptionalField(field, value));

        assertThat(optionalFieldValue(normalized, field)).isEqualTo(value);
        assertThat(value.codePointCount(0, value.length())).isEqualTo(maximumLength);
        assertThat(value.length()).isGreaterThan(maximumLength);
    }

    @ParameterizedTest
    @MethodSource("optionalFieldBoundaries")
    void rejectsOptionalTextOneCodePointOverLimit(OptionalField field, int maximumLength) {
        String value = codePointText(maximumLength + 1);

        assertInvalid(
                field.inputField(),
                () -> VALIDATOR.validateCreate(createWithOptionalField(field, value)));
    }

    @Test
    void normalizesContactEmailBeforeValidatingIt() {
        String fullWidthEmail = "  ＵＳＥＲ＠ＥＸＡＭＰＬＥ．ＣＯＭ  ";

        assertThat(VALIDATOR.validateCreate(createWithContactEmail(fullWidthEmail)).contactEmail())
                .isEqualTo("user@example.com");
    }

    @Test
    void acceptsContactEmailAtExactCodePointLimit() {
        String email = emailWithLength(BusinessInputValidator.CONTACT_EMAIL_MAX_LENGTH);

        assertThat(VALIDATOR.validateCreate(createWithContactEmail(email)).contactEmail())
                .isEqualTo(email);
        assertThat(email.codePointCount(0, email.length())).isEqualTo(320);
    }

    @Test
    void rejectsContactEmailOneCodePointOverLimit() {
        String email = emailWithLength(BusinessInputValidator.CONTACT_EMAIL_MAX_LENGTH) + "a";

        assertInvalid(
                InputField.CONTACT_EMAIL,
                () -> VALIDATOR.validateCreate(createWithContactEmail(email)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "person@", "@example.com"})
    void rejectsInvalidContactEmail(String email) {
        assertInvalid(
                InputField.CONTACT_EMAIL,
                () -> VALIDATOR.validateCreate(createWithContactEmail(email)));
    }

    @Test
    void invalidInputContainsOnlySafeStructuredContext() {
        String rawValue = "RAW-SECRET-VALUE";

        assertThatThrownBy(() -> VALIDATOR.validateCreate(createWithContactEmail(rawValue)))
                .isInstanceOfSatisfying(InvalidInput.class, exception -> {
                    assertThat(exception.field()).isEqualTo(InputField.CONTACT_EMAIL);
                    assertThat(exception.getMessage()).isEqualTo("Business input is invalid");
                    assertThat(exception.toString()).doesNotContain(rawValue);
                });
    }

    @Test
    void validatesBusinessIdentifiersAndExpectedVersions() {
        UUID businessId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThat(VALIDATOR.validateBusinessId(businessId)).isEqualTo(businessId);
        assertThat(VALIDATOR.validateExpectedVersion(0)).isZero();
        assertInvalid(InputField.BUSINESS_ID, () -> VALIDATOR.validateBusinessId(null));
        assertInvalid(InputField.EXPECTED_VERSION, () -> VALIDATOR.validateExpectedVersion(-1));
    }

    @ParameterizedTest
    @MethodSource("validPages")
    void acceptsPaginationBoundaries(int page, int size) {
        assertThat(VALIDATOR.validatePage(page, size))
                .isEqualTo(new BusinessInputValidator.PageInput(page, size));
    }

    @ParameterizedTest
    @MethodSource("invalidPages")
    void rejectsInvalidPagination(int page, int size, InputField field) {
        assertInvalid(field, () -> VALIDATOR.validatePage(page, size));
    }

    @Test
    void rejectsNullApplicationCommandsSafely() {
        assertInvalid(InputField.COMMAND, () -> VALIDATOR.validateCreate(null));
        assertInvalid(InputField.COMMAND, () -> VALIDATOR.validateUpdate(null));
    }

    @Test
    void businessPageDefensivelyCopiesItsBusinessList() {
        var mutable = new ArrayList<BusinessSummary>();
        var page = new BusinessPage(mutable, 0, 50, 0);

        mutable.add(summary());

        assertThat(page.businesses()).isEmpty();
        assertThatThrownBy(() -> page.businesses().add(summary()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void publicContractsExposeExactlySevenOperationsAndNoInfrastructureTypes() {
        assertThat(Stream.of(BusinessAdministration.class.getDeclaredMethods())
                        .map(Method::getName))
                .containsExactlyInAnyOrder(
                        "list",
                        "get",
                        "create",
                        "update",
                        "activateDraft",
                        "suspendActive",
                        "reactivateSuspended");

        Stream.concat(
                        Stream.of(BusinessAdministration.class.getDeclaredMethods())
                                .flatMap(method -> Stream.concat(
                                        Stream.of(method.getGenericReturnType()),
                                        Stream.of(method.getGenericParameterTypes()))),
                        Stream.of(BusinessRecords.class.getDeclaredClasses())
                                .flatMap(type -> Stream.of(type.getRecordComponents())
                                        .flatMap(Stream::of)
                                        .map(component -> component.getGenericType())))
                .map(type -> type.getTypeName())
                .forEach(typeName -> assertThat(typeName).doesNotContain(".infrastructure."));
    }

    @Test
    void businessExceptionsAreDistinctAndMissingActiveOwnerIsNotDefinedHere() {
        assertThat(BusinessApplicationException.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(
                        BusinessApplicationException.BusinessNotFound.class,
                        BusinessApplicationException.BusinessSlugConflict.class,
                        BusinessApplicationException.InvalidLifecycleTransition.class,
                        BusinessApplicationException.ConcurrentUpdate.class,
                        BusinessApplicationException.InvalidInput.class);
        assertThat(Stream.of(BusinessApplicationException.class.getDeclaredClasses())
                        .map(Class::getSimpleName))
                .doesNotContain("MissingActiveOwner");
    }

    private static void assertInvalid(InputField field, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        InvalidInput.class,
                        exception -> assertThat(exception.field()).isEqualTo(field));
    }

    private static CreateBusinessCommand validCreate() {
        return new CreateBusinessCommand(
                "valid-business",
                "Валиден бизнес",
                BusinessType.OTHER,
                "Europe/Sofia",
                "Описание",
                "София",
                "1000",
                "Примерна",
                "1",
                "вход А",
                "+359 2 000 0000",
                "contact@example.invalid");
    }

    private static CreateBusinessCommand createWithSlug(String slug) {
        CreateBusinessCommand command = validCreate();
        return new CreateBusinessCommand(
                slug,
                command.displayName(),
                command.businessType(),
                command.timezone(),
                command.description(),
                command.city(),
                command.postalCode(),
                command.street(),
                command.streetNumber(),
                command.addressDetails(),
                command.phone(),
                command.contactEmail());
    }

    private static CreateBusinessCommand createWithDisplayName(String displayName) {
        CreateBusinessCommand command = validCreate();
        return new CreateBusinessCommand(
                command.slug(),
                displayName,
                command.businessType(),
                command.timezone(),
                command.description(),
                command.city(),
                command.postalCode(),
                command.street(),
                command.streetNumber(),
                command.addressDetails(),
                command.phone(),
                command.contactEmail());
    }

    private static CreateBusinessCommand createWithTimezone(String timezone) {
        CreateBusinessCommand command = validCreate();
        return new CreateBusinessCommand(
                command.slug(),
                command.displayName(),
                command.businessType(),
                timezone,
                command.description(),
                command.city(),
                command.postalCode(),
                command.street(),
                command.streetNumber(),
                command.addressDetails(),
                command.phone(),
                command.contactEmail());
    }

    private static CreateBusinessCommand createWithContactEmail(String contactEmail) {
        return createWithOptionalField(OptionalField.CONTACT_EMAIL, contactEmail);
    }

    private static CreateBusinessCommand createWithOptionalField(
            OptionalField field, String value) {
        CreateBusinessCommand command = validCreate();
        return new CreateBusinessCommand(
                command.slug(),
                command.displayName(),
                command.businessType(),
                command.timezone(),
                field == OptionalField.DESCRIPTION ? value : command.description(),
                field == OptionalField.CITY ? value : command.city(),
                field == OptionalField.POSTAL_CODE ? value : command.postalCode(),
                field == OptionalField.STREET ? value : command.street(),
                field == OptionalField.STREET_NUMBER ? value : command.streetNumber(),
                field == OptionalField.ADDRESS_DETAILS ? value : command.addressDetails(),
                field == OptionalField.PHONE ? value : command.phone(),
                field == OptionalField.CONTACT_EMAIL ? value : command.contactEmail());
    }

    private static String optionalFieldValue(
            CreateBusinessCommand command, OptionalField field) {
        return switch (field) {
            case DESCRIPTION -> command.description();
            case CITY -> command.city();
            case POSTAL_CODE -> command.postalCode();
            case STREET -> command.street();
            case STREET_NUMBER -> command.streetNumber();
            case ADDRESS_DETAILS -> command.addressDetails();
            case PHONE -> command.phone();
            case CONTACT_EMAIL -> command.contactEmail();
        };
    }

    private static UpdateBusinessCommand updateWithTimezone(String timezone) {
        CreateBusinessCommand command = validCreate();
        return new UpdateBusinessCommand(
                command.slug(),
                command.displayName(),
                command.businessType(),
                timezone,
                command.description(),
                command.city(),
                command.postalCode(),
                command.street(),
                command.streetNumber(),
                command.addressDetails(),
                command.phone(),
                command.contactEmail(),
                0);
    }

    private static String codePointText(int length) {
        StringBuilder value = new StringBuilder(length * 2);
        for (int index = 0; index < length; index++) {
            value.appendCodePoint(index % 2 == 0 ? 'Б' : 0x1F600);
        }
        return value.toString();
    }

    private static String emailWithLength(int length) {
        String localPart = "a".repeat(64);
        int domainLength = length - localPart.length() - 1;
        int labelCount = 4;
        int labelCharacters = domainLength - (labelCount - 1);
        int baseLabelLength = labelCharacters / labelCount;
        int remainder = labelCharacters % labelCount;
        var labels = new ArrayList<String>();
        for (int index = 0; index < labelCount; index++) {
            labels.add("a".repeat(baseLabelLength + (index < remainder ? 1 : 0)));
        }
        return localPart + "@" + String.join(".", labels);
    }

    private static BusinessSummary summary() {
        Instant timestamp = Instant.parse("2026-08-14T10:00:00Z");
        return new BusinessSummary(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "business",
                "Business",
                BusinessType.OTHER,
                BusinessStatus.DRAFT,
                "Europe/Sofia",
                0,
                timestamp,
                timestamp);
    }

    private static Stream<Arguments> blankOptionalValues() {
        List<String> values = java.util.Arrays.asList(null, "", "   ", "\t", "\n", " \t\n ");
        return Stream.of(OptionalField.values())
                .flatMap(field -> values.stream().map(value -> Arguments.of(field, value)));
    }

    private static Stream<Arguments> optionalFieldBoundaries() {
        return Stream.of(
                Arguments.of(
                        OptionalField.DESCRIPTION,
                        BusinessInputValidator.DESCRIPTION_MAX_LENGTH),
                Arguments.of(OptionalField.CITY, BusinessInputValidator.CITY_MAX_LENGTH),
                Arguments.of(
                        OptionalField.POSTAL_CODE,
                        BusinessInputValidator.POSTAL_CODE_MAX_LENGTH),
                Arguments.of(OptionalField.STREET, BusinessInputValidator.STREET_MAX_LENGTH),
                Arguments.of(
                        OptionalField.STREET_NUMBER,
                        BusinessInputValidator.STREET_NUMBER_MAX_LENGTH),
                Arguments.of(
                        OptionalField.ADDRESS_DETAILS,
                        BusinessInputValidator.ADDRESS_DETAILS_MAX_LENGTH),
                Arguments.of(OptionalField.PHONE, BusinessInputValidator.PHONE_MAX_LENGTH));
    }

    private static Stream<Arguments> validPages() {
        return Stream.of(Arguments.of(0, 1), Arguments.of(0, 100), Arguments.of(10, 50));
    }

    private static Stream<Arguments> invalidPages() {
        return Stream.of(
                Arguments.of(-1, 50, InputField.PAGE),
                Arguments.of(0, 0, InputField.SIZE),
                Arguments.of(0, -1, InputField.SIZE),
                Arguments.of(0, 101, InputField.SIZE));
    }

    private enum OptionalField {
        DESCRIPTION(InputField.DESCRIPTION),
        CITY(InputField.CITY),
        POSTAL_CODE(InputField.POSTAL_CODE),
        STREET(InputField.STREET),
        STREET_NUMBER(InputField.STREET_NUMBER),
        ADDRESS_DETAILS(InputField.ADDRESS_DETAILS),
        PHONE(InputField.PHONE),
        CONTACT_EMAIL(InputField.CONTACT_EMAIL);

        private final InputField inputField;

        OptionalField(InputField inputField) {
            this.inputField = inputField;
        }

        InputField inputField() {
            return inputField;
        }
    }
}
