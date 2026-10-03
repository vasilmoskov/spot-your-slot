package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.StoredCustomer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Authentication, owner-only authorization, tenant isolation, lifecycle, CSRF, and the endpoint
 * inventory of the private Customer API.
 */
class BusinessCustomerAuthorizationApiIntegrationTests extends BusinessCustomerApiIntegrationTest {
    private static final String SENTINEL_NAME = "Сентинел Роутинг";
    private static final UUID MISSING_ID = UUID.fromString("00000000-0000-0000-0000-000000000404");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    // ---- authentication and selection ----------------------------------------------------------

    @Test
    void everyEndpointRequiresAuthentication() throws Exception {
        assertUnauthenticated(mvc.perform(get("/api/business/customers")));
        assertUnauthenticated(mvc.perform(get("/api/business/customers/{id}", MISSING_ID)));
        assertUnauthenticated(mvc.perform(post("/api/business/customers/search")
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"search\":\"x\"}")));
        assertUnauthenticated(mvc.perform(post("/api/business/customers")
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")));
        assertUnauthenticated(mvc.perform(put("/api/business/customers/{id}", MISSING_ID)
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")));
    }

    @Test
    void anAbsentBusinessSelectionIsRequiredOnEveryEndpoint() throws Exception {
        Actor noSelection = api.actor("ACTIVE", "BUSINESS_OWNER", true, false, false);

        assertActiveBusinessRequired(api.list(noSelection, null));
        assertActiveBusinessRequired(api.search(noSelection, "x"));
        assertActiveBusinessRequired(api.detail(noSelection, MISSING_ID));
        assertActiveBusinessRequired(api.create(noSelection, NAME, PHONE, null, true));
        assertActiveBusinessRequired(api.update(noSelection, MISSING_ID, NAME, PHONE, null, 0L));
    }

    // ---- owner-only roles ------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "STAFF"})
    void managerAndStaffAreDeniedOnEveryEndpoint(String role) throws Exception {
        Actor denied = api.actor("ACTIVE", role, true, false, true);
        UUID customerId = api.insertCustomer(denied.businessId(), NAME, PHONE, null);

        assertAccessDenied(api.list(denied, null));
        assertAccessDenied(api.search(denied, "x"));
        assertAccessDenied(api.detail(denied, customerId));
        assertAccessDenied(api.create(denied, "Нов", OTHER_PHONE, null, true));
        assertAccessDenied(api.update(denied, customerId, "Променен", PHONE, null, 0L));

        StoredCustomer unchanged = api.stored(customerId);
        assertThat(unchanged.displayName()).isEqualTo(NAME);
        assertThat(unchanged.version()).isZero();
        assertThat(api.customerCount(denied.businessId())).isEqualTo(1L);
    }

    @Test
    void inactiveMissingForeignAndPlatformAuthorityAloneAreRejectedBeforeAnyCustomerAccess()
            throws Exception {
        Actor inactive = api.actor("ACTIVE", "BUSINESS_OWNER", false, false, true);
        Actor missing = api.actor("ACTIVE", null, false, false, true);
        Actor foreign = api.actorWithForeignMembership();
        Actor platformOnly = api.actor("ACTIVE", null, false, true, true);

        for (Actor actor : List.of(inactive, missing, foreign, platformOnly)) {
            assertActiveBusinessRequired(api.list(actor, null));
            assertActiveBusinessRequired(api.search(actor, "x"));
            assertActiveBusinessRequired(api.detail(actor, MISSING_ID));
            assertActiveBusinessRequired(api.create(actor, NAME, PHONE, null, true));
            assertActiveBusinessRequired(api.update(actor, MISSING_ID, NAME, PHONE, null, 0L));
            assertThat(api.customerCount(actor.businessId())).isZero();
        }
    }

    @Test
    void aPlatformAdministratorWhoIsAlsoAnOwnerIsAllowedAsAnOwner() throws Exception {
        Actor platformOwner = api.actor("ACTIVE", "BUSINESS_OWNER", true, true, true);

        api.create(platformOwner, NAME, PHONE, null, true).andExpect(status().isCreated());
    }

    // ---- tenant isolation ------------------------------------------------------------------------

    @Test
    void anOwnerReachesOnlyTheirOwnBusinessCustomers() throws Exception {
        Actor ownerA = api.owner("ACTIVE");
        Actor ownerB = api.owner("ACTIVE");
        UUID customerA = api.insertCustomer(ownerA.businessId(), "Клиент на А", PHONE, EMAIL);
        UUID customerB = api.insertCustomer(ownerB.businessId(), "Клиент на Б", OTHER_PHONE, OTHER_EMAIL);

        api.detail(ownerA, customerA).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Клиент на А"));
        api.detail(ownerB, customerB).andExpect(status().isOk());

        api.list(ownerA, null)
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(customerA.toString()));
        api.list(ownerB, null)
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(customerB.toString()));
        api.search(ownerA, "Клиент на Б").andExpect(jsonPath("$.total").value(0));
        api.search(ownerA, OTHER_PHONE).andExpect(jsonPath("$.total").value(0));
        api.search(ownerA, "boris@").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void aForeignCustomerIdIsIndistinguishableFromAnUnknownOne() throws Exception {
        Actor ownerA = api.owner("ACTIVE");
        Actor ownerB = api.owner("ACTIVE");
        UUID foreign = api.insertCustomer(ownerB.businessId(), "Чужд клиент", OTHER_PHONE, null);

        String foreignRead = body(api.detail(ownerA, foreign));
        String unknownRead = body(api.detail(ownerA, MISSING_ID));
        assertNotFound(api.detail(ownerA, foreign));
        assertNotFound(api.detail(ownerA, MISSING_ID));
        assertThat(foreignRead).isEqualTo(unknownRead);

        String foreignWrite = body(api.update(ownerA, foreign, "Присвоен", PHONE, null, 0L));
        String unknownWrite = body(api.update(ownerA, MISSING_ID, "Присвоен", PHONE, null, 0L));
        assertNotFound(api.update(ownerA, foreign, "Присвоен", PHONE, null, 0L));
        assertThat(foreignWrite).isEqualTo(unknownWrite);

        StoredCustomer untouched = api.stored(foreign);
        assertThat(untouched.displayName()).isEqualTo("Чужд клиент");
        assertThat(untouched.version()).isZero();
        assertThat(untouched.businessId()).isEqualTo(ownerB.businessId());
    }

    @Test
    void aClientSuppliedBusinessIdIsIgnoredEverywhere() throws Exception {
        Actor ownerA = api.owner("ACTIVE");
        Actor ownerB = api.owner("ACTIVE");

        var createBody = BusinessCustomerApiTestSupport.body(NAME, PHONE, null, null);
        createBody.put("businessId", ownerB.businessId().toString());
        String created = body(api.createJson(ownerA, createBody, true).andExpect(status().isCreated()));
        UUID id = UUID.fromString(BusinessCustomerApiTestSupport.parse(created).get("id").asString());

        assertThat(api.stored(id).businessId()).isEqualTo(ownerA.businessId());
        assertThat(api.customerCount(ownerB.businessId())).isZero();
        api.list(ownerA, "businessId=" + ownerB.businessId()).andExpect(jsonPath("$.total").value(1));
        api.list(ownerB, "businessId=" + ownerA.businessId()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void theSameContactMayExistInTwoBusinessesAsTwoUnrelatedCustomers() throws Exception {
        Actor ownerA = api.owner("ACTIVE");
        Actor ownerB = api.owner("ACTIVE");

        api.create(ownerA, NAME, PHONE, EMAIL, true).andExpect(status().isCreated());
        api.create(ownerB, NAME, PHONE, EMAIL, true).andExpect(status().isCreated());

        assertThat(api.customerCount(ownerA.businessId())).isEqualTo(1L);
        assertThat(api.customerCount(ownerB.businessId())).isEqualTo(1L);
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ACTIVE"})
    void draftAndActiveBusinessesAllowReadsSearchCreateAndUpdate(String status) throws Exception {
        Actor owner = api.owner(status);
        UUID seeded = api.insertCustomer(owner.businessId(), NAME, PHONE, null);

        api.list(owner, null).andExpect(status().isOk());
        api.search(owner, "Анна").andExpect(status().isOk());
        api.detail(owner, seeded).andExpect(status().isOk());
        api.create(owner, "Нов клиент", OTHER_PHONE, null, true).andExpect(status().isCreated());
        api.update(owner, seeded, "Променен", PHONE, null, 0L).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void aSuspendedBusinessAllowsReadsAndSearchAndRejectsCreateAndUpdate() throws Exception {
        Actor owner = api.owner("SUSPENDED");
        UUID seeded = api.insertCustomer(owner.businessId(), NAME, PHONE, EMAIL);

        api.list(owner, null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        api.search(owner, "Анна").andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        api.detail(owner, seeded).andExpect(status().isOk());

        assertBusinessSuspended(api.create(owner, "Нов клиент", OTHER_PHONE, null, true));
        assertBusinessSuspended(api.update(owner, seeded, "Променен", PHONE, EMAIL, 0L));

        assertThat(api.customerCount(owner.businessId())).isEqualTo(1L);
        StoredCustomer unchanged = api.stored(seeded);
        assertThat(unchanged.displayName()).isEqualTo(NAME);
        assertThat(unchanged.version()).isZero();
    }

    @Test
    void aSuspendedBusinessRejectionPrecedesValidationAndNotFound() throws Exception {
        Actor owner = api.owner("SUSPENDED");

        assertBusinessSuspended(api.create(owner, " ", null, null, true));
        assertBusinessSuspended(api.update(owner, MISSING_ID, " ", null, null, null));
    }

    // ---- CSRF and method surface -----------------------------------------------------------------

    @Test
    void csrfProtectsEveryPostAndPutEndpoint() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID seeded = api.insertCustomer(owner.businessId(), NAME, PHONE, null);

        assertAccessDenied(api.search(owner, BusinessCustomerApiTestSupport.searchBody("x"), false));
        assertAccessDenied(api.create(owner, "Без токен", OTHER_PHONE, null, false));
        assertAccessDenied(api.updateJson(
                owner, seeded, BusinessCustomerApiTestSupport.body("Без токен", PHONE, null, 0L), false));

        assertThat(api.customerCount(owner.businessId())).isEqualTo(1L);
        assertThat(api.stored(seeded).version()).isZero();
    }

    @Test
    void deleteOfAValidCustomerIsAMethodNotAllowedWithAFixedSafeProblemAndChangesNothing()
            throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID seeded = api.insertCustomer(owner.businessId(), SENTINEL_NAME, PHONE, EMAIL);
        StoredCustomer before = api.stored(seeded);

        ResultActions result = mvc.perform(
                        delete("/api/business/customers/{id}", seeded).cookie(owner.session()).with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.detail").value("Методът не е разрешен за този адрес."))
                .andExpect(jsonPath("$.instance").value("/api"));

        assertRoutingProblemIsSafe(body(result), seeded);
        assertThat(api.stored(seeded)).isEqualTo(before);
        assertThat(api.customerCount(owner.businessId())).isEqualTo(1L);
    }

    @Test
    void anUnapprovedMergePathIsANotFoundWithAFixedSafeProblemAndChangesNothing() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID seeded = api.insertCustomer(owner.businessId(), SENTINEL_NAME, PHONE, EMAIL);
        StoredCustomer before = api.stored(seeded);

        ResultActions result = mvc.perform(post("/api/business/customers/{id}/merge", seeded)
                        .cookie(owner.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + SENTINEL_NAME + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Адресът не е намерен."))
                .andExpect(jsonPath("$.instance").value("/api"));

        assertRoutingProblemIsSafe(body(result), seeded);
        assertThat(api.stored(seeded)).isEqualTo(before);
        assertThat(api.customerCount(owner.businessId())).isEqualTo(1L);
    }

    @Test
    void routingErrorsDoNotWeakenAuthenticationOrCsrf() throws Exception {
        Actor owner = api.owner("ACTIVE");
        UUID seeded = api.insertCustomer(owner.businessId(), NAME, PHONE, null);

        // Anonymous callers are rejected before routing; a missing CSRF token is still denied.
        assertUnauthenticated(mvc.perform(delete("/api/business/customers/{id}", seeded).with(csrf())));
        assertUnauthenticated(mvc.perform(post("/api/business/customers/{id}/merge", seeded).with(csrf())));
        assertAccessDenied(mvc.perform(
                delete("/api/business/customers/{id}", seeded).cookie(owner.session())));
        assertAccessDenied(mvc.perform(
                post("/api/business/customers/{id}/merge", seeded).cookie(owner.session())));
        assertThat(api.stored(seeded).displayName()).isEqualTo(NAME);
    }

    @Test
    void getAndPutOnTheSearchPathAreMethodNotAllowedBecauseOnlyPostExists() throws Exception {
        Actor owner = api.owner("ACTIVE");
        api.insertCustomer(owner.businessId(), SENTINEL_NAME, PHONE, null);

        for (var request : List.of(
                get("/api/business/customers/search?search=" + "Анна"),
                put("/api/business/customers/search").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"x\"}"))) {
            ResultActions result = mvc.perform(request.cookie(owner.session()))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(header().string("Allow", "POST"))
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                    .andExpect(jsonPath("$.instance").value("/api"));
            assertRoutingProblemIsSafe(body(result), null);
        }
    }

    @Test
    void otherMalformedCustomerIdsRemainAGenericValidationError() throws Exception {
        Actor owner = api.owner("ACTIVE");

        assertValidationWithoutFields(mvc.perform(
                get("/api/business/customers/not-a-uuid").cookie(owner.session())));
        assertValidationWithoutFields(mvc.perform(
                get("/api/business/customers/searching").cookie(owner.session())));
        assertValidationWithoutFields(mvc.perform(
                put("/api/business/customers/not-a-uuid").cookie(owner.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")));
    }

    private void assertRoutingProblemIsSafe(String problem, UUID customerId) {
        assertThat(problem)
                .doesNotContain("Сентинел")
                .doesNotContain("Sentinel")
                .doesNotContain("/api/business/customers")
                .doesNotContain("merge")
                .doesNotContain("SQL")
                .doesNotContain("Exception")
                .doesNotContain("bg.spotyourslot")
                .doesNotContain("\tat ");
        if (customerId != null) {
            assertThat(problem).doesNotContain(customerId.toString());
        }
    }

    // ---- endpoint inventory ----------------------------------------------------------------------

    @Test
    void exactlyTheFiveApprovedPrivateEndpointsExistAndNoneIsPublic() {
        Set<String> customerEndpoints = new TreeSet<>();
        List<String> outsideTheCustomerPrefix = new ArrayList<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            Set<String> patterns = info.getPatternValues();
            boolean customerHandler = handler.getBeanType().getName().startsWith("bg.spotyourslot.customer.");
            for (String pattern : patterns) {
                String methods = info.getMethodsCondition().getMethods().toString();
                if (customerHandler) {
                    customerEndpoints.add(methods + " " + pattern);
                } else if (pattern.toLowerCase().contains("customer")) {
                    outsideTheCustomerPrefix.add(pattern);
                }
                if (pattern.startsWith("/api/public") || pattern.equals("/{slug}")) {
                    assertThat(customerHandler).as(pattern).isFalse();
                }
            }
        }

        assertThat(customerEndpoints).containsExactlyInAnyOrder(
                "[GET] /api/business/customers",
                "[POST] /api/business/customers/search",
                "[GET] /api/business/customers/{customerId:^(?!search$).+}",
                "[POST] /api/business/customers",
                "[PUT] /api/business/customers/{customerId:^(?!search$).+}");
        assertThat(outsideTheCustomerPrefix).isEmpty();
    }
}
