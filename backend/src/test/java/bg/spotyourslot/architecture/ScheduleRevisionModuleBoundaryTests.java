package bg.spotyourslot.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.SpotYourSlotApplication;
import bg.spotyourslot.business.ScheduleRevisionBump;
import bg.spotyourslot.business.ScheduleRevisionConcurrentConflict;
import bg.spotyourslot.business.ScheduleRevisionFailure;
import bg.spotyourslot.business.ScheduleRevisionGuard;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Issue #18 Phase 3 (ADR-0025): the {@code business} module owns the schedule revision table and
 * publishes two narrow contracts. {@code workforce} and {@code scheduling} bump it from their own
 * transactions, booking will lock it in Phase 4, and no module reads the table or reaches into the
 * Business module's internals.
 */
class ScheduleRevisionModuleBoundaryTests {
    private static final String PACKAGE = "bg.spotyourslot.";
    private static final String STORE = "bg.spotyourslot.business.infrastructure.ScheduleRevisionStore";
    private static final String SERVICE = "bg.spotyourslot.business.application.ScheduleRevisionService";

    private final ApplicationModules modules = ApplicationModules.of(SpotYourSlotApplication.class);
    private final JavaClasses main = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("bg.spotyourslot");

    @Test
    void theContractsAndTheirFailuresArePublishedInTheBusinessModuleRoot() {
        for (Class<?> published : List.of(
                ScheduleRevisionBump.class,
                ScheduleRevisionGuard.class,
                ScheduleRevisionConcurrentConflict.class,
                ScheduleRevisionFailure.class)) {
            assertThat(published.getPackageName()).isEqualTo("bg.spotyourslot.business");
        }
        assertThat(ScheduleRevisionBump.class.isInterface()).isTrue();
        assertThat(ScheduleRevisionGuard.class.isInterface()).isTrue();
    }

    @Test
    void theBumpAndTheGuardAreSeparateContractsSoNeitherConsumerCanDoTheOthersJob() {
        assertThat(ScheduleRevisionBump.class.isAssignableFrom(ScheduleRevisionGuard.class)).isFalse();
        assertThat(ScheduleRevisionGuard.class.isAssignableFrom(ScheduleRevisionBump.class)).isFalse();
        assertThat(ScheduleRevisionBump.class.getDeclaredMethods()).hasSize(1);
        assertThat(ScheduleRevisionGuard.class.getDeclaredMethods()).hasSize(1);
    }

    @Test
    void onlyTheBusinessApplicationServiceImplementsTheContractsAndOnlyItUsesTheStore() {
        List<String> implementations = main.stream()
                .filter(type -> !type.isInterface())
                .filter(type -> type.isAssignableTo(ScheduleRevisionBump.class)
                        || type.isAssignableTo(ScheduleRevisionGuard.class))
                .map(JavaClass::getName)
                .toList();
        assertThat(implementations).containsExactly(SERVICE);

        assertThat(dependentsOf(STORE)).containsExactly(SERVICE);
    }

    @Test
    void onlyWorkforceAndSchedulingBumpAndOnlyTheBookingAttemptTakesTheSharedGuard() {
        assertThat(dependentsOf(ScheduleRevisionBump.class.getName()))
                .containsExactlyInAnyOrder(
                        SERVICE,
                        "bg.spotyourslot.workforce.application.StaffWorkingScheduleService",
                        "bg.spotyourslot.scheduling.application.ScheduleExceptionAdministrationService");
        assertThat(dependentsOf(ScheduleRevisionGuard.class.getName()))
                .containsExactlyInAnyOrder(
                        SERVICE, "bg.spotyourslot.booking.application.BookingAttemptProcedure");
    }

    @Test
    void noClassOutsideBusinessReachesIntoItsApplicationOrInfrastructureInternals() {
        for (JavaClass type : main) {
            if (type.getPackageName().startsWith(PACKAGE + "business")) {
                continue;
            }
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getPackageName();
                assertThat(target)
                        .as(type.getName() + " -> " + dependency.getTargetClass().getName())
                        .doesNotStartWith(PACKAGE + "business.application")
                        .doesNotStartWith(PACKAGE + "business.infrastructure");
            }
        }
    }

    @Test
    void thePhaseAddsNoNewModuleDependencyAndTheBusinessModuleDependsOnNothing() {
        assertThat(dependenciesOf("business")).isEmpty();
        assertThat(dependenciesOf("workforce")).contains("business");
        assertThat(dependenciesOf("scheduling")).contains("business");
        assertThat(dependenciesOf("workforce")).doesNotContain("scheduling", "booking");
        assertThat(dependenciesOf("scheduling")).doesNotContain("booking");
        for (ApplicationModule module : modules) {
            String name = module.getIdentifier().toString();
            // Only the public HTTP adapter of guest booking (ADR-0026) depends on booking.
            if (!name.equals("publicbooking")) {
                assertThat(dependenciesOf(name)).as(name).doesNotContain("booking");
            }
        }
    }

    @Test
    void theModuleGraphStaysAcyclicAndVerified() {
        modules.verify();
    }

    private Set<String> dependentsOf(String targetName) {
        return main.stream()
                .filter(type -> !type.getName().equals(targetName))
                .filter(type -> type.getDirectDependenciesFromSelf().stream()
                        .anyMatch(dependency -> dependency.getTargetClass().getName().equals(targetName)))
                .map(JavaClass::getName)
                // Nested and anonymous classes belong to their enclosing class.
                .map(name -> name.contains("$") ? name.substring(0, name.indexOf('$')) : name)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private Set<String> dependenciesOf(String moduleName) {
        ApplicationModule module = modules.getModuleByName(moduleName).orElseThrow();
        return module.getDirectDependencies(modules).uniqueModules()
                .map(dependency -> dependency.getIdentifier().toString())
                .collect(Collectors.toSet());
    }
}
