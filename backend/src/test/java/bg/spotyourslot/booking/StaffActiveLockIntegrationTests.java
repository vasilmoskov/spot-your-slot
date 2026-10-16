package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.workforce.StaffBookingAccess;
import bg.spotyourslot.workforce.StaffBookingAccess.BookingStaffMember;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * {@link StaffBookingAccess#lockActiveForBooking}: the lock of the time-only move of an Appointment.
 * It locks one active StaffMember of the Business {@code FOR SHARE} without any Service assignment
 * check (ADR-0028 section 6) and translates a serialization failure into the published, cause-free
 * conflict.
 */
class StaffActiveLockIntegrationTests extends BookingIntegrationTest {
    @Autowired StaffBookingAccess staffAccess;

    private <T> T inSnapshot(java.util.function.Supplier<T> work) {
        return snapshotTransaction().execute(status -> work.get());
    }

    private boolean rowIsLocked(UUID staff) {
        return completed(async(() -> jdbc.sql("SELECT id FROM staff_member WHERE id = :id FOR UPDATE SKIP LOCKED")
                .param("id", staff).query(UUID.class).list().isEmpty()));
    }

    @Test
    void theContractRequiresTheCallersTransaction() {
        assertThatThrownBy(() -> staffAccess.lockActiveForBooking(tenant.business(), tenant.staff()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void anActiveStaffMemberIsLockedWithItsCanonicalNameWhetherOrNotItIsStillAssigned() {
        availabilityFixtures.unassign(tenant.business(), tenant.staff(), tenant.service());

        Optional<BookingStaffMember> locked = inSnapshot(
                () -> staffAccess.lockActiveForBooking(tenant.business(), tenant.staff()));

        assertThat(locked).isPresent();
        assertThat(locked.get().id()).isEqualTo(tenant.staff());
        assertThat(locked.get().displayName()).isEqualTo(
                jdbc.sql("SELECT display_name FROM staff_member WHERE id = :id").param("id", tenant.staff())
                        .query(String.class).single());
    }

    @Test
    void aMissingForeignAndInactiveStaffMemberAreAllTheSameEmptyResult() {
        var other = openTenant();
        UUID inactive = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.setStaffMemberActive(inactive, false);

        for (UUID staff : new UUID[] {UUID.randomUUID(), other.staff(), inactive}) {
            assertThat(inSnapshot(() -> staffAccess.lockActiveForBooking(tenant.business(), staff))).isEmpty();
        }
        // A StaffMember is found only inside its own Business.
        assertThat(inSnapshot(() -> staffAccess.lockActiveForBooking(other.business(), tenant.staff()))).isEmpty();
    }

    @Test
    void theRowIsLockedSharedUntilTheTransactionEndsAndOtherRowsAreNot() {
        UUID untouched = fixtures.staffMember(tenant.business(), tenant.service());

        snapshotTransaction().executeWithoutResult(status -> {
            staffAccess.lockActiveForBooking(tenant.business(), tenant.staff());
            assertThat(rowIsLocked(tenant.staff())).isTrue();
            assertThat(rowIsLocked(untouched)).isFalse();
        });

        assertThat(rowIsLocked(tenant.staff())).isFalse();
    }

    @Test
    void aStaffMemberChangedAfterTheSnapshotFailsTheLockWithThePublishedCauseFreeConflict() {
        assertThatThrownBy(() -> snapshotTransaction().executeWithoutResult(status -> {
            jdbc.sql("SELECT 1").query(Integer.class).single(); // the snapshot exists
            completed(async(() -> jdbc.sql("UPDATE staff_member SET updated_at = updated_at WHERE id = :id")
                    .param("id", tenant.staff()).update()));
            staffAccess.lockActiveForBooking(tenant.business(), tenant.staff());
        }))
                .isInstanceOf(StaffBookingAccess.ConcurrentConflict.class)
                .hasNoCause()
                .hasNoSuppressedExceptions();
    }

    @Test
    void theUnexpectedFailureIsTheFixedFailureWithNoCause() {
        assertThatThrownBy(() -> snapshotTransaction().executeWithoutResult(status -> {
            try {
                jdbc.sql("SELECT 1 / 0").query().singleRow();
            } catch (RuntimeException swallowed) {
                // the transaction is now aborted; the next statement fails with 25P02
            }
            staffAccess.lockActiveForBooking(tenant.business(), tenant.staff());
        }))
                .isInstanceOf(StaffBookingAccess.Failure.class)
                .hasNoCause();
    }

    @Test
    void theLockExposesOnlyTheIdentifierTheCanonicalNameAndTheCreationInstant() {
        assertThat(BookingStaffMember.class.getRecordComponents()).extracting(component -> component.getName())
                .containsExactly("id", "displayName", "createdAt");
    }
}
