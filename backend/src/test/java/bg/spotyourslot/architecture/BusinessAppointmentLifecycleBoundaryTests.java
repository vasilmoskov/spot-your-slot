package bg.spotyourslot.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantees of the Business cancellation and internal-note edit (ADR-0029 sections 2
 * to 4 as amended by the Phase 6 note): which collaborators the bodies may use, the lock order as the
 * order of calls in their source, the persistence methods only they call, one clock read per body, the
 * READ COMMITTED single-attempt runner without any retry construct, and that neither class inspects a
 * cause, an SQLState, or a driver or persistence exception.
 */
class BusinessAppointmentLifecycleBoundaryTests {
    private static final String PROCEDURE = "bg.spotyourslot.booking.application.BusinessAppointmentLifecycleProcedure";
    private static final String SERVICE = "bg.spotyourslot.booking.application.BusinessAppointmentLifecycleService";
    private static final Path DIRECTORY = Path.of("src/main/java/bg/spotyourslot/booking/application");

    private final JavaClasses main = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("bg.spotyourslot");

    private static String code(String name) throws IOException {
        return Files.readString(DIRECTORY.resolve(name + ".java")).replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }

    /** The source of one method: from its signature to the next member signature. */
    private static String body(String code, String signature, String nextSignature) {
        int start = code.indexOf(signature);
        int end = code.indexOf(nextSignature, start);
        assertThat(start).as(signature).isGreaterThan(-1);
        assertThat(end).as(nextSignature).isGreaterThan(start);
        return code.substring(start, end);
    }

