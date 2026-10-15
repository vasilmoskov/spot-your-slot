package bg.spotyourslot.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.customer.CustomerDirectory;
import bg.spotyourslot.customer.CustomerDirectory.CustomerContact;
import bg.spotyourslot.integration.AvailabilityFixtures;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The published Customer read of the Business calendar against real PostgreSQL. */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class CustomerDirectoryIntegrationTests extends PostgresIntegrationTest {
    @Autowired CustomerDirectory directory;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;

    private UUID business;
    private UUID other;

    @BeforeEach
    void setUp() {
        AvailabilityFixtures fixtures = new AvailabilityFixtures(jdbc);
        business = fixtures.business("ACTIVE", "Europe/Sofia");
        other = fixtures.business("ACTIVE", "Europe/Sofia");
    }

    private UUID customer(UUID owner, String name, String phone, String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO customer(id,business_id,display_name,phone,email,version,created_at,updated_at)
                        VALUES (:id,:business,:name,:phone,:email,0,now(),now())
                        """)
                .param("id", id).param("business", owner).param("name", name)
                .param("phone", phone, Types.VARCHAR).param("email", email, Types.VARCHAR)
                .update();
        return id;
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }

    @Test
    void displayNamesOfTheRequestedCustomersOfTheBusinessOnly() {
        UUID first = customer(business, "Анна", "+359888000001", null);
        UUID second = customer(business, "Борис", null, "boris@example.test");
        UUID foreign = customer(other, "Чужд", "+359888000002", null);

        Map<UUID, String> names = inTransaction(() -> directory.displayNames(
                business, List.of(first, second, foreign, UUID.randomUUID())));

        assertThat(names).containsOnlyKeys(first, second);
        assertThat(names).containsEntry(first, "Анна").containsEntry(second, "Борис");
    }

    @Test
    void anEmptyRequestIssuesNoStatementAndDuplicatesAreHarmless() {
        UUID first = customer(business, "Анна", "+359888000001", null);

        assertThat(inTransaction(() -> directory.displayNames(business, Set.of()))).isEmpty();
        assertThat(inTransaction(() -> directory.displayNames(business, List.of(first, first)))).hasSize(1);
    }

    @Test
    void theContactOfOneCustomerIsReturnedAndAForeignOrMissingOneIsEmpty() {
        UUID mine = customer(business, "Анна", "+359888000001", "anna@example.test");
        UUID foreign = customer(other, "Чужд", "+359888000002", null);

        Optional<CustomerContact> found = inTransaction(() -> directory.findContact(business, mine));

        assertThat(found).isPresent();
        assertThat(found.get().displayName()).isEqualTo("Анна");
        assertThat(found.get().phone()).isEqualTo("+359888000001");
        assertThat(found.get().email()).isEqualTo("anna@example.test");
        assertThat(inTransaction(() -> directory.findContact(business, foreign))).isEmpty();
        assertThat(inTransaction(() -> directory.findContact(business, UUID.randomUUID()))).isEmpty();
    }

    @Test
    void itRequiresATransactionAndRedactsToString() {
        UUID mine = customer(business, "СЕНТИНЕЛ-ИМЕ", "+359888000001", "sentinel@example.test");

        assertThatThrownBy(() -> directory.displayNames(business, List.of(mine)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> directory.findContact(business, mine))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(inTransaction(() -> directory.findContact(business, mine)).orElseThrow().toString())
                .doesNotContain("СЕНТИНЕЛ", "sentinel", "+3598");
    }
}
