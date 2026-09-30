package bg.spotyourslot.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.SpotYourSlotApplication;
import bg.spotyourslot.business.PublicBusinessProfileAccess;
import bg.spotyourslot.catalog.PublicServiceAccess;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class PublicProfileModuleBoundaryTests {
    private final ApplicationModules modules = ApplicationModules.of(SpotYourSlotApplication.class);

    @Test
    void publicProfileDependsOnlyOnThePublishedBusinessAndCatalogContracts() {
        assertThat(dependenciesOf("publicprofile")).containsExactlyInAnyOrder("business", "catalog");
    }

    @Test
    void noModuleDependsBackOnPublicProfile() {
        for (ApplicationModule module : modules) {
            String name = module.getIdentifier().toString();
            if (!name.equals("publicprofile")) {
                assertThat(dependenciesOf(name)).as(name).doesNotContain("publicprofile");
            }
        }
    }

    @Test
    void businessAndCatalogStillHaveNoCycleBetweenThem() {
        assertThat(dependenciesOf("business")).doesNotContain("catalog", "publicprofile");
        modules.verify();
    }

    @Test
    void theReadContractsLiveInTheProvidersPublishedRootPackages() {
        assertThat(PublicBusinessProfileAccess.class.getPackageName())
                .isEqualTo("bg.spotyourslot.business");
        assertThat(PublicServiceAccess.class.getPackageName())
                .isEqualTo("bg.spotyourslot.catalog");
    }

    private Set<String> dependenciesOf(String moduleName) {
        ApplicationModule module = modules.getModuleByName(moduleName).orElseThrow();
        return module.getDirectDependencies(modules).uniqueModules()
                .map(dependency -> dependency.getIdentifier().toString())
                .collect(Collectors.toSet());
    }
}
