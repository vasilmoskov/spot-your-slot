package bg.spotyourslot.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.SpotYourSlotApplication;
import bg.spotyourslot.scheduling.BusyIntervalSource;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Issue #18 Phases 2, 4, and 5 and issue #21 Phase 3: the {@code booking} module owns Appointment persistence, the real
 * busy-interval source, the guest booking orchestration, and the public abuse limiter. It depends only on the published
 * contracts of Business, Catalog, Workforce, Scheduling, Customer, and the narrow identity context
 * types and on the shared contact canonicalization policy; only the two HTTP adapters (the public
 * booking adapter and the private calendar adapter) depend on it, {@code scheduling} never imports it, and exactly
 * one busy-interval source exists.
 */
class BookingModuleBoundaryTests {
    private final ApplicationModules modules = ApplicationModules.of(SpotYourSlotApplication.class);
    private final JavaClasses main = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("bg.spotyourslot");

    @Test
    void theBookingModuleDependsOnlyOnThePublishedContractsAndTheSharedContactPolicy() {
        assertThat(modules.getModuleByName("booking")).isPresent();
        assertThat(dependenciesOf("booking")).containsExactlyInAnyOrder(
                "business", "catalog", "workforce", "scheduling", "customer", "identity", "shared");
        // The only shared package used is the published contact canonicalization policy.
        for (JavaClass type : main) {
            if (type.getPackageName().startsWith("bg.spotyourslot.booking")) {
                for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                    String target = dependency.getTargetClass().getPackageName();
                    if (target.startsWith("bg.spotyourslot.shared")) {
                        assertThat(target).as(type.getName()).isEqualTo("bg.spotyourslot.shared.contact");
                    }
                }
            }
        }
    }

    @Test
    void onlyThePublicBookingAdapterDependsOnTheBookingModule() {
        for (ApplicationModule module : modules) {
            String name = module.getIdentifier().toString();
            if (name.equals("booking")) {
                continue;
            }
            if (name.equals("publicbooking") || name.equals("calendar")) {
                assertThat(dependenciesOf(name)).as(name).contains("booking");
            } else {
                assertThat(dependenciesOf(name)).as(name).doesNotContain("booking");
            }
        }
    }

    @Test
    void schedulingNeverImportsAnyBookingClass() {
        for (JavaClass type : main) {
            if (type.getPackageName().startsWith("bg.spotyourslot.scheduling")) {
                for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                    assertThat(dependency.getTargetClass().getPackageName())
                            .as(type.getName() + " -> " + dependency.getTargetClass().getName())
                            .doesNotStartWith("bg.spotyourslot.booking");
                }
            }
        }
    }

    @Test
    void theDomainUsesOnlyTheJdkItselfAndTheSharedContactPolicyAndTheStoreStaysInsideTheModule() {
        for (JavaClass type : main) {
            String packageName = type.getPackageName();
            if (packageName.equals("bg.spotyourslot.booking.domain")) {
                for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                    String target = dependency.getTargetClass().getPackageName();
                    assertThat(target.startsWith("java.")
                            || target.equals(packageName)
                            || target.equals("bg.spotyourslot.shared.contact"))
                            .as(type.getName() + " -> " + dependency.getTargetClass().getName())
                            .isTrue();
                }
            }
            if (!packageName.startsWith("bg.spotyourslot.booking")) {
                for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                    assertThat(dependency.getTargetClass().getPackageName())
                            .as(type.getName() + " -> " + dependency.getTargetClass().getName())
                            .doesNotStartWith("bg.spotyourslot.booking.infrastructure")
                            .doesNotStartWith("bg.spotyourslot.booking.domain");
                }
            }
        }
    }

    /**
     * Appointment administration must not depend conceptually on the guest booking request model:
     * the note policy lives in a neutral JDK-only helper that both use, and the transitions know
     * nothing of the request, its fingerprint, or the booking attempt.
     */
    @Test
    void theAdministrationTransitionsAndTheGuestRequestShareOnlyTheNeutralNotePolicy() {
        Set<String> transitionTargets = dependencyNamesOf("bg.spotyourslot.booking.domain.AppointmentTransitions");
        Set<String> requestTargets = dependencyNamesOf("bg.spotyourslot.booking.domain.NormalizedBookingRequest");
        String noteText = "bg.spotyourslot.booking.domain.NoteText";

        assertThat(transitionTargets).contains(noteText);
        assertThat(requestTargets).contains(noteText);
        assertThat(transitionTargets).noneMatch(name -> name.endsWith("NormalizedBookingRequest")
                || name.endsWith("BookingAttempt") || name.endsWith("RequestField")
                || name.contains("Fingerprint"));
        assertThat(dependencyNamesOf(noteText)).allMatch(name -> name.startsWith("java.")
                || name.equals(noteText));
    }

    private Set<String> dependencyNamesOf(String className) {
        return main.get(className).getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass().getName())
                .collect(Collectors.toSet());
    }

    /**
     * ADR-0028 section 13: booking reaches identity only for the server-derived owner context and, since
     * the owner-created Appointment (ADR-0029), the sanitized conflict of the Membership lock.
     */
    @Test
    void bookingUsesOnlyTheNarrowIdentityContextAndOwnerLockTypes() {
        Set<String> identityTargets = new TreeSet<>();
        for (JavaClass type : main) {
            if (type.getPackageName().startsWith("bg.spotyourslot.booking")) {
                for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                    String target = dependency.getTargetClass().getName();
                    if (target.startsWith("bg.spotyourslot.identity")) {
                        identityTargets.add(target);
                    }
                }
            }
        }

        assertThat(identityTargets).containsExactlyInAnyOrder(
                "bg.spotyourslot.identity.AuthenticatedBusinessContext",
                "bg.spotyourslot.identity.SelectedBusinessOwnerAccess",
                "bg.spotyourslot.identity.SelectedBusinessOwnerAccess$Authorization",
                "bg.spotyourslot.identity.SelectedBusinessOwnerAccess$ConcurrentConflict",
                "bg.spotyourslot.identity.SelectedBusinessRequired");
    }

    @Test
    void theCalendarReadServiceIsReadOnlyRepeatableReadAndTakesNoLockOrWrite() throws Exception {
        String code = Files.readString(Path.of(
                "src/main/java/bg/spotyourslot/booking/application/AppointmentCalendarService.java"))
                .replaceAll("(?s)/\\*.*?\\*/", "");
        assertThat(code).contains("Isolation.REPEATABLE_READ").contains("readOnly = true")
                .doesNotContain("REQUIRES_NEW").doesNotContain("Savepoint")
                .doesNotContain("lockAndAuthorize").doesNotContain("lockScheduleContext")
                .doesNotContain("Thread.sleep").doesNotContain("Logger");
        String store = Files.readString(Path.of(
                "src/main/java/bg/spotyourslot/booking/infrastructure/AppointmentReadStore.java"))
                .replaceAll("(?s)/\\*.*?\\*/", "");
        assertThat(store).doesNotContain("FOR UPDATE").doesNotContain("FOR SHARE")
                .doesNotContain("INSERT ").doesNotContain("UPDATE appointment").doesNotContain("DELETE ");
    }

    @Test
    void exactlyOneClassImplementsTheBusyIntervalSourceAndItIsTheBookingOwnedOne() {
        List<String> implementations = main.stream()
                .filter(type -> !type.isInterface())
                .filter(type -> type.isAssignableTo(BusyIntervalSource.class))
                .map(JavaClass::getName)
                .toList();

        assertThat(implementations)
                .containsExactly("bg.spotyourslot.booking.infrastructure.BookingBusyIntervalSource");
        assertThat(main.stream().map(JavaClass::getSimpleName))
                .doesNotContain("NoBookingBusyIntervalSource");
    }

    @Test
    void bookingHasNoControllerAndOnlyTheDiagnosticsClassLogs() {
        for (JavaClass type : main) {
            if (!type.getPackageName().startsWith("bg.spotyourslot.booking")) {
                continue;
            }
            assertThat(type.isAnnotatedWith(org.springframework.web.bind.annotation.RestController.class))
                    .as(type.getName()).isFalse();
            assertThat(type.isAnnotatedWith(org.springframework.stereotype.Controller.class))
                    .as(type.getName()).isFalse();
            boolean diagnostics = type.getName().startsWith(
                    "bg.spotyourslot.booking.application.BookingDiagnostics");
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getPackageName();
                if (!diagnostics) {
                    assertThat(target).as(type.getName() + " -> " + dependency.getTargetClass().getName())
                            .doesNotStartWith("org.slf4j");
                }
                assertThat(target).as(type.getName() + " -> " + dependency.getTargetClass().getName())
                        .doesNotStartWith("java.util.logging")
                        .doesNotStartWith("org.springframework.web");
            }
        }
        assertThat(main.stream()
                .filter(type -> type.getPackageName().equals("bg.spotyourslot.booking"))
                .filter(type -> type.getEnclosingClass().isEmpty())
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toSet())).containsExactlyInAnyOrder(
                        "package-info",
                        "GuestBooking",
                        "GuestBookingRequest",
                        "PublicBookingRateLimiter",
                        "BookingResult",
                        "BookedAppointment",
                        "BookingField",
                        "BookingOrchestrationFailure",
                        "AppointmentCalendar",
                        "AppointmentCalendarRecords",
                        "AppointmentCalendarException",
                        "AppointmentAdministration",
                        "AppointmentAdministrationRecords",
                        "AppointmentAdministrationException");
    }

    @Test
    void theWholeModuleGraphStaysAcyclicAndVerified() {
        modules.verify();
    }

    private Set<String> dependenciesOf(String moduleName) {
        ApplicationModule module = modules.getModuleByName(moduleName).orElseThrow();
        return module.getDirectDependencies(modules).uniqueModules()
                .map(dependency -> dependency.getIdentifier().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void bookingReachesOtherModulesOnlyThroughTheirPublishedRootPackages() {
        for (JavaClass type : main) {
            if (!type.getPackageName().startsWith("bg.spotyourslot.booking")) {
                continue;
            }
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getPackageName();
                for (String module : List.of(
                        "business", "catalog", "workforce", "scheduling", "customer", "identity")) {
                    assertThat(target).as(type.getName() + " -> " + dependency.getTargetClass().getName())
                            .isNotIn(
                                    "bg.spotyourslot." + module + ".application",
                                    "bg.spotyourslot." + module + ".infrastructure",
                                    "bg.spotyourslot." + module + ".domain",
                                    "bg.spotyourslot." + module + ".web");
                }
            }
        }
    }

    @Test
    void theOrchestrationEntryPointIsNotTransactionalAndNeverUsesRequiresNewOrSavepoints() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bg/spotyourslot/booking/application/GuestBookingService.java"));
        // Code only: the Javadoc may mention the rejected alternatives.
        String code = source.replaceAll("(?s)/\\*.*?\\*/", "");
        assertThat(code).doesNotContain("@Transactional")
                .doesNotContain("REQUIRES_NEW")
                .doesNotContain("PROPAGATION_NESTED")
                .doesNotContain("Savepoint")
                .doesNotContain("Thread.sleep");
        assertThat(code).contains("PROPAGATION_REQUIRED").contains("TRANSACTION_REPEATABLE_READ");
    }

    /**
     * The owner-created Appointment runner mirrors the guest runner's transaction rules (ADR-0029
     * section 2): it is not transactional, begins every attempt with REQUIRED and REPEATABLE_READ, and
     * never uses a new-transaction propagation, a savepoint, or a sleep.
     */
    @Test
    void theBusinessRunnerIsNotTransactionalAndNeverUsesRequiresNewOrSavepoints() throws Exception {
        for (String name : List.of("BusinessAppointmentAdministrationService", "BusinessAppointmentCreationProcedure")) {
            String source = Files.readString(Path.of("src/main/java/bg/spotyourslot/booking/application/" + name + ".java"));
            String code = source.replaceAll("(?s)/\\*.*?\\*/", "");
            assertThat(code).as(name).doesNotContain("@Transactional")
                    .doesNotContain("REQUIRES_NEW")
                    .doesNotContain("PROPAGATION_NESTED")
                    .doesNotContain("Savepoint")
                    .doesNotContain("Thread.sleep");
            if (name.endsWith("Service")) {
                assertThat(code).contains("PROPAGATION_REQUIRED").contains("TRANSACTION_REPEATABLE_READ")
                        .contains("requireNoCallerTransaction");
            }
        }
    }

    /**
     * Q7: neither the runner nor the procedure inspects an SQLState, a driver exception, or a Spring
     * persistence exception. The owning modules translate their lock failures into published,
     * sanitized, cause-free conflicts, and the runner retries only those.
     */
    @Test
    void theBusinessRunnerAndProcedureNeverInspectPersistenceExceptionsOrSqlStates() throws Exception {
        for (String name : List.of("BusinessAppointmentAdministrationService", "BusinessAppointmentCreationProcedure")) {
            JavaClass type = main.get("bg.spotyourslot.booking.application." + name);
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getName();
                assertThat(target).as(name + " -> " + target)
                        .doesNotStartWith("java.sql")
                        .doesNotStartWith("org.postgresql")
                        .doesNotStartWith("org.springframework.dao")
                        .doesNotStartWith("org.springframework.jdbc");
            }
            String code = Files.readString(Path.of("src/main/java/bg/spotyourslot/booking/application/" + name + ".java"))
                    .replaceAll("(?s)/\\*.*?\\*/", "");
            assertThat(code).as(name).doesNotContain("getSQLState").doesNotContain("SQLException")
                    .doesNotContain("getCause");
        }
    }

    /** The conflicts the runner retries are exactly the published sanitized types (ADR-0029 section 8). */
    @Test
    void theRunnerRetriesExactlyThePublishedSanitizedConflicts() throws Exception {
        String code = Files.readString(Path.of(
                "src/main/java/bg/spotyourslot/booking/application/BusinessAppointmentAdministrationService.java"))
                .replaceAll("(?s)/\\*.*?\\*/", "");
        String retry = code.substring(code.indexOf("private static Retry retryReason"), code.indexOf("private AppointmentAdministrationException exhausted"));
        assertThat(retry).contains("AppointmentPersistenceException.OverlapConflict")
                .contains("BusinessScheduleContextAccess.ConcurrentConflict")
                .contains("SelectedBusinessOwnerAccess.ConcurrentConflict")
                .contains("StaffBookingAccess.ConcurrentConflict")
                .contains("ScheduleRevisionConcurrentConflict")
                .contains("ServiceBookingAccess.ConcurrentConflict")
                .contains("AppointmentPersistenceException.ConcurrentFailure")
                .contains("AppointmentPersistenceException.DuplicatePublicReference")
                .doesNotContain("Exception ").doesNotContain("RuntimeException")
                .doesNotContain("DuplicateAttempt")
                .doesNotContain("CustomerConcurrentConflict");
    }
}
