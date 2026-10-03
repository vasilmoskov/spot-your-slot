package bg.spotyourslot.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CustomerSearchCriteriaTests {
    @Test
    void noneIsEmptyAndFiltersNothing() {
        assertThat(CustomerSearchCriteria.none().isEmpty()).isTrue();
    }

    @Test
    void aTextTermSearchesTheNameAndEmailButNeverThePhone() {
        CustomerSearchCriteria criteria = CustomerSearchCriteria.fromTerm("Анна  ИВАНОВА");

        assertThat(criteria.nameFragment()).isEqualTo("Анна ИВАНОВА");
        assertThat(criteria.emailFragment()).isEqualTo("анна  иванова");
        assertThat(criteria.phoneExact()).isNull();
        assertThat(criteria.phonePrefix()).isNull();
    }

    @Test
    void theEmailFragmentIsLowerCase() {
        assertThat(CustomerSearchCriteria.fromTerm("Ime@Primer.TEST").emailFragment())
                .isEqualTo("ime@primer.test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"+359895555777", "0895 555 777", "00359 895 555 777", "0895-555-777"})
    void aFullValidNumberMatchesTheCanonicalPhoneExactly(String term) {
        CustomerSearchCriteria criteria = CustomerSearchCriteria.fromTerm(term);

        assertThat(criteria.phoneExact()).isEqualTo("+359895555777");
        assertThat(criteria.phonePrefix()).isNull();
    }

    @Test
    void aPartialPlusInputIsAPrefix() {
        assertThat(CustomerSearchCriteria.fromTerm("+35989").phonePrefix()).isEqualTo("+35989");
    }

    @Test
    void aPartialDoubleZeroInputBecomesAPlusPrefix() {
        assertThat(CustomerSearchCriteria.fromTerm("0035989").phonePrefix()).isEqualTo("+35989");
    }

    @Test
    void aPartialLocalInputBecomesABulgarianPrefix() {
        assertThat(CustomerSearchCriteria.fromTerm("089").phonePrefix()).isEqualTo("+35989");
        assertThat(CustomerSearchCriteria.fromTerm("0").phonePrefix()).isEqualTo("+359");
    }

    @ParameterizedTest
    @ValueSource(strings = {"+", "00", "+%", "+359%", "0_89", "089\\", "089a", "123", "895555777", "ext 5"})
    void malformedPhoneLikeInputNeverAddsAPhoneCriterion(String term) {
        CustomerSearchCriteria criteria = CustomerSearchCriteria.fromTerm(term);

        assertThat(criteria.phoneExact()).isNull();
        assertThat(criteria.phonePrefix()).isNull();
    }

    @Test
    void aWildcardCharacterIsKeptAsALiteralFragment() {
        CustomerSearchCriteria criteria = CustomerSearchCriteria.fromTerm("50%_\\");

        assertThat(criteria.nameFragment()).isEqualTo("50%_\\");
        assertThat(criteria.emailFragment()).isEqualTo("50%_\\");
    }

    @Test
    void toStringNeverShowsTheTerm() {
        assertThat(CustomerSearchCriteria.fromTerm("Sentinel-Term-4711").toString())
                .doesNotContain("Sentinel")
                .doesNotContain("4711");
    }
}
