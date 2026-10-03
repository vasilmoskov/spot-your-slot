package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.StoredCustomer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit create and version-guarded update of the private Customer API against real PostgreSQL:
 * canonical storage, validation, conflicts, optimistic versioning, rollback, side-effect freedom,
 * and concurrency. All data is synthetic.
 */
class BusinessCustomerMutationApiIntegrationTests extends BusinessCustomerApiIntegrationTest {
    private static final Instant LATER = NOW.plusSeconds(60);
    private static final List<String> ACCOUNT_TABLES =
            List.of("app_user", "membership", "user_session", "platform_role", "business");

    // ---- create ----------------------------------------------------------------------------------

    @Test
    void createStoresTheCanonicalProfileAndReturnsTheDetailWithLocation() throws Exception {
        Actor owner = api.owner("ACTIVE");

        String raw = body(api.create(owner, " Анна   Иванова ", " 0895 555 777 ", " ANNA@Example.TEST ", true)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/business/customers/"))));
        JsonNode created = BusinessCustomerApiTestSupport.parse(raw);

        assertThat(keys(created)).containsExactly(
                "id", "displayName", "phone", "email", "version", "createdAt", "updatedAt");
        assertThat(created.get("displayName").asString()).isEqualTo("Анна Иванова");
        assertThat(created.get("phone").asString()).isEqualTo("+359895555777");
        assertThat(created.get("email").asString()).isEqualTo("anna@example.test");
        assertThat(created.get("version").asLong()).isZero();
        assertThat(created.get("createdAt").asString()).isEqualTo(NOW.toString());
        assertThat(created.get("updatedAt").asString()).isEqualTo(NOW.toString());
        assertThat(raw).doesNotContain("businessId").doesNotContain(owner.businessId().toString());

        UUID id = UUID.fromString(created.get("id").asString());
        StoredCustomer stored = api.stored(id);
        assertThat(stored.businessId()).isEqualTo(owner.businessId());
        assertThat(stored.displayName()).isEqualTo("Анна Иванова");
        assertThat(stored.phone()).isEqualTo("+359895555777");
        assertThat(stored.email()).isEqualTo("anna@example.test");
        api.detail(owner, id).andExpect(status().isOk());
    }

    @Test
    void createLocationPointsAtTheNewCustomer() throws Exception {
        Actor owner = api.owner("ACTIVE");

        var result = api.create(owner, NAME, PHONE, null, true).andExpect(status().isCreated());
        String id = BusinessCustomerApiTestSupport.parse(body(result)).get("id").asString();

        result.andExpect(header().string("Location", endsWith("/api/business/customers/" + id)));
    }

    @Test
    void createAcceptsAPhoneOnlyAnEmailOnlyAndABothContactCustomer() throws Exception {
        Actor owner = api.owner("ACTIVE");

        api.create(owner, "Само телефон", PHONE, null, true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").isEmpty());
        api.create(owner, "Само имейл", null, EMAIL, true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").isEmpty());
        api.create(owner, "И двете", OTHER_PHONE, OTHER_EMAIL, true).andExpect(status().isCreated());
        api.create(owner, "Празни низове", "  ", OTHER_EMAIL.replace("boris", "praznen"), true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").isEmpty());

        assertThat(api.customerCount(owner.businessId())).isEqualTo(4L);
    }

    @Test
    void createNeverUsesTheOwnersContactOrCreatesAnyAccountRecord() throws Exception {
        Actor owner = api.owner("ACTIVE");
        Map<String, Long> before = tableCounts();

        api.create(owner, NAME, PHONE, null, true).andExpect(status().isCreated());
        api.create(owner, "Втори", null, EMAIL, true).andExpect(status().isCreated());

        assertThat(tableCounts()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM customer WHERE phone IS NULL AND email IS NULL")
                .query(Long.class).single()).isZero();
    }

    @Test
    void createValidationNamesEveryInvalidFieldTogetherWithTheApprovedMessages() throws Exception {
        Actor owner = api.owner("ACTIVE");

        ResultActions all = api.create(owner, " ", "abc", "not-an-email", true);
        assertValidationFields(all, "displayName", "phone", "email");
        all.andExpect(jsonPath("$.fieldErrors.displayName").value("Въведете име на клиента до 200 знака."))
                .andExpect(jsonPath("$.fieldErrors.phone").value("Въведеният телефонен номер не е валиден."))
                .andExpect(jsonPath("$.fieldErrors.email").value("Въведеният имейл адрес не е валиден."));

        ResultActions noContact = api.create(owner, NAME, null, "  ", true);
        assertValidationFields(noContact, "contact");
        noContact.andExpect(jsonPath("$.fieldErrors.contact").value("Въведете телефон или имейл."));

        assertValidationFields(api.create(owner, "а".repeat(201), PHONE, null, true), "displayName");
        assertValidationFields(
                api.createJson(owner, BusinessCustomerApiTestSupport.emptyObject(), true),
                "displayName", "contact");
        assertThat(api.customerCount(owner.businessId())).isZero();
    }

    @Test
    void aSuppliedButInvalidContactIsAFieldErrorNotAContactError() throws Exception {
        Actor owner = api.owner("ACTIVE");

        assertValidationFields(api.create(owner, NAME, "123", null, true), "phone");
        assertValidationFields(api.create(owner, NAME, null, "a@b", true), "email");
        assertValidationFields(api.create(owner, NAME, "+359 888 123 456 ext 5", null, true), "phone");
    }

    @Test
    void aNonObjectOrMalformedCreateBodyIsAGenericValidationError() throws Exception {
        Actor owner = api.owner("ACTIVE");

        assertValidationWithoutFields(mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/business/customers")
                        .cookie(owner.session())
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": ")));
    }

    @Test
    void createReportsADuplicatePhoneOrEmailAsASafeConflictAndStoresNothing() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID holder = api.insertCustomer(owner.businessId(), "Първоначален", PHONE, EMAIL);

        ResultActions phone = api.create(owner, "Нов", PHONE, null, true);
        assertContactConflict(phone, true, false);
        assertContactConflict(api.create(owner, "Нов", null, EMAIL, true), false, true);
        assertContactConflict(api.create(owner, "Нов", PHONE, EMAIL, true), true, true);
        // A different phone with the held email is still a conflict, never a match or a merge.
        assertContactConflict(api.create(owner, "Нов", OTHER_PHONE, EMAIL, true), false, true);

        String raw = body(api.create(owner, "Нов", PHONE, EMAIL, true));
        assertThat(raw).doesNotContain(holder.toString()).doesNotContain("Първоначален");
        assertThat(api.customerCount(owner.businessId())).isEqualTo(1L);
        StoredCustomer unchanged = api.stored(holder);
        assertThat(unchanged.version()).isZero();
        assertThat(unchanged.displayName()).isEqualTo("Първоначален");
    }

    @Test
    void createNeverReusesAnExistingCustomerEvenForAnIdenticalSubmission() throws Exception {
        Actor owner = api.owner("ACTIVE");
        api.create(owner, NAME, PHONE, EMAIL, true).andExpect(status().isCreated());

        assertContactConflict(api.create(owner, NAME, PHONE, EMAIL, true), true, true);

        assertThat(api.customerCount(owner.businessId())).isEqualTo(1L);
    }

    @Test
    void sameNameWithDifferentContactCreatesAnotherCustomer() throws Exception {
        Actor owner = api.owner("ACTIVE");

        api.create(owner, NAME, PHONE, null, true).andExpect(status().isCreated());
        api.create(owner, NAME, OTHER_PHONE, null, true).andExpect(status().isCreated());

        assertThat(api.customerCount(owner.businessId())).isEqualTo(2L);
    }

    // ---- update ----------------------------------------------------------------------------------

    @Test
    void updateReplacesNamePhoneAndEmailAndBumpsTheVersionAndTimestamp() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, EMAIL);
        clock.set(LATER);

