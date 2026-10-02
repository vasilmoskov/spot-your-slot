package bg.spotyourslot.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerTests {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BUSINESS = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final CustomerProfile PROFILE =
            new CustomerProfile("Анна Иванова", "+359895555777", null);
    private static final Instant CREATED = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-10-01T09:00:00Z");

    @Test
    void exposesTheProfileValuesAndKeepsBusinessOwnership() {
        Customer customer = new Customer(ID, BUSINESS, PROFILE, 3, CREATED, UPDATED);

        assertThat(customer.id()).isEqualTo(ID);
        assertThat(customer.businessId()).isEqualTo(BUSINESS);
        assertThat(customer.displayName()).isEqualTo("Анна Иванова");
        assertThat(customer.phone()).isEqualTo("+359895555777");
        assertThat(customer.email()).isNull();
        assertThat(customer.version()).isEqualTo(3);
        assertThat(customer.createdAt()).isEqualTo(CREATED);
        assertThat(customer.updatedAt()).isEqualTo(UPDATED);
    }

    @Test
    void acceptsVersionZeroAndEqualAuditInstants() {
        Customer customer = new Customer(ID, BUSINESS, PROFILE, 0, CREATED, CREATED);

        assertThat(customer.version()).isZero();
        assertThat(customer.updatedAt()).isEqualTo(customer.createdAt());
    }

    @Test
    void rejectsANegativeVersion() {
        assertThatThrownBy(() -> new Customer(ID, BUSINESS, PROFILE, -1, CREATED, UPDATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Customer version must not be negative");
    }

    @Test
    void rejectsAnUpdateInstantBeforeCreation() {
        assertThatThrownBy(() -> new Customer(ID, BUSINESS, PROFILE, 0, UPDATED, CREATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Customer update time must not precede creation");
    }

    @Test
    void rejectsMissingParts() {
        assertThatThrownBy(() -> new Customer(null, BUSINESS, PROFILE, 0, CREATED, UPDATED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Customer(ID, null, PROFILE, 0, CREATED, UPDATED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Customer(ID, BUSINESS, null, 0, CREATED, UPDATED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Customer(ID, BUSINESS, PROFILE, 0, null, UPDATED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Customer(ID, BUSINESS, PROFILE, 0, CREATED, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void customersAreImmutableValuesWithRecordEquality() {
        Customer first = new Customer(ID, BUSINESS, PROFILE, 1, CREATED, UPDATED);
        Customer second = new Customer(ID, BUSINESS, PROFILE, 1, CREATED, UPDATED);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(new Customer(ID, BUSINESS, PROFILE, 2, CREATED, UPDATED));
        assertThat(Customer.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("id", "businessId", "profile", "version", "createdAt", "updatedAt");
    }

    @Test
    void aNewCustomerRequiresEveryPart() {
        NewCustomer valid = new NewCustomer(ID, BUSINESS, PROFILE, CREATED);

        assertThat(valid.profile()).isEqualTo(PROFILE);
        assertThatThrownBy(() -> new NewCustomer(null, BUSINESS, PROFILE, CREATED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NewCustomer(ID, null, PROFILE, CREATED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NewCustomer(ID, BUSINESS, null, CREATED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NewCustomer(ID, BUSINESS, PROFILE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theDomainModelHasNoLifecycleNoteAccountOrRawContactState() {
        var names = java.util.Arrays.stream(Customer.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase(java.util.Locale.ROOT))
                .toList();
        var profileNames = java.util.Arrays.stream(CustomerProfile.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase(java.util.Locale.ROOT))
                .toList();

        assertThat(names).noneMatch(name -> name.contains("status") || name.contains("active")
                || name.contains("note") || name.contains("membership") || name.contains("user")
                || name.contains("account") || name.contains("raw") || name.contains("appointment"));
        assertThat(profileNames).containsExactly("displayname", "phone", "email");
    }
}
