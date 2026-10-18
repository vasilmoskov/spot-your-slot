package bg.spotyourslot.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.OfferedStart;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsException;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsRecords.RescheduleAvailability;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsRecords.RescheduleOptions;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.domain.AppointmentCancelledBy;
import bg.spotyourslot.booking.domain.AppointmentServiceLine;
import bg.spotyourslot.booking.domain.AppointmentSource;
import bg.spotyourslot.booking.domain.AppointmentStatus;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.StaffMemberNotEligible;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySlot;
import bg.spotyourslot.scheduling.AvailabilityRecords.BusinessAvailability;
import bg.spotyourslot.workforce.PublicStaffAccess;
import bg.spotyourslot.workforce.PublicStaffAccess.PublicStaffMember;
import bg.spotyourslot.workforce.StaffAvailabilityAccess;
import bg.spotyourslot.workforce.StaffAvailabilityAccess.EligibleStaffMember;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Deterministic unit tests of the decisions and the delegation of
 * {@link AppointmentRescheduleOptionsService} (the persistence and the agreement with the real
 * reschedule are proved on PostgreSQL by the integration tests). Every collaborator is a stub.
 */
class AppointmentRescheduleOptionsServiceTests {
    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private static final Instant NOW = Instant.parse("2026-10-02T08:00:00.123456789Z");
    private static final Instant CREATED = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant START = Instant.parse("2026-11-10T07:00:00Z");

    private final UUID business = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID appointmentId = UUID.randomUUID();
    private final UUID current = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final AtomicInteger clockReads = new AtomicInteger();

