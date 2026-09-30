package bg.spotyourslot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import bg.spotyourslot.catalog.PublicServiceAccess.PublicService;
import bg.spotyourslot.catalog.PublicServiceAccess.PublicServiceFailure;
import bg.spotyourslot.catalog.infrastructure.PublicServiceRow;
import bg.spotyourslot.catalog.infrastructure.ServicePersistenceException.UnexpectedFailure;
import bg.spotyourslot.catalog.infrastructure.ServiceStore;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublicServiceAccessServiceTests {
    private static final UUID BUSINESS = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    ServiceStore store;

    @Test
    void mapsEachRowToExactlyTheFourPublicValuesInStoreOrder() {
        when(store.findActivePublicServices(BUSINESS)).thenReturn(List.of(
                new PublicServiceRow("Алфа", null, 30, new BigDecimal("10.50")),
                new PublicServiceRow("Бета", "Описание", 45, new BigDecimal("0.00"))));

        List<PublicService> services =
                new PublicServiceAccessService(store).findActiveServices(BUSINESS);

        assertThat(services).containsExactly(
                new PublicService("Алфа", null, 30, new BigDecimal("10.50")),
                new PublicService("Бета", "Описание", 45, new BigDecimal("0.00")));
        assertThat(services.get(0).price().scale()).isEqualTo(2);
    }

    @Test
    void anEmptyCatalogIsAnEmptyList() {
        when(store.findActivePublicServices(BUSINESS)).thenReturn(List.of());

        assertThat(new PublicServiceAccessService(store).findActiveServices(BUSINESS)).isEmpty();
    }

    @Test
    void wrapsPersistenceFailuresWithoutExposingTheirDetail() {
        when(store.findActivePublicServices(BUSINESS))
                .thenThrow(new UnexpectedFailure(new RuntimeException("sql detail")));

        assertThatThrownBy(() -> new PublicServiceAccessService(store).findActiveServices(BUSINESS))
                .isInstanceOf(PublicServiceFailure.class)
                .hasMessage("Public Service access failed");
    }
}
