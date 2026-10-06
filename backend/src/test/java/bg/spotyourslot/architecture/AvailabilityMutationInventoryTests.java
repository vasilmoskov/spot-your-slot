package bg.spotyourslot.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.business.ScheduleRevisionBump;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionStore;
import bg.spotyourslot.workforce.infrastructure.StaffWorkingScheduleStore;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The enumeration test required by ADR-0025: every SQL write statement in production code is
 * classified, so a new availability-affecting mutation cannot be added without a decision about
 * the schedule revision. The scan reads the production sources; a new or changed statement fails
 * here until it is added to the inventory below and to the audit table in ADR-0025.
 */
class AvailabilityMutationInventoryTests {
    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final Pattern WRITE = Pattern.compile(
            "\\b(INSERT INTO|UPDATE|DELETE FROM)\\s+([a-z_][a-z_0-9]*)");

    private enum Protection {
        /** Changes availability and must run behind a schedule revision bump. */
        BUMPED,
        /** Changes availability through a row that every booking locks FOR SHARE. */
        PROTECTED_BY_BOOKING_ROW_LOCK,
        /** Only adds availability, so it cannot invalidate a validated booking. */
        ADDS_AVAILABILITY_ONLY,
        /** The booking's own write. */
        BOOKING_WRITE,
        /** The revision row itself. */
        REVISION_ROW,
        /** Does not read or change availability or booking eligibility. */
        UNRELATED
    }

    private static final Map<String, Protection> INVENTORY = inventory();

    private static Map<String, Protection> inventory() {
        Map<String, Protection> inventory = new TreeMap<>();
        // Business lifecycle and profile (including timezone): the Business row is locked FOR SHARE.
        put(inventory, Protection.PROTECTED_BY_BOOKING_ROW_LOCK, "BusinessStore", "UPDATE", "business");
        put(inventory, Protection.ADDS_AVAILABILITY_ONLY, "BusinessStore", "INSERT", "business");
        // Service rows: the Service row is locked FOR SHARE.
        put(inventory, Protection.PROTECTED_BY_BOOKING_ROW_LOCK, "ServiceStore", "UPDATE", "service");
        put(inventory, Protection.ADDS_AVAILABILITY_ONLY, "ServiceStore", "INSERT", "service");
        // StaffMember rows and assignments: the StaffMember row is locked FOR SHARE and every
        // assignment change updates it.
        put(inventory, Protection.PROTECTED_BY_BOOKING_ROW_LOCK, "StaffMemberStore", "UPDATE", "staff_member");
        put(inventory, Protection.ADDS_AVAILABILITY_ONLY, "StaffMemberStore", "INSERT", "staff_member");
        put(inventory, Protection.PROTECTED_BY_BOOKING_ROW_LOCK,
                "StaffMemberStore", "INSERT", "staff_member_service");
        put(inventory, Protection.PROTECTED_BY_BOOKING_ROW_LOCK,
                "StaffMemberStore", "DELETE", "staff_member_service");
        // Recurring weekly schedule: bumped (creation of the empty schedule only adds nothing).
        put(inventory, Protection.ADDS_AVAILABILITY_ONLY,
                "StaffWorkingScheduleStore", "INSERT", "staff_working_schedule");
        put(inventory, Protection.BUMPED,
                "StaffWorkingScheduleStore", "UPDATE", "staff_working_schedule");
        put(inventory, Protection.BUMPED,
                "StaffWorkingScheduleStore", "DELETE", "staff_working_period");
        put(inventory, Protection.BUMPED,
                "StaffWorkingScheduleStore", "INSERT", "staff_working_period");
        // Schedule exceptions of all four kinds: bumped for every operation.
        for (String verb : new String[] {"INSERT", "UPDATE", "DELETE"}) {
            put(inventory, Protection.BUMPED, "ScheduleExceptionStore", verb, "schedule_exception");
        }
        put(inventory, Protection.BUMPED, "ScheduleExceptionStore", "DELETE", "schedule_exception_period");
        put(inventory, Protection.BUMPED, "ScheduleExceptionStore", "INSERT", "schedule_exception_period");
        // Booking and the revision itself.
        put(inventory, Protection.BOOKING_WRITE, "AppointmentStore", "INSERT", "appointment");
        put(inventory, Protection.REVISION_ROW,
                "ScheduleRevisionStore", "UPDATE", "business_schedule_revision");
        return inventory;
    }

