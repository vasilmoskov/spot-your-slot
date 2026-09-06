package bg.spotyourslot.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bg.spotyourslot.business.BusinessAdministration;
import bg.spotyourslot.business.BusinessApplicationException.BusinessNotFound;
import bg.spotyourslot.business.BusinessApplicationException.ConcurrentUpdate;
import bg.spotyourslot.business.BusinessApplicationException.InputField;
import bg.spotyourslot.business.BusinessApplicationException.InvalidInput;
import bg.spotyourslot.business.BusinessApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.business.BusinessRecords.BusinessDetails;
import bg.spotyourslot.business.BusinessRecords.BusinessPage;
import bg.spotyourslot.business.BusinessRecords.CreateBusinessCommand;
import bg.spotyourslot.business.BusinessRecords.UpdateBusinessCommand;
import bg.spotyourslot.business.domain.BusinessStatus;
import bg.spotyourslot.business.domain.BusinessType;
import bg.spotyourslot.identity.ActiveBusinessOwnerQuery;
import bg.spotyourslot.platform.MissingActiveOwner;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlatformBusinessServiceTests {
    private static final UUID BUSINESS_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000004c");
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    @Mock
    BusinessAdministration businesses;

    @Mock
    ActiveBusinessOwnerQuery activeOwners;

    private PlatformBusinessService service;

    @BeforeEach
    void setUp() {
        service = new PlatformBusinessService(businesses, activeOwners);
    }

    @Test
    void rejectsNullActivationBusinessIdBeforeDependencies() {
        assertThatThrownBy(() -> service.activateDraft(null, 0))
                .isInstanceOfSatisfying(InvalidInput.class, exception ->
                        assertThat(exception.field()).isEqualTo(InputField.BUSINESS_ID));
        verifyNoInteractions(businesses, activeOwners);
    }

    @Test
    void rejectsNegativeActivationVersionBeforeDependencies() {
        assertThatThrownBy(() -> service.activateDraft(BUSINESS_ID, -1))
                .isInstanceOfSatisfying(InvalidInput.class, exception ->
                        assertThat(exception.field()).isEqualTo(InputField.EXPECTED_VERSION));
        verifyNoInteractions(businesses, activeOwners);
    }

    @Test
    void propagatesNotFoundBeforeOwnerReadiness() {
        when(businesses.get(BUSINESS_ID)).thenThrow(new BusinessNotFound());

        assertThatThrownBy(() -> service.activateDraft(BUSINESS_ID, 0))
                .isInstanceOf(BusinessNotFound.class);
        verifyNoInteractions(activeOwners);
        verify(businesses, never()).activateDraft(BUSINESS_ID, 0);
    }

    @Test
    void rejectsStaleVersionBeforeOwnerReadiness() {
        when(businesses.get(BUSINESS_ID)).thenReturn(details(BusinessStatus.DRAFT, 2));

        assertThatThrownBy(() -> service.activateDraft(BUSINESS_ID, 1))
                .isInstanceOf(ConcurrentUpdate.class);
        verifyNoInteractions(activeOwners);
        verify(businesses, never()).activateDraft(BUSINESS_ID, 1);
    }

    @Test
    void rejectsWrongLifecycleBeforeOwnerReadiness() {
        when(businesses.get(BUSINESS_ID)).thenReturn(details(BusinessStatus.ACTIVE, 2));

        assertThatThrownBy(() -> service.activateDraft(BUSINESS_ID, 2))
                .isInstanceOfSatisfying(InvalidLifecycleTransition.class, exception -> {
                    assertThat(exception.currentStatus()).isEqualTo(BusinessStatus.ACTIVE);
                    assertThat(exception.targetStatus()).isEqualTo(BusinessStatus.ACTIVE);
                });
        verifyNoInteractions(activeOwners);
        verify(businesses, never()).activateDraft(BUSINESS_ID, 2);
    }

    @Test
    void rejectsMissingActiveOwnerAfterBusinessPreconditions() {
        when(businesses.get(BUSINESS_ID)).thenReturn(details(BusinessStatus.DRAFT, 2));
        when(activeOwners.hasActiveBusinessOwner(BUSINESS_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.activateDraft(BUSINESS_ID, 2))
                .isInstanceOf(MissingActiveOwner.class)
                .hasMessage("Business requires an active owner")
                .hasNoCause();
        verify(businesses, never()).activateDraft(BUSINESS_ID, 2);
    }

    @Test
    void activatesInRequiredCrossModuleOrder() {
        BusinessDetails draft = details(BusinessStatus.DRAFT, 2);
        BusinessDetails active = details(BusinessStatus.ACTIVE, 3);
        when(businesses.get(BUSINESS_ID)).thenReturn(draft);
        when(activeOwners.hasActiveBusinessOwner(BUSINESS_ID)).thenReturn(true);
        when(businesses.activateDraft(BUSINESS_ID, 2)).thenReturn(active);

        assertThat(service.activateDraft(BUSINESS_ID, 2)).isEqualTo(active);

        var order = inOrder(businesses, activeOwners);
        order.verify(businesses).get(BUSINESS_ID);
        order.verify(activeOwners).hasActiveBusinessOwner(BUSINESS_ID);
        order.verify(businesses).activateDraft(BUSINESS_ID, 2);
    }

    @Test
    void propagatesConcurrentConflictFromAtomicActivation() {
        when(businesses.get(BUSINESS_ID)).thenReturn(details(BusinessStatus.DRAFT, 2));
        when(activeOwners.hasActiveBusinessOwner(BUSINESS_ID)).thenReturn(true);
        when(businesses.activateDraft(BUSINESS_ID, 2)).thenThrow(new ConcurrentUpdate());

        assertThatThrownBy(() -> service.activateDraft(BUSINESS_ID, 2))
                .isInstanceOf(ConcurrentUpdate.class);
    }

    @Test
    void listDelegatesWithoutOwnerReadiness() {
        var page = new BusinessPage(List.of(), 0, 20, 0);
        when(businesses.list(0, 20)).thenReturn(page);

        assertThat(service.list(0, 20)).isSameAs(page);
        verifyNoInteractions(activeOwners);
    }

    @Test
    void getDelegatesWithoutOwnerReadiness() {
        BusinessDetails details = details(BusinessStatus.DRAFT, 0);
        when(businesses.get(BUSINESS_ID)).thenReturn(details);

        assertThat(service.get(BUSINESS_ID)).isSameAs(details);
        verifyNoInteractions(activeOwners);
    }

    @Test
    void createDelegatesWithoutOwnerReadiness() {
        CreateBusinessCommand command = org.mockito.Mockito.mock(CreateBusinessCommand.class);
        BusinessDetails created = details(BusinessStatus.DRAFT, 0);
        when(businesses.create(command)).thenReturn(created);

        assertThat(service.create(command)).isSameAs(created);
        verifyNoInteractions(activeOwners);
    }

    @Test
    void updateDelegatesWithoutOwnerReadiness() {
        UpdateBusinessCommand command = org.mockito.Mockito.mock(UpdateBusinessCommand.class);
        BusinessDetails updated = details(BusinessStatus.DRAFT, 1);
        when(businesses.update(BUSINESS_ID, command)).thenReturn(updated);

        assertThat(service.update(BUSINESS_ID, command)).isSameAs(updated);
        verifyNoInteractions(activeOwners);
    }

    @Test
    void suspensionDelegatesWithoutOwnerReadiness() {
        BusinessDetails suspended = details(BusinessStatus.SUSPENDED, 3);
        when(businesses.suspendActive(BUSINESS_ID, 2)).thenReturn(suspended);

        assertThat(service.suspendActive(BUSINESS_ID, 2)).isSameAs(suspended);
        verifyNoInteractions(activeOwners);
    }

    @Test
    void reactivationDelegatesWithoutOwnerReadiness() {
        BusinessDetails active = details(BusinessStatus.ACTIVE, 4);
        when(businesses.reactivateSuspended(BUSINESS_ID, 3)).thenReturn(active);

        assertThat(service.reactivateSuspended(BUSINESS_ID, 3)).isSameAs(active);
        verifyNoInteractions(activeOwners);
    }

    private BusinessDetails details(BusinessStatus status, long version) {
        return new BusinessDetails(
                BUSINESS_ID,
                "platform-business",
                "Platform Business",
                BusinessType.OTHER,
                status,
                "Europe/Sofia",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                version,
                NOW,
                NOW);
    }
}
