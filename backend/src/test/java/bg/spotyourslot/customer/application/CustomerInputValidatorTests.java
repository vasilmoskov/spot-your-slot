package bg.spotyourslot.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.customer.application.CustomerAdministrationException.InputField;
import bg.spotyourslot.customer.application.CustomerAdministrationException.InvalidInput;
import bg.spotyourslot.customer.domain.CustomerProfile;
import bg.spotyourslot.customer.domain.CustomerSortField;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CustomerInputValidatorTests {
    private final CustomerInputValidator validator = new CustomerInputValidator();

    @Test
    void pagingDefaultsToPageZeroAndSizeTen() {
        assertThat(validator.validatePage(null, null))
                .isEqualTo(new CustomerInputValidator.PageInput(0, 10));
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 25, 50})
    void everyAllowedSizeIsAccepted(int size) {
        assertThat(validator.validatePage(3, size).size()).isEqualTo(size);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 9, 11, 24, 51, 100, Integer.MAX_VALUE})
    void everyOtherSizeIsRejectedWithoutAField(int size) {
        assertThatThrownBy(() -> validator.validatePage(0, size))
                .isInstanceOfSatisfying(InvalidInput.class,
                        invalid -> assertThat(invalid.fields()).containsExactly(InputField.SIZE));
    }

    @Test
    void aNegativePageIsRejected() {
        assertThatThrownBy(() -> validator.validatePage(-1, 10))
                .isInstanceOfSatisfying(InvalidInput.class,
                        invalid -> assertThat(invalid.fields()).containsExactly(InputField.PAGE));
    }

    @Test
    void sortAcceptsOnlyTheAllowlistAndDefaultsToName() {
        assertThat(validator.validateSort(null)).isEqualTo(CustomerSortField.NAME);
        assertThat(validator.validateSort("name")).isEqualTo(CustomerSortField.NAME);
        assertThat(validator.validateSort("phone")).isEqualTo(CustomerSortField.PHONE);
        assertThat(validator.validateSort("email")).isEqualTo(CustomerSortField.EMAIL);
        for (String invalid : new String[] {"", "NAME", "id", "version", "created_at", "name; DROP"}) {
            assertThatThrownBy(() -> validator.validateSort(invalid)).isInstanceOf(InvalidInput.class);
        }
    }

    @Test
    void directionAcceptsOnlyAscAndDesc() {
        assertThat(validator.validateAscending(null)).isTrue();
        assertThat(validator.validateAscending("asc")).isTrue();
        assertThat(validator.validateAscending("desc")).isFalse();
        for (String invalid : new String[] {"", "ASC", "up", "descending"}) {
            assertThatThrownBy(() -> validator.validateAscending(invalid)).isInstanceOf(InvalidInput.class);
        }
    }

    @Test
    void aBlankOrMissingSearchTermMeansNoFilter() {
        assertThat(validator.validateSearch(null).isEmpty()).isTrue();
        assertThat(validator.validateSearch("").isEmpty()).isTrue();
        assertThat(validator.validateSearch("   \t ").isEmpty()).isTrue();
    }

    @Test
    void theSearchTermIsTrimmedAndLimitedToOneHundredCodePointsAfterTrimming() {
        assertThat(validator.validateSearch("  " + "а".repeat(100) + "  ").isEmpty()).isFalse();
        assertThatThrownBy(() -> validator.validateSearch("а".repeat(101)))
                .isInstanceOfSatisfying(InvalidInput.class,
                        invalid -> assertThat(invalid.fields()).containsExactly(InputField.SEARCH));
    }

    @Test
    void theSearchLimitCountsCodePointsNotUtf16Units() {
        String supplementary = "😀".repeat(100);

        assertThat(validator.validateSearch(supplementary).isEmpty()).isFalse();
        assertThatThrownBy(() -> validator.validateSearch(supplementary + "x"))
                .isInstanceOf(InvalidInput.class);
    }

    @Test
    void aValidProfileIsCanonicalized() {
        CustomerProfile profile = validator.validateProfile(
                "  Анна  Иванова ", " 0895 555 777 ", " ANNA@Example.TEST ");

        assertThat(profile).isEqualTo(
                new CustomerProfile("Анна Иванова", "+359895555777", "anna@example.test"));
    }

    @Test
    void everyInvalidFieldIsReportedTogether() {
        assertThatThrownBy(() -> validator.validateProfile(" ", "abc", "not-an-email"))
                .isInstanceOfSatisfying(InvalidInput.class, invalid -> assertThat(invalid.fields())
                        .isEqualTo(Set.of(InputField.DISPLAY_NAME, InputField.PHONE, InputField.EMAIL)));
    }

    @Test
    void missingBothContactsIsAContactError() {
        assertThatThrownBy(() -> validator.validateProfile("Анна", null, "  "))
                .isInstanceOfSatisfying(InvalidInput.class,
                        invalid -> assertThat(invalid.fields()).containsExactly(InputField.CONTACT));
    }

    @Test
    void anOverlongNameIsADisplayNameError() {
        assertThatThrownBy(() -> validator.validateProfile("а".repeat(201), "0895555777", null))
                .isInstanceOfSatisfying(InvalidInput.class, invalid ->
                        assertThat(invalid.fields()).containsExactly(InputField.DISPLAY_NAME));
    }

    @Test
    void theExpectedVersionIsRequiredAndNonNegative() {
        assertThat(validator.validateExpectedVersion(0L)).isZero();
        assertThatThrownBy(() -> validator.validateExpectedVersion(null)).isInstanceOf(InvalidInput.class);
        assertThatThrownBy(() -> validator.validateExpectedVersion(-1L)).isInstanceOf(InvalidInput.class);
    }

    @Test
    void identifiersAreRequired() {
        assertThatThrownBy(() -> validator.validateCustomerId(null)).isInstanceOf(InvalidInput.class);
        assertThatThrownBy(() -> validator.validateBusinessId(null)).isInstanceOf(InvalidInput.class);
        UUID id = UUID.randomUUID();
        assertThat(validator.validateCustomerId(id)).isEqualTo(id);
    }
}
