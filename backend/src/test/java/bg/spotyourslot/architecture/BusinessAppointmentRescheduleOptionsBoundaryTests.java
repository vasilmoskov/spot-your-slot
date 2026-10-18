package bg.spotyourslot.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.workforce.StaffAvailabilityAccess;
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
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees of the reschedule options reads (ADR-0028 section 4, ADR-0029 section 1):
 * they take no lock and write nothing, read no Service, take the duration and Services from the stored
 * Appointment only, ignore exactly that Appointment, read the clock once per operation, and never touch
 * the mutation's lock-taking collaborators.
 */
class BusinessAppointmentRescheduleOptionsBoundaryTests {
    private static final String SERVICE = "bg.spotyourslot.booking.application.AppointmentRescheduleOptionsService";
    private static final Path SOURCE = Path.of(
            "src/main/java/bg/spotyourslot/booking/application/AppointmentRescheduleOptionsService.java");

    private final JavaClasses main = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("bg.spotyourslot");

    private static String code() throws IOException {
        return Files.readString(SOURCE).replaceAll("(?s)/\\*.*?\\*/", "");
    }

    @Test
    void theReadsUseOnlyReadContractsAndNoServiceCustomerLockOrRevisionContract() {
        Set<String> targets = new TreeSet<>();
        for (Dependency dependency : main.get(SERVICE).getDirectDependenciesFromSelf()) {
            targets.add(dependency.getTargetClass().getName());
        }

        assertThat(targets).contains(
                AppointmentStore.class.getName(),
                StaffAvailabilityAccess.class.getName(),
                "bg.spotyourslot.workforce.PublicStaffAccess",
                "bg.spotyourslot.scheduling.AvailabilityQuery",
                "bg.spotyourslot.business.BusinessScheduleContextAccess",
                "bg.spotyourslot.identity.SelectedBusinessOwnerAccess");
        assertThat(targets).doesNotContain(
                StaffBookingAccess.class.getName(),
                "bg.spotyourslot.business.ScheduleRevisionGuard",
                "bg.spotyourslot.catalog.ServiceAvailabilityAccess",
                "bg.spotyourslot.catalog.ServiceBookingAccess",
                "bg.spotyourslot.customer.CustomerReferenceAccess");
        assertThat(targets).noneMatch(target -> target.startsWith("bg.spotyourslot.catalog")
                || target.startsWith("bg.spotyourslot.customer")
                || target.startsWith("java.sql")
                || target.startsWith("org.postgresql")
                || target.startsWith("org.springframework.dao")
                || target.startsWith("org.springframework.jdbc"));
    }

    @Test
    void theOnlyAppointmentStoreCallIsTheStoredReadAndNothingWrites() throws IOException {
        Set<String> storeCalls = new TreeSet<>();
        for (JavaMethodCall call : main.get(SERVICE).getMethodCallsFromSelf()) {
            if (call.getTargetOwner().getName().equals(AppointmentStore.class.getName())) {
                storeCalls.add(call.getName());
            }
        }
        String code = code();

        assertThat(storeCalls).containsExactly("find");
        assertThat(code).doesNotContain("INSERT").doesNotContain("UPDATE ").doesNotContain("DELETE")
                .doesNotContain("JdbcClient").doesNotContain("Thread.sleep").doesNotContain("Logger")
                .doesNotContain("lockShared(").doesNotContain("lockActive").doesNotContain("lockEligible")
                .doesNotContain("FOR SHARE").doesNotContain("FOR UPDATE").doesNotContain("REQUIRES_NEW");
    }

    @Test
    void theOperationsAreReadOnlyRepeatableReadTransactionsAndNoOtherMethodIsTransactional() throws IOException {
        String code = code();

        assertThat(count(code, "@Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)")).isEqualTo(2);
        assertThat(count(code, "@Transactional")).isEqualTo(2);
    }

    @Test
    void theClockIsReadOncePerOperationAndTheEngineIgnoresExactlyTheStoredAppointmentWithItsStoredDuration()
            throws IOException {
        String code = code();

        // options reads it for the state check; availability reads it once and reuses it for both the
        // state check and the engine.
        assertThat(count(code, "clock.instant()")).isEqualTo(2);
        assertThat(count(code, "availability.calculateForBusiness(")).isEqualTo(1);
        String call = code.substring(code.indexOf("availability.calculateForBusiness("));
        call = call.substring(0, call.indexOf(");"));
        assertThat(call).contains("Duration.ofMinutes(current.durationMinutes())")
                .contains("now,").contains("current.id()");
    }

    @Test
    void nothingElseCallsTheReadsAndTheServiceIsTheOnlyImplementation() {
        Set<String> callers = new TreeSet<>();
        for (JavaClass type : main) {
            for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
                if (call.getTargetOwner().getName().equals("bg.spotyourslot.booking.AppointmentRescheduleOptions")) {
                    callers.add(type.getName());
                }
            }
        }

        assertThat(callers).containsExactly("bg.spotyourslot.calendar.web.BusinessAppointmentRescheduleOptionsController");
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }
}
