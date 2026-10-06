package bg.spotyourslot.scheduling.application;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.await;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.awaitWaiterBlockedBy;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.backendPid;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.business.BusinessAdministration;
import bg.spotyourslot.business.BusinessRecords.BusinessDetails;
import bg.spotyourslot.business.BusinessRecords.UpdateBusinessCommand;
import bg.spotyourslot.business.ScheduleRevisionConcurrentConflict;
import bg.spotyourslot.business.ScheduleRevisionFailure;
import bg.spotyourslot.business.ScheduleRevisionGuard;
import bg.spotyourslot.catalog.ServiceAdministration;
import bg.spotyourslot.catalog.ServiceRecords.CreateServiceCommand;
import bg.spotyourslot.catalog.ServiceRecords.ServiceDetails;
import bg.spotyourslot.catalog.ServiceRecords.ServiceVersionCommand;
import bg.spotyourslot.catalog.ServiceRecords.UpdateServiceCommand;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.scheduling.ScheduleExceptionAdministration;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.BusinessAccessDenied;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.BusinessSuspended;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.ConcurrentUpdate;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.InvalidInput;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.OverlapConflict;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.ScheduleExceptionNotFound;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.StaffMemberInactive;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.StaffMemberNotFound;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.CreateScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ExceptionPeriod;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ReplaceScheduleExceptionCommand;
import bg.spotyourslot.scheduling.application.ScheduleExceptionTestSupport.Fixture;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionKind;
import bg.spotyourslot.workforce.StaffMemberAdministration;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.ReplaceServiceAssignmentsCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberDetails;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.UpdateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleAdministration;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.ReplaceWorkingPeriodsCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every availability-affecting mutation path of ADR-0025 against real PostgreSQL: the weekly
 * working-schedule replacement and every schedule-exception create, replace, and delete for all
 * four kinds. It proves that an accepted mutation advances the Business schedule revision exactly
 * once in the mutation's own transaction, that a rejected or rolled-back mutation changes neither
 * the schedule nor the revision, that the writes ADR-0025 leaves unbumped do not advance it, and
 * that the real mutations coordinate with the booking-facing guard.
 *
 * <p>The transaction that stands in for a booking uses only the published guard; it is not the
 * booking orchestration, whose real race tests belong to Phase 4.
 */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ScheduleRevisionMutationIntegrationTests extends PostgresIntegrationTest {
    private static final LocalDate DATE = LocalDate.of(2026, 12, 24);

    @Autowired ScheduleExceptionAdministration exceptions;
    @Autowired StaffWorkingScheduleAdministration schedules;
    @Autowired StaffMemberAdministration staffMembers;
    @Autowired ServiceAdministration services;
    @Autowired BusinessAdministration businesses;
    @Autowired ScheduleRevisionGuard guard;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private ScheduleExceptionTestSupport support;

    @BeforeEach
    void setUp() {
        support = new ScheduleExceptionTestSupport(jdbc);
    }

    // ---- the audited mutation paths ------------------------------------------

    enum Operation {
        CREATE,
        REPLACE,
        DELETE
    }

    /** One audited mutation path; a null kind is the weekly working-schedule replacement. */
    record Path(ScheduleExceptionKind kind, Operation operation) {
        @Override
        public String toString() {
            return kind == null ? "WEEKLY_SCHEDULE_REPLACE" : kind + "_" + operation;
        }
    }

    static Stream<Path> auditedPaths() {
        List<Path> paths = new ArrayList<>();
        paths.add(new Path(null, Operation.REPLACE));
        for (ScheduleExceptionKind kind : ScheduleExceptionKind.values()) {
            for (Operation operation : Operation.values()) {
                paths.add(new Path(kind, operation));
            }
        }
        return paths.stream();
    }

    @Test
    void theAuditedPathsCoverTheWeeklyReplacementAndEveryKindAndOperation() {
        assertThat(auditedPaths().toList()).hasSize(1 + ScheduleExceptionKind.values().length * 3);
        assertThat(ScheduleExceptionKind.values()).containsExactlyInAnyOrder(
                ScheduleExceptionKind.BUSINESS_CLOSURE,
                ScheduleExceptionKind.STAFF_TIME_OFF,
                ScheduleExceptionKind.WORKING_DAY_OVERRIDE,
                ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS);
    }

    @ParameterizedTest
    @MethodSource("auditedPaths")
    void anAcceptedMutationAdvancesTheRevisionByExactlyOneAndNoOtherBusinessRevision(Path path) {
        Subject subject = new Subject(path);
        Fixture other = support.fixture("ACTIVE");
        long baseline = subject.revision();
        long otherBaseline = revisionOf(other.businessId());
        String before = subject.aggregate();

        subject.mutate();

        assertThat(subject.revision()).isEqualTo(baseline + 1);
        assertThat(revisionOf(other.businessId())).isEqualTo(otherBaseline);
        assertThat(subject.aggregate()).isNotEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("auditedPaths")
    void aRolledBackEnclosingTransactionLeavesNeitherTheWriteNorTheBump(Path path) {
        Subject subject = new Subject(path);
        long baseline = subject.revision();
        String before = subject.aggregate();

        transaction().executeWithoutResult(status -> {
            subject.mutate();
            // Both writes are visible inside the enclosing transaction ...
            assertThat(subject.revision()).isEqualTo(baseline + 1);
            assertThat(subject.aggregate()).isNotEqualTo(before);
            status.setRollbackOnly();
        });

        // ... and neither is committed.
        assertThat(subject.revision()).isEqualTo(baseline);
        assertThat(subject.aggregate()).isEqualTo(before);
    }

    @Test
    void aFailureAfterTheBumpInAnEnclosingTransactionRollsBackEarlierMutationsToo() {
        Fixture fixture = support.fixture("ACTIVE");
        long baseline = revisionOf(fixture.businessId());

        Throwable failure = captureFailure(() -> transaction().executeWithoutResult(status -> {
            exceptions.create(fixture.context(), closure(DATE, DATE.plusDays(1)));
            exceptions.create(fixture.context(), closure(DATE.plusDays(1), DATE.plusDays(2)));
        }));

        assertThat(failure).isInstanceOf(OverlapConflict.class);
        assertThat(revisionOf(fixture.businessId())).isEqualTo(baseline);
        assertThat(support.exceptionCount(fixture.businessId())).isZero();
    }

    // ---- rejected mutations --------------------------------------------------

    @Test
    void aRejectedWeeklyReplacementChangesNeitherTheScheduleNorAnyRevision() {
        Subject subject = new Subject(new Path(null, Operation.REPLACE));
        Fixture foreign = support.fixture("ACTIVE");
        UUID foreignStaff = staffMembers.create(
                foreign.context(), new CreateStaffMemberCommand("Foreign", null, null)).id();
        UUID inactiveStaff = staffMembers.create(
                subject.fixture.context(), new CreateStaffMemberCommand("Inactive", null, null)).id();
        staffMembers.deactivate(
                subject.fixture.context(), inactiveStaff, new StaffMemberVersionCommand(0L));
        Fixture stranger = new Fixture(
                subject.fixture.businessId(),
                support.user(),
                new ScheduleExceptionTestSupport.TestContext(
                        support.user(), subject.fixture.businessId()));
        ReplaceWorkingPeriodsCommand valid = weekly(0L, monday());

        long baseline = subject.revision();
        long foreignBaseline = revisionOf(foreign.businessId());
        String before = subject.aggregate();
        String foreignBefore = aggregate(foreign.businessId());

        assertRejected(StaffWorkingScheduleApplicationException.ConcurrentUpdate.class, () ->
                schedules.replace(subject.fixture.context(), subject.staffMemberId, weekly(9L, monday())));
        assertRejected(StaffWorkingScheduleApplicationException.InvalidInput.class, () ->
                schedules.replace(subject.fixture.context(), subject.staffMemberId, weekly(
                        0L, monday(), new WorkingPeriod(
                                DayOfWeek.MONDAY, LocalTime.of(16, 0), LocalTime.of(18, 0)))));
        assertRejected(StaffWorkingScheduleApplicationException.InvalidInput.class, () ->
                schedules.replace(subject.fixture.context(), subject.staffMemberId,
                        new ReplaceWorkingPeriodsCommand(List.of(monday()), null)));
        assertRejected(StaffWorkingScheduleApplicationException.InvalidInput.class, () ->
                schedules.replace(subject.fixture.context(), subject.staffMemberId, null));
        assertRejected(StaffWorkingScheduleApplicationException.StaffMemberInactive.class, () ->
                schedules.replace(subject.fixture.context(), inactiveStaff, valid));
        assertRejected(StaffWorkingScheduleApplicationException.StaffMemberNotFound.class, () ->
                schedules.replace(subject.fixture.context(), UUID.randomUUID(), valid));
        assertRejected(StaffWorkingScheduleApplicationException.StaffMemberNotFound.class, () ->
                schedules.replace(subject.fixture.context(), foreignStaff, valid));
        assertRejected(StaffWorkingScheduleApplicationException.BusinessAccessDenied.class, () ->
                schedules.replace(stranger.context(), subject.staffMemberId, valid));
        assertRejected(AuthenticationCredentialsNotFoundException.class, () ->
                schedules.replace(null, subject.staffMemberId, valid));
        assertRejected(SelectedBusinessRequired.class, () -> schedules.replace(
                new ScheduleExceptionTestSupport.TestContext(subject.fixture.userId(), null),
                subject.staffMemberId,
                valid));
        support.setBusinessStatus(subject.fixture.businessId(), "SUSPENDED");
        assertRejected(StaffWorkingScheduleApplicationException.BusinessSuspended.class, () ->
                schedules.replace(subject.fixture.context(), subject.staffMemberId, valid));

        assertThat(subject.revision()).isEqualTo(baseline);
        assertThat(revisionOf(foreign.businessId())).isEqualTo(foreignBaseline);
        assertThat(subject.aggregate()).isEqualTo(before);
        assertThat(aggregate(foreign.businessId())).isEqualTo(foreignBefore);
    }

    @Test
    void aRejectedExceptionMutationChangesNeitherTheScheduleNorAnyRevision() {
        Fixture fixture = support.fixture("ACTIVE");
        Fixture foreign = support.fixture("ACTIVE");
        UUID staff = staffMembers.create(
                fixture.context(), new CreateStaffMemberCommand("Staff", null, null)).id();
        UUID inactive = staffMembers.create(
                fixture.context(), new CreateStaffMemberCommand("Inactive", null, null)).id();
        staffMembers.deactivate(fixture.context(), inactive, new StaffMemberVersionCommand(0L));
        UUID foreignStaff = staffMembers.create(
                foreign.context(), new CreateStaffMemberCommand("Foreign", null, null)).id();
        UUID existing = exceptions.create(
                fixture.context(), closure(DATE, DATE.plusDays(1))).exception().id();
        UUID foreignException = exceptions.create(
                foreign.context(), closure(DATE, DATE)).exception().id();
        Fixture stranger = new Fixture(
                fixture.businessId(),
                support.user(),
                new ScheduleExceptionTestSupport.TestContext(support.user(), fixture.businessId()));

        long baseline = revisionOf(fixture.businessId());
        long foreignBaseline = revisionOf(foreign.businessId());
        String before = aggregate(fixture.businessId());
        String foreignBefore = aggregate(foreign.businessId());

        // Validation
        assertRejected(InvalidInput.class, () -> exceptions.create(fixture.context(),
                new CreateScheduleExceptionCommand(
                        ScheduleExceptionKind.BUSINESS_CLOSURE, null, DATE, DATE, true,
                        List.of(period(9, 10)))));
        assertRejected(InvalidInput.class, () -> exceptions.create(fixture.context(),
                new CreateScheduleExceptionCommand(
                        ScheduleExceptionKind.STAFF_TIME_OFF, null, DATE, DATE, true, List.of())));
        assertRejected(InvalidInput.class, () -> exceptions.replace(
                fixture.context(), existing,
                new ReplaceScheduleExceptionCommand(-1L, DATE, DATE, true, List.of())));
        assertRejected(InvalidInput.class, () -> exceptions.delete(fixture.context(), existing, null));
        // Stale optimistic versions
        assertRejected(ConcurrentUpdate.class, () -> exceptions.replace(
                fixture.context(), existing,
                new ReplaceScheduleExceptionCommand(7L, DATE, DATE, true, List.of())));
        assertRejected(ConcurrentUpdate.class, () -> exceptions.delete(fixture.context(), existing, 7L));
        // Not found, including another Business's exception
        assertRejected(ScheduleExceptionNotFound.class, () -> exceptions.delete(
                fixture.context(), UUID.randomUUID(), 0L));
        assertRejected(ScheduleExceptionNotFound.class, () -> exceptions.delete(
                fixture.context(), foreignException, 0L));
        assertRejected(ScheduleExceptionNotFound.class, () -> exceptions.replace(
                fixture.context(), foreignException,
                new ReplaceScheduleExceptionCommand(0L, DATE, DATE, true, List.of())));
        // StaffMember state
        assertRejected(StaffMemberInactive.class, () -> exceptions.create(
                fixture.context(), timeOff(inactive, DATE)));
        assertRejected(StaffMemberNotFound.class, () -> exceptions.create(
                fixture.context(), timeOff(UUID.randomUUID(), DATE)));
        assertRejected(StaffMemberNotFound.class, () -> exceptions.create(
                fixture.context(), timeOff(foreignStaff, DATE)));
        // Authorization
        assertRejected(BusinessAccessDenied.class, () -> exceptions.create(
                stranger.context(), closure(DATE.plusDays(30), DATE.plusDays(30))));
        assertRejected(AuthenticationCredentialsNotFoundException.class, () -> exceptions.create(
                null, closure(DATE.plusDays(30), DATE.plusDays(30))));
        assertRejected(SelectedBusinessRequired.class, () -> exceptions.create(
                new ScheduleExceptionTestSupport.TestContext(fixture.userId(), null),
                closure(DATE.plusDays(30), DATE.plusDays(30))));
        // A rejection raised only after the bump: the database refuses the overlapping range.
        assertRejected(OverlapConflict.class, () -> exceptions.create(
                fixture.context(), closure(DATE.plusDays(1), DATE.plusDays(2))));
        exceptions.create(fixture.context(), timeOffFor(staff, DATE, DATE.plusDays(1)));
        assertRejected(OverlapConflict.class, () -> exceptions.create(
                fixture.context(), timeOffFor(staff, DATE.plusDays(1), DATE.plusDays(3))));
        // Lifecycle
        support.setBusinessStatus(fixture.businessId(), "SUSPENDED");
        assertRejected(BusinessSuspended.class, () -> exceptions.delete(
                fixture.context(), existing, 0L));
        assertRejected(BusinessSuspended.class, () -> exceptions.create(
                fixture.context(), closure(DATE.plusDays(40), DATE.plusDays(40))));

        // Only the one accepted creation above advanced the revision and changed the schedule.
        assertThat(revisionOf(fixture.businessId())).isEqualTo(baseline + 1);
        assertThat(aggregate(fixture.businessId())).isNotEqualTo(before);
        assertThat(support.exceptionCount(fixture.businessId())).isEqualTo(2);
        assertThat(revisionOf(foreign.businessId())).isEqualTo(foreignBaseline);
        assertThat(aggregate(foreign.businessId())).isEqualTo(foreignBefore);
    }

    @Test
    void aMissingRevisionRowFailsEveryMutationSanitizedAndLeavesTheScheduleUnchanged() {
        Subject weekly = new Subject(new Path(null, Operation.REPLACE));
        Subject replaced = new Subject(new Path(ScheduleExceptionKind.BUSINESS_CLOSURE, Operation.REPLACE));
        Subject deleted = new Subject(new Path(ScheduleExceptionKind.STAFF_TIME_OFF, Operation.DELETE));
        Subject created = new Subject(new Path(ScheduleExceptionKind.WORKING_DAY_OVERRIDE, Operation.CREATE));

        for (Subject subject : List.of(weekly, replaced, deleted, created)) {
            jdbc.sql("DELETE FROM business_schedule_revision WHERE business_id = :id")
                    .param("id", subject.fixture.businessId())
                    .update();
            String before = subject.aggregate();

            Throwable failure = captureFailure(subject::mutate);

            assertThat(failure).as(subject.path.toString()).isExactlyInstanceOf(ScheduleRevisionFailure.class);
            assertThat(failure.getMessage()).doesNotContain(subject.fixture.businessId().toString());
            assertThat(failure.getCause()).isNull();
            assertThat(subject.aggregate()).isEqualTo(before);
            assertThat(jdbc.sql("""
                            SELECT count(*) FROM business_schedule_revision WHERE business_id = :id
                            """)
                    .param("id", subject.fixture.businessId())
                    .query(Long.class).single()).isZero();
        }
    }

    // ---- writes that ADR-0025 deliberately leaves unbumped ---------------------

    @Test
    void lifecycleTimezoneServiceStaffMemberAndAssignmentWritesDoNotAdvanceTheRevision() {
        Fixture fixture = support.fixture("ACTIVE");
        long baseline = revisionOf(fixture.businessId());
        List<String> steps = new ArrayList<>();

        BusinessDetails business = businesses.get(fixture.businessId());
        business = businesses.update(fixture.businessId(), new UpdateBusinessCommand(
                business.slug(),
                business.displayName(),
                business.businessType(),
                "Europe/London",
                business.description(),
                business.city(),
                business.postalCode(),
                business.street(),
                business.streetNumber(),
                business.addressDetails(),
                business.phone(),
                business.contactEmail(),
                business.version()));
        assertThat(business.timezone()).isEqualTo("Europe/London");
        assertUnchanged(fixture, baseline, steps, "business timezone update");
        business = businesses.suspendActive(fixture.businessId(), business.version());
        assertUnchanged(fixture, baseline, steps, "suspension");
        businesses.reactivateSuspended(fixture.businessId(), business.version());
        assertUnchanged(fixture, baseline, steps, "reactivation");

        ServiceDetails service = services.create(fixture.context(), new CreateServiceCommand(
                "Haircut", "Description", 30, new BigDecimal("20.00")));
        assertUnchanged(fixture, baseline, steps, "service creation");
        service = services.update(fixture.context(), service.id(), new UpdateServiceCommand(
                "Haircut long", "Description", 45, new BigDecimal("25.00"), service.version()));
        assertUnchanged(fixture, baseline, steps, "service update");
        service = services.deactivate(
                fixture.context(), service.id(), new ServiceVersionCommand(service.version()));
        assertUnchanged(fixture, baseline, steps, "service deactivation");
        service = services.reactivate(
                fixture.context(), service.id(), new ServiceVersionCommand(service.version()));
        assertUnchanged(fixture, baseline, steps, "service reactivation");

        StaffMemberDetails staff = staffMembers.create(
                fixture.context(), new CreateStaffMemberCommand("Staff", null, null));
        assertUnchanged(fixture, baseline, steps, "StaffMember creation");
        staff = staffMembers.update(fixture.context(), staff.id(), new UpdateStaffMemberCommand(
                "Staff renamed", null, null, staff.version()));
        assertUnchanged(fixture, baseline, steps, "StaffMember update");
        staffMembers.replaceServiceAssignments(
                fixture.context(),
                staff.id(),
                new ReplaceServiceAssignmentsCommand(List.of(service.id()), staff.version()));
        assertUnchanged(fixture, baseline, steps, "assignment replacement");
        staff = staffMembers.get(fixture.context(), staff.id());
        staff = staffMembers.deactivate(
                fixture.context(), staff.id(), new StaffMemberVersionCommand(staff.version()));
        assertUnchanged(fixture, baseline, steps, "StaffMember deactivation");
        staffMembers.reactivate(
                fixture.context(), staff.id(), new StaffMemberVersionCommand(staff.version()));
        assertUnchanged(fixture, baseline, steps, "StaffMember reactivation");

        assertThat(steps).hasSize(12);
        assertThat(revisionOf(fixture.businessId())).isEqualTo(baseline).isZero();
    }

    // ---- the ADR-0025 guarantee with the real mutations -----------------------

    @ParameterizedTest
    @MethodSource("auditedPaths")
    void aStandInBookingWhoseSnapshotPredatesTheCommittedMutationFailsTheGuardAndWritesNothing(Path path) {
        Subject subject = new Subject(path);
        long baseline = subject.revision();
        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch mutated = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            CompletableFuture<Throwable> booking = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> snapshotTransaction().executeWithoutResult(status -> {
                        // The first statement fixes the repeatable-read snapshot before the change.
                        assertThat(subject.revision()).isEqualTo(baseline);
                        snapshotTaken.countDown();
                        await(mutated, "the mutation did not commit");
                        guard.lockShared(subject.fixture.businessId());
                    })),
                    executor);
            await(snapshotTaken, "the snapshot was not taken");

            subject.mutate();
            mutated.countDown();

            Throwable outcome = completed(booking);
            assertThat(outcome).isExactlyInstanceOf(ScheduleRevisionConcurrentConflict.class);
            assertThat(outcome.getMessage()).doesNotContain(subject.fixture.businessId().toString());
            assertThat(subject.revision()).isEqualTo(baseline + 1);
        } finally {
            mutated.countDown();
        }
    }

    @ParameterizedTest
    @MethodSource("auditedPaths")
    void aHeldGuardMakesTheMutationWaitAtTheRevisionRowAndTheMutationCommitsAfterTheStandIn(Path path) {
        Subject subject = new Subject(path);
        long baseline = subject.revision();
        String before = subject.aggregate();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger guardPid = new AtomicInteger();
        AtomicLong standInCommittedAt = new AtomicLong();
        AtomicLong mutationCompletedAt = new AtomicLong();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> booking = CompletableFuture.runAsync(
                    () -> snapshotTransaction().executeWithoutResult(status -> {
                        guardPid.set(backendPid(jdbc));
                        guard.lockShared(subject.fixture.businessId());
                        TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                    @Override
                                    public void afterCommit() {
                                        standInCommittedAt.set(System.nanoTime());
                                    }
                                });
                        holding.countDown();
                        await(release, "the stand-in booking was not released");
                    }),
                    executor);
            await(holding, "the guard did not lock");

            CompletableFuture<Void> mutation = CompletableFuture.runAsync(() -> {
                subject.mutate();
                mutationCompletedAt.set(System.nanoTime());
            }, executor);
            var waiting = awaitWaiterBlockedBy(jdbc, guardPid.get());
            assertThat(waiting.query()).containsIgnoringCase("business_schedule_revision");
            assertThat(mutation).isNotDone();
            assertThat(subject.revision()).isEqualTo(baseline);
            assertThat(subject.aggregate()).isEqualTo(before);
            release.countDown();

            completed(booking);
            completed(mutation);
            assertThat(standInCommittedAt.get()).isPositive();
            assertThat(mutationCompletedAt.get()).isGreaterThan(standInCommittedAt.get());
            assertThat(subject.revision()).isEqualTo(baseline + 1);
            assertThat(subject.aggregate()).isNotEqualTo(before);
        } finally {
            release.countDown();
        }
    }

    @Test
    void controlWithoutTheGuardAStaleSnapshotNeverLearnsOfTheCommittedChange() {
        Fixture fixture = support.fixture("ACTIVE");
        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch mutated = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            CompletableFuture<Throwable> booking = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> snapshotTransaction().executeWithoutResult(status -> {
                        assertThat(support.exceptionCount(fixture.businessId())).isZero();
                        snapshotTaken.countDown();
                        await(mutated, "the mutation did not commit");
                        // The closure is committed, but this snapshot still shows none and nothing
                        // signals the change: the silent write skew the guard exists to prevent.
                        assertThat(support.exceptionCount(fixture.businessId())).isZero();
                    })),
                    executor);
            await(snapshotTaken, "the snapshot was not taken");
            exceptions.create(fixture.context(), closure(DATE, DATE));
            mutated.countDown();

            assertThat(completed(booking)).isNull();
            assertThat(support.exceptionCount(fixture.businessId())).isEqualTo(1);
        } finally {
            mutated.countDown();
        }
    }

    // ---- helpers -------------------------------------------------------------

    /** One Business with an owner, an active StaffMember, and the setup the path needs. */
    private final class Subject {
        private final Path path;
        private final Fixture fixture;
        private final UUID staffMemberId;
        private UUID existingExceptionId;

        Subject(Path path) {
            this.path = path;
            this.fixture = support.fixture("ACTIVE");
            this.staffMemberId = staffMembers.create(
                    fixture.context(), new CreateStaffMemberCommand("Revision Staff", null, null)).id();
            if (path.kind() != null && path.operation() != Operation.CREATE) {
                existingExceptionId = exceptions.create(
                        fixture.context(), createCommand(path.kind())).exception().id();
            }
        }

        void mutate() {
            if (path.kind() == null) {
                schedules.replace(
                        fixture.context(), staffMemberId, weekly(0L, monday()));
            } else if (path.operation() == Operation.CREATE) {
                exceptions.create(fixture.context(), createCommand(path.kind()));
            } else if (path.operation() == Operation.REPLACE) {
                exceptions.replace(
                        fixture.context(), existingExceptionId, replaceCommand(path.kind()));
            } else {
                exceptions.delete(fixture.context(), existingExceptionId, 0L);
            }
        }

        long revision() {
            return revisionOf(fixture.businessId());
        }

        String aggregate() {
            return ScheduleRevisionMutationIntegrationTests.this.aggregate(fixture.businessId());
        }

        private CreateScheduleExceptionCommand createCommand(ScheduleExceptionKind kind) {
            return switch (kind) {
                case BUSINESS_CLOSURE -> closure(DATE, DATE);
                case STAFF_TIME_OFF -> timeOff(staffMemberId, DATE);
                case WORKING_DAY_OVERRIDE -> new CreateScheduleExceptionCommand(
                        kind, staffMemberId, DATE, DATE, false, List.of(period(10, 14)));
                case ADDITIONAL_WORKING_PERIODS -> new CreateScheduleExceptionCommand(
                        kind, staffMemberId, DATE, DATE, false, List.of(period(18, 20)));
            };
        }

        private ReplaceScheduleExceptionCommand replaceCommand(ScheduleExceptionKind kind) {
            return switch (kind) {
                case BUSINESS_CLOSURE, STAFF_TIME_OFF -> new ReplaceScheduleExceptionCommand(
                        0L, DATE, DATE.plusDays(1), true, List.of());
                case WORKING_DAY_OVERRIDE -> new ReplaceScheduleExceptionCommand(
                        0L, DATE, DATE, false, List.of(period(11, 15)));
                case ADDITIONAL_WORKING_PERIODS -> new ReplaceScheduleExceptionCommand(
                        0L, DATE, DATE, false, List.of(period(19, 21)));
            };
        }
    }

    private void assertUnchanged(Fixture fixture, long baseline, List<String> steps, String step) {
        assertThat(revisionOf(fixture.businessId())).as(step).isEqualTo(baseline);
        steps.add(step);
    }

    private static <T extends Throwable> void assertRejected(Class<T> type, Runnable operation) {
        assertThat(captureFailure(operation)).isInstanceOf(type);
    }

    private long revisionOf(UUID businessId) {
        return jdbc.sql("SELECT revision FROM business_schedule_revision WHERE business_id = :id")
                .param("id", businessId)
                .query(Long.class)
                .single();
    }

    /** Everything an audited mutation can change for one Business, as one comparable string. */
    private String aggregate(UUID businessId) {
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM schedule_exception
                                WHERE business_id = :id)
                            || ':' || (SELECT coalesce(sum(version), 0) FROM schedule_exception
                                WHERE business_id = :id)
                            || ':' || (SELECT count(*) FROM schedule_exception_period
                                WHERE business_id = :id)
                            || ':' || (SELECT coalesce(sum(version), 0) FROM staff_working_schedule
                                WHERE business_id = :id)
                            || ':' || (SELECT count(*) FROM staff_working_period
                                WHERE business_id = :id)
                        """)
                .param("id", businessId)
                .query(String.class)
                .single();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private TransactionTemplate snapshotTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        return template;
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static WorkingPeriod monday() {
        return new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0));
    }

    private static ReplaceWorkingPeriodsCommand weekly(long expectedVersion, WorkingPeriod... periods) {
        return new ReplaceWorkingPeriodsCommand(List.of(periods), expectedVersion);
    }

    private static CreateScheduleExceptionCommand closure(LocalDate first, LocalDate last) {
        return new CreateScheduleExceptionCommand(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, first, last, true, List.of());
    }

    private static CreateScheduleExceptionCommand timeOff(UUID staffMemberId, LocalDate date) {
        return timeOffFor(staffMemberId, date, date);
    }

    private static CreateScheduleExceptionCommand timeOffFor(
            UUID staffMemberId, LocalDate first, LocalDate last) {
        return new CreateScheduleExceptionCommand(
                ScheduleExceptionKind.STAFF_TIME_OFF, staffMemberId, first, last, true, List.of());
    }

    private static ExceptionPeriod period(int startHour, int endHour) {
        return new ExceptionPeriod(LocalTime.of(startHour, 0), LocalTime.of(endHour, 0));
    }
}
