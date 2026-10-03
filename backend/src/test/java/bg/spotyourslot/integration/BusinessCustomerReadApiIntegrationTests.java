package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.customer.application.CustomerSqlRecording;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** List, body-based search, and detail of the private Customer API. All data is synthetic. */
class BusinessCustomerReadApiIntegrationTests extends BusinessCustomerApiIntegrationTest {
    // ---- response boundary -----------------------------------------------------------------------

    @Test
    void theListUsesTheDefaultsAndExposesExactlyTheApprovedKeys() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, EMAIL);

        var result = api.list(owner, null).andExpect(status().isOk());
        JsonNode page = json(result);

        assertThat(keys(page)).containsExactly("items", "page", "size", "total");
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(10);
        assertThat(page.get("total").asLong()).isEqualTo(1L);
        JsonNode item = page.get("items").get(0);
        assertThat(keys(item)).containsExactly("id", "displayName", "phone", "email");
        assertThat(item.get("id").asString()).isEqualTo(id.toString());
        assertThat(item.get("displayName").asString()).isEqualTo(NAME);
        assertThat(item.get("phone").asString()).isEqualTo(PHONE);
        assertThat(item.get("email").asString()).isEqualTo(EMAIL);
    }

    @Test
    void aMissingContactSerializesAsNullInTheListAndDetail() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, null);

        api.list(owner, null).andExpect(jsonPath("$.items[0].email").isEmpty());
        api.detail(owner, id).andExpect(jsonPath("$.email").isEmpty());
    }

    @Test
    void theDetailExposesExactlyTheApprovedKeysAndNoInternals() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, EMAIL);

        String raw = body(api.detail(owner, id).andExpect(status().isOk()));
        JsonNode detail = BusinessCustomerApiTestSupport.parse(raw);

        assertThat(keys(detail)).containsExactly(
                "id", "displayName", "phone", "email", "version", "createdAt", "updatedAt");
        assertThat(detail.get("version").asLong()).isZero();
        assertThat(detail.get("createdAt").asString()).isEqualTo(NOW.toString());
        assertThat(detail.get("updatedAt").asString()).isEqualTo(NOW.toString());
        assertThat(raw)
                .doesNotContain("businessId")
                .doesNotContain("normalized")
                .doesNotContain(owner.businessId().toString())
                .doesNotContain(owner.userId().toString())
                .doesNotContain("lifecycle")
                .doesNotContain("active")
                .doesNotContain("membership")
                .doesNotContain("appointment");
    }

    @Test
    void theListRowsOmitVersionTimestampsAndOperationalMetadata() throws Exception {
        Actor owner = api.owner("ACTIVE");
        api.insertCustomer(owner.businessId(), NAME, PHONE, EMAIL);

        String raw = body(api.list(owner, null));

        assertThat(raw)
                .doesNotContain("version")
                .doesNotContain("createdAt")
                .doesNotContain("updatedAt")
                .doesNotContain("normalized")
                .doesNotContain("businessId");
    }

    @Test
    void readResponsesAreNotCacheable() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, EMAIL);

        assertNoStore(api.list(owner, null));
        assertNoStore(api.search(owner, "Анна"));
        assertNoStore(api.detail(owner, id));
        assertNoStore(api.detail(owner, UUID.randomUUID()));
    }

    // ---- paging and sorting ----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {10, 25, 50})
    void everyAllowedPageSizeIsAcceptedAndEchoed(int size) throws Exception {
        Actor owner = api.owner("ACTIVE");
        for (int index = 0; index < 12; index++) {
            api.insertCustomer(owner.businessId(), "Клиент " + index, phoneNumber(index), null);
        }

        api.list(owner, "size=" + size).andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(size))
                .andExpect(jsonPath("$.total").value(12))
                .andExpect(jsonPath("$.items.length()").value(Math.min(size, 12)));
        api.search(owner, BusinessCustomerApiTestSupport.searchBody("Клиент", null, size, null, null), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(size))
                .andExpect(jsonPath("$.items.length()").value(Math.min(size, 12)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "page=-1", "page=abc", "page=1.5", "size=0", "size=1", "size=5", "size=11", "size=100",
        "size=-10", "size=abc", "sort=version", "sort=id", "sort=NAME", "sort=created_at",
        "sort=name%3Bdrop", "direction=sideways", "direction=ASC", "sort=name&direction=up"})
    void invalidPagingSortOrDirectionIsAGenericValidationErrorWithoutAField(String query)
            throws Exception {
        Actor owner = api.owner("ACTIVE");
        api.insertCustomer(owner.businessId(), NAME, PHONE, null);

        assertValidationWithoutFields(api.list(owner, query));
    }

    @Test
    void invalidBodyPagingSortOrDirectionIsRejectedTheSameWay() throws Exception {
        Actor owner = api.owner("ACTIVE");
        var invalid = new Object[][] {
            {-1, 10, "name", "asc"}, {0, 7, "name", "asc"}, {0, 10, "version", "asc"},
            {0, 10, "name", "sideways"}};

        for (Object[] values : invalid) {
            assertValidationWithoutFields(api.search(owner, BusinessCustomerApiTestSupport.searchBody(
                    "x", (Integer) values[0], (Integer) values[1], (String) values[2], (String) values[3]),
                    true));
        }
    }

    @Test
    void everyAllowedSortInBothDirectionsOrdersByTheApprovedRules() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID cvetan = api.insertCustomer(owner.businessId(), "Цветан", "+359887000001", null);
        UUID anna = api.insertCustomer(owner.businessId(), "Анна", "+359899000009", "z@example.test");
        UUID boris = api.insertCustomer(owner.businessId(), "Борис", null, "a@example.test");
        UUID david = api.insertCustomer(owner.businessId(), "Давид", null, "d@example.test");

        assertThat(ids(api.list(owner, null))).containsExactly(anna, boris, david, cvetan);
        assertThat(ids(api.list(owner, "sort=name&direction=asc"))).containsExactly(anna, boris, david, cvetan);
        assertThat(ids(api.list(owner, "sort=name&direction=desc"))).containsExactly(cvetan, david, boris, anna);
        // A missing value sorts last in both directions; ties fall back to the name.
        assertThat(ids(api.list(owner, "sort=phone&direction=asc"))).containsExactly(cvetan, anna, boris, david);
        assertThat(ids(api.list(owner, "sort=phone&direction=desc"))).containsExactly(anna, cvetan, boris, david);
        assertThat(ids(api.list(owner, "sort=email&direction=asc"))).containsExactly(boris, david, anna, cvetan);
        assertThat(ids(api.list(owner, "sort=email&direction=desc"))).containsExactly(anna, david, boris, cvetan);
    }

    @Test
    void sortingAppliesToSearchResultsThroughTheBody() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID first = api.insertCustomer(owner.businessId(), "Търсен Първи", "+359887000001", null);
        UUID second = api.insertCustomer(owner.businessId(), "Търсен Втори", "+359899000009", null);
        api.insertCustomer(owner.businessId(), "Друг", "+359895555111", null);

        assertThat(ids(api.search(owner,
                BusinessCustomerApiTestSupport.searchBody("търсен", null, null, "phone", "desc"), true)))
                .containsExactly(second, first);
        assertThat(ids(api.search(owner,
                BusinessCustomerApiTestSupport.searchBody("търсен", null, null, "name", "asc"), true)))
                .containsExactly(second, first);
    }

    @Test
    void pagesAreStableAndCoverEveryCustomerExactlyOnce() throws Exception {
        Actor owner = api.owner("ACTIVE");
        Set<UUID> inserted = new HashSet<>();
        for (int index = 0; index < 23; index++) {
            // Identical names make the id tie-breaker decisive.
            inserted.add(api.insertCustomer(owner.businessId(), "Същото Име", phoneNumber(index), null));
        }

        for (String sort : List.of("name", "phone", "email")) {
            for (String direction : List.of("asc", "desc")) {
                List<UUID> paged = new ArrayList<>();
                for (int page = 0; page < 3; page++) {
                    var result = api.list(owner, "size=10&page=" + page + "&sort=" + sort
                            + "&direction=" + direction).andExpect(status().isOk());
                    JsonNode node = json(result);
                    assertThat(node.get("total").asLong()).isEqualTo(23L);
                    assertThat(node.get("items")).hasSize(page < 2 ? 10 : 3);
                    node.get("items").forEach(item -> paged.add(UUID.fromString(item.get("id").asString())));
                }
                assertThat(paged).as(sort + " " + direction).doesNotHaveDuplicates();
                assertThat(new HashSet<>(paged)).isEqualTo(inserted);
                api.list(owner, "size=10&page=3&sort=" + sort + "&direction=" + direction)
                        .andExpect(jsonPath("$.items.length()").value(0))
                        .andExpect(jsonPath("$.total").value(23))
                        .andExpect(jsonPath("$.page").value(3));
            }
        }
    }

    @Test
    void anEmptyBusinessReturnsAnEmptyPageAndZeroTotal() throws Exception {
        Actor owner = api.owner("ACTIVE");

        api.list(owner, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10));
        api.search(owner, "ничто").andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));
    }

    // ---- search ----------------------------------------------------------------------------------

    @Test
    void searchMatchesTheNormalizedNameLowercaseEmailAndCanonicalPhone() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID byName = api.insertCustomer(owner.businessId(), "Мария Георгиева", "+359887111111", null);
        UUID byEmail = api.insertCustomer(owner.businessId(), "Само имейл", null, "unique.part@example.test");
        UUID byPhone = api.insertCustomer(owner.businessId(), "Само телефон", "+359899222222", null);

        assertThat(ids(api.search(owner, "ГЕОРГИЕВ"))).containsExactly(byName);
        assertThat(ids(api.search(owner, "UNIQUE.PART@"))).containsExactly(byEmail);
        assertThat(ids(api.search(owner, "0899 222 222"))).containsExactly(byPhone);
        assertThat(ids(api.search(owner, "+359899222222"))).containsExactly(byPhone);
        assertThat(ids(api.search(owner, "00359899"))).containsExactly(byPhone);
        assertThat(ids(api.search(owner, "+3598991"))).isEmpty();
        assertThat(ids(api.search(owner, "08992"))).containsExactly(byPhone);
    }

    @Test
    void aBlankOrMissingSearchTermReturnsEveryCustomer() throws Exception {
        Actor owner = api.owner("ACTIVE");
        api.insertCustomer(owner.businessId(), "Първи", PHONE, null);
        api.insertCustomer(owner.businessId(), "Втори", OTHER_PHONE, null);

        api.search(owner, "").andExpect(jsonPath("$.total").value(2));
        api.search(owner, "   ").andExpect(jsonPath("$.total").value(2));
        api.search(owner, BusinessCustomerApiTestSupport.emptyObject(), true)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void searchTermsWithWildcardOrMalformedPhoneCharactersNeverBroadenTheResult() throws Exception {
        Actor owner = api.owner("ACTIVE");
        api.insertCustomer(owner.businessId(), "Обикновен", PHONE, EMAIL);

        for (String term : new String[] {"%", "_", "\\", "+%", "+359%", "0_89", "089\\", ".*", "' OR 1=1 --"}) {
            api.search(owner, term).andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
        }
    }

    @Test
    void anOverlongSearchTermIsAGenericValidationErrorAndAHundredCodePointsIsAccepted() throws Exception {
        Actor owner = api.owner("ACTIVE");

        api.search(owner, "а".repeat(100)).andExpect(status().isOk());
        assertValidationWithoutFields(api.search(owner, "а".repeat(101)));
        assertValidationWithoutFields(api.search(owner, "  " + "x".repeat(101) + "  "));
    }

    @Test
    void aMalformedSearchBodyIsAGenericValidationError() throws Exception {
        Actor owner = api.owner("ACTIVE");

        assertValidationWithoutFields(mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/business/customers/search")
                        .cookie(owner.session())
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"search\": [1,2]")));
        assertValidationWithoutFields(mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/business/customers/search")
                        .cookie(owner.session())
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"size\":\"many\"}")));
    }

    @Test
    void theSearchTermNeverAppearsInTheRequestUriOrTheResponse() throws Exception {
        Actor owner = api.owner("ACTIVE");
        api.insertCustomer(owner.businessId(), "Сентинел Търсач", null, "sentinel.search@example.test");

        var result = api.search(owner, "sentinel.search").andExpect(status().isOk());
        var request = result.andReturn().getRequest();

        assertThat(request.getRequestURI()).isEqualTo("/api/business/customers/search");
        assertThat(request.getQueryString()).isNull();
        // MockMvc's csrf() post-processor is the only parameter (a real client sends a header).
        assertThat(request.getParameterMap().keySet()).isSubsetOf("_csrf");
        assertThat(result.andReturn().getResponse().getHeaderNames()).doesNotContain("Location");
        assertThat(result.andReturn().getResponse().getRedirectedUrl()).isNull();
        assertThat(body(result)).doesNotContain("\"search\"");
    }

    // ---- detail ----------------------------------------------------------------------------------

    @Test
    void aMalformedCustomerIdIsAGenericValidationError() throws Exception {
        Actor owner = api.owner("ACTIVE");

        assertValidationWithoutFields(mvc.perform(
                get("/api/business/customers/not-a-uuid").cookie(owner.session())));
    }

    // ---- statement counts ------------------------------------------------------------------------

    @Test
    void listAndSearchRunAConstantNumberOfCustomerStatementsWhateverThePageOrResultSize()
            throws Exception {
        Actor owner = api.owner("ACTIVE");
        for (int index = 0; index < 60; index++) {
            api.insertCustomer(owner.businessId(), "Брой " + index, phoneNumber(index), null);
        }

        List<Integer> counts = new ArrayList<>();
        for (String query : new String[] {"size=10", "size=25", "size=50", "size=50&page=1&sort=phone"}) {
            CustomerSqlRecording.clear();
            api.list(owner, query).andExpect(status().isOk());
            counts.add(CustomerSqlRecording.statementsOfCurrentThread().size());
        }
        for (int size : new int[] {10, 25, 50}) {
            CustomerSqlRecording.clear();
            api.search(owner, BusinessCustomerApiTestSupport.searchBody("брой", null, size, null, null), true)
                    .andExpect(status().isOk());
            counts.add(CustomerSqlRecording.statementsOfCurrentThread().size());
        }
        CustomerSqlRecording.clear();
        api.search(owner, "нищо подобно").andExpect(status().isOk());
        counts.add(CustomerSqlRecording.statementsOfCurrentThread().size());

        // One page statement and one count statement, never one per row.
        assertThat(counts).containsOnly(2);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String phoneNumber(int index) {
        return String.format("+3598955%05d", 10000 + index);
    }

    private static List<UUID> ids(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        List<UUID> ids = new ArrayList<>();
        json(result.andExpect(status().isOk())).get("items")
                .forEach(item -> ids.add(UUID.fromString(item.get("id").asString())));
        return ids;
    }
}
