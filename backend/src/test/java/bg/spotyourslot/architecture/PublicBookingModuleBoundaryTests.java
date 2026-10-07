package bg.spotyourslot.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.SpotYourSlotApplication;
import bg.spotyourslot.booking.PublicBookingRateLimiter;
import bg.spotyourslot.workforce.PublicStaffAccess;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Issue #18 Phase 5: the public HTTP adapter of guest booking (ADR-0026) depends only on published
 * contracts, is never depended on, and cannot reach identity, the session, or the authenticated
 * principal. The limiter stays Booking-owned and web-free.
 */
class PublicBookingModuleBoundaryTests {
    private static final String PACKAGE = "bg.spotyourslot.publicbooking";

    private final ApplicationModules modules = ApplicationModules.of(SpotYourSlotApplication.class);
    private final JavaClasses main = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("bg.spotyourslot");

    @Test
    void thePublicBookingModuleDependsOnlyOnTheFourPublishedContractModules() {
        assertThat(modules.getModuleByName("publicbooking")).isPresent();
        assertThat(dependenciesOf("publicbooking"))
                .containsExactlyInAnyOrder("booking", "business", "scheduling", "workforce");
    }

    @Test
    void noModuleDependsOnThePublicBookingModule() {
        for (ApplicationModule module : modules) {
            String name = module.getIdentifier().toString();
            if (!name.equals("publicbooking")) {
                assertThat(dependenciesOf(name)).as(name).doesNotContain("publicbooking");
            }
        }
    }

    @Test
    void thePublicBookingModuleNeverReachesIdentitySessionOrPrincipal() {
        for (JavaClass type : classesOfModule()) {
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getName();
                assertThat(target).as(type.getName() + " -> " + target)
                        .doesNotStartWith("bg.spotyourslot.identity")
                        .doesNotStartWith("bg.spotyourslot.customer")
                        .doesNotStartWith("bg.spotyourslot.catalog")
                        .doesNotStartWith("org.springframework.security")
                        .doesNotStartWith("jakarta.servlet.http.HttpSession")
                        .doesNotStartWith("jakarta.servlet.http.Cookie")
                        .doesNotStartWith("java.security.Principal");
            }
        }
    }

    @Test
    void theAdapterReachesOtherModulesOnlyThroughTheirPublishedRootPackages() {
        for (JavaClass type : classesOfModule()) {
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getPackageName();
                for (String module : List.of("booking", "business", "scheduling", "workforce")) {
                    assertThat(target).as(type.getName() + " -> " + dependency.getTargetClass().getName())
                            .isNotIn(
                                    "bg.spotyourslot." + module + ".application",
                                    "bg.spotyourslot." + module + ".infrastructure",
                                    "bg.spotyourslot." + module + ".domain",
                                    "bg.spotyourslot." + module + ".web",
                                    "bg.spotyourslot." + module + ".configuration");
                }
            }
        }
    }

    @Test
    void theControllerDelegatesAndOwnsNoTransactionOrBookingLogic() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bg/spotyourslot/publicbooking/web/PublicBookingController.java"));
        String code = source.replaceAll("(?s)/\\*.*?\\*/", "");
        assertThat(code).doesNotContain("@Transactional")
                .doesNotContain("TransactionTemplate")
                .doesNotContain("Authentication")
                .doesNotContain("Principal")
                .doesNotContain("HttpSession")
                .doesNotContain("getCookies")
                .doesNotContain("getHeader")
                .doesNotContain("CustomerIdentification")
                .doesNotContain("AvailabilityQuery")
                .doesNotContain("Thread.sleep");
        assertThat(code).contains("booking.book(").contains("limiter.admitBookingContact(");
    }

    @Test
    void theClientAddressIsOnlyTheServletRemoteAddressAndNeverAForwardedHeader() throws Exception {
        for (String file : List.of(
                "src/main/java/bg/spotyourslot/publicbooking/web/PublicBookingRateLimitInterceptor.java",
                "src/main/java/bg/spotyourslot/publicbooking/web/PublicBookingController.java")) {
            String code = Files.readString(Path.of(file)).replaceAll("(?s)/\\*.*?\\*/", "");
            assertThat(code).doesNotContain("X-Forwarded").doesNotContain("Forwarded")
                    .doesNotContain("X-Real-IP").doesNotContain("getHeader(");
        }
        String interceptor = Files.readString(Path.of(
                "src/main/java/bg/spotyourslot/publicbooking/web/PublicBookingRateLimitInterceptor.java"));
        assertThat(interceptor).contains("request.getRemoteAddr()");
    }

    @Test
    void theLimiterIsBookingOwnedPublishedAsOneInterfaceAndFreeOfWebAndLogging() {
        assertThat(PublicBookingRateLimiter.class.getPackageName()).isEqualTo("bg.spotyourslot.booking");
        assertThat(PublicStaffAccess.class.getPackageName()).isEqualTo("bg.spotyourslot.workforce");
        for (JavaClass type : main) {
            if (!type.getPackageName().startsWith("bg.spotyourslot.booking")) {
                continue;
            }
            boolean limiterType = type.getSimpleName().contains("RateLimit")
                    || type.getSimpleName().equals("BookingRateLimiter")
                    || type.getSimpleName().equals("FixedWindowCounters")
                    || type.getSimpleName().equals("Digest");
            if (limiterType) {
                for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                    assertThat(dependency.getTargetClass().getPackageName())
                            .as(type.getName() + " -> " + dependency.getTargetClass().getName())
                            .doesNotStartWith("org.springframework.web")
                            .doesNotStartWith("jakarta.servlet")
                            .doesNotStartWith("org.slf4j")
                            .doesNotStartWith("java.util.logging");
                }
            }
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

    private List<JavaClass> classesOfModule() {
        return main.stream().filter(type -> type.getPackageName().startsWith(PACKAGE)).toList();
    }
}
