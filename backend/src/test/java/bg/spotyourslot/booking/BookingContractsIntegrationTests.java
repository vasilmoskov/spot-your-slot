package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.business.BusinessBookingAccess;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.catalog.ServiceBookingAccess;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.workforce.StaffBookingAccess;
import bg.spotyourslot.workforce.StaffBookingAccess.BookingStaffMember;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/** The narrow published contracts added for booking, and the Appointment count used by assignment. */
class BookingContractsIntegrationTests extends BookingIntegrationTest {
    @Autowired BusinessBookingAccess businessAccess;
    @Autowired ServiceBookingAccess serviceAccess;
    @Autowired StaffBookingAccess staffAccess;
    @Autowired AppointmentStore store;

    private <T> T inSnapshot(java.util.function.Supplier<T> work) {
        return snapshotTransaction().execute(status -> work.get());
    }

    private boolean rowIsLocked(String sql, Map<String, Object> params) {
        return async(() -> {
            var statement = jdbc.sql(sql + " FOR UPDATE SKIP LOCKED");
            for (var entry : params.entrySet()) {
                statement = statement.param(entry.getKey(), entry.getValue());
            }
            return statement.query(UUID.class).list().isEmpty();
        }).join();
    }

    @Test
    void everyContractRequiresTheCallersTransaction() {
        assertThatThrownBy(() -> businessAccess.lockBySlug("x")).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> serviceAccess.lockForBooking(tenant.business(), tenant.service()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> staffAccess.lockEligibleForBooking(tenant.business(), tenant.service(), null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theBusinessIsLockedAtAnyLifecycleStatusAndOnlyIdAndStatusAreExposed() {
        for (String status : List.of("DRAFT", "ACTIVE", "SUSPENDED")) {
            availabilityFixtures.setBusinessStatus(tenant.business(), status);
            var found = inSnapshot(() -> businessAccess.lockBySlug(slugOf(tenant.business())));
            assertThat(found).isPresent();
            assertThat(found.get().businessId()).isEqualTo(tenant.business());
            assertThat(found.get().status()).isEqualTo(LifecycleStatus.valueOf(status));
        }
    }

    @Test
    void theBusinessLockIsHeldUntilTheTransactionEnds() {
        snapshotTransaction().executeWithoutResult(status -> {
            businessAccess.lockBySlug(slugOf(tenant.business()));
            assertThat(rowIsLocked("SELECT id FROM business WHERE id = :id", Map.of("id", tenant.business()))).isTrue();
        });
        assertThat(rowIsLocked("SELECT id FROM business WHERE id = :id", Map.of("id", tenant.business()))).isFalse();
    }

    @Test
    void anUnknownMalformedOrReservedSlugIsEmptyAndCanonicalizationApplies() {
        assertThat(inSnapshot(() -> businessAccess.lockBySlug("nope"))).isEmpty();
        assertThat(inSnapshot(() -> businessAccess.lockBySlug("Bad Slug"))).isEmpty();
        assertThat(inSnapshot(() -> businessAccess.lockBySlug("api"))).isEmpty();
        assertThat(inSnapshot(() -> businessAccess.lockBySlug(null))).isEmpty();
        assertThat(inSnapshot(() -> businessAccess.lockBySlug("  " + slugOf(tenant.business()).toUpperCase() + " ")))
                .isPresent();
    }

    @Test
    void theServiceIsSnapshottedLockedAndForeignOrMissingIsEmpty() {
        var other = openTenant();
        UUID inactive = availabilityFixtures.service(tenant.business(), 45, false);

        var service = inSnapshot(() -> serviceAccess.lockForBooking(tenant.business(), tenant.service()));
        var stopped = inSnapshot(() -> serviceAccess.lockForBooking(tenant.business(), inactive));

        assertThat(service).isPresent();
        assertThat(service.get().durationMinutes()).isEqualTo(30);
        assertThat(service.get().price()).isEqualByComparingTo("10.00");
        assertThat(service.get().active()).isTrue();
        assertThat(stopped.orElseThrow().active()).isFalse();
        assertThat(inSnapshot(() -> serviceAccess.lockForBooking(tenant.business(), other.service()))).isEmpty();
        assertThat(inSnapshot(() -> serviceAccess.lockForBooking(tenant.business(), UUID.randomUUID()))).isEmpty();
        snapshotTransaction().executeWithoutResult(status -> {
            serviceAccess.lockForBooking(tenant.business(), tenant.service());
            assertThat(rowIsLocked("SELECT id FROM service WHERE id = :id", Map.of("id", tenant.service()))).isTrue();
        });
    }

    @Test
    void theQualifiedStaffMembersAreLockedInIdentifierOrderAndOthersAreExcluded() {
        UUID second = fixtures.staffMember(tenant.business(), tenant.service());
        UUID inactive = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.setStaffMemberActive(inactive, false);
        UUID unassigned = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.unassign(tenant.business(), unassigned, tenant.service());
        var other = openTenant();

        List<BookingStaffMember> all = inSnapshot(
                () -> staffAccess.lockEligibleForBooking(tenant.business(), tenant.service(), null));
        List<BookingStaffMember> one = inSnapshot(
                () -> staffAccess.lockEligibleForBooking(tenant.business(), tenant.service(), second));

        // PostgreSQL orders uuid values bytewise, which is the lowercase text order (not UUID.compareTo).
        assertThat(all).extracting(member -> member.id().toString())
                .containsExactlyInAnyOrder(tenant.staff().toString(), second.toString())
                .isSorted();
        assertThat(one).extracting(BookingStaffMember::id).containsExactly(second);
        for (UUID excluded : List.of(inactive, unassigned, other.staff(), UUID.randomUUID())) {
            assertThat(inSnapshot(() -> staffAccess.lockEligibleForBooking(
                    tenant.business(), tenant.service(), excluded))).isEmpty();
        }
        assertThat(inSnapshot(() -> staffAccess.lockEligibleForBooking(
                tenant.business(), other.service(), null))).isEmpty();
        snapshotTransaction().executeWithoutResult(status -> {
            staffAccess.lockEligibleForBooking(tenant.business(), tenant.service(), null);
            assertThat(rowIsLocked("SELECT id FROM staff_member WHERE id = :id", Map.of("id", second))).isTrue();
            assertThat(rowIsLocked("SELECT id FROM staff_member WHERE id = :id", Map.of("id", inactive))).isFalse();
        });
    }

    @Test
    void theAppointmentCountCoversOnlyConfirmedRowsStartingInTheHalfOpenRange() {
        UUID other = fixtures.staffMember(tenant.business(), tenant.service());
        Instant from = at("00:00");
        Instant to = at("00:00").plusSeconds(86_400);
        insert(tenant.staff(), from, "CONFIRMED");
        insert(tenant.staff(), to.minusSeconds(1800), "CONFIRMED");
        insert(tenant.staff(), to, "CONFIRMED");
        insert(tenant.staff(), from.plusSeconds(3600), "CANCELLED");
        insert(other, from.plusSeconds(7200), "CONFIRMED");

        Map<UUID, Long> counts = snapshotTransaction().execute(status ->
                store.countConfirmedStartingBetween(tenant.business(), List.of(tenant.staff(), other, UUID.randomUUID()), from, to));

        assertThat(counts).containsEntry(tenant.staff(), 2L).containsEntry(other, 1L).hasSize(3);
        assertThat(store.countConfirmedStartingBetween(tenant.business(), List.of(), from, to)).isEmpty();
        assertThat(store.countConfirmedStartingBetween(tenant.business(), List.of(tenant.staff()), to, from))
                .containsEntry(tenant.staff(), 0L);
        assertThat(store.countConfirmedStartingBetween(UUID.randomUUID(), List.of(tenant.staff()), from, to))
                .containsEntry(tenant.staff(), 0L);
    }

    private void insert(UUID staff, Instant start, String status) {
        var row = AppointmentFixtures.row(new AppointmentFixtures.Tenant(
                tenant.business(), tenant.service(), staff, fixtures.customer(tenant.business())), start, 15);
        row.put("status", status);
        fixtures.insertRow(row);
    }
}
