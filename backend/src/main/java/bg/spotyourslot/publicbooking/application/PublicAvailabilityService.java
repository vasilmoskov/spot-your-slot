package bg.spotyourslot.publicbooking.application;

import bg.spotyourslot.business.PublicBusinessProfileAccess;
import bg.spotyourslot.publicbooking.application.PublicBookingViews.Availability;
import bg.spotyourslot.publicbooking.application.PublicBookingViews.BookingOptions;
import bg.spotyourslot.publicbooking.application.PublicBookingViews.Slot;
import bg.spotyourslot.publicbooking.application.PublicBookingViews.Staff;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.BusinessNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.ServiceNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.StaffMemberNotEligible;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySlot;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySnapshot;
import bg.spotyourslot.workforce.PublicStaffAccess;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The public read side of guest booking (ADR-0026): the choices and the offered slots for one
 * Service of one ACTIVE Business. Every read of one request comes from a single repeatable-read,
 * read-only snapshot. It takes no lock, writes nothing, reads no identity, and never attaches a
 * StaffMember to a slot, even for "no preference".
 *
 * <p>The Business is resolved only from the slug and is public only while ACTIVE; a missing,
 * DRAFT, SUSPENDED, malformed, or reserved slug is one {@link PublicBookingUnavailable.Business}.
 */
@Service
public class PublicAvailabilityService {
    private final PublicBusinessProfileAccess businesses;
    private final AvailabilityQuery availability;
    private final PublicStaffAccess staff;

    public PublicAvailabilityService(
            PublicBusinessProfileAccess businesses,
            AvailabilityQuery availability,
            PublicStaffAccess staff) {
        this.businesses = businesses;
        this.availability = availability;
        this.staff = staff;
    }

    /**
     * @throws PublicBookingUnavailable.Business when the Business is not public
     * @throws PublicBookingUnavailable.Service when the Service is missing, foreign, or inactive
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public BookingOptions bookingOptions(String slug, UUID serviceId) {
        requireSnapshotIsolation();
        Objects.requireNonNull(serviceId, "serviceId");
        UUID businessId = resolveBusiness(slug);
        AvailabilitySnapshot snapshot = calculate(businessId, serviceId, null);
        List<Staff> choices = staff.findBookableStaff(businessId, serviceId).stream()
                .map(member -> new Staff(member.id(), member.displayName()))
                .toList();
        return new BookingOptions(
                snapshot.timezone(), snapshot.firstDate(), snapshot.lastDate(), choices);
    }

    /**
     * @param staffMemberIdOrNull a specific StaffMember, or {@code null} for "no preference"
     * @throws PublicBookingUnavailable.Staff when a requested StaffMember is not eligible
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public Availability availability(
            String slug, UUID serviceId, LocalDate date, UUID staffMemberIdOrNull) {
        requireSnapshotIsolation();
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(date, "date");
        UUID businessId = resolveBusiness(slug);
        AvailabilitySnapshot snapshot = calculate(businessId, serviceId, staffMemberIdOrNull);
        ZoneId zone = snapshot.timezone();

        TreeSet<LocalDate> dates = new TreeSet<>();
        List<Slot> ofDate = new ArrayList<>();
        for (AvailabilitySlot slot : snapshot.slots()) {
            LocalDate slotDate = LocalDate.ofInstant(slot.start(), zone);
            dates.add(slotDate);
            if (slotDate.equals(date)) {
                ofDate.add(new Slot(slot.start(), slot.end()));
            }
        }
        return new Availability(date, zone, List.copyOf(dates), ofDate);
    }

    private UUID resolveBusiness(String slug) {
        return businesses.findActiveBySlug(slug)
                .map(PublicBusinessProfileAccess.PublicBusinessProfile::businessId)
                .orElseThrow(PublicBookingUnavailable.Business::new);
    }

    private AvailabilitySnapshot calculate(UUID businessId, UUID serviceId, UUID staffMemberIdOrNull) {
        try {
            return availability.calculate(businessId, serviceId, staffMemberIdOrNull);
        } catch (BusinessNotBookable exception) {
            throw new PublicBookingUnavailable.Business();
        } catch (ServiceNotBookable exception) {
            throw new PublicBookingUnavailable.Service();
        } catch (StaffMemberNotEligible exception) {
            throw new PublicBookingUnavailable.Staff();
        }
    }

    /**
     * {@code REQUIRED} would silently join a weaker caller transaction and ignore the declared
     * isolation, so the effective isolation is verified before any read (as for the profile).
     */
    private static void requireSnapshotIsolation() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        boolean snapshotCoherent = TransactionSynchronizationManager.isActualTransactionActive()
                && isolation != null
                && (isolation == Connection.TRANSACTION_REPEATABLE_READ
                        || isolation == Connection.TRANSACTION_SERIALIZABLE);
        if (!snapshotCoherent) {
            throw new IllegalStateException(
                    "Public availability requires a repeatable-read or serializable transaction");
        }
    }
}