    private static void put(
            Map<String, Protection> inventory,
            Protection protection,
            String store,
            String verb,
            String table) {
        inventory.put(store + " " + verb + " " + table, protection);
    }

    @Test
    void everyWriteStatementInProductionCodeIsClassifiedAndNoClassificationIsStale() throws IOException {
        Map<String, Set<String>> found = scanWrites();
        Set<String> classified = new TreeSet<>(INVENTORY.keySet());
        Set<String> unclassified = new TreeSet<>();
        for (Map.Entry<String, Set<String>> entry : found.entrySet()) {
            for (String statement : entry.getValue()) {
                String key = entry.getKey() + " " + statement;
                if (!classified.remove(key) && !isUnrelatedWrite(entry.getKey())) {
                    unclassified.add(key);
                }
            }
        }

        assertThat(unclassified)
                .as("write statements not in the ADR-0025 inventory; decide whether each needs a bump")
                .isEmpty();
        assertThat(classified).as("inventory entries with no statement in the source").isEmpty();
    }

    /**
     * Identity and Customer persistence neither read nor change availability or booking
     * eligibility (ADR-0025: unlisted mutation paths). They are matched by owning class.
     */
    private static boolean isUnrelatedWrite(String storeClass) {
        return storeClass.equals("IdentityStore") || storeClass.equals("CustomerStore");
    }

    @Test
    void everyBumpedTableIsWrittenOnlyByTheStoresWhoseCallersBumpTheRevision() {
        Set<String> bumpedStores = INVENTORY.entrySet().stream()
                .filter(entry -> entry.getValue() == Protection.BUMPED)
                .map(entry -> entry.getKey().substring(0, entry.getKey().indexOf(' ')))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(bumpedStores).containsExactly("ScheduleExceptionStore", "StaffWorkingScheduleStore");
    }

    @Test
    void everyProductionCallerOfABumpedWriteAlsoDependsOnTheRevisionBump() {
        JavaClasses main = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("bg.spotyourslot");
        Map<Class<?>, Set<String>> bumpedWrites = Map.of(
                ScheduleExceptionStore.class, Set.of("insert", "replace", "delete"),
                StaffWorkingScheduleStore.class, Set.of("advanceScheduleVersion", "replacePeriods"));

        int callers = 0;
        for (JavaClass type : main) {
            for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
                Set<String> writes = bumpedWrites.get(forName(call.getTargetOwner().getName()));
                if (writes != null && writes.contains(call.getName())) {
                    callers++;
                    String owner = outermost(type.getName());
                    assertThat(dependsOnBump(main, owner))
                            .as("%s calls %s.%s", owner, call.getTargetOwner().getSimpleName(), call.getName())
                            .isTrue();
                }
            }
        }
        // The weekly replacement (two writes) and the exception create, replace, and delete.
        assertThat(callers).isEqualTo(5);
    }

    private static boolean dependsOnBump(JavaClasses main, String ownerName) {
        return main.stream()
                .filter(type -> outermost(type.getName()).equals(ownerName))
                .flatMap(type -> type.getDirectDependenciesFromSelf().stream())
                .anyMatch(dependency ->
                        dependency.getTargetClass().getName().equals(ScheduleRevisionBump.class.getName()));
    }

    private static Class<?> forName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException notPublishedHere) {
            return Void.class;
        }
    }

    private static String outermost(String className) {
        return className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
    }

    /** Store class (simple name) to the distinct {@code VERB table} statements it contains. */
    private static Map<String, Set<String>> scanWrites() throws IOException {
        Map<String, Set<String>> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String simpleName = file.getFileName().toString().replace(".java", "");
                String code = Files.readAllLines(file).stream()
                        .filter(line -> {
                            String trimmed = line.trim();
                            return !trimmed.startsWith("*")
                                    && !trimmed.startsWith("//")
                                    && !trimmed.startsWith("/*");
                        })
                        .collect(Collectors.joining("\n"));
                Matcher matcher = WRITE.matcher(code);
                while (matcher.find()) {
                    String verb = matcher.group(1).split(" ")[0];
                    found.computeIfAbsent(simpleName, key -> new TreeSet<>())
                            .add(verb + " " + matcher.group(2));
                }
            }
        }
        return found;
    }
}
