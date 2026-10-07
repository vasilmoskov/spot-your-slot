package bg.spotyourslot.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.SpotYourSlotApplication;
import bg.spotyourslot.architecture.customerconsumer.CustomerConsumerProbe;
import bg.spotyourslot.customer.CustomerConcurrentConflict;
import bg.spotyourslot.customer.CustomerIdentification;
import bg.spotyourslot.customer.CustomerIdentity;
import bg.spotyourslot.customer.CustomerMatchOutcome;
import bg.spotyourslot.customer.CustomerOperationFailure;
import bg.spotyourslot.customer.CustomerReferenceAccess;
import bg.spotyourslot.customer.IdentityField;
import bg.spotyourslot.shared.contact.ContactEmailPolicy;
import bg.spotyourslot.shared.contact.ContactPhoneNumbers;
import bg.spotyourslot.shared.contact.ContactTextCanonicalizer;
import bg.spotyourslot.shared.web.ApiExceptionHandler;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModuleDependency;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

/**
 * Boundaries of the Customer module: Phase 2 (ADR-0019), Phase 3 (ADR-0020), which publishes the
 * {@code CustomerIdentification} and {@code CustomerReferenceAccess} contracts from its root package,
 * and Phase 4 (ADR-0021), which adds the private owner-only administration API.
 *
 * <p>The module depends on the shared contact policy through the {@code shared::contact} named
 * interface only, and since Phase 4 on the published owner-access types of {@code identity} and
 * {@code business}, exactly like the other owner APIs. It never depends on Workforce, Catalog,
 * Scheduling, Platform, Public Profile, or Booking, and nothing depends on it. The other
 * {@code shared} sub-packages stay internal.
 */
class CustomerModuleBoundaryTests {
    private final ApplicationModules modules = ApplicationModules.of(SpotYourSlotApplication.class);

    @Test
    void customerIsADetectedModuleThatDependsOnlyOnSharedIdentityAndBusiness() {
        assertThat(modules.getModuleByName("customer")).isPresent();
        assertThat(dependenciesOf("customer")).containsExactlyInAnyOrder("shared", "identity", "business");
    }

    @Test
    void customerDoesNotDependOnWorkforceOrAnyOtherBusinessModule() {
        assertThat(dependenciesOf("customer"))
                .doesNotContain("workforce", "catalog", "scheduling", "platform", "publicprofile", "booking");
    }

    @Test
    void customerReachesIdentityAndBusinessOnlyThroughTheOwnerAccessTypes() {
        ApplicationModule customer = modules.getModuleByName("customer").orElseThrow();

        Set<String> targets = customer.getDirectDependencies(modules).stream()
                .filter(dependency -> Set.of("identity", "business")
                        .contains(dependency.getTargetModule().getIdentifier().toString()))
                .map(dependency -> dependency.getTargetType().getName())
                .collect(Collectors.toSet());

        assertThat(targets).isNotEmpty();
        assertThat(targets).isSubsetOf(
                "bg.spotyourslot.identity.AuthenticatedBusinessContext",
                "bg.spotyourslot.identity.SelectedBusinessOwnerAccess",
                "bg.spotyourslot.identity.SelectedBusinessOwnerAccess$Authorization",
                "bg.spotyourslot.identity.SelectedBusinessRequired",
                "bg.spotyourslot.business.BusinessLifecycleAccess",
                "bg.spotyourslot.business.BusinessLifecycleAccess$BusinessLifecycle",
                "bg.spotyourslot.business.BusinessLifecycleAccess$LifecycleStatus");
    }

    @Test
    void identityAndBusinessNeverDependOnCustomer() {
        assertThat(dependenciesOf("identity")).doesNotContain("customer");
        assertThat(dependenciesOf("business")).doesNotContain("customer");
        assertThat(dependenciesOf("publicprofile")).doesNotContain("customer");
    }