    private static void assertInOrder(String code, List<String> ordered) {
        int previous = -1;
        for (String call : ordered) {
            int position = code.indexOf(call);
            assertThat(position).as(call).isGreaterThan(-1);
            assertThat(position).as(call + " after the previous step").isGreaterThan(previous);
            previous = position;
        }
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void theBodiesUseOnlyTheBusinessLockTheOwnerLockAndTheAppointmentStore() {
        Set<String> targets = new TreeSet<>();
        for (Dependency dependency : main.get(PROCEDURE).getDirectDependenciesFromSelf()) {
            targets.add(dependency.getTargetClass().getName());
        }

        assertThat(targets).contains(
                AppointmentStore.class.getName(),
                "bg.spotyourslot.business.BusinessScheduleContextAccess",
                "bg.spotyourslot.identity.SelectedBusinessOwnerAccess");
        assertThat(targets).noneMatch(target -> target.startsWith("bg.spotyourslot.catalog")
                || target.startsWith("bg.spotyourslot.customer")
                || target.startsWith("bg.spotyourslot.workforce")
                || target.startsWith("bg.spotyourslot.scheduling")
                || target.equals("bg.spotyourslot.business.ScheduleRevisionGuard")
                || target.equals("bg.spotyourslot.booking.application.PublicReferenceSource"));
        assertThat(targets).noneMatch(target -> target.startsWith("java.sql")
                || target.startsWith("org.postgresql")
                || target.startsWith("org.springframework.dao")
                || target.startsWith("org.springframework.jdbc"));
    }

    @Test
    void theCancellationLocksTheBusinessThenTheMembershipThenReadsThenReadsTheClockThenUpdatesLast() throws IOException {
        String code = code("BusinessAppointmentLifecycleProcedure");
        assertInOrder(body(code, "Outcome cancel(", "Outcome editInternalNote("), List.of(
                "gate(userId, businessId)",
                "appointments.find(businessId, command.appointmentId())",
                "clock.instant()",
                "AppointmentTransitions.cancel(",
                "appointments.cancel(applied.update())",
                "classifyMissedUpdate(",
                "readBack("));
    }

    @Test
    void theNoteEditLocksTheBusinessThenTheMembershipThenReadsJudgesTheVersionThenReadsTheClockThenUpdatesLast()
            throws IOException {
        String code = code("BusinessAppointmentLifecycleProcedure");
        assertInOrder(body(code, "Outcome editInternalNote(", "private Rejection gate("), List.of(
                "gate(userId, businessId)",
                "appointments.find(businessId, command.appointmentId())",
                "current.version() != command.expectedVersion()",
                "clock.instant()",
                "AppointmentTransitions.editInternalNote(",
                "appointments.updateInternalNote(applied.update())",
                "classifyMissedUpdate(",
                "readBack("));
    }

    @Test
    void theGateIsTheBusinessLockThenTheMembershipLockThenTheLifecycleDecision() throws IOException {
        String code = code("BusinessAppointmentLifecycleProcedure");
        assertInOrder(body(code, "private Rejection gate(", "private static Rejection refusal("), List.of(
                "businesses.lockScheduleContextSanitized(",
                "owners.lockAndAuthorizeSanitized(",
                "LifecycleStatus.DRAFT",
                "LifecycleStatus.SUSPENDED"));
        assertThat(count(code, "businesses.lock")).isEqualTo(1);
        assertThat(count(code, "owners.lock")).isEqualTo(1);
    }

    @Test
    void eachBodyReadsTheClockExactlyOnceAndNoUnchangedNoteIssuesAnUpdate() throws IOException {
        String code = code("BusinessAppointmentLifecycleProcedure");
        assertThat(count(body(code, "Outcome cancel(", "Outcome editInternalNote("), "clock.instant()")).isEqualTo(1);
        assertThat(count(body(code, "Outcome editInternalNote(", "private Rejection gate("), "clock.instant()")).isEqualTo(1);
        assertThat(count(code, "clock.instant()")).isEqualTo(2);
        // The no-op branch returns before the only UPDATE of the note body.
        String edit = body(code, "Outcome editInternalNote(", "private Rejection gate(");
        assertThat(edit.indexOf("return new Unchanged(")).isGreaterThan(-1)
                .isLessThan(edit.indexOf("appointments.updateInternalNote("));
    }

    @Test
    void theBodiesWriteOnlyThroughTheTwoConditionalUpdatesAndContainNoSqlOrSleep() throws IOException {
        String code = code("BusinessAppointmentLifecycleProcedure");

        assertThat(count(code, "appointments.cancel(")).isEqualTo(1);
        assertThat(count(code, "appointments.updateInternalNote(")).isEqualTo(1);
        assertThat(code).doesNotContain("appointments.insert(").doesNotContain("appointments.reschedule(")
                .doesNotContain("INSERT").doesNotContain("UPDATE ").doesNotContain("DELETE")
                .doesNotContain("JdbcClient").doesNotContain("Thread.sleep").doesNotContain("@Transactional")
                .doesNotContain("REQUIRES_NEW").doesNotContain("Savepoint");
    }

    @Test
    void onlyTheLifecycleBodiesCallTheConditionalCancelAndNoteUpdates() {
        assertThat(callers(AppointmentStore.class.getName(), "cancel")).containsExactly(PROCEDURE);
        assertThat(callers(AppointmentStore.class.getName(), "updateInternalNote")).containsExactly(PROCEDURE);
    }

    @Test
    void theServiceIsNotTransactionalUsesOneReadCommittedRequiredTransactionAndHasNoRetryConstruct()
            throws IOException {
        String code = code("BusinessAppointmentLifecycleService");

        assertThat(code).doesNotContain("@Transactional").doesNotContain("REQUIRES_NEW")
                .doesNotContain("PROPAGATION_NESTED").doesNotContain("Savepoint").doesNotContain("Thread.sleep")
                .doesNotContain("TRANSACTION_REPEATABLE_READ").doesNotContain("TRANSACTION_SERIALIZABLE");
        assertThat(code).contains("PROPAGATION_REQUIRED").contains("TRANSACTION_READ_COMMITTED");
        // Exactly one transaction call per operation and no loop, attempt counter, or backoff.
        assertThat(count(code, "transaction.execute(")).isEqualTo(1);
        assertThat(code).doesNotContain("for (").doesNotContain("while (").doesNotContain("MAX_ATTEMPTS")
                .doesNotContain("attempt").doesNotContain("Backoff").doesNotContain("Retry");
    }

    @Test
    void theEntryGuardRunsBeforeTheContextIsReadAndBeforeAnyCollaborator() throws IOException {
        String code = code("BusinessAppointmentLifecycleService");
        assertInOrder(body(code, "private UpdatedAppointment perform(", "private static void requireNoCallerTransaction"),
                List.of("requireNoCallerTransaction();", "context.userId()", "selectedBusinessId()", "transaction.execute("));
    }

    @Test
    void neitherClassInspectsACauseAnSqlStateADriverOrAPersistenceException() throws IOException {
        for (String name : List.of(SERVICE, PROCEDURE)) {
            for (Dependency dependency : main.get(name).getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getName();
                assertThat(target).as(name + " -> " + target)
                        .doesNotStartWith("java.sql")
                        .doesNotStartWith("org.postgresql")
                        .doesNotStartWith("org.springframework.dao")
                        .doesNotStartWith("org.springframework.jdbc");
            }
        }
        for (String name : List.of("BusinessAppointmentLifecycleService", "BusinessAppointmentLifecycleProcedure")) {
            assertThat(code(name)).as(name).doesNotContain("getSQLState").doesNotContain("SQLException")
                    .doesNotContain("getCause").doesNotContain("getSuppressed");
        }
    }

    @Test
    void theOnlyConflictsMappedToTheStaleVersionResultAreThePublishedSanitizedTypes() throws IOException {
        String code = code("BusinessAppointmentLifecycleService");
        String published = body(code, "private static boolean isPublishedConflict(", "private static AppointmentAdministrationException rejection(");

        assertThat(published)
                .contains("BusinessScheduleContextAccess.ConcurrentConflict")
                .contains("SelectedBusinessOwnerAccess.ConcurrentConflict")
                .contains("AppointmentPersistenceException.ConcurrentFailure")
                .doesNotContain("RuntimeException").doesNotContain("Exception ")
                .doesNotContain("OverlapConflict").doesNotContain("DuplicatePublicReference")
                .doesNotContain("StaffBookingAccess").doesNotContain("ScheduleRevisionConcurrentConflict")
                .doesNotContain("ServiceBookingAccess");
    }

    private Set<String> callers(String owner, String method) {
        Set<String> callers = new TreeSet<>();
        for (JavaClass type : main) {
            for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
                if (call.getTargetOwner().getName().equals(owner) && call.getName().equals(method)
                        && !type.getName().equals(owner)) {
                    callers.add(type.getName());
                }
            }
        }
        return callers;
    }
}
