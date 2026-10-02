package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.workforce.StaffMemberApplicationException.InputField;
import bg.spotyourslot.workforce.StaffMemberApplicationException.InvalidInput;
import bg.spotyourslot.workforce.StaffMemberRecords.AssignedServiceSummary;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.ReplaceServiceAssignmentsCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberAssignments;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberDetails;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberPage;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberSortField;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.UpdateStaffMemberCommand;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class StaffMemberInputValidatorTests {
    private static final StaffMemberInputValidator VALIDATOR = new StaffMemberInputValidator();

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
                        "+35921234567"));

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

    @ParameterizedTest
    @ValueSource(strings = {
        "a@a",
        "a@\u0430",
        "a@xn--80a",
        "@primer.bg",
        "a@",
        "a@.bg",
        "a@primer.",
        "a@primer..bg",
        "a@-primer.bg",
        "a@primer-.bg",
        "a@primer.-bg",
        "a@primer.bg-",
        "a b@primer.bg",
        "a@pri mer.bg",
        "a@@primer.bg",
        "a@b@primer.bg",
        "a..b@primer.bg",
        ".ab@primer.bg",
        "ab.@primer.bg",
        "\u0438\u0432\u0430\u043d@primer.bg",
        "\u0438\u0432\u0430\u043d@\u043f\u0440\u0438\u043c\u0435\u0440.\u0431\u0433",
        "ime@\u043f\u0440\u0438\u043c\u0435\u0440.\u0431\u0433",
    })
    void rejectsAddressesWithoutARoutableDottedDomain(String email) {
        assertInvalid(InputField.CONTACT_EMAIL, () -> VALIDATOR.validateCreate(
                new CreateStaffMemberCommand("Екип", email, null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ime@primer.bg",
        "ime.prezime@primer.bg",
        "a@ab.bg",
        "ime@mail.primer.bg",
        "ime.prezime+tag@sub.primer.co.uk",
    })
    void acceptsAsciiAddressesWithDottedDomains(String email) {
        assertThat(VALIDATOR.validateCreate(new CreateStaffMemberCommand("Екип", email, null))
                        .contactEmail())
                .isEqualTo(email);
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
    void canonicalizesApprovedPhoneFormsToCompactE164(String rawPhone, String expectedCanonical) {
        assertThat(VALIDATOR.validateCreate(
                                new CreateStaffMemberCommand("Екип", null, rawPhone))
                        .contactPhone())
                .isEqualTo(expectedCanonical);
    }

    @ParameterizedTest
    @MethodSource("invalidPhones")
    void rejectsInvalidPhoneCharactersAmbiguityAndDigitBounds(String phone) {
        assertInvalid(InputField.CONTACT_PHONE, () -> VALIDATOR.validateCreate(
                new CreateStaffMemberCommand("Екип", null, phone)));
    }

    @Test
    void rejectsStructurallyInvalidForeignAndBulgarianNumbers() {
        // Too few national digits for Germany's numbering plan.
        assertInvalid(InputField.CONTACT_PHONE, () -> VALIDATOR.validateCreate(
                new CreateStaffMemberCommand("Екип", null, "+491")));
        // Too few national digits for Bulgaria's numbering plan.
        assertInvalid(InputField.CONTACT_PHONE, () -> VALIDATOR.validateCreate(
                new CreateStaffMemberCommand("Екип", null, "+35921")));
    }

    @Test
    void validatesIdentifiersVersionsAndPagination() {
        UUID id = UUID.randomUUID();

        assertThat(VALIDATOR.validateBusinessId(id)).isEqualTo(id);
        assertThat(VALIDATOR.validateStaffMemberId(id)).isEqualTo(id);
        assertThat(VALIDATOR.validateVersion(new StaffMemberVersionCommand(0L))).isZero();
        assertThat(VALIDATOR.validatePage(0, 10))
                .isEqualTo(new StaffMemberInputValidator.PageInput(0, 10));
        assertThat(VALIDATOR.validatePage(4, StaffMemberInputValidator.MAX_PAGE_SIZE))
                .isEqualTo(new StaffMemberInputValidator.PageInput(4, 50));
        assertThat(StaffMemberInputValidator.DEFAULT_PAGE_SIZE).isEqualTo(10);

        assertInvalid(InputField.BUSINESS_ID, () -> VALIDATOR.validateBusinessId(null));
        assertInvalid(InputField.STAFF_MEMBER_ID, () -> VALIDATOR.validateStaffMemberId(null));
        assertInvalid(InputField.EXPECTED_VERSION,
                () -> VALIDATOR.validateVersion(new StaffMemberVersionCommand(null)));
        assertInvalid(InputField.EXPECTED_VERSION,
                () -> VALIDATOR.validateVersion(new StaffMemberVersionCommand(-1L)));
        assertInvalid(InputField.PAGE, () -> VALIDATOR.validatePage(-1, 50));
        assertInvalid(InputField.SIZE, () -> VALIDATOR.validatePage(0, 0));
        assertInvalid(InputField.SIZE, () -> VALIDATOR.validatePage(0, 51));
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 25, 50})
    void acceptsOnlyTheApprovedPageSizes(int size) {
        assertThat(VALIDATOR.validatePage(0, size))
                .isEqualTo(new StaffMemberInputValidator.PageInput(0, size));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 20, 37, 49, 51})
    void rejectsEveryPageSizeOutsideTheApprovedAllowlist(int size) {
        assertInvalid(InputField.SIZE, () -> VALIDATOR.validatePage(0, size));
    }

    @Test
    void validatesSortAndDirection() {
        assertThat(VALIDATOR.validateSort(null)).isEqualTo(StaffMemberSortField.NAME);
        assertThat(VALIDATOR.validateSort("name")).isEqualTo(StaffMemberSortField.NAME);
        assertThat(VALIDATOR.validateSort("status")).isEqualTo(StaffMemberSortField.STATUS);
        assertThat(VALIDATOR.validateSort("phone")).isEqualTo(StaffMemberSortField.PHONE);
        assertThat(VALIDATOR.validateSort("email")).isEqualTo(StaffMemberSortField.EMAIL);
        assertInvalid(InputField.SORT, () -> VALIDATOR.validateSort("unknown"));
        assertInvalid(InputField.SORT, () -> VALIDATOR.validateSort("NAME"));

        assertThat(VALIDATOR.validateAscending(null)).isTrue();
        assertThat(VALIDATOR.validateAscending("asc")).isTrue();
        assertThat(VALIDATOR.validateAscending("desc")).isFalse();
        assertInvalid(InputField.DIRECTION, () -> VALIDATOR.validateAscending("ASC"));
        assertInvalid(InputField.DIRECTION, () -> VALIDATOR.validateAscending("sideways"));
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

    @Test
    void validatesAssignmentIdsVersionAndDefensiveCopies() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var mutable = new ArrayList<>(List.of(first, second));
        var command = new ReplaceServiceAssignmentsCommand(mutable, 0L);
        mutable.clear();

        assertThat(VALIDATOR.validateAssignments(command))
                .isEqualTo(new ReplaceServiceAssignmentsCommand(List.of(first, second), 0L));
        assertThatThrownBy(() -> command.serviceIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(VALIDATOR.validateAssignments(
                        new ReplaceServiceAssignmentsCommand(List.of(), 4L))
                .serviceIds()).isEmpty();
    }

    @Test
    void rejectsInvalidAssignmentCommandsAndDuplicateIds() {
        UUID serviceId = UUID.randomUUID();
        assertInvalid(InputField.COMMAND, () -> VALIDATOR.validateAssignments(null));
        assertInvalid(InputField.SERVICE_IDS, () -> VALIDATOR.validateAssignments(
                new ReplaceServiceAssignmentsCommand(null, 0L)));
        assertInvalid(InputField.SERVICE_IDS, () -> VALIDATOR.validateAssignments(
                new ReplaceServiceAssignmentsCommand(
                        java.util.Arrays.asList(serviceId, null), 0L)));
        assertInvalid(InputField.SERVICE_IDS, () -> VALIDATOR.validateAssignments(
                new ReplaceServiceAssignmentsCommand(List.of(serviceId, serviceId), 0L)));
        assertInvalid(InputField.EXPECTED_VERSION, () -> VALIDATOR.validateAssignments(
                new ReplaceServiceAssignmentsCommand(List.of(serviceId), null)));
        assertInvalid(InputField.EXPECTED_VERSION, () -> VALIDATOR.validateAssignments(
                new ReplaceServiceAssignmentsCommand(List.of(serviceId), -1L)));
    }

    @Test
    void assignmentResultsDefensivelyCopySafeSummaries() {
        UUID serviceId = UUID.randomUUID();
        var mutable = new ArrayList<>(List.of(
                new AssignedServiceSummary(serviceId, "Услуга", true)));
        Instant timestamp = Instant.parse("2026-09-22T10:00:00Z");

        var assignments = new StaffMemberAssignments(
                UUID.randomUUID(), 2, timestamp, timestamp, mutable);
        mutable.clear();

        assertThat(assignments.services())
                .containsExactly(new AssignedServiceSummary(serviceId, "Услуга", true));
        assertThatThrownBy(() -> assignments.services().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(StaffMemberAssignments.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("businessId", "membershipId", "userId");
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

    private static Stream<Arguments> validPhones() {
        return Stream.of(
                // Bulgarian national prefix '0' is interpreted with the default BG country code.
                Arguments.of("0895555777", "+359895555777"),
                Arguments.of("02 123 45 67", "+35921234567"),
                // International prefix '00' is converted to '+'.
                Arguments.of("0049 151 23456789", "+4915123456789"),
                // A value already starting with '+' preserves its supplied country code.
                Arguments.of("+49 151 23456789", "+4915123456789"),
                Arguments.of("+359 (895) 555-777", "+359895555777"),
                // Allowed visual separators are removed from anywhere in the value.
                Arguments.of("+359.895.555.777", "+359895555777"));
    }

    private static Stream<String> invalidPhones() {
        return Stream.of(
                // Ambiguous: no '0', '00', or '+' prefix, so no country code can be inferred.
                "895555777",
                "123456789",
                // Malformed: contains characters that are not allowed visual separators or digits.
                "+359 abc",
                "+359_123456",
                "☎359123456",
                "١٢٣٤٥٦٧٨", // Arabic-Indic digits
                // A leading '0' inside an already-international number is not a valid E.164 form.
                "+0895555777",
                // A malformed leading '+' sequence.
                "++359123456");
    }
}
