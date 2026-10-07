package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import bg.spotyourslot.workforce.PublicStaffAccess.PublicStaffFailure;
import bg.spotyourslot.workforce.PublicStaffAccess.PublicStaffMember;
import bg.spotyourslot.workforce.infrastructure.PublicStaffRow;
import bg.spotyourslot.workforce.infrastructure.StaffMemberPersistenceException.UnexpectedFailure;
import bg.spotyourslot.workforce.infrastructure.StaffMemberStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublicStaffAccessServiceTests {
    private static final UUID BUSINESS = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SERVICE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    @Mock
    StaffMemberStore store;

    @Test
    void mapsEachRowToExactlyTheIdentifierAndTheDisplayNameInStoreOrder() {
        when(store.findBookableStaff(BUSINESS, SERVICE)).thenReturn(List.of(
                new PublicStaffRow(SECOND, "Алфа"), new PublicStaffRow(FIRST, "Бета")));

        List<PublicStaffMember> staff = new PublicStaffAccessService(store).findBookableStaff(BUSINESS, SERVICE);

        assertThat(staff).containsExactly(new PublicStaffMember(SECOND, "Алфа"), new PublicStaffMember(FIRST, "Бета"));
    }

    @Test
    void wrapsPersistenceFailuresWithoutExposingTheirDetail() {
        when(store.findBookableStaff(BUSINESS, SERVICE))
                .thenThrow(new UnexpectedFailure(new RuntimeException("sql detail")));

        assertThatThrownBy(() -> new PublicStaffAccessService(store).findBookableStaff(BUSINESS, SERVICE))
                .isInstanceOf(PublicStaffFailure.class)
                .hasMessage("Public StaffMember access failed");
    }
}
