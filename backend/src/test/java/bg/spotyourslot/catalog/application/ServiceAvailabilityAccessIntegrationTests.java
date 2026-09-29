package bg.spotyourslot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.catalog.ServiceAvailabilityAccess;
import bg.spotyourslot.catalog.ServiceAvailabilityAccess.BookableService;
import bg.spotyourslot.integration.AvailabilityFixtures;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ServiceAvailabilityAccessIntegrationTests extends PostgresIntegrationTest {
    @Autowired ServiceAvailabilityAccess access;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;

    private AvailabilityFixtures fixtures;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        fixtures = new AvailabilityFixtures(jdbc);
        transaction = new TransactionTemplate(transactions);
    }

    private Optional<BookableService> find(UUID businessId, UUID serviceId) {
        return transaction.execute(status -> access.findBookableService(businessId, serviceId));
    }

    @Test
    void anActiveServiceReturnsOnlyItsIdentityAndDuration() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID service = fixtures.service(business, 45, true);

        assertThat(find(business, service)).contains(
                new BookableService(service, Duration.ofMinutes(45)));
        assertThat(Arrays.stream(BookableService.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly("id", "duration");
    }

    @Test
    void theCommittedDurationBoundsMapToExactDurations() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID shortest = fixtures.service(business, 1, true);
        UUID longest = fixtures.service(business, 480, true);

        assertThat(find(business, shortest).orElseThrow().duration())
                .isEqualTo(Duration.ofMinutes(1));
        assertThat(find(business, longest).orElseThrow().duration())
                .isEqualTo(Duration.ofMinutes(480));
    }

    @Test
    void anInactiveServiceIsAbsentAndBecomesPresentAgainWhenReactivated() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID service = fixtures.service(business, 30, false);

        assertThat(find(business, service)).isEmpty();

        fixtures.setServiceActive(service, true);
        assertThat(find(business, service)).isPresent();
    }

    @Test
    void aServiceOfAnotherBusinessOrAnUnknownServiceIsAbsent() {
        UUID businessA = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID businessB = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID serviceOfB = fixtures.service(businessB, 30, true);

        assertThat(find(businessA, serviceOfB)).isEmpty();
        assertThat(find(businessB, serviceOfB)).isPresent();
        assertThat(find(businessA, UUID.randomUUID())).isEmpty();
    }

    @Test
    void theLookupRequiresTheCallersTransaction() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID service = fixtures.service(business, 30, true);

        assertThatThrownBy(() -> access.findBookableService(business, service))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
