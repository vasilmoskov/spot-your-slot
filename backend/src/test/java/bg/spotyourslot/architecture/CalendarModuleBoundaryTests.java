package bg.spotyourslot.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.SpotYourSlotApplication;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Issue #21 Phase 3: the private {@code calendar} HTTP adapter depends only on the published
 * {@code booking} root package and on the published {@code identity} context type, owns no
 * transaction, store, or logging, exposes exactly the four approved {@code GET} routes and (issue #19
 * Phase 4) the one approved {@code POST /api/business/appointments}, and no module depends on it.
 */
class CalendarModuleBoundaryTests {
    private final ApplicationModules modules = ApplicationModules.of(SpotYourSlotApplication.class);
    private final JavaClasses main = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("bg.spotyourslot");

    private Set<String> dependenciesOf(String moduleName) {
        ApplicationModule module = modules.getModuleByName(moduleName).orElseThrow();
        return module.getDirectDependencies(modules).uniqueModules()
                .map(dependency -> dependency.getIdentifier().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void calendarIsADetectedModuleThatDependsOnlyOnBookingAndIdentity() {
        assertThat(modules.getModuleByName("calendar")).isPresent();
        assertThat(dependenciesOf("calendar")).containsExactlyInAnyOrder("booking", "identity");
    }

    @Test
    void noModuleDependsOnCalendar() {
        for (ApplicationModule module : modules) {
            String name = module.getIdentifier().toString();
            if (!name.equals("calendar")) {
                assertThat(dependenciesOf(name)).as(name).doesNotContain("calendar");
            }
        }
    }

    @Test
    void theAdapterUsesOnlyPublishedRootTypesOfBookingAndIdentity() {
        Set<String> targets = new TreeSet<>();
        for (JavaClass type : main) {
            if (!type.getPackageName().startsWith("bg.spotyourslot.calendar")) {
                continue;
            }
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                String name = dependency.getTargetClass().getName();
                String packageName = dependency.getTargetClass().getPackageName();
                if (packageName.startsWith("bg.spotyourslot.") && !packageName.startsWith("bg.spotyourslot.calendar")) {
                    targets.add(name);
                    assertThat(packageName).as(type.getName() + " -> " + name)
                            .isIn("bg.spotyourslot.booking", "bg.spotyourslot.identity");
                }
            }
        }

        assertThat(targets).allMatch(name -> name.startsWith("bg.spotyourslot.booking.AppointmentCalendar")
                || name.startsWith("bg.spotyourslot.booking.AppointmentAdministration")
                || name.startsWith("bg.spotyourslot.booking.AppointmentCreationOptions")
                || name.startsWith("bg.spotyourslot.booking.AppointmentRescheduleOptions")
                || name.equals("bg.spotyourslot.booking.AppointmentLifecycleAdministration")
                || name.equals("bg.spotyourslot.booking.BookingOrchestrationFailure")
                || name.equals("bg.spotyourslot.identity.AuthenticatedBusinessContext")
                || name.equals("bg.spotyourslot.identity.SelectedBusinessRequired"));
        assertThat(targets).contains(
                "bg.spotyourslot.booking.AppointmentCalendar",
                "bg.spotyourslot.identity.AuthenticatedBusinessContext");
    }

    @Test
    void theAdapterOwnsNoTransactionStoreSqlOrLogging() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java/bg/spotyourslot/calendar"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String code = Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "");
                assertThat(code).as(file.toString())
                        .doesNotContain("@Transactional")
                        .doesNotContain("TransactionTemplate")
                        .doesNotContain("JdbcClient")
                        .doesNotContain("DataSource")
                        .doesNotContain("Logger")
                        .doesNotContain("HttpServletRequest")
                        .doesNotContain("getRequestURI")
                        .doesNotContain("Thread.sleep");
            }
        }
    }

    @Test
    void exactlyTheEightApprovedGetRoutesTheThreeApprovedPostsAndTheOnePutExistAndNothingElseIsMapped() throws Exception {
        List<String> mappings = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java/bg/spotyourslot/calendar/web"))) {
            for (Path file : files.filter(path -> path.toString().endsWith("Controller.java")).toList()) {
                String code = Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "");
                String base = Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)")
                        .matcher(code).results().map(match -> match.group(1)).findFirst().orElseThrow();
                assertThat(code).doesNotContain("@DeleteMapping").doesNotContain("@PatchMapping");
                // Phases 4 to 6: exactly four mutations, all in the appointments controller: the
                // owner-created Appointment, its reschedule or reassignment, its cancellation (three
                // POSTs), and the internal-note edit (the one PUT).
                boolean appointments = base.equals("/api/business/appointments");
                assertThat(code.split("@PostMapping", -1).length - 1).isEqualTo(appointments ? 3 : 0);
                assertThat(code.split("@PutMapping", -1).length - 1).isEqualTo(appointments ? 1 : 0);
                assertThat(code.split("@RequestBody", -1).length - 1).isEqualTo(appointments ? 4 : 0);
                Matcher post = Pattern.compile("@PostMapping(?:\\(\"([^\"]*)\"\\))?").matcher(code);
                while (post.find()) {
                    mappings.add("POST " + base + (post.group(1) == null ? "" : post.group(1)));
                }
                Matcher put = Pattern.compile("@PutMapping(?:\\(\"([^\"]*)\"\\))?").matcher(code);
                while (put.find()) {
                    mappings.add("PUT " + base + (put.group(1) == null ? "" : put.group(1)));
                }
                Matcher get = Pattern
                        .compile("@GetMapping(?:\\(\"([^\"]*)\"\\))?").matcher(code);
                while (get.find()) {
                    mappings.add("GET " + base + (get.group(1) == null ? "" : get.group(1)));
                }
            }
        }

        assertThat(mappings).containsExactlyInAnyOrder(
                "POST /api/business/appointments",
                "POST /api/business/appointments/{appointmentId}/reschedule",
                "POST /api/business/appointments/{appointmentId}/cancel",
                "PUT /api/business/appointments/{appointmentId}/internal-note",
                "GET /api/business/calendar",
                "GET /api/business/appointments",
                "GET /api/business/appointments/{appointmentId}",
                "GET /api/business/customers/{customerId}/appointments",
                "GET /api/business/appointment-options",
                "GET /api/business/appointment-options/availability",
                "GET /api/business/appointments/{appointmentId}/reschedule-options",
                "GET /api/business/appointments/{appointmentId}/reschedule-options/availability");
    }

    @Test
    void customerNeverDependsOnBookingOrCalendar() {
        assertThat(dependenciesOf("customer")).doesNotContain("booking", "calendar");
    }

    @Test
    void theWholeModuleGraphStaysAcyclicAndVerified() {
        modules.verify();
    }
}
