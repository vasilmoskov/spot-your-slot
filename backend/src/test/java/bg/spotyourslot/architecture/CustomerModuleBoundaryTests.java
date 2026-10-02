package bg.spotyourslot.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.SpotYourSlotApplication;
import bg.spotyourslot.shared.contact.ContactEmailPolicy;
import bg.spotyourslot.shared.contact.ContactPhoneNumbers;
import bg.spotyourslot.shared.contact.ContactTextCanonicalizer;
import bg.spotyourslot.shared.web.ApiExceptionHandler;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModuleDependency;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

/**
 * Phase 2 boundaries of the Customer module (ADR-0019): it depends only on the small shared contact
 * policy through the {@code shared::contact} named interface only, never on Workforce, and nothing
 * depends on it yet. The other {@code shared} sub-packages stay internal.
 */
class CustomerModuleBoundaryTests {
    private final ApplicationModules modules = ApplicationModules.of(SpotYourSlotApplication.class);

    @Test
    void customerIsADetectedModuleThatDependsOnlyOnShared() {
        assertThat(modules.getModuleByName("customer")).isPresent();
        assertThat(dependenciesOf("customer")).containsExactly("shared");
    }

    @Test
    void customerDoesNotDependOnWorkforceOrAnyOtherBusinessModule() {
        assertThat(dependenciesOf("customer"))
                .doesNotContain("workforce", "identity", "business", "catalog", "scheduling", "platform",
                        "publicprofile");
    }

    @Test
    void workforceUsesTheSharedContactPolicy() {
        assertThat(dependenciesOf("workforce")).contains("shared");
    }

    @Test
    void nothingOutsideCustomerDependsOnCustomerYet() {
        for (ApplicationModule module : modules) {
            String name = module.getIdentifier().toString();
            if (!name.equals("customer")) {
                assertThat(dependenciesOf(name)).as(name).doesNotContain("customer");
            }
        }
    }

    @Test
    void theSharedModuleExposesOnlyTheNamedContactInterface() {
        ApplicationModule shared = modules.getModuleByName("shared").orElseThrow();

        Set<String> namedInterfaces = shared.getNamedInterfaces().stream()
                .filter(NamedInterface::isNamed)
                .map(NamedInterface::getName)
                .collect(Collectors.toSet());
        Set<String> contact = shared.getNamedInterfaces().getByName("contact").orElseThrow()
                .asJavaClasses()
                .map(JavaClass::getName)
                .collect(Collectors.toSet());
        Set<String> exposedAtTheRoot = shared.getNamedInterfaces().getUnnamedInterface()
                .asJavaClasses()
                .map(JavaClass::getName)
                .collect(Collectors.toSet());

        assertThat(namedInterfaces).containsExactly("contact");
        assertThat(contact).contains(
                ContactEmailPolicy.class.getName(),
                ContactPhoneNumbers.class.getName(),
                ContactTextCanonicalizer.class.getName());
        assertThat(contact).allMatch(name -> name.startsWith("bg.spotyourslot.shared.contact."));
        // Neither the unnamed root API nor any other shared sub-package exposes the web, security,
        // or configuration internals, and no contact type is reachable through the root.
        assertThat(exposedAtTheRoot).noneMatch(name -> name.startsWith("bg.spotyourslot.shared.contact."));
        assertThat(shared.getNamedInterfaces().stream()
                        .anyMatch(exposed -> exposed.contains(ApiExceptionHandler.class)))
                .isFalse();
    }

    @Test
    void customerAndWorkforceReachSharedOnlyThroughTheContactNamedInterface() {
        ApplicationModule shared = modules.getModuleByName("shared").orElseThrow();
        var contact = shared.getNamedInterfaces().getByName("contact").orElseThrow();

        for (String consumer : List.of("customer", "workforce")) {
            ApplicationModule module = modules.getModuleByName(consumer).orElseThrow();
            List<JavaClass> sharedTypesUsed = module.getDirectDependencies(modules).stream()
                    .filter(dependency -> dependency.getTargetModule().equals(shared))
                    .map(ApplicationModuleDependency::getTargetType)
                    .toList();

            assertThat(sharedTypesUsed).as(consumer).isNotEmpty();
            assertThat(sharedTypesUsed).as(consumer).allMatch(contact::contains);
        }
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
}
