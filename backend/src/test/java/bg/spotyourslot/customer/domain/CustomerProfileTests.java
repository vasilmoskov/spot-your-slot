package bg.spotyourslot.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CustomerProfileTests {
    private static final String PHONE = "+359895555777";
    private static final String EMAIL = "ime@primer.bg";

    @Test
    void acceptsAPhoneOnlyAnEmailOnlyAndBothContacts() {
        assertThat(new CustomerProfile("Анна Иванова", PHONE, null).email()).isNull();
        assertThat(new CustomerProfile("Анна Иванова", null, EMAIL).phone()).isNull();
        CustomerProfile both = new CustomerProfile("Анна Иванова", PHONE, EMAIL);
        assertThat(both.phone()).isEqualTo(PHONE);
        assertThat(both.email()).isEqualTo(EMAIL);
    }

    @Test
    void rejectsAProfileWithNeitherContact() {
        assertFields(() -> new CustomerProfile("Анна", null, null), CustomerField.CONTACT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " Анна", "Анна ", "Анна  Иванова", "Анна\tИванова", "ＡＢＣ"})
    void rejectsADisplayNameThatIsBlankOrNotCanonical(String name) {
        assertFields(() -> new CustomerProfile(name, PHONE, null), CustomerField.DISPLAY_NAME);
    }

    @Test
    void rejectsANullDisplayName() {
        assertFields(() -> new CustomerProfile(null, PHONE, null), CustomerField.DISPLAY_NAME);
    }

    @Test
    void countsTheTwoHundredCharacterLimitInCodePoints() {
        assertThat(new CustomerProfile("я".repeat(200), PHONE, null)).isNotNull();
        assertThat(new CustomerProfile("😀".repeat(200), PHONE, null)).isNotNull();
        assertFields(() -> new CustomerProfile("я".repeat(201), PHONE, null), CustomerField.DISPLAY_NAME);
        assertFields(() -> new CustomerProfile("😀".repeat(201), PHONE, null), CustomerField.DISPLAY_NAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0895555777", "+359 895 555 777", "00359895555777", "+359895555777x5", "abc", ""})
    void rejectsAPhoneThatIsNotCanonicalE164(String phone) {
        assertFields(() -> new CustomerProfile("Анна", phone, EMAIL), CustomerField.PHONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ime@primer.bg", " ime@primer.bg", "ime@primer", "иван@primer.bg", "", "a b@primer.bg"})
    void rejectsAnEmailThatIsNotTheCanonicalLowercaseAddress(String email) {
        assertFields(() -> new CustomerProfile("Анна", PHONE, email), CustomerField.EMAIL);
    }

    @Test
    void reportsEveryInvalidFieldFoundInOnePass() {
        assertFields(
                () -> new CustomerProfile(" ", "0895555777", "Ime@primer.bg"),
                CustomerField.DISPLAY_NAME,
                CustomerField.PHONE,
                CustomerField.EMAIL);
    }

    @Test
    void fromInputCanonicalizesRawValuesWithTheSharedPolicy() {
        CustomerProfile profile = CustomerProfile.fromInput(
                "  Анна 　 Иванова ", " 0895 555 777 ", " Ime.Prezime@Primer.BG ");

        assertThat(profile.displayName()).isEqualTo("Анна Иванова");
        assertThat(profile.phone()).isEqualTo(PHONE);
        assertThat(profile.email()).isEqualTo("ime.prezime@primer.bg");
    }

    @Test
    void fromInputTreatsBlankContactsAsAbsentAndKeepsTheOtherOne() {
        CustomerProfile phoneOnly = CustomerProfile.fromInput("Анна", "0895555777", " 　 ");
        CustomerProfile emailOnly = CustomerProfile.fromInput("Анна", "", EMAIL);

        assertThat(phoneOnly.email()).isNull();
        assertThat(emailOnly.phone()).isNull();
    }

    @Test
    void fromInputReportsContactOnlyWhenNeitherContactWasSupplied() {
        assertFields(() -> CustomerProfile.fromInput("Анна", " ", null), CustomerField.CONTACT);
        assertFields(() -> CustomerProfile.fromInput("Анна", "abc", null), CustomerField.PHONE);
        assertFields(() -> CustomerProfile.fromInput("Анна", null, "bad"), CustomerField.EMAIL);
    }

    @Test
    void fromInputRejectsExtensionsAndLettersInsteadOfDroppingThem() {
        assertFields(
                () -> CustomerProfile.fromInput("Анна", "+359895555777 ext 5", null), CustomerField.PHONE);
        assertFields(() -> CustomerProfile.fromInput("Анна", "0888 123 456 abc", null), CustomerField.PHONE);
    }

    @Test
    void fromInputReportsAllInvalidFieldsTogether() {
        assertFields(
                () -> CustomerProfile.fromInput("", "12", "nope"),
                CustomerField.DISPLAY_NAME,
                CustomerField.PHONE,
                CustomerField.EMAIL);
    }

    @Test
    void failuresCarryFieldIdentifiersOnlyAndNeverTheSubmittedValues() {
        String name = "Секретно Име  ";
        String phone = "0895 000 111 ext 9";
        String email = "secret@@primer.bg";

        assertThatThrownBy(() -> CustomerProfile.fromInput(name, phone, email))
                .isInstanceOfSatisfying(InvalidCustomerData.class, exception -> {
                    assertThat(exception.getMessage()).isEqualTo("Customer data is invalid");
                    assertThat(exception.getCause()).isNull();
                    assertThat(exception.toString())
                            .doesNotContain("Секретно")
                            .doesNotContain("0895")
                            .doesNotContain("secret");
                });
    }

    @Test
    void theReportedFieldSetIsImmutable() {
        InvalidCustomerData failure = new InvalidCustomerData(EnumSet.of(CustomerField.PHONE));

        assertThatThrownBy(() -> failure.fields().add(CustomerField.EMAIL))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(failure.fields()).containsExactly(CustomerField.PHONE);
    }

    @Test
    void anInvalidDataFailureNeedsAtLeastOneField() {
        assertThatThrownBy(() -> new InvalidCustomerData(Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvalidCustomerData(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void profilesAreValuesWithRecordEquality() {
        CustomerProfile first = new CustomerProfile("Анна", PHONE, EMAIL);
        CustomerProfile second = new CustomerProfile("Анна", PHONE, EMAIL);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(new CustomerProfile("Анна", PHONE, null));
    }

    private static void assertFields(Runnable action, CustomerField... expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        InvalidCustomerData.class,
                        exception -> assertThat(exception.fields()).containsExactlyInAnyOrder(expected));
    }
}
