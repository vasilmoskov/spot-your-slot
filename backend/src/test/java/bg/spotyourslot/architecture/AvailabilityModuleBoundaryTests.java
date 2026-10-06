package bg.spotyourslot.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.SpotYourSlotApplication;
import bg.spotyourslot.scheduling.BusyIntervalSource;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class AvailabilityModuleBoundaryTests {
    private final ApplicationModules modules = ApplicationModules.of(SpotYourSlotApplication.class);

    @Test
    void schedulingDependsOnlyOnPublishedBusinessCatalogWorkforceAndIdentityContracts() {
        assertThat(dependenciesOf("scheduling"))
                .isSubsetOf("business", "catalog", "workforce", "identity", "shared");
        assertThat(dependenciesOf("scheduling")).contains("business", "catalog", "workforce");
    }

    @Test
    void schedulingHasNoDependencyOnBooking() {
        assertThat(modules.getModuleByName("booking")).isPresent();
        assertThat(dependenciesOf("scheduling")).doesNotContain("booking");
        assertThat(dependenciesOf("booking")).contains("scheduling");
    }

    @Test
    void theProvidersHaveNoReverseDependencyOnScheduling() {
        assertThat(dependenciesOf("catalog")).doesNotContain("scheduling");
        assertThat(dependenciesOf("workforce")).doesNotContain("scheduling");
        assertThat(dependenciesOf("business")).doesNotContain("scheduling");
    }

    @Test
    void schedulingOwnsTheBusyIntervalSourceInItsPublishedRootPackage() {
        assertThat(BusyIntervalSource.class.getPackageName()).isEqualTo("bg.spotyourslot.scheduling");
    }

    @Test
    void theWholeModuleGraphStillVerifies() {
        modules.verify();
    }

    private Set<String> dependenciesOf(String moduleName) {
        ApplicationModule module = modules.getModuleByName(moduleName).orElseThrow();
        return module.getDirectDependencies(modules).uniqueModules()
                .map(dependency -> dependency.getIdentifier().toString())
                .collect(Collectors.toSet());
    }
}
