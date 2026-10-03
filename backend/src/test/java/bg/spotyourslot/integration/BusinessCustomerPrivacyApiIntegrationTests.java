package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceFailures;
import bg.spotyourslot.customer.infrastructure.CustomerStore;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.StoredCustomer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Privacy and failure hygiene of the private Customer API: sentinel personal values never appear in
 * Problem Details or logs, failures are sanitized, a failed mutation rolls back, responses are not
 * cacheable, and nothing leaks through the public profile. All values are synthetic sentinels.
 */
class BusinessCustomerPrivacyApiIntegrationTests extends BusinessCustomerApiIntegrationTest {
    private static final String SENTINEL_NAME = "Сентинел Зюкс";
    private static final String SENTINEL_NAME_LATIN = "Sentinel Zyuks";
    private static final String SENTINEL_PHONE = "+359887654321";
    private static final String SENTINEL_PHONE_LOCAL = "0887 654 321";
    private static final String SENTINEL_EMAIL = "sentinel.zyuks@example.test";
    private static final String SENTINEL_TERM = "sentinel.zyuks";
    private static final List<String> SENTINEL_FRAGMENTS =
            List.of("Сентинел", "Зюкс", "Sentinel", "sentinel", "Zyuks", "887654321", "887 654 321");

    @MockitoSpyBean CustomerStore store;

