package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.workforce.StaffMemberApplicationException.InputField;
import bg.spotyourslot.workforce.StaffMemberApplicationException.InvalidInput;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberDetails;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberPage;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.UpdateStaffMemberCommand;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class StaffMemberInputValidatorTests {
    private static final ValidatorFactory VALIDATOR_FACTORY =
            Validation.buildDefaultValidatorFactory();
    private static final StaffMemberInputValidator VALIDATOR =
            new StaffMemberInputValidator(VALIDATOR_FACTORY.getValidator());

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void canonicalizesBeforeValidatingCreateAndUpdateCommands() {
        var create = new CreateStaffMemberCommand(
                "\u2003Ａｎｎａ\u00A0\u00A0Иванова\n",
                "\u3000ＴＥＡＭ@ＥＸＡＭＰＬＥ.INVALID\u2009",
                "\u2003＋３５９ (\uFF12) １２３-４５-６７\n");

        assertThat(VALIDATOR.validateCreate(create))
                .isEqualTo(new CreateStaffMemberCommand(
                        "Anna Иванова",
                        "team@example.invalid",
                        "+359 (2) 123-45-67"));

        var update = new UpdateStaffMemberCommand(
                " \uFB03 e\u0301 ", "\u2003\n", "\u3000\t", 0L);
        assertThat(VALIDATOR.validateUpdate(update))
                .isEqualTo(new UpdateStaffMemberCommand("ffi é", null, null, 0L));
    }

    @ParameterizedTest
    @MethodSource("invalidDisplayNames")
    void rejectsDisplayNamesThatAreInvalidAfterCanonicalization(String displayName) {
        assertInvalid(InputField.DISPLAY_NAME, () -> VALIDATOR.validateCreate(
                new CreateStaffMemberCommand(displayName, null, null)));
    }

    @Test
    void countsCanonicalDisplayNameBoundsByUnicodeCodePoint() {
        String atLimit = "😀".repeat(200);

        assertThat(VALIDATOR.validateCreate(
                                new CreateStaffMemberCommand(atLimit, null, null))
                        .displayName())
                .hasSize(400);
        assertInvalid(InputField.DISPLAY_NAME, () -> VALIDATOR.validateCreate(
                new CreateStaffMemberCommand("😀".repeat(201), null, null)));
    }

    @Test
    void validatesCanonicalEmailSyntaxAndCodePointBounds() {
        String domainLabel = "a".repeat(63);
        String atLimit = "a".repeat(64) + "@"
                + String.join(".", domainLabel, domainLabel, domainLabel, domainLabel);
        String overLimit = "a" + atLimit;

        assertThat(atLimit.codePointCount(0, atLimit.length())).isEqualTo(320);
        assertThat(VALIDATOR.validateCreate(
                                new CreateStaffMemberCommand("Екип", atLimit, null))
                        .contactEmail())
                .isEqualTo(atLimit);
        assertInvalid(InputField.CONTACT_EMAIL, () -> VALIDATOR.validateCreate(
                new CreateStaffMemberCommand("Екип", overLimit, null)));
        assertInvalid(InputField.CONTACT_EMAIL, () -> VALIDATOR.validateCreate(
                new CreateStaffMemberCommand("Екип", "not-an-email", null)));
        assertInvalid(InputField.CONTACT_EMAIL, () -> VALIDATOR.validateCreate(
                new CreateStaffMemberCommand("Екип", "a b@example.invalid", null)));
    }

    @Test
    void convertsBlankEmailAndPhoneToNull() {
        CreateStaffMemberCommand validated = VALIDATOR.validateCreate(
                new CreateStaffMemberCommand("Екип", "\u2003\n", "\u3000\t"));

        assertThat(validated.contactEmail()).isNull();
        assertThat(validated.contactPhone()).isNull();
    }

    @ParameterizedTest
    @MethodSource("validPhones")
    void acceptsApprovedFormattedPhones(String phone) {
        assertThat(VALIDATOR.validateCreate(
                                new CreateStaffMemberCommand("Екип", null, phone))
                        .contactPhone())
                .isEqualTo(phone);
    }

    @ParameterizedTest
    @MethodSource("invalidPhones")
    void rejectsInvalidPhoneCharactersAndDigitBounds(String phone) {
        assertInvalid(InputField.CONTACT_PHONE, () -> VALIDATOR.validateCreate(
                new CreateStaffMemberCommand("Екип", null, phone)));
    }

    @Test
    void enforcesCanonicalPhoneCodePointLimit() {
        String atLimit = "1" + " ".repeat(47) + "23";
        String overLimit = "1" + " ".repeat(48) + "23";

        assertThat(atLimit.codePointCount(0, atLimit.length())).isEqualTo(50);
        assertThat(VALIDATOR.validateCreate(
                                new CreateStaffMemberCommand("Екип", null, atLimit))
                        .contactPhone())
                .isEqualTo(atLimit);
        assertInvalid(InputField.CONTACT_PHONE, () -> VALIDATOR.validateCreate(
                new CreateStaffMemberCommand("Екип", null, overLimit)));
    }

    @Test
    void validatesIdentifiersVersionsAndPagination() {
        UUID id = UUID.randomUUID();

        assertThat(VALIDATOR.validateBusinessId(id)).isEqualTo(id);
        assertThat(VALIDATOR.validateStaffMemberId(id)).isEqualTo(id);
        assertThat(VALIDATOR.validateVersion(new StaffMemberVersionCommand(0L))).isZero();
        assertThat(VALIDATOR.validatePage(0, 1))
                .isEqualTo(new StaffMemberInputValidator.PageInput(0, 1));
        assertThat(VALIDATOR.validatePage(4, StaffMemberInputValidator.MAX_PAGE_SIZE))
                .isEqualTo(new StaffMemberInputValidator.PageInput(4, 100));
        assertThat(StaffMemberInputValidator.DEFAULT_PAGE_SIZE).isEqualTo(50);

        assertInvalid(InputField.BUSINESS_ID, () -> VALIDATOR.validateBusinessId(null));
        assertInvalid(InputField.STAFF_MEMBER_ID, () -> VALIDATOR.validateStaffMemberId(null));
        assertInvalid(InputField.EXPECTED_VERSION,
                () -> VALIDATOR.validateVersion(new StaffMemberVersionCommand(null)));
        assertInvalid(InputField.EXPECTED_VERSION,
                () -> VALIDATOR.validateVersion(new StaffMemberVersionCommand(-1L)));
        assertInvalid(InputField.PAGE, () -> VALIDATOR.validatePage(-1, 50));
        assertInvalid(InputField.SIZE, () -> VALIDATOR.validatePage(0, 0));
        assertInvalid(InputField.SIZE, () -> VALIDATOR.validatePage(0, 101));
    }

    @Test
    void rejectsNullCommandsWithoutRetainingInput() {
        assertInvalid(InputField.COMMAND, () -> VALIDATOR.validateCreate(null));
        assertInvalid(InputField.COMMAND, () -> VALIDATOR.validateUpdate(null));
        assertInvalid(InputField.COMMAND, () -> VALIDATOR.validateVersion(null));

        InvalidInput failure = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidInput.class,
                () -> VALIDATOR.validateCreate(new CreateStaffMemberCommand(
                        "", "private@example.invalid", "+359123456")));
        assertThat(failure)
                .hasMessage("StaffMember input is invalid")
                .hasNoCause();
        assertThat(failure.getMessage())
                .doesNotContain("private@example.invalid")
                .doesNotContain("+359123456");
    }

    @Test
    void staffMemberPageDefensivelyCopiesItsContents() {
        var mutable = new ArrayList<StaffMemberDetails>();
        mutable.add(details());

        StaffMemberPage page = new StaffMemberPage(mutable, 0, 50, 1);
        mutable.clear();

        assertThat(page.staffMembers()).containsExactly(details());
        assertThatThrownBy(() -> page.staffMembers().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertInvalid(InputField field, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(InvalidInput.class,
                        failure -> assertThat(failure.field()).isEqualTo(field));
    }

    private static StaffMemberDetails details() {
        Instant timestamp = Instant.parse("2026-09-21T10:00:00Z");
        return new StaffMemberDetails(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Анна Иванова",
                "anna@example.invalid",
                "+359 888 123 456",
                true,
                0,
                timestamp,
                timestamp);
    }

    private static Stream<String> invalidDisplayNames() {
        return Stream.of(null, "", "\u2003\n\u3000", "a".repeat(201), "😀".repeat(201));
    }

    private static Stream<String> validPhones() {
        return Stream.of(
                "123",
                "+123",
                "+359 (2) 123-45-67",
                "02 123.45/67-8",
                "1".repeat(20));
    }

    private static Stream<String> invalidPhones() {
        return Stream.of(
                "12",
                "+12",
                "1".repeat(21),
                "+359abc",
                "+359_123",
                "++359123",
                "359+123",
                "☎359123",
                "١٢٣");
    }
}
