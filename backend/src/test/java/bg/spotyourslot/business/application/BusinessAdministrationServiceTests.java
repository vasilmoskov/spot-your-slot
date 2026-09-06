package bg.spotyourslot.business.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bg.spotyourslot.business.BusinessApplicationException.BusinessNotFound;
import bg.spotyourslot.business.BusinessApplicationException.BusinessSlugConflict;
import bg.spotyourslot.business.BusinessApplicationException.ConcurrentUpdate;
import bg.spotyourslot.business.BusinessApplicationException.InputField;
import bg.spotyourslot.business.BusinessApplicationException.InvalidInput;
import bg.spotyourslot.business.BusinessApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.business.BusinessRecords.BusinessDetails;
import bg.spotyourslot.business.BusinessRecords.CreateBusinessCommand;
import bg.spotyourslot.business.BusinessRecords.UpdateBusinessCommand;
import bg.spotyourslot.business.application.BusinessInputValidator.PageInput;
import bg.spotyourslot.business.domain.BusinessSlug;
import bg.spotyourslot.business.domain.BusinessStatus;
import bg.spotyourslot.business.domain.BusinessTimezone;
import bg.spotyourslot.business.domain.BusinessType;
import bg.spotyourslot.business.infrastructure.BusinessProfileUpdateRow;
import bg.spotyourslot.business.infrastructure.BusinessRow;
import bg.spotyourslot.business.infrastructure.BusinessStore;
import bg.spotyourslot.business.infrastructure.NewBusinessRow;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class BusinessAdministrationServiceTests {
    private static final UUID BUSINESS_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000041");
    private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");

    @Mock
    BusinessStore store;

    @Mock
    BusinessInputValidator validator;

    private BusinessAdministrationService service;

    @BeforeEach
    void setUp() {
        service = new BusinessAdministrationService(
                store, validator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listsValidatedPageAndMapsEverySummaryField() {
        BusinessRow row = row(BusinessStatus.ACTIVE, 3, NOW.minusSeconds(60), NOW);
        when(validator.validatePage(2, 10)).thenReturn(new PageInput(2, 10));
        when(store.list(2, 10)).thenReturn(List.of(row));
        when(store.count()).thenReturn(21L);

        var page = service.list(2, 10);

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(21);
        assertThat(page.businesses()).singleElement().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo(row.id());
            assertThat(summary.slug()).isEqualTo(row.slug().value());
            assertThat(summary.displayName()).isEqualTo(row.displayName());
            assertThat(summary.businessType()).isEqualTo(row.businessType());
            assertThat(summary.status()).isEqualTo(row.status());
            assertThat(summary.timezone()).isEqualTo(row.timezone().value());
            assertThat(summary.version()).isEqualTo(row.version());
            assertThat(summary.createdAt()).isEqualTo(row.createdAt());
            assertThat(summary.updatedAt()).isEqualTo(row.updatedAt());
        });
    }

    @Test
    void getsBusinessAndMapsEveryDetailField() {
        BusinessRow row = row(BusinessStatus.DRAFT, 0, NOW, NOW);
        when(validator.validateBusinessId(BUSINESS_ID)).thenReturn(BUSINESS_ID);
        when(store.findById(BUSINESS_ID)).thenReturn(Optional.of(row));

        assertCompleteDetails(service.get(BUSINESS_ID), row);
    }

    @Test
    void reportsMissingBusiness() {
        when(validator.validateBusinessId(BUSINESS_ID)).thenReturn(BUSINESS_ID);
        when(store.findById(BUSINESS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(BUSINESS_ID))
                .isInstanceOf(BusinessNotFound.class);
    }

    @Test
    void createsFromValidatedCommandUsingGeneratedIdAndFixedClock() {
        CreateBusinessCommand raw = createCommand(" Raw ");
        CreateBusinessCommand validated = createCommand("created-business");
        BusinessRow persisted = row(BusinessStatus.DRAFT, 0, NOW, NOW);
        when(validator.validateCreate(raw)).thenReturn(validated);
        when(store.create(any(NewBusinessRow.class))).thenReturn(persisted);

        BusinessDetails result = service.create(raw);

        var captor = ArgumentCaptor.forClass(NewBusinessRow.class);
        verify(store).create(captor.capture());
        NewBusinessRow creation = captor.getValue();
        assertThat(creation.id()).isNotNull();
        assertThat(creation.slug().value()).isEqualTo(validated.slug());
        assertThat(creation.displayName()).isEqualTo(validated.displayName());
        assertThat(creation.businessType()).isEqualTo(validated.businessType());
        assertThat(creation.timezone().value()).isEqualTo(validated.timezone());
        assertThat(creation.description()).isEqualTo(validated.description());
        assertThat(creation.city()).isEqualTo(validated.city());
        assertThat(creation.postalCode()).isEqualTo(validated.postalCode());
        assertThat(creation.street()).isEqualTo(validated.street());
        assertThat(creation.streetNumber()).isEqualTo(validated.streetNumber());
        assertThat(creation.addressDetails()).isEqualTo(validated.addressDetails());
        assertThat(creation.phone()).isEqualTo(validated.phone());
        assertThat(creation.contactEmail()).isEqualTo(validated.contactEmail());
        assertThat(creation.createdAt()).isEqualTo(NOW);
        assertCompleteDetails(result, persisted);
    }

    @Test
    void mapsConfirmedUniqueViolationOnCreateWithoutRetainingDatabaseCause() {
        CreateBusinessCommand command = createCommand("duplicate");
        var databaseFailure = new DataIntegrityViolationException(
                "internal", new SQLException("database detail", "23505"));
        when(validator.validateCreate(command)).thenReturn(command);
        when(store.create(any())).thenThrow(databaseFailure);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(BusinessSlugConflict.class)
                .hasNoCause();
    }

    @Test
    void rethrowsUnrelatedIntegrityFailureOnCreateUnchanged() {
        CreateBusinessCommand command = createCommand("valid");
        var databaseFailure = new DataIntegrityViolationException(
                "internal", new SQLException("database detail", "23503"));
        when(validator.validateCreate(command)).thenReturn(command);
        when(store.create(any())).thenThrow(databaseFailure);

        assertThatThrownBy(() -> service.create(command)).isSameAs(databaseFailure);
    }

    @Test
    void updatesFromValidatedCommandAfterRetrievalAndUsesFixedClock() {
        UpdateBusinessCommand raw = updateCommand(" Raw ", 4);
        UpdateBusinessCommand validated = updateCommand("updated-business", 4);
        BusinessRow current = row(BusinessStatus.ACTIVE, 4, NOW.minusSeconds(60), NOW.minusSeconds(1));
        BusinessRow persisted = row(BusinessStatus.ACTIVE, 5, current.createdAt(), NOW);
        when(validator.validateBusinessId(BUSINESS_ID)).thenReturn(BUSINESS_ID);
        when(validator.validateUpdate(raw)).thenReturn(validated);
        when(store.findById(BUSINESS_ID)).thenReturn(Optional.of(current));
        when(store.updateProfile(any(), any())).thenReturn(Optional.of(persisted));

        BusinessDetails result = service.update(BUSINESS_ID, raw);

        var captor = ArgumentCaptor.forClass(BusinessProfileUpdateRow.class);
        verify(store).updateProfile(org.mockito.Mockito.eq(BUSINESS_ID), captor.capture());
        BusinessProfileUpdateRow update = captor.getValue();
        assertThat(update.slug().value()).isEqualTo(validated.slug());
        assertThat(update.displayName()).isEqualTo(validated.displayName());
        assertThat(update.businessType()).isEqualTo(validated.businessType());
        assertThat(update.timezone().value()).isEqualTo(validated.timezone());
        assertThat(update.description()).isEqualTo(validated.description());
        assertThat(update.city()).isEqualTo(validated.city());
        assertThat(update.postalCode()).isEqualTo(validated.postalCode());
        assertThat(update.street()).isEqualTo(validated.street());
        assertThat(update.streetNumber()).isEqualTo(validated.streetNumber());
        assertThat(update.addressDetails()).isEqualTo(validated.addressDetails());
        assertThat(update.phone()).isEqualTo(validated.phone());
        assertThat(update.contactEmail()).isEqualTo(validated.contactEmail());
        assertThat(update.expectedVersion()).isEqualTo(4);
        assertThat(update.updatedAt()).isEqualTo(NOW);
        assertCompleteDetails(result, persisted);
    }

    @Test
    void rejectsStaleProfileVersionBeforeMutation() {
        UpdateBusinessCommand command = updateCommand("updated", 3);
        BusinessRow current = row(BusinessStatus.ACTIVE, 4, NOW, NOW);
        stubValidatedUpdate(command, current);

        assertThatThrownBy(() -> service.update(BUSINESS_ID, command))
                .isInstanceOf(ConcurrentUpdate.class);
        verify(store, never()).updateProfile(any(), any());
    }

    @Test
    void translatesEmptyAtomicProfileUpdateToConcurrentUpdate() {
        UpdateBusinessCommand command = updateCommand("updated", 4);
        BusinessRow current = row(BusinessStatus.ACTIVE, 4, NOW, NOW);
        stubValidatedUpdate(command, current);
        when(store.updateProfile(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(BUSINESS_ID, command))
                .isInstanceOf(ConcurrentUpdate.class);
    }

    @Test
    void mapsConfirmedUniqueViolationOnProfileUpdate() {
        UpdateBusinessCommand command = updateCommand("duplicate", 4);
        BusinessRow current = row(BusinessStatus.ACTIVE, 4, NOW, NOW);
        var databaseFailure = new DataIntegrityViolationException(
                "internal", new SQLException("database detail", "23505"));
        stubValidatedUpdate(command, current);
        when(store.updateProfile(any(), any())).thenThrow(databaseFailure);

        assertThatThrownBy(() -> service.update(BUSINESS_ID, command))
                .isInstanceOf(BusinessSlugConflict.class)
                .hasNoCause();
    }

    @Test
    void rethrowsUnrelatedIntegrityFailureOnProfileUpdateUnchanged() {
        UpdateBusinessCommand command = updateCommand("updated", 4);
        BusinessRow current = row(BusinessStatus.ACTIVE, 4, NOW, NOW);
        var databaseFailure = new DataIntegrityViolationException(
                "internal", new SQLException("database detail", "23514"));
        stubValidatedUpdate(command, current);
        when(store.updateProfile(any(), any())).thenThrow(databaseFailure);

        assertThatThrownBy(() -> service.update(BUSINESS_ID, command))
                .isSameAs(databaseFailure);
    }

    @Test
    void activateDraftUsesOnlyDraftToActiveTransition() {
        assertLifecycleTransition(
                BusinessStatus.DRAFT,
                BusinessStatus.ACTIVE,
                () -> service.activateDraft(BUSINESS_ID, 2));
    }

    @Test
    void suspendActiveUsesOnlyActiveToSuspendedTransition() {
        assertLifecycleTransition(
                BusinessStatus.ACTIVE,
                BusinessStatus.SUSPENDED,
                () -> service.suspendActive(BUSINESS_ID, 2));
    }

    @Test
    void reactivateSuspendedUsesOnlySuspendedToActiveTransition() {
        assertLifecycleTransition(
                BusinessStatus.SUSPENDED,
                BusinessStatus.ACTIVE,
                () -> service.reactivateSuspended(BUSINESS_ID, 2));
    }

    @Test
    void lifecycleChecksStaleVersionBeforeLifecycleValidity() {
        BusinessRow current = row(BusinessStatus.ACTIVE, 3, NOW, NOW);
        stubValidatedLifecycle(current, 2);

        assertThatThrownBy(() -> service.activateDraft(BUSINESS_ID, 2))
                .isInstanceOf(ConcurrentUpdate.class);
        verify(store, never()).transition(any(), any(), any(), anyLong(), any());
    }

    @Test
    void rejectsInvalidNamedLifecycleOperationBeforeMutation() {
        BusinessRow current = row(BusinessStatus.ACTIVE, 2, NOW, NOW);
        stubValidatedLifecycle(current, 2);

        assertThatThrownBy(() -> service.activateDraft(BUSINESS_ID, 2))
                .isInstanceOfSatisfying(InvalidLifecycleTransition.class, exception -> {
                    assertThat(exception.currentStatus()).isEqualTo(BusinessStatus.ACTIVE);
                    assertThat(exception.targetStatus()).isEqualTo(BusinessStatus.ACTIVE);
                });
        verify(store, never()).transition(any(), any(), any(), anyLong(), any());
    }

    @Test
    void translatesEmptyAtomicLifecycleMutationToConcurrentUpdate() {
        BusinessRow current = row(BusinessStatus.DRAFT, 2, NOW, NOW);
        stubValidatedLifecycle(current, 2);
        when(store.transition(any(), any(), any(), anyLong(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activateDraft(BUSINESS_ID, 2))
                .isInstanceOf(ConcurrentUpdate.class);
    }

    @Test
    void validationFailureOccursBeforeAnyStoreInteraction() {
        var failure = new InvalidInput(InputField.BUSINESS_ID);
        when(validator.validateBusinessId(BUSINESS_ID)).thenThrow(failure);

        assertThatThrownBy(() -> service.get(BUSINESS_ID)).isSameAs(failure);
        verifyNoInteractions(store);
    }

    @Test
    void updateValidationCompletesBeforeRetrieval() {
        UpdateBusinessCommand command = updateCommand("updated", 0);
        var failure = new InvalidInput(InputField.SLUG);
        when(validator.validateBusinessId(BUSINESS_ID)).thenReturn(BUSINESS_ID);
        when(validator.validateUpdate(command)).thenThrow(failure);

        assertThatThrownBy(() -> service.update(BUSINESS_ID, command)).isSameAs(failure);
        verifyNoInteractions(store);
    }

    private void assertLifecycleTransition(
            BusinessStatus currentStatus,
            BusinessStatus targetStatus,
            Runnable operation) {
        BusinessRow current = row(currentStatus, 2, NOW.minusSeconds(60), NOW.minusSeconds(1));
        BusinessRow persisted = row(targetStatus, 3, current.createdAt(), NOW);
        stubValidatedLifecycle(current, 2);
        when(store.transition(BUSINESS_ID, currentStatus, targetStatus, 2, NOW))
                .thenReturn(Optional.of(persisted));

        operation.run();

        var order = inOrder(validator, store);
        order.verify(validator).validateBusinessId(BUSINESS_ID);
        order.verify(validator).validateExpectedVersion(2);
        order.verify(store).findById(BUSINESS_ID);
        order.verify(store).transition(BUSINESS_ID, currentStatus, targetStatus, 2, NOW);
    }

    private void stubValidatedUpdate(UpdateBusinessCommand command, BusinessRow current) {
        when(validator.validateBusinessId(BUSINESS_ID)).thenReturn(BUSINESS_ID);
        when(validator.validateUpdate(command)).thenReturn(command);
        when(store.findById(BUSINESS_ID)).thenReturn(Optional.of(current));
    }

    private void stubValidatedLifecycle(BusinessRow current, long version) {
        when(validator.validateBusinessId(BUSINESS_ID)).thenReturn(BUSINESS_ID);
        when(validator.validateExpectedVersion(version)).thenReturn(version);
        when(store.findById(BUSINESS_ID)).thenReturn(Optional.of(current));
    }

    private CreateBusinessCommand createCommand(String slug) {
        return new CreateBusinessCommand(
                slug,
                "Business Name",
                BusinessType.OTHER,
                "Europe/Sofia",
                "Description",
                "Sofia",
                "1000",
                "Example",
                "1",
                "Entrance A",
                "+359 2 000 0000",
                "contact@example.invalid");
    }

    private UpdateBusinessCommand updateCommand(String slug, long expectedVersion) {
        CreateBusinessCommand create = createCommand(slug);
        return new UpdateBusinessCommand(
                create.slug(),
                create.displayName(),
                create.businessType(),
                create.timezone(),
                create.description(),
                create.city(),
                create.postalCode(),
                create.street(),
                create.streetNumber(),
                create.addressDetails(),
                create.phone(),
                create.contactEmail(),
                expectedVersion);
    }

    private BusinessRow row(
            BusinessStatus status, long version, Instant createdAt, Instant updatedAt) {
        return new BusinessRow(
                BUSINESS_ID,
                new BusinessSlug("stored-business"),
                "Stored Business",
                BusinessType.BEAUTY_STUDIO,
                status,
                new BusinessTimezone("Europe/Sofia"),
                "Stored description",
                "Sofia",
                "1000",
                "Stored street",
                "1",
                "Stored details",
                "+359 2 111 1111",
                "stored@example.invalid",
                version,
                createdAt,
                updatedAt);
    }

    private void assertCompleteDetails(BusinessDetails details, BusinessRow row) {
        assertThat(details.id()).isEqualTo(row.id());
        assertThat(details.slug()).isEqualTo(row.slug().value());
        assertThat(details.displayName()).isEqualTo(row.displayName());
        assertThat(details.businessType()).isEqualTo(row.businessType());
        assertThat(details.status()).isEqualTo(row.status());
        assertThat(details.timezone()).isEqualTo(row.timezone().value());
        assertThat(details.description()).isEqualTo(row.description());
        assertThat(details.city()).isEqualTo(row.city());
        assertThat(details.postalCode()).isEqualTo(row.postalCode());
        assertThat(details.street()).isEqualTo(row.street());
        assertThat(details.streetNumber()).isEqualTo(row.streetNumber());
        assertThat(details.addressDetails()).isEqualTo(row.addressDetails());
        assertThat(details.phone()).isEqualTo(row.phone());
        assertThat(details.contactEmail()).isEqualTo(row.contactEmail());
        assertThat(details.version()).isEqualTo(row.version());
        assertThat(details.createdAt()).isEqualTo(row.createdAt());
        assertThat(details.updatedAt()).isEqualTo(row.updatedAt());
    }
}
