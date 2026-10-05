package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The approved SUSPENDED exception of the private Customer API: an owner may still update an
 * existing Customer, but nothing else changes. Creation stays rejected, every other rule of the
 * update (optimistic version, validation, uniqueness, tenant isolation, authorization) still
 * applies in a SUSPENDED Business, and no other Business mutation is affected. Synthetic data only.
 */
class BusinessCustomerSuspendedApiIntegrationTests extends BusinessCustomerApiIntegrationTest {

    @Test
    void anOwnerUpdatesAnExistingCustomerAndTheCanonicalProfileIsStored() throws Exception {
        Actor owner = api.owner("SUSPENDED");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, null);

        api.update(owner, id, " Анна   Петрова ", " 0888 123 456 ", " ANNA.P@Example.TEST ", 0L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Анна Петрова"))
                .andExpect(jsonPath("$.phone").value(OTHER_PHONE))
                .andExpect(jsonPath("$.email").value("anna.p@example.test"))
                .andExpect(jsonPath("$.version").value(1));

        var stored = api.stored(id);
        assertThat(stored.businessId()).isEqualTo(owner.businessId());
        assertThat(stored.phone()).isEqualTo(OTHER_PHONE);
    }

    @Test
    void creationStaysRejectedAndCreatesNoRowForEveryContactShape() throws Exception {
        Actor owner = api.owner("SUSPENDED");

        assertBusinessSuspended(api.create(owner, NAME, PHONE, null, true));
        assertBusinessSuspended(api.create(owner, NAME, null, EMAIL, true));
        assertBusinessSuspended(api.create(owner, NAME, PHONE, EMAIL, true));

        assertThat(api.customerCount(owner.businessId())).isZero();
    }

    @Test
    void draftAndActiveBusinessesBehaveExactlyAsBefore() throws Exception {
        for (String status : new String[] {"DRAFT", "ACTIVE"}) {
            Actor owner = api.owner(status);
            UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, null);

            api.create(owner, "Нов " + status, OTHER_PHONE, null, true).andExpect(status().isCreated());
            api.update(owner, id, "Променен", PHONE, null, 0L)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(1));
            assertThat(api.customerCount(owner.businessId())).isEqualTo(2L);
        }
    }

    @Test
    void theOptimisticVersionStillGuardsTheUpdate() throws Exception {
        Actor owner = api.owner("SUSPENDED");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, null);

        api.update(owner, id, "Първа промяна", PHONE, null, 0L).andExpect(status().isOk());
        assertConcurrentUpdate(api.update(owner, id, "Остаряла промяна", PHONE, null, 0L));
        assertValidationWithoutFields(api.update(owner, id, NAME, PHONE, null, null));

        assertThat(api.stored(id).displayName()).isEqualTo("Първа промяна");
        assertThat(api.stored(id).version()).isEqualTo(1L);
    }

    @Test
    void validationAndContactUniquenessStillApplyAndNothingChangesOnFailure() throws Exception {
        Actor owner = api.owner("SUSPENDED");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, null);
        api.insertCustomer(owner.businessId(), "Борис Петров", OTHER_PHONE, OTHER_EMAIL);

        assertValidationFields(api.update(owner, id, " ", "abc", "not-an-email", 0L),
                "displayName", "phone", "email");
        assertValidationFields(api.update(owner, id, NAME, null, null, 0L), "contact");
        assertContactConflict(api.update(owner, id, NAME, OTHER_PHONE, null, 0L), true, false);
        assertContactConflict(api.update(owner, id, NAME, PHONE, OTHER_EMAIL, 0L), false, true);

        var unchanged = api.stored(id);
        assertThat(unchanged.displayName()).isEqualTo(NAME);
        assertThat(unchanged.phone()).isEqualTo(PHONE);
        assertThat(unchanged.version()).isZero();
    }

    @Test
    void aForeignOrUnknownCustomerIsTheSameSafeNotFoundAndNothingLeaks() throws Exception {
        Actor owner = api.owner("SUSPENDED");
        Actor otherOwner = api.owner("ACTIVE");
        UUID foreign = api.insertCustomer(otherOwner.businessId(), NAME, PHONE, null);

        String foreignBody = body(api.update(owner, foreign, "Опит", PHONE, null, 0L)
                .andExpect(status().isNotFound()));
        String unknownBody = body(api.update(owner, UUID.randomUUID(), "Опит", PHONE, null, 0L)
                .andExpect(status().isNotFound()));
        assertNotFound(api.update(owner, foreign, "Опит", PHONE, null, 0L));
        assertThat(foreignBody).isEqualTo(unknownBody);
        assertThat(api.stored(foreign).displayName()).isEqualTo(NAME);
        assertThat(api.stored(foreign).version()).isZero();
    }

    @Test
    void unauthorizedCallersAreStillRejectedBeforeAnyUpdate() throws Exception {
        Actor owner = api.owner("SUSPENDED");
        UUID id = api.insertCustomer(owner.businessId(), NAME, PHONE, null);
        Actor manager = api.actor("SUSPENDED", "MANAGER", true, false, true);
        Actor staff = api.actor("SUSPENDED", "STAFF", true, false, true);
        Actor inactive = api.actor("SUSPENDED", "BUSINESS_OWNER", false, false, true);
        Actor noSelection = api.actor("SUSPENDED", "BUSINESS_OWNER", true, false, false);
        Actor platformAdminOnly = api.actor("SUSPENDED", null, true, true, true);

        UUID managerId = api.insertCustomer(manager.businessId(), NAME, PHONE, null);
        assertAccessDenied(api.update(manager, managerId, "Опит", PHONE, null, 0L));
        UUID staffId = api.insertCustomer(staff.businessId(), NAME, PHONE, null);
        assertAccessDenied(api.update(staff, staffId, "Опит", PHONE, null, 0L));
        UUID inactiveId = api.insertCustomer(inactive.businessId(), NAME, PHONE, null);
        assertActiveBusinessRequired(api.update(inactive, inactiveId, "Опит", PHONE, null, 0L));
        assertActiveBusinessRequired(api.update(noSelection, id, "Опит", PHONE, null, 0L));
        UUID adminId = api.insertCustomer(platformAdminOnly.businessId(), NAME, PHONE, null);
        assertActiveBusinessRequired(api.update(platformAdminOnly, adminId, "Опит", PHONE, null, 0L));

        for (UUID untouched : new UUID[] {id, managerId, staffId, inactiveId, adminId}) {
            assertThat(api.stored(untouched).displayName()).isEqualTo(NAME);
            assertThat(api.stored(untouched).version()).isZero();
        }
    }

    @Test
    void theExceptionDoesNotReachOtherBusinessMutations() throws Exception {
        Actor owner = api.owner("SUSPENDED");

        mvc.perform(post("/api/business/services")
                        .cookie(owner.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Масаж\",\"durationMinutes\":30,\"price\":\"20.00\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_SUSPENDED"));
        mvc.perform(post("/api/business/staff-members")
                        .cookie(owner.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Мария\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_SUSPENDED"));

        assertThat(jdbc.sql("SELECT count(*) FROM service").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM staff_member").query(Long.class).single()).isZero();
    }
}
