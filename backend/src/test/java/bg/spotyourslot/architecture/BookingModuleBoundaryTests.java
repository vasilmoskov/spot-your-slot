package bg.spotyourslot.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.SpotYourSlotApplication;
import bg.spotyourslot.scheduling.BusyIntervalSource;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Issue #18 Phases 2, 4, and 5: the {@code booking} module owns Appointment persistence, the real
 * busy-interval source, the guest booking orchestration, and the public abuse limiter. It depends only on the published
 * contracts of Business, Catalog, Workforce, Scheduling, and Customer and on the shared contact
 * canonicalization policy; only the public booking adapter depends on it, {@code scheduling} never imports it, and exactly
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
                "business", "catalog", "workforce", "scheduling", "customer", "shared");
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
            if (name.equals("publicbooking")) {
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
                        "BookingOrchestrationFailure");
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
                for (String module : List.of("business", "catalog", "workforce", "scheduling", "customer")) {
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
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
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
}
