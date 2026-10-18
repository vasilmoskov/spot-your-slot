package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.AppointmentAdministration;
import bg.spotyourslot.booking.AppointmentAdministrationException;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduleAppointmentCommand;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.OfferedStart;
import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.AppointmentRescheduleOptions;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsException;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The read side and the write side of a reschedule agree on real PostgreSQL through the real
 * orchestration: every start the options offer is accepted, every start they do not offer is refused,
 * the Appointment does not conflict with itself while a different overlapping one still blocks,
 * adjacency is free, the StaffMember rules (the current one needs only to be active, a replacement must
 * be active and assigned to every stored Service) and the lifecycle, state, role, and tenant results are
 * the mutation's. Each accepted move runs on a fresh Appointment, so every offered start is judged from
 * the same state the options were read from. Nothing is mocked and no sleep is used.
 *
 * <p>The clock is 2026-10-02 11:00 in Sofia; the Appointment is Tuesday 2026-11-10 09:00 to 09:30 in
 * Sofia (UTC+02:00); both StaffMembers work 09:00 to 12:00 every day.
 */
class AppointmentRescheduleOptionsAgreeWithRescheduleIntegrationTests extends BusinessCalendarApiIntegrationTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @Autowired AppointmentRescheduleOptions options;
    @Autowired AppointmentAdministration administration;

    private Actor owner;
    private AuthenticatedBusinessContext context;
    private Tenant tenant;
    private UUID other;

    private record Context(UUID userId, UUID businessId) implements AuthenticatedBusinessContext {
        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.of(businessId);
        }
    }

    @BeforeEach
    void seed() {
        owner = api.owner("ACTIVE");
        context = new Context(owner.userId(), owner.businessId());
        tenant = tenant(owner.businessId(), "Мария Петрова", "Анна Иванова");
        availability.everyDay(owner.businessId(), tenant.staff(), "09:00", "12:00");
        other = staff(owner.businessId(), "Борис Георгиев", true);
        availability.assign(owner.businessId(), other, tenant.service());
        availability.everyDay(owner.businessId(), other, "09:00", "12:00");
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private UUID fresh() {
        return appointment(tenant, NOV_10, 30);
    }

    /** Removes every Appointment and line (the lines are immutable, so a delete is refused), test data only. */
    private void clearAppointments() {
        jdbc.sql("TRUNCATE appointment, appointment_service").update();
    }

    /** The different Appointment (10:00 local) and a cancelled one that the offers are judged against. */
    private void blockers() {
        appointment(tenant, Instant.parse("2026-11-10T08:00:00Z"), 30);
        appointment(tenant, Instant.parse("2026-11-10T09:00:00Z"), 30, AppointmentFixtures::cancelled);
    }

    private List<Instant> offered(AuthenticatedBusinessContext who, UUID appointment, UUID staff) {
        return options.availability(who, appointment, staff, DAY).starts().stream().map(OfferedStart::start).toList();
    }

    private void move(AuthenticatedBusinessContext who, UUID appointment, Instant start, UUID staffOrNull) {
        administration.reschedule(who, new RescheduleAppointmentCommand(appointment, 0L, start, staffOrNull));
    }

    /** The local grid of the working day and the hour around it, in quarter hours, plus off-grid minutes. */
    private static List<Instant> candidates() {
        List<Instant> all = new ArrayList<>();
        for (Instant start = Instant.parse("2026-11-10T05:00:00Z");
                start.isBefore(Instant.parse("2026-11-10T11:00:00Z")); start = start.plusSeconds(900)) {
            all.add(start);
            all.add(start.plusSeconds(60));
        }
        return all;
    }

    private static String simpleName(Throwable failure) {
        return failure.getClass().getSimpleName();
    }

    // ---- start agreement ------------------------------------------------------------------------------

    @Test
    void everyStartTheOptionsOfferIsAcceptedAndEveryOtherStartIsRefused() {
        blockers();
        int accepted = 0;
        int refused = 0;
        for (UUID staff : List.of(tenant.staff(), other)) {
            UUID subject = fresh();
            List<Instant> offered = offered(context, subject, staff);
            assertThat(offered).isNotEmpty();
            if (staff.equals(tenant.staff())) {
                // Next to and over the own interval is offered; over the different Appointment it is not.
                assertThat(offered).contains(Instant.parse("2026-11-10T07:15:00Z"), Instant.parse("2026-11-10T07:30:00Z"),
                        Instant.parse("2026-11-10T08:30:00Z")).doesNotContain(NOV_10,
                        Instant.parse("2026-11-10T07:45:00Z"), Instant.parse("2026-11-10T08:00:00Z"));
            }
            UUID target = staff.equals(tenant.staff()) ? null : staff;
            for (Instant start : candidates()) {
                if (offered.contains(start)) {
                    move(context, subject, start, target);
                    assertThat(jdbc.sql("SELECT start_at FROM appointment WHERE id = :id")
                            .param("id", subject).query(java.time.OffsetDateTime.class).single().toInstant())
                            .isEqualTo(start);
                    clearAppointments();
                    blockers();
                    subject = fresh();
                    accepted++;
                } else {
                    UUID refusedSubject = subject;
                    assertThatThrownBy(() -> move(context, refusedSubject, start, target))
                            .isInstanceOfAny(AppointmentAdministrationException.SlotUnavailable.class,
                                    AppointmentAdministrationException.InvalidInput.class);
                    refused++;
                }
            }
            clearAppointments();
            blockers();
        }
        assertThat(accepted).isGreaterThan(15);
        assertThat(refused).isGreaterThan(15);
    }

    @Test
    void theUnchangedStartIsNotOfferedToTheCurrentStaffMemberExactlyBecauseTheMutationRefusesNoChange() {
        UUID probe = fresh();

        assertThat(offered(context, probe, tenant.staff())).doesNotContain(NOV_10);
        assertThatThrownBy(() -> move(context, probe, NOV_10, null))
                .isInstanceOf(AppointmentAdministrationException.InvalidInput.class);
        // A reassignment to the same start is a change, and it is offered.
        assertThat(offered(context, probe, other)).contains(NOV_10);
        move(context, probe, NOV_10, other);
    }

    @Test
    void theStoredDurationOfAMultiServiceVisitIsWhatBothSidesUseWhateverTheServicesBecame() {
        UUID visit = visit(tenant, Instant.parse("2026-11-10T08:00:00Z"), "Първа", "Втора"); // 60 minutes
        jdbc.sql("UPDATE service SET duration_minutes = 15").update();
        jdbc.sql("UPDATE service SET active = false").update();

        List<Instant> offered = offered(context, visit, tenant.staff());
        assertThat(options.availability(context, visit, tenant.staff(), DAY).durationMinutes()).isEqualTo(60);
        assertThat(offered).contains(Instant.parse("2026-11-10T07:00:00Z"), Instant.parse("2026-11-10T08:15:00Z"))
                .doesNotContain(Instant.parse("2026-11-10T09:15:00Z"));

        move(context, visit, Instant.parse("2026-11-10T08:15:00Z"), null);
        UUID again = visit(tenant, Instant.parse("2026-11-12T08:00:00Z"), "Първа", "Втора");
        assertThatThrownBy(() -> administration.reschedule(context, new RescheduleAppointmentCommand(
                again, 0L, Instant.parse("2026-11-12T09:15:00Z"), null)))
                .isInstanceOf(AppointmentAdministrationException.SlotUnavailable.class);
    }

    // ---- StaffMember agreement ------------------------------------------------------------------------

    @Test
    void theCurrentStaffMemberNeedsToBeActiveOnlyAndTheMutationAgrees() {
        UUID probe = fresh();
        availability.unassign(owner.businessId(), tenant.staff(), tenant.service());
        assertThat(options.options(context, probe).currentStaffMember().available()).isTrue();
        List<Instant> offered = offered(context, probe, tenant.staff());
        assertThat(offered).isNotEmpty();
        move(context, probe, offered.getFirst(), null);
        clearAppointments();

        UUID inactive = fresh();
        availability.setStaffMemberActive(tenant.staff(), false);
        assertThat(options.options(context, inactive).currentStaffMember().available()).isFalse();
        AppointmentRescheduleOptionsException failure = catchOptions(() -> offered(context, inactive, tenant.staff()));
        assertThat(simpleName(failure)).isEqualTo("StaffUnavailable");
        assertThatThrownBy(() -> move(context, inactive, Instant.parse("2026-11-10T07:15:00Z"), null))
                .isInstanceOf(AppointmentAdministrationException.StaffUnavailable.class);
    }

    @Test
    void aReplacementIsEligibleExactlyWhenTheMutationLocksItAndEveryOtherCaseIsOneResult() {
        UUID inactive = staff(owner.businessId(), "Неактивен", false);
        availability.assign(owner.businessId(), inactive, tenant.service());
        availability.everyDay(owner.businessId(), inactive, "09:00", "12:00");
        UUID unassigned = staff(owner.businessId(), "Без услуга", true);
        availability.everyDay(owner.businessId(), unassigned, "09:00", "12:00");
        Actor foreignOwner = api.owner("ACTIVE");
        Tenant foreign = tenant(foreignOwner.businessId(), "ЧУЖД", "Чужд");
        availability.everyDay(foreignOwner.businessId(), foreign.staff(), "09:00", "12:00");
        Instant start = Instant.parse("2026-11-10T07:30:00Z");
        UUID probe = fresh();

        for (UUID candidate : List.of(inactive, unassigned, foreign.staff(), UUID.randomUUID())) {
            assertThat(simpleName(catchOptions(() -> offered(context, probe, candidate)))).isEqualTo("StaffUnavailable");
            assertThat(simpleName(catchAdministration(() -> move(context, probe, start, candidate))))
                    .isEqualTo("StaffUnavailable");
        }
        assertThat(options.options(context, probe).replacements()).extracting(member -> member.id())
                .containsExactly(other);
        assertThat(catchOptions(() -> offered(context, probe, other))).isNull();
        assertThat(catchAdministration(() -> move(context, probe, start, other))).isNull();
    }

    @Test
    void aReplacementMustBeAssignedToEveryStoredServiceOfAMultiServiceVisit() {
        UUID visit = visit(tenant, Instant.parse("2026-11-10T08:00:00Z"), "Първа", "Втора");
        UUID second = jdbc.sql("SELECT service_id FROM appointment_service WHERE appointment_id = :id AND position = 2")
                .param("id", visit).query(UUID.class).single();
        availability.assign(owner.businessId(), tenant.staff(), second);

        // 'other' has only the first Service: neither the options nor the mutation accept it.
        assertThat(simpleName(catchOptions(() -> offered(context, visit, other)))).isEqualTo("StaffUnavailable");
        assertThat(simpleName(catchAdministration(() -> move(context, visit, Instant.parse("2026-11-10T07:30:00Z"), other))))
                .isEqualTo("StaffUnavailable");
        assertThat(options.options(context, visit).replacements()).isEmpty();

        availability.assign(owner.businessId(), other, second);
        assertThat(options.options(context, visit).replacements()).extracting(member -> member.id())
                .containsExactly(other);
        move(context, visit, Instant.parse("2026-11-10T07:30:00Z"), other);
    }

    // ---- state, lifecycle, role, tenant ----------------------------------------------------------------

    @Test
    void aCancelledAndAStartedAppointmentExposeNoOptionsAndTheMutationRefusesThemToo() {
        UUID cancelled = appointment(tenant, NOV_10, 30, AppointmentFixtures::cancelled);
        UUID started = appointment(tenant, Instant.parse("2026-10-02T07:00:00Z"), 30);

        for (UUID id : List.of(cancelled, started)) {
            assertThat(simpleName(catchOptions(() -> options.options(context, id)))).isEqualTo("StateConflict");
            assertThat(simpleName(catchOptions(() -> offered(context, id, other)))).isEqualTo("StateConflict");
            assertThat(simpleName(catchAdministration(() -> administration.reschedule(
                    context, new RescheduleAppointmentCommand(id, 0L, Instant.parse("2026-11-10T07:30:00Z"), null)))))
                    .isEqualTo("StateConflict");
        }
    }

    @Test
    void lifecycleRoleAndTenantResultsAreTheMutationsResults() {
        UUID probe = fresh();
        Actor draft = api.owner("DRAFT");
        Tenant draftTenant = tenant(draft.businessId(), "Служител", "Клиент");
        UUID draftAppointment = appointment(draftTenant, NOV_10, 30);
        Actor suspended = api.owner("SUSPENDED");
        Tenant suspendedTenant = tenant(suspended.businessId(), "Служител", "Клиент");
        UUID suspendedAppointment = appointment(suspendedTenant, NOV_10, 30);
        Actor manager = api.actor("ACTIVE", "MANAGER", true, false, true);
        Actor stranger = api.actorWithForeignMembership();
        Instant start = Instant.parse("2026-11-10T07:30:00Z");

        record Case(String label, AuthenticatedBusinessContext who, UUID id, String expected) {
        }
        List<Case> cases = List.of(
                new Case("draft", new Context(draft.userId(), draft.businessId()), draftAppointment, "BusinessNotActive"),
                new Case("suspended", new Context(suspended.userId(), suspended.businessId()), suspendedAppointment,
                        "BusinessSuspended"),
                new Case("manager", new Context(manager.userId(), manager.businessId()), probe, "AccessDenied"),
                new Case("foreign membership", new Context(stranger.userId(), stranger.businessId()), probe, "AccessDenied"),
                new Case("missing", context, UUID.randomUUID(), "AppointmentNotFound"),
                new Case("foreign Appointment", context, draftAppointment, "AppointmentNotFound"));
        for (Case each : cases) {
            assertThat(simpleName(catchOptions(() -> options.options(each.who(), each.id())))).as(each.label())
                    .isEqualTo(each.expected());
            assertThat(simpleName(catchOptions(() -> options.availability(each.who(), each.id(), other, DAY))))
                    .as(each.label()).isEqualTo(each.expected());
            assertThat(simpleName(catchAdministration(() -> move(each.who(), each.id(), start, null))))
                    .as(each.label()).isEqualTo(each.expected());
        }
    }

    private static AppointmentRescheduleOptionsException catchOptions(Runnable call) {
        try {
            call.run();
            return null;
        } catch (AppointmentRescheduleOptionsException failure) {
            return failure;
        }
    }

    private static AppointmentAdministrationException catchAdministration(Runnable call) {
        try {
            call.run();
            return null;
        } catch (AppointmentAdministrationException failure) {
            return failure;
        }
    }
}