    private ListAppender<ILoggingEvent> logs;
    private Logger root;
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        previousLevel = root.getLevel();
        root.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(logs);
        root.setLevel(previousLevel);
    }

    // ---- Problem Details -------------------------------------------------------------------------

    @Test
    void noProblemDetailEchoesASubmittedValueAnIdentifierOrAnInternalDetail() throws Exception {
        Actor owner = api.owner("ACTIVE");
        Actor suspended = api.owner("SUSPENDED");
        UUID holder = api.insertCustomer(owner.businessId(), SENTINEL_NAME, SENTINEL_PHONE, SENTINEL_EMAIL);
        UUID other = api.insertCustomer(owner.businessId(), "Друг клиент", OTHER_PHONE, null);
        UUID missing = UUID.randomUUID();

        List<String> bodies = new ArrayList<>();
        bodies.add(body(api.create(owner, SENTINEL_NAME_LATIN, SENTINEL_PHONE_LOCAL, SENTINEL_EMAIL, true)));
        bodies.add(body(api.create(owner, SENTINEL_NAME, "sentinel-phone", "sentinel@@example.test", true)));
        bodies.add(body(api.create(owner, " ", null, null, true)));
        bodies.add(body(api.update(owner, holder, SENTINEL_NAME, SENTINEL_PHONE, SENTINEL_EMAIL, 9L)));
        bodies.add(body(api.update(owner, other, SENTINEL_NAME, SENTINEL_PHONE, null, 0L)));
        bodies.add(body(api.update(owner, missing, SENTINEL_NAME, SENTINEL_PHONE, null, 0L)));
        bodies.add(body(api.detail(owner, missing)));
        bodies.add(body(api.search(owner, "x".repeat(101))));
        bodies.add(body(api.create(suspended, SENTINEL_NAME, SENTINEL_PHONE, SENTINEL_EMAIL, true)));
        bodies.add(body(api.list(owner, "sort=" + SENTINEL_TERM)));

        for (String problem : bodies) {
            assertThat(problem).contains("\"code\"");
            for (String fragment : SENTINEL_FRAGMENTS) {
                assertThat(problem).as(fragment).doesNotContain(fragment);
            }
            assertThat(problem)
                    .doesNotContain(holder.toString())
                    .doesNotContain(other.toString())
                    .doesNotContain(missing.toString())
                    .doesNotContain(owner.businessId().toString())
                    .doesNotContain(owner.userId().toString())
                    .doesNotContain("customer_business")
                    .doesNotContain("constraint")
                    .doesNotContain("SQL")
                    .doesNotContain("Exception")
                    .doesNotContain("bg.spotyourslot")
                    .doesNotContain("org.postgresql")
                    .doesNotContain("\tat ");
        }
    }

    @Test
    void theProblemInstanceNeverEchoesACustomerId() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID existing = api.insertCustomer(owner.businessId(), NAME, PHONE, null);
        UUID missing = UUID.randomUUID();

        api.detail(owner, missing).andExpect(jsonPath("$.instance").value("/api/business/customers"));
        api.update(owner, existing, NAME, "bad", null, 0L)
                .andExpect(jsonPath("$.instance").value("/api/business/customers"));
        api.update(owner, existing, NAME, PHONE, null, 7L)
                .andExpect(jsonPath("$.instance").value("/api/business/customers"));
        assertValidationWithoutFields(mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/business/customers/{id}", existing)
                        .cookie(owner.session())
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": ")));
    }

    @Test
    void everyCustomerResponseCarriesNoStoreIncludingMutationsAndErrors() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, null);

        assertNoStore(api.create(owner, "Нов", OTHER_PHONE, null, true));
        assertNoStore(api.update(owner, id, NAME, PHONE, null, 0L));
        assertNoStore(api.update(owner, id, NAME, PHONE, null, 0L));
        assertNoStore(api.create(owner, " ", null, null, true));
        assertNoStore(api.detail(owner, UUID.randomUUID()));
        assertNoStore(api.create(owner, "Дубликат", PHONE, null, true));
    }

    // ---- logging ---------------------------------------------------------------------------------

    @Test
    void searchAndMutationPathsLogNoCustomerDataEvenAtDebugLevel() throws Exception {
        Actor owner = api.owner("ACTIVE");
        Actor suspended = api.owner("SUSPENDED");
        UUID id = api.insertCustomer(owner.businessId(), SENTINEL_NAME, SENTINEL_PHONE, SENTINEL_EMAIL);
        logs.list.clear();

        api.search(owner, SENTINEL_TERM).andExpect(status().isOk());
        api.search(owner, SENTINEL_PHONE_LOCAL).andExpect(status().isOk());
        api.create(owner, SENTINEL_NAME, SENTINEL_PHONE, null, true).andExpect(status().isConflict());
        api.create(owner, SENTINEL_NAME_LATIN, "0888 000 111", "other.zyuks@example.test", true)
                .andExpect(status().isCreated());
        api.update(owner, id, SENTINEL_NAME, SENTINEL_PHONE, SENTINEL_EMAIL, 5L).andExpect(status().isConflict());
        api.update(owner, id, SENTINEL_NAME, "bad", null, 0L).andExpect(status().isBadRequest());
        api.detail(owner, UUID.randomUUID()).andExpect(status().isNotFound());
        api.create(suspended, SENTINEL_NAME, SENTINEL_PHONE, SENTINEL_EMAIL, true).andExpect(status().isConflict());
        api.search(owner, "x".repeat(101)).andExpect(status().isBadRequest());

        assertNoSensitiveLogs(id);
        assertThat(logs.list).noneMatch(event -> event.getLoggerName().startsWith("bg.spotyourslot.customer"));
    }

    // ---- failures --------------------------------------------------------------------------------

    @Test
    void anUnexpectedPersistenceFailureIsAGenericInternalErrorAndRollsBack() throws Exception {
        Actor owner = api.owner("ACTIVE");
        doThrow(CustomerPersistenceFailures.unexpected("XX000")).when(store).insert(any());
        logs.list.clear();

        ResultActions result = api.create(owner, SENTINEL_NAME, SENTINEL_PHONE, SENTINEL_EMAIL, true);

        result.andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Възникна неочаквана грешка."));
        assertThat(body(result)).doesNotContain("XX000").doesNotContain("Customer persistence");
        assertThat(api.customerCount(owner.businessId())).isZero();
        assertNoSensitiveLogs(null);
    }

    @Test
    void serializationFailureAndDeadlockAreTheRetryableConcurrentConflict() throws Exception {
        Actor owner = api.owner("ACTIVE");
        for (String sqlState : new String[] {"40001", "40P01"}) {
            doThrow(CustomerPersistenceFailures.unexpected(sqlState)).when(store).insert(any());

            api.create(owner, SENTINEL_NAME, SENTINEL_PHONE, null, true)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CUSTOMER_CONCURRENT_CONFLICT"))
                    .andExpect(jsonPath("$.detail").value("Операцията не можа да бъде завършена. Опитайте отново."));
        }
        assertThat(api.customerCount(owner.businessId())).isZero();
    }

    @Test
    void unknownBusinessAndInvalidDataFailuresAreGenericInternalErrors() throws Exception {
        Actor owner = api.owner("ACTIVE");
        for (RuntimeException failure : List.of(
                CustomerPersistenceFailures.unknownBusiness(), CustomerPersistenceFailures.invalidData())) {
            doThrow(failure).when(store).insert(any());

            api.create(owner, NAME, PHONE, null, true)
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        }
    }

    @Test
    void aReadFailureIsAGenericProblem() throws Exception {
        Actor owner = api.owner("ACTIVE");
        doThrow(CustomerPersistenceFailures.unexpected("08006")).when(store).findById(any(), any());
        doThrow(CustomerPersistenceFailures.unexpected("40001")).when(store).count(any(), any());

        api.detail(owner, UUID.randomUUID()).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        api.list(owner, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOMER_CONCURRENT_CONFLICT"));
    }

    @Test
    void aFailureAfterAnUpdateStatementRollsTheWholeTransactionBack() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, EMAIL);
        StoredCustomer before = api.stored(id);
        clock.set(NOW.plusSeconds(60));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw CustomerPersistenceFailures.unexpected("XX000");
        }).when(store).update(any(), any(), any(), anyLong(), any());

        api.update(owner, id, "Не бива да остане", PHONE, null, 0L)
                .andExpect(status().isInternalServerError());

        assertThat(api.stored(id)).isEqualTo(before);
    }

    // ---- public surface --------------------------------------------------------------------------

    @Test
    void noCustomerValueAppearsOnThePublicProfileOrToAnAnonymousCaller() throws Exception {
        Actor owner = api.owner("ACTIVE");
        api.insertCustomer(owner.businessId(), SENTINEL_NAME, SENTINEL_PHONE, SENTINEL_EMAIL);
        String slug = api.slugOf(owner.businessId());

        ResultActions publicProfile = mvc.perform(get("/api/public/businesses/{slug}", slug))
                .andExpect(status().isOk());
        String publicBody = body(publicProfile);
        String anonymousBody = body(mvc.perform(get("/api/business/customers"))
                .andExpect(status().isUnauthorized()));
        String anonymousSearch = body(mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/business/customers/search")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"search\":\"" + SENTINEL_TERM + "\"}")));

        for (String text : List.of(publicBody, anonymousBody, anonymousSearch)) {
            for (String fragment : SENTINEL_FRAGMENTS) {
                assertThat(text).as(fragment).doesNotContain(fragment);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private void assertNoSensitiveLogs(UUID customerId) {
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            String text = event.getFormattedMessage() + "\n" + event.getThrowableProxy() + "\n"
                    + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()))
                    + "\n" + event.getMarkerList() + event.getMDCPropertyMap();
            for (String fragment : SENTINEL_FRAGMENTS) {
                assertThat(text).as(event.getLoggerName() + " " + fragment).doesNotContain(fragment);
            }
            assertThat(text).doesNotContain("customer_business");
            // The opaque ID is allowed in a path, and Spring's DEBUG request line (off by default)
            // prints the path; nothing at INFO or above may carry it.
            if (customerId != null && event.getLevel().isGreaterOrEqual(Level.INFO)) {
                assertThat(text).doesNotContain(customerId.toString());
            }
        }
    }
}
