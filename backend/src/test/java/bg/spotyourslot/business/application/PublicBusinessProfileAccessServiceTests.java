package bg.spotyourslot.business.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import bg.spotyourslot.business.PublicBusinessProfileAccess.PublicAddress;
import bg.spotyourslot.business.domain.BusinessSlug;
import bg.spotyourslot.business.domain.ReservedBusinessSlugs;
import bg.spotyourslot.business.infrastructure.BusinessStore;
import bg.spotyourslot.business.infrastructure.PublicBusinessProfileRow;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublicBusinessProfileAccessServiceTests {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Mock
    BusinessStore store;

    private PublicBusinessProfileAccessService access() {
        return new PublicBusinessProfileAccessService(store);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "bad_slug", "-x", "x-", "a--b", "a.b", "ключ", "a'or'1'='1"})
    void aMalformedSlugNeverReachesTheStore(String slug) {
        assertThat(access().findActiveBySlug(slug)).isEmpty();

        verify(store, never()).findActivePublicProfileBySlug(any());
    }

    @Test
    void anOverlongSlugNeverReachesTheStore() {
        assertThat(access().findActiveBySlug("x".repeat(101))).isEmpty();

        verify(store, never()).findActivePublicProfileBySlug(any());
    }

    @Test
    void everyReservedRootNeverReachesTheStore() {
        for (String reserved : ReservedBusinessSlugs.values()) {
            assertThat(access().findActiveBySlug(reserved)).as(reserved).isEmpty();
            assertThat(access().findActiveBySlug(reserved.toUpperCase())).as(reserved).isEmpty();
        }

        verify(store, never()).findActivePublicProfileBySlug(any());
    }

    @Test
    void theSlugIsCanonicalizedBeforeTheLookup() {
        when(store.findActivePublicProfileBySlug(any())).thenReturn(Optional.empty());

        assertThat(access().findActiveBySlug("  Studio-A ")).isEmpty();

        var captor = ArgumentCaptor.forClass(BusinessSlug.class);
        verify(store).findActivePublicProfileBySlug(captor.capture());
        assertThat(captor.getValue().value()).isEqualTo("studio-a");
    }

    @Test
    void mapsOnlyThePublicFieldsAndKeepsTheAddressAsOneUnit() {
        when(store.findActivePublicProfileBySlug(any())).thenReturn(Optional.of(
                new PublicBusinessProfileRow(
                        ID, "studio-a", "Студио", "HAIR_SALON", "Описание",
                        "София", "1000", "Улица", "1", null, "+359 88 000 0000")));

        var profile = access().findActiveBySlug("studio-a").orElseThrow();

        assertThat(profile.businessId()).isEqualTo(ID);
        assertThat(profile.slug()).isEqualTo("studio-a");
        assertThat(profile.displayName()).isEqualTo("Студио");
        assertThat(profile.businessType()).isEqualTo("HAIR_SALON");
        assertThat(profile.description()).isEqualTo("Описание");
        assertThat(profile.phone()).isEqualTo("+359 88 000 0000");
        assertThat(profile.address())
                .isEqualTo(new PublicAddress("София", "1000", "Улица", "1", null));
    }

    @Test
    void theAddressIsNullWhenEveryPartIsEmpty() {
        when(store.findActivePublicProfileBySlug(any())).thenReturn(Optional.of(
                new PublicBusinessProfileRow(
                        ID, "studio-a", "Студио", "OTHER", null,
                        null, null, null, null, null, null)));

        var profile = access().findActiveBySlug("studio-a").orElseThrow();

        assertThat(profile.address()).isNull();
        assertThat(profile.phone()).isNull();
        assertThat(profile.description()).isNull();
    }

    @Test
    void anySinglePresentAddressPartKeepsTheAddress() {
        when(store.findActivePublicProfileBySlug(any())).thenReturn(Optional.of(
                new PublicBusinessProfileRow(
                        ID, "studio-a", "Студио", "OTHER", null,
                        null, null, null, null, "вход А", null)));

        assertThat(access().findActiveBySlug("studio-a").orElseThrow().address())
                .isEqualTo(new PublicAddress(null, null, null, null, "вход А"));
    }
}