    private BusinessScheduleContextAccess businesses;
    private SelectedBusinessOwnerAccess owners;
    private AppointmentStore appointments;
    private PublicStaffAccess staffAccess;
    private StaffAvailabilityAccess activity;
    private AvailabilityQuery availability;
    private AppointmentRescheduleOptionsService service;
    private final AuthenticatedBusinessContext context = new AuthenticatedBusinessContext() {
        @Override
        public UUID userId() {
            return user;
        }

        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.of(business);
        }
    };

    @BeforeEach
    void wire() {
        businesses = mock(BusinessScheduleContextAccess.class);
        owners = mock(SelectedBusinessOwnerAccess.class);
        appointments = mock(AppointmentStore.class);
        staffAccess = mock(PublicStaffAccess.class);
        activity = mock(StaffAvailabilityAccess.class);
        availability = mock(AvailabilityQuery.class);
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                clockReads.incrementAndGet();
                return NOW;
            }
        };
        service = new AppointmentRescheduleOptionsService(
                businesses, owners, appointments, staffAccess, activity, availability, clock);
        when(businesses.findScheduleContext(business))
                .thenReturn(Optional.of(new BusinessScheduleContext(business, LifecycleStatus.ACTIVE, SOFIA)));
        when(owners.authorize(user, business)).thenReturn(Authorization.GRANTED);
        store(row(AppointmentStatus.CONFIRMED, START));
        when(staffAccess.findBookableStaffForServices(any(), anyCollection())).thenReturn(List.of(
                new PublicStaffMember(current, "Мария Петрова"), new PublicStaffMember(other, "Анна Бойчева")));
        when(activity.findActiveWithWeeklyPeriods(business, current))
                .thenReturn(Optional.of(new EligibleStaffMember(current, List.of())));
    }

    private Appointment row(AppointmentStatus status, Instant start) {
        boolean cancelled = status == AppointmentStatus.CANCELLED;
        Instant end = start.plus(Duration.ofMinutes(75));
        return new Appointment(
                appointmentId, business, UUID.randomUUID(), first, current, AppointmentSource.MANUAL, status,
                start, end, end, "Europe/Sofia", 75, new BigDecimal("50.00"), "Първа", "Снимка на името", null,
                "ABCDEFGHJK", null, 4L, CREATED, CREATED, cancelled ? CREATED : null,
                cancelled ? AppointmentCancelledBy.BUSINESS : null, null,
                List.of(new AppointmentServiceLine(1, first, "Първа", 30, new BigDecimal("20.00")),
                        new AppointmentServiceLine(2, second, "Втора", 45, new BigDecimal("30.00"))));
    }

    private void store(Appointment appointment) {
        when(appointments.find(business, appointmentId)).thenReturn(Optional.of(appointment));
    }

    private BusinessAvailability offered(AvailabilitySlot... slots) {
        return new BusinessAvailability(SOFIA, NOW, Duration.ofMinutes(75), DAY, DAY, List.of(slots));
    }

    private AvailabilitySlot slot(String start, UUID... members) {
        Instant instant = Instant.parse(start);
        // The slot contract wants distinct identifiers in ascending order.
        List<UUID> ordered = java.util.Arrays.stream(members).sorted().toList();
        return new AvailabilitySlot(instant, instant.plus(Duration.ofMinutes(75)), ZoneOffset.ofHours(2), ordered);
    }

    private static void denies(Runnable call, Class<? extends AppointmentRescheduleOptionsException> type) {
        assertThatThrownBy(call::run).isInstanceOf(type);
    }

    // ---- options -----------------------------------------------------------------------------------

    @Test
    void theOptionsUseTheStoredDurationAndTheStoredServiceIdentitiesNotTheCurrentServices() {
        RescheduleOptions options = service.options(context, appointmentId);

        assertThat(options.timezone()).isEqualTo(SOFIA);
        assertThat(options.durationMinutes()).isEqualTo(75);
        verify(staffAccess).findBookableStaffForServices(business, List.of(first, second));
        verifyNoInteractions(availability);
        assertThat(clockReads).hasValue(1);
    }

    @Test
    void theCurrentStaffMemberIsReportedWithItsStoredNameAndNeverListedAsAReplacement() {
        RescheduleOptions options = service.options(context, appointmentId);

        assertThat(options.currentStaffMember().id()).isEqualTo(current);
        assertThat(options.currentStaffMember().displayName()).isEqualTo("Снимка на името");
        assertThat(options.currentStaffMember().available()).isTrue();
        assertThat(options.replacements()).singleElement().satisfies(member -> {
            assertThat(member.id()).isEqualTo(other);
            assertThat(member.displayName()).isEqualTo("Анна Бойчева");
        });
    }

    @Test
    void anInactiveCurrentStaffMemberIsNotAvailableToKeep() {
        when(activity.findActiveWithWeeklyPeriods(business, current)).thenReturn(Optional.empty());

        assertThat(service.options(context, appointmentId).currentStaffMember().available()).isFalse();
    }

    @Test
    void aCurrentStaffMemberWhoLostTheAssignmentIsStillAvailableToKeep() {
        when(staffAccess.findBookableStaffForServices(any(), anyCollection()))
                .thenReturn(List.of(new PublicStaffMember(other, "Анна Бойчева")));

        RescheduleOptions options = service.options(context, appointmentId);

        assertThat(options.currentStaffMember().available()).isTrue();
        assertThat(options.replacements()).extracting(member -> member.id()).containsExactly(other);
    }

    // ---- state --------------------------------------------------------------------------------------

    @Test
    void aMissingAppointmentIsNotFoundAndNothingElseIsRead() {
        when(appointments.find(business, appointmentId)).thenReturn(Optional.empty());

        denies(() -> service.options(context, appointmentId), AppointmentRescheduleOptionsException.AppointmentNotFound.class);
        denies(() -> service.availability(context, appointmentId, current, DAY),
                AppointmentRescheduleOptionsException.AppointmentNotFound.class);
        verifyNoInteractions(staffAccess, activity, availability);
        assertThat(clockReads).hasValue(0);
    }

    @Test
    void aCancelledAppointmentAndAStartedOneAreAStateConflictWithoutOptions() {
        store(row(AppointmentStatus.CANCELLED, START));
        denies(() -> service.options(context, appointmentId), AppointmentRescheduleOptionsException.StateConflict.class);
        denies(() -> service.availability(context, appointmentId, other, DAY),
                AppointmentRescheduleOptionsException.StateConflict.class);

        // A start equal to the clock instant has started (the clock is truncated to microseconds).
        store(row(AppointmentStatus.CONFIRMED, NOW.minusNanos(123_456_789).plusNanos(123_456_000)));
        denies(() -> service.options(context, appointmentId), AppointmentRescheduleOptionsException.StateConflict.class);
        verifyNoInteractions(staffAccess, activity, availability);
    }

    // ---- availability ---------------------------------------------------------------------------------

    @Test
    void theEngineIsCalledWithZeroNoticeTheStoredDurationTheOneStaffMemberOneDateAndOnlyThisAppointmentIgnored() {
        when(availability.calculateForBusiness(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(offered(slot("2026-11-10T07:15:00Z", other)));

        RescheduleAvailability result = service.availability(context, appointmentId, other, DAY);

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(availability).calculateForBusiness(
                org.mockito.ArgumentMatchers.eq(business),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(75)),
                org.mockito.ArgumentMatchers.eq(other),
                org.mockito.ArgumentMatchers.eq(DAY),
                org.mockito.ArgumentMatchers.eq(DAY),
                now.capture(),
                org.mockito.ArgumentMatchers.eq(appointmentId));
        assertThat(now.getValue()).isEqualTo(Instant.parse("2026-10-02T08:00:00.123456Z"));
        assertThat(clockReads).hasValue(1);
        assertThat(result.durationMinutes()).isEqualTo(75);
        assertThat(result.starts()).extracting(OfferedStart::start).containsExactly(Instant.parse("2026-11-10T07:15:00Z"));
    }

    @Test
    void theCurrentStartIsNotOfferedToTheCurrentStaffMemberButIsToAReplacement() {
        when(availability.calculateForBusiness(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(offered(slot("2026-11-10T07:00:00Z", current, other), slot("2026-11-10T07:15:00Z", current, other)));

        assertThat(service.availability(context, appointmentId, current, DAY).starts())
                .extracting(OfferedStart::start).containsExactly(Instant.parse("2026-11-10T07:15:00Z"));
        assertThat(service.availability(context, appointmentId, other, DAY).starts())
                .extracting(OfferedStart::start)
                .containsExactly(Instant.parse("2026-11-10T07:00:00Z"), Instant.parse("2026-11-10T07:15:00Z"));
    }

    @Test
    void onlyStartsOfTheRequestedStaffMemberAndDateAreReturned() {
        when(availability.calculateForBusiness(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(offered(
                        slot("2026-11-10T07:15:00Z", UUID.randomUUID()),
                        slot("2026-11-10T07:30:00Z", other),
                        slot("2026-11-11T07:30:00Z", other)));

        assertThat(service.availability(context, appointmentId, other, DAY).starts())
                .extracting(OfferedStart::start).containsExactly(Instant.parse("2026-11-10T07:30:00Z"));
    }

    @Test
    void keepingTheCurrentStaffMemberNeedsNoAssignmentCheckButAReplacementMustBeEligible() {
        when(availability.calculateForBusiness(any(), any(), any(), any(), any(), any(), any())).thenReturn(offered());

        service.availability(context, appointmentId, current, DAY);
        verify(staffAccess, never()).findBookableStaffForServices(any(), anyCollection());

        UUID stranger = UUID.randomUUID();
        denies(() -> service.availability(context, appointmentId, stranger, DAY),
                AppointmentRescheduleOptionsException.StaffUnavailable.class);
        verify(staffAccess).findBookableStaffForServices(business, List.of(first, second));
        // The ineligible replacement never reached the engine (one call, for the current StaffMember).
        verify(availability).calculateForBusiness(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anInactiveCurrentStaffMemberReportedByTheEngineIsTheSameResult() {
        when(availability.calculateForBusiness(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new StaffMemberNotEligible());

        denies(() -> service.availability(context, appointmentId, current, DAY),
                AppointmentRescheduleOptionsException.StaffUnavailable.class);
    }

    @Test
    void theStoredAndTheOccupiedDurationMustAgree() {
        when(availability.calculateForBusiness(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new BusinessAvailability(SOFIA, NOW, Duration.ofMinutes(30), DAY, DAY, List.of()));

        denies(() -> service.availability(context, appointmentId, other, DAY),
                AppointmentRescheduleOptionsException.Failure.class);
    }

    @Test
    void theIdentifiersAndTheDateAreBounded() {
        denies(() -> service.options(context, null), AppointmentRescheduleOptionsException.InvalidInput.class);
        denies(() -> service.availability(context, null, other, DAY), AppointmentRescheduleOptionsException.InvalidInput.class);
        denies(() -> service.availability(context, appointmentId, null, DAY),
                AppointmentRescheduleOptionsException.InvalidInput.class);
        denies(() -> service.availability(context, appointmentId, other, null),
                AppointmentRescheduleOptionsException.InvalidInput.class);
        denies(() -> service.availability(context, appointmentId, other, LocalDate.of(1999, 12, 31)),
                AppointmentRescheduleOptionsException.InvalidInput.class);
        denies(() -> service.availability(context, appointmentId, other, LocalDate.of(2101, 1, 1)),
                AppointmentRescheduleOptionsException.InvalidInput.class);
        verifyNoInteractions(availability);
    }

    // ---- authorization and lifecycle -------------------------------------------------------------------

    @Test
    void authorizationComesBeforeEverythingAndNothingElseIsRead() {
        when(owners.authorize(user, business)).thenReturn(Authorization.DENIED);

        denies(() -> service.options(context, null), AppointmentRescheduleOptionsException.AccessDenied.class);
        denies(() -> service.availability(context, appointmentId, other, DAY),
                AppointmentRescheduleOptionsException.AccessDenied.class);
        verifyNoInteractions(appointments, staffAccess, activity, availability);
        assertThat(clockReads).hasValue(0);
    }

    @Test
    void anUnknownBusinessAndAMissingContextAreDeniedAndASelectionIsRequired() {
        when(businesses.findScheduleContext(business)).thenReturn(Optional.empty());
        denies(() -> service.options(context, appointmentId), AppointmentRescheduleOptionsException.AccessDenied.class);
        denies(() -> service.options(null, appointmentId), AppointmentRescheduleOptionsException.AccessDenied.class);
        AuthenticatedBusinessContext none = new AuthenticatedBusinessContext() {
            @Override
            public UUID userId() {
                return user;
            }

            @Override
            public Optional<UUID> selectedBusinessId() {
                return Optional.empty();
            }
        };
        assertThatThrownBy(() -> service.options(none, appointmentId)).isInstanceOf(SelectedBusinessRequired.class);
        verifyNoInteractions(appointments);
    }

    @Test
    void aDraftAndASuspendedBusinessOfferNothingAndReadNothingMore() {
        when(businesses.findScheduleContext(business))
                .thenReturn(Optional.of(new BusinessScheduleContext(business, LifecycleStatus.DRAFT, SOFIA)));
        denies(() -> service.options(context, appointmentId), AppointmentRescheduleOptionsException.BusinessNotActive.class);
        denies(() -> service.availability(context, appointmentId, other, DAY),
                AppointmentRescheduleOptionsException.BusinessNotActive.class);
        when(businesses.findScheduleContext(business))
                .thenReturn(Optional.of(new BusinessScheduleContext(business, LifecycleStatus.SUSPENDED, SOFIA)));
        denies(() -> service.options(context, appointmentId), AppointmentRescheduleOptionsException.BusinessSuspended.class);

        verifyNoInteractions(appointments, staffAccess, activity, availability);
    }

    @Test
    void anUnexpectedFailureIsAFixedFailureWithoutCauseOrDetail() {
        when(appointments.find(any(), any())).thenThrow(new IllegalStateException("SQLState 23P01 secret-constraint"));

        assertThatThrownBy(() -> service.options(context, appointmentId))
                .isInstanceOf(AppointmentRescheduleOptionsException.Failure.class)
                .hasNoCause()
                .hasMessage("Reschedule options read failed")
                .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
    }
}