        JsonNode updated = json(api.update(owner, id, " Анна  Петрова ", "0888 123 456", " NEW@Example.TEST ", 0L)
                .andExpect(status().isOk()));

        assertThat(keys(updated)).containsExactly(
                "id", "displayName", "phone", "email", "version", "createdAt", "updatedAt");
        assertThat(updated.get("displayName").asString()).isEqualTo("Анна Петрова");
        assertThat(updated.get("phone").asString()).isEqualTo("+359888123456");
        assertThat(updated.get("email").asString()).isEqualTo("new@example.test");
        assertThat(updated.get("version").asLong()).isEqualTo(1L);
        assertThat(updated.get("createdAt").asString()).isEqualTo(NOW.toString());
        assertThat(updated.get("updatedAt").asString()).isEqualTo(LATER.toString());
        StoredCustomer stored = api.stored(id);
        assertThat(stored.version()).isEqualTo(1L);
        assertThat(stored.updatedAt()).isEqualTo(LATER);
        assertThat(stored.createdAt()).isEqualTo(NOW);
        assertThat(stored.businessId()).isEqualTo(owner.businessId());
    }

    @Test
    void updateMayRemoveOneContactWhileTheOtherRemains() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, EMAIL);

        api.update(owner, id, NAME, PHONE, null, 0L).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").isEmpty()).andExpect(jsonPath("$.version").value(1));
        api.update(owner, id, NAME, null, EMAIL, 1L).andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").isEmpty()).andExpect(jsonPath("$.email").value(EMAIL));

        StoredCustomer stored = api.stored(id);
        assertThat(stored.phone()).isNull();
        assertThat(stored.email()).isEqualTo(EMAIL);
        assertThat(stored.version()).isEqualTo(2L);
    }

    @Test
    void updateRejectsRemovingTheFinalContactAndKeepsThePreviousRow() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, null);
        StoredCustomer before = api.stored(id);
        clock.set(LATER);

        ResultActions result = api.update(owner, id, NAME, null, "  ", 0L);

        assertValidationFields(result, "contact");
        assertThat(api.stored(id)).isEqualTo(before);
    }

    @Test
    void updateValidationNamesEveryInvalidFieldAndKeepsThePreviousRow() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, EMAIL);
        StoredCustomer before = api.stored(id);

        assertValidationFields(api.update(owner, id, "", "abc", "bad", 0L), "displayName", "phone", "email");

        assertThat(api.stored(id)).isEqualTo(before);
    }

    @Test
    void anUnchangedUpdateStillAdvancesTheVersionOnceAndTheTimestamp() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, EMAIL);
        clock.set(LATER);

        api.update(owner, id, NAME, PHONE, EMAIL, 0L).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.updatedAt").value(LATER.toString()));

        assertThat(api.stored(id).version()).isEqualTo(1L);
    }

    @Test
    void aStaleVersionIsAConflictThatNeverOverwritesNewerData() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, EMAIL);
        api.update(owner, id, "Нова версия", PHONE, EMAIL, 0L).andExpect(status().isOk());
        StoredCustomer current = api.stored(id);

        assertConcurrentUpdate(api.update(owner, id, "Остаряла версия", PHONE, EMAIL, 0L));
        assertConcurrentUpdate(api.update(owner, id, "Бъдеща версия", PHONE, EMAIL, 5L));

        assertThat(api.stored(id)).isEqualTo(current);
        assertThat(current.displayName()).isEqualTo("Нова версия");
    }

    @Test
    void theExpectedVersionIsRequired() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, null);

        assertValidationWithoutFields(api.update(owner, id, NAME, PHONE, null, null));
        assertValidationWithoutFields(api.update(owner, id, NAME, PHONE, null, -1L));
        assertThat(api.stored(id).version()).isZero();
    }

    @Test
    void updateOntoAnotherCustomersIdentifiersIsASafeConflictThatChangesNothing() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID subject = api.insertCustomer(owner.businessId(), "Субект", "+359887000001", "subject@example.test");
        UUID holder = api.insertCustomer(owner.businessId(), "Притежател", PHONE, EMAIL);
        StoredCustomer subjectBefore = api.stored(subject);
        StoredCustomer holderBefore = api.stored(holder);

        assertContactConflict(api.update(owner, subject, "Субект", PHONE, "subject@example.test", 0L), true, false);
        assertContactConflict(api.update(owner, subject, "Субект", "+359887000001", EMAIL, 0L), false, true);
        ResultActions both = api.update(owner, subject, "Субект", PHONE, EMAIL, 0L);
        assertContactConflict(both, true, true);

        String raw = body(api.update(owner, subject, "Субект", PHONE, EMAIL, 0L));
        assertThat(raw).doesNotContain(holder.toString()).doesNotContain("Притежател");
        assertThat(api.stored(subject)).isEqualTo(subjectBefore);
        assertThat(api.stored(holder)).isEqualTo(holderBefore);
        assertThat(api.customerCount(owner.businessId())).isEqualTo(2L);
    }

    @Test
    void anIdentifierMovesOnlyThroughTwoExplicitOperationsNeverImplicitly() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID first = api.insertCustomer(owner.businessId(), "Първи", PHONE, null);
        UUID second = api.insertCustomer(owner.businessId(), "Втори", null, EMAIL);

        assertContactConflict(api.update(owner, second, "Втори", PHONE, EMAIL, 0L), true, false);
        api.update(owner, first, "Първи", null, OTHER_EMAIL, 0L).andExpect(status().isOk());
        api.update(owner, second, "Втори", PHONE, EMAIL, 0L).andExpect(status().isOk());

        assertThat(api.stored(first).phone()).isNull();
        assertThat(api.stored(second).phone()).isEqualTo(PHONE);
        assertThat(api.customerCount(owner.businessId())).isEqualTo(2L);
    }

    @Test
    void aCustomerMayKeepItsOwnIdentifiersInAnUpdate() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, EMAIL);

        api.update(owner, id, "Ново име", PHONE, EMAIL, 0L).andExpect(status().isOk());
    }

    // ---- shared policy vectors -------------------------------------------------------------------

    static List<Vector> acceptedPhones() {
        return vectors("phone", "accepted");
    }

    static List<Vector> rejectedPhones() {
        return vectors("phone", "rejected");
    }

    static List<Vector> acceptedEmails() {
        return vectors("email", "accepted");
    }

    static List<Vector> rejectedEmails() {
        return vectors("email", "rejected");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedPhones")
    void everyAcceptedGoldenPhoneIsStoredCanonically(Vector vector) throws Exception {
        Actor owner = api.owner("ACTIVE");

        api.create(owner, NAME, vector.input(), null, true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value(vector.canonical()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedPhones")
    void everyRejectedGoldenPhoneIsAPhoneFieldError(Vector vector) throws Exception {
        Actor owner = api.owner("ACTIVE");

        assertValidationFields(api.create(owner, NAME, vector.input(), EMAIL, true), "phone");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedEmails")
    void everyAcceptedGoldenEmailIsStoredCanonically(Vector vector) throws Exception {
        Actor owner = api.owner("ACTIVE");

        api.create(owner, NAME, null, vector.input(), true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(vector.canonical()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedEmails")
    void everyRejectedGoldenEmailIsAnEmailFieldError(Vector vector) throws Exception {
        Actor owner = api.owner("ACTIVE");

        assertValidationFields(api.create(owner, NAME, PHONE, vector.input(), true), "email");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " ", "\t"})
    void aBlankNameIsADisplayNameError(String name) throws Exception {
        Actor owner = api.owner("ACTIVE");

        assertValidationFields(api.create(owner, name, PHONE, null, true), "displayName");
    }

    // ---- concurrency -----------------------------------------------------------------------------

    @Test
    void twoConcurrentUpdatesWithTheSameVersionHaveExactlyOneWinner() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, EMAIL);

        List<Integer> statuses = runConcurrently(
                () -> api.update(owner, id, "Победител едно", PHONE, EMAIL, 0L).andReturn().getResponse().getStatus(),
                () -> api.update(owner, id, "Победител две", PHONE, EMAIL, 0L).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        StoredCustomer stored = api.stored(id);
        assertThat(stored.version()).isEqualTo(1L);
        assertThat(stored.displayName()).startsWith("Победител");
    }

    @Test
    void twoConcurrentCreatesWithTheSamePhoneHaveExactlyOneWinnerAndNoDuplicate() throws Exception {
        Actor owner = api.owner("ACTIVE");

        List<Integer> statuses = runConcurrently(
                () -> api.create(owner, "Първи", PHONE, null, true).andReturn().getResponse().getStatus(),
                () -> api.create(owner, "Втори", PHONE, null, true).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(api.customerCount(owner.businessId())).isEqualTo(1L);
    }

    @Test
    void twoConcurrentCreatesWithTheSameEmailHaveExactlyOneWinnerAndNoDuplicate() throws Exception {
        Actor owner = api.owner("ACTIVE");

        List<Integer> statuses = runConcurrently(
                () -> api.create(owner, "Първи", null, EMAIL, true).andReturn().getResponse().getStatus(),
                () -> api.create(owner, "Втори", null, EMAIL, true).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(api.customerCount(owner.businessId())).isEqualTo(1L);
    }

    @Test
    void twoConcurrentUpdatesClaimingTheSameIdentifierHaveExactlyOneWinner() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID first = api.insertCustomer(owner.businessId(), "Първи", "+359887000001", null);
        UUID second = api.insertCustomer(owner.businessId(), "Втори", "+359887000002", null);

        List<Integer> statuses = runConcurrently(
                () -> api.update(owner, first, "Първи", PHONE, null, 0L).andReturn().getResponse().getStatus(),
                () -> api.update(owner, second, "Втори", PHONE, null, 0L).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.sql("SELECT count(*) FROM customer WHERE phone=:phone")
                .param("phone", PHONE).query(Long.class).single()).isEqualTo(1L);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private interface Call {
        int run() throws Exception;
    }

    private static List<Integer> runConcurrently(Call first, Call second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Call call : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call.run();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        }
    }

    private Map<String, Long> tableCounts() {
        Map<String, Long> counts = new java.util.TreeMap<>();
        for (String table : ACCOUNT_TABLES) {
            counts.put(table, jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
        }
        return counts;
    }

    record Vector(String id, String input, String canonical) {
        @Override
        public String toString() {
            return id;
        }
    }

    private static List<Vector> vectors(String kind, String group) {
        String relative = "shared-test-data/contact-policy-vectors.json";
        Path file = Files.isRegularFile(Path.of("..", relative)) ? Path.of("..", relative) : Path.of(relative);
        try {
            JsonNode root = JsonMapper.builder().build().readTree(Files.readString(file));
            List<Vector> vectors = new ArrayList<>();
            for (JsonNode node : root.get(kind).get(group)) {
                vectors.add(new Vector(
                        node.get("id").asString(),
                        node.get("input").asString(),
                        node.has("canonical") ? node.get("canonical").asString() : null));
            }
            return vectors;
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