    @Test
    void layersStayInOneDirectionWebToApplicationToDomainAndInfrastructure() {
        var imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("bg.spotyourslot.customer");

        for (JavaClass type : imported) {
            String layer = type.getPackageName();
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getPackageName();
                String description = type.getName() + " -> " + dependency.getTargetClass().getName();
                if (layer.equals("bg.spotyourslot.customer.web")) {
                    assertThat(target).as(description).isNotEqualTo("bg.spotyourslot.customer.infrastructure");
                    assertThat(target).as(description).isNotEqualTo("bg.spotyourslot.customer.domain");
                }
                if (layer.equals("bg.spotyourslot.customer.domain")) {
                    assertThat(target).as(description).doesNotStartWith("bg.spotyourslot.customer.web")
                            .doesNotStartWith("bg.spotyourslot.customer.application")
                            .doesNotStartWith("bg.spotyourslot.customer.infrastructure");
                }
                if (layer.equals("bg.spotyourslot.customer.infrastructure")) {
                    assertThat(target).as(description).doesNotStartWith("bg.spotyourslot.customer.web")
                            .doesNotStartWith("bg.spotyourslot.customer.application");
                }
            }
        }
    }

    @Test
    void theOnlyCustomerControllerIsThePrivateOwnerApiUnderTheBusinessPrefix() throws java.io.IOException {
        var imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("bg.spotyourslot.customer");

        List<String> controllers = imported.stream()
                .filter(type -> type.isAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                        || type.isAnnotatedWith(org.springframework.stereotype.Controller.class))
                .map(JavaClass::getName)
                .toList();
        assertThat(controllers).containsExactly("bg.spotyourslot.customer.web.BusinessCustomerController");

        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bg/spotyourslot/customer/web/BusinessCustomerController.java"));
        assertThat(source).contains("@RequestMapping(\"/api/business/customers\")")
                .doesNotContain("/api/public")
                .doesNotContain("@DeleteMapping")
                .doesNotContain("@PatchMapping");
    }

    @Test
    void workforceUsesTheSharedContactPolicy() {
        assertThat(dependenciesOf("workforce")).contains("shared");
    }

    @Test
    void onlyBookingDependsOnCustomerAndThroughTheIdentificationContract() {
        for (ApplicationModule module : modules) {
            String name = module.getIdentifier().toString();
            if (name.equals("booking")) {
                assertThat(dependenciesOf(name)).as(name).contains("customer");
            } else if (!name.equals("customer")) {
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
    void theRootPackageIsTheOnlyPublishedCustomerApiAndHoldsExactlyTheApprovedTypes() {
        ApplicationModule customer = modules.getModuleByName("customer").orElseThrow();

        Set<String> published = customer.getNamedInterfaces().getUnnamedInterface()
                .asJavaClasses()
                .map(JavaClass::getName)
                .collect(Collectors.toSet());

        assertThat(published).allMatch(name -> name.startsWith("bg.spotyourslot.customer.")
                && name.lastIndexOf('.') == "bg.spotyourslot.customer".length());
        assertThat(published).contains(
                CustomerIdentification.class.getName(),
                CustomerIdentity.class.getName(),
                CustomerMatchOutcome.class.getName(),
                CustomerMatchOutcome.ExistingCustomer.class.getName(),
                CustomerMatchOutcome.CreatedCustomer.class.getName(),
                CustomerMatchOutcome.InvalidIdentity.class.getName(),
                CustomerMatchOutcome.IdentityConflict.class.getName(),
                IdentityField.class.getName(),
                CustomerReferenceAccess.class.getName(),
                CustomerReferenceAccess.CustomerReference.class.getName(),
                CustomerConcurrentConflict.class.getName(),
                CustomerOperationFailure.class.getName());
        assertThat(published).noneMatch(name -> name.contains("Administration")
                || name.contains("BusinessCustomer")
                || name.contains("InputValidator")
                || name.contains("SearchCriteria")
                || name.contains("SortField"));
        assertThat(published).noneMatch(name -> name.contains("CustomerStore")
                || name.contains("CustomerPersistenceException")
                || name.contains("CustomerProfile")
                || name.contains("CustomerField")
                || name.contains("CustomerIdentificationService"));
        assertThat(CustomerMatchOutcome.class.getPermittedSubclasses()).hasSize(4);
    }

    @Test
    void thePublishedTypesDependOnlyOnTheJdkAndTheirOwnPackage() {
        var imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("bg.spotyourslot.customer");

        for (JavaClass type : imported) {
            if (!type.getPackageName().equals("bg.spotyourslot.customer")) {
                continue;
            }
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getPackageName();
                assertThat(target).as(type.getName() + " -> " + dependency.getTargetClass().getName())
                        .satisfiesAnyOf(
                                name -> assertThat(name).startsWith("java."),
                                name -> assertThat(name).isEqualTo("bg.spotyourslot.customer"));
            }
        }
    }

    @Test
    void theTestOnlyConsumerUsesThePublishedRootPackageAndNothingInternal() {
        var consumerClasses = new ClassFileImporter().importPackages(
                CustomerConsumerProbe.class.getPackageName());

        assertThat(consumerClasses.contain(CustomerConsumerProbe.class)).isTrue();
        Set<String> customerTargets = consumerClasses.stream()
                .flatMap(type -> type.getDirectDependenciesFromSelf().stream())
                .map(dependency -> dependency.getTargetClass())
                .filter(target -> target.getPackageName().startsWith("bg.spotyourslot.customer"))
                .map(JavaClass::getName)
                .collect(Collectors.toSet());

        assertThat(customerTargets).isNotEmpty();
        assertThat(customerTargets)
                .allMatch(name -> name.startsWith("bg.spotyourslot.customer.")
                        && name.indexOf('.', "bg.spotyourslot.customer.".length()) < 0
                        || name.equals("bg.spotyourslot.customer"));
        assertThat(customerTargets).noneMatch(name -> name.contains(".domain.")
                || name.contains(".infrastructure.")
                || name.contains(".application."));
    }

    @Test
    void theTestOnlyConsumerCanUseEveryPublishedContract() {
        // Compiles only because the contracts are public: the identity, the outcomes, and the two
        // unchecked failures are all reachable from outside the module.
        CustomerIdentity identity = new CustomerIdentity("Name", "+359895555777", null);
        CustomerMatchOutcome outcome = new CustomerMatchOutcome.IdentityConflict();

        assertThat(identity.displayName()).isEqualTo("Name");
        assertThat(outcome).isInstanceOf(CustomerMatchOutcome.class);
        assertThat(new CustomerConcurrentConflict()).isInstanceOf(RuntimeException.class);
        assertThat(new CustomerOperationFailure()).isInstanceOf(RuntimeException.class);
    }

    @Test
    void theCustomerCapabilityHasNoLoggingAtAll() throws java.io.IOException {
        // Privacy by construction: with no logger, no name, phone, email, ID, or SQL can be logged.
        try (var sources = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java/bg/spotyourslot/customer"))) {
            for (java.nio.file.Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = java.nio.file.Files.readString(source);
                assertThat(text).as(source.toString())
                        .doesNotContain("org.slf4j")
                        .doesNotContain("java.util.logging")
                        .doesNotContain("System.out")
                        .doesNotContain("System.err")
                        .doesNotContain("printStackTrace");
            }
        }
    }

    @Test
    void customerNeverDependsOnBookingOrWorkforce() {
        assertThat(modules.getModuleByName("booking")).isPresent();
        assertThat(dependenciesOf("customer")).doesNotContain("booking", "workforce");
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
