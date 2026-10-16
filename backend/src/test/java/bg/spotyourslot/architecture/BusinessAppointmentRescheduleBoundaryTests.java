package bg.spotyourslot.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.business.ScheduleRevisionGuard;
import bg.spotyourslot.catalog.ServiceBookingAccess;
import bg.spotyourslot.customer.CustomerReferenceAccess;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.workforce.StaffBookingAccess;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees of the reschedule and reassignment attempt (ADR-0029 sections 2 to 4):
 * which collaborators its body may use, the lock order as the order of calls in its source, that it
 * writes through exactly one persistence method and takes no Service or Customer contract, that only
 * it calls the conditional reschedule and the active-StaffMember lock, and that it reads the clock
 * exactly once.
 */
class BusinessAppointmentRescheduleBoundaryTests {
    private static final String PROCEDURE = "bg.spotyourslot.booking.application.BusinessAppointmentRescheduleProcedure";
    private static final Path SOURCE = Path.of(
            "src/main/java/bg/spotyourslot/booking/application/BusinessAppointmentRescheduleProcedure.java");

    private final JavaClasses main = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("bg.spotyourslot");

    private static String code() throws IOException {
        return Files.readString(SOURCE).replaceAll("(?s)/\\*.*?\\*/", "");
    }

    @Test
    void theBodyUsesTheGuardTheStaffLocksAndTheBusinessAvailabilityButNoServiceOrCustomerContract() {
        Set<String> targets = new TreeSet<>();
        for (Dependency dependency : main.get(PROCEDURE).getDirectDependenciesFromSelf()) {
            targets.add(dependency.getTargetClass().getName());
        }

        assertThat(targets).contains(
                ScheduleRevisionGuard.class.getName(),
                StaffBookingAccess.class.getName(),
                AvailabilityQuery.class.getName(),
                AppointmentStore.class.getName(),
                "bg.spotyourslot.business.BusinessScheduleContextAccess",
                "bg.spotyourslot.identity.SelectedBusinessOwnerAccess");
        assertThat(targets).doesNotContain(
                ServiceBookingAccess.class.getName(),
                CustomerReferenceAccess.class.getName(),
                "bg.spotyourslot.booking.application.PublicReferenceSource");
        assertThat(targets).noneMatch(target -> target.startsWith("bg.spotyourslot.catalog")
                && !target.equals("bg.spotyourslot.catalog.ServiceBookingAccess$BookingService"));
        assertThat(targets).noneMatch(target -> target.startsWith("bg.spotyourslot.customer"));
        assertThat(targets).noneMatch(target -> target.startsWith("java.sql")
                || target.startsWith("org.postgresql")
                || target.startsWith("org.springframework.dao")
                || target.startsWith("org.springframework.jdbc"));
    }

    @Test
    void theLockOrderIsTheOrderOfTheCallsInTheBody() throws IOException {
        String code = code();
        List<String> ordered = List.of(
                "businesses.lockScheduleContextSanitized(",
                "owners.lockAndAuthorizeSanitized(",
                "appointments.find(businessId, command.appointmentId())",
                "AppointmentTransitions.rescheduleRefusal(",
                "lockTarget(businessId, current, targetId, reassigned)",
                "scheduleRevision.lockShared(",
                "AppointmentTransitions.reschedule(",
                "availability.calculateForBusiness(",
                "appointments.reschedule(applied.update())",
                "appointments.find(businessId, current.id())");
        int previous = -1;
        for (String call : ordered) {
            int position = code.indexOf(call);
            assertThat(position).as(call).isGreaterThan(-1);
            assertThat(position).as(call + " after the previous step").isGreaterThan(previous);
            previous = position;
        }
        // The StaffMember step is one private method holding the two branches of that single step; it is
        // called once, between the pre-lock refusals and the revision lock.
        assertThat(count(code, "lockTarget(businessId")).isEqualTo(1);
        String lockTarget = code.substring(code.indexOf("private Optional<BookingStaffMember> lockTarget("));
        assertThat(lockTarget).contains("staffMembers.lockActiveForBooking(").contains("staffMembers.lockEligibleForBooking(");
        assertThat(count(code, "staffMembers.lock")).isEqualTo(2);
        assertThat(count(code, "scheduleRevision.lockShared(")).isEqualTo(1);
    }

    @Test
    void theBodyWritesThroughExactlyOnePersistenceMethodAndContainsNoSqlOrSleep() throws IOException {
        String code = code();

        assertThat(count(code, "appointments.reschedule(")).isEqualTo(1);
        assertThat(code).doesNotContain("appointments.insert(").doesNotContain("appointments.cancel(")
                .doesNotContain("appointments.updateInternalNote(")
                .doesNotContain("INSERT").doesNotContain("UPDATE ").doesNotContain("DELETE")
                .doesNotContain("JdbcClient").doesNotContain("Thread.sleep").doesNotContain("@Transactional")
                .doesNotContain("REQUIRES_NEW").doesNotContain("Savepoint");
    }

    @Test
    void theClockIsReadExactlyOnceInTheBody() throws IOException {
        assertThat(count(code(), "clock.instant()")).isEqualTo(1);
    }

    @Test
    void onlyTheRescheduleBodyCallsTheConditionalRescheduleAndTheActiveStaffMemberLock() {
        assertThat(callers(AppointmentStore.class.getName(), "reschedule")).containsExactly(PROCEDURE);
        assertThat(callers(StaffBookingAccess.class.getName(), "lockActiveForBooking")).containsExactly(PROCEDURE);
    }

    @Test
    void theRunnerKeepsExactlyThreeAttemptsAndNoBackoff() throws IOException {
        String code = Files.readString(Path.of(
                        "src/main/java/bg/spotyourslot/booking/application/BusinessAppointmentAdministrationService.java"))
                .replaceAll("(?s)/\\*.*?\\*/", "");

        assertThat(code).contains("static final int MAX_ATTEMPTS = 3;");
        assertThat(count(code, "for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)")).isEqualTo(1);
        assertThat(code).doesNotContain("Thread.sleep").doesNotContain("wait(").doesNotContain("Backoff");
    }

    private Set<String> callers(String owner, String method) {
        Set<String> callers = new TreeSet<>();
        for (JavaClass type : main) {
            for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
                if (call.getTargetOwner().getName().equals(owner) && call.getName().equals(method)
                        && !type.getName().equals(owner)) {
                    callers.add(type.getName());
                }
            }
        }
        return callers;
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }
}
