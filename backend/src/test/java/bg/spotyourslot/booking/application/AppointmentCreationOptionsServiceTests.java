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

import bg.spotyourslot.booking.AppointmentCreationOptionsException;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.CreationAvailability;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.CreationOptions;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
import bg.spotyourslot.catalog.ServiceAvailabilityAccess;
import bg.spotyourslot.catalog.ServiceAvailabilityAccess.BookableService;
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
 * Deterministic unit tests of the decisions and the delegation of {@link AppointmentCreationOptionsService}
 * (the persistence behavior is proved on PostgreSQL by the integration tests). Every collaborator is
 * a stub; no value is real.
 */
class AppointmentCreationOptionsServiceTests {
    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private static final Instant NOW = Instant.parse("2026-10-02T08:00:00.123456789Z");

    private final UUID business = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID staff = UUID.randomUUID();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final AtomicInteger clockReads = new AtomicInteger();

    private BusinessScheduleContextAccess businesses;
    private SelectedBusinessOwnerAccess owners;
    private ServiceAvailabilityAccess services;
    private PublicStaffAccess staffAccess;
    private AvailabilityQuery availability;
    private AppointmentCreationOptionsService service;
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
        services = mock(ServiceAvailabilityAccess.class);
        staffAccess = mock(PublicStaffAccess.class);
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
        service = new AppointmentCreationOptionsService(businesses, owners, services, staffAccess, availability, clock);
        when(businesses.findScheduleContext(business))
                .thenReturn(Optional.of(new BusinessScheduleContext(business, LifecycleStatus.ACTIVE, SOFIA)));
        when(owners.authorize(user, business)).thenReturn(Authorization.GRANTED);
        when(services.findBookableServices(any(), anyCollection())).thenReturn(List.of(
                new BookableService(first, Duration.ofMinutes(30)),
                new BookableService(second, Duration.ofMinutes(45))));
        when(staffAccess.findBookableStaffForServices(any(), anyCollection()))
                .thenReturn(List.of(new PublicStaffMember(staff, "Мария Петрова")));
    }

    private BusinessAvailability offered(AvailabilitySlot... slots) {
        return new BusinessAvailability(SOFIA, NOW, Duration.ofMinutes(75), DAY, DAY, List.of(slots));
    }

    private AvailabilitySlot slot(String start, UUID... members) {
        Instant instant = Instant.parse(start);
        return new AvailabilitySlot(instant, instant.plus(Duration.ofMinutes(75)), ZoneOffset.ofHours(2), List.of(members));
    }

    // ---- options -----------------------------------------------------------------------------------

    @Test
    void theOptionsAreTheSummedDurationAndTheIntersectionInTheOrderOfTheStaffRead() {
        CreationOptions options = service.options(context, List.of(second, first));

        assertThat(options.timezone()).isEqualTo(SOFIA);
        assertThat(options.durationMinutes()).isEqualTo(75);
        assertThat(options.staffMembers()).singleElement().satisfies(member -> {
            assertThat(member.id()).isEqualTo(staff);
            assertThat(member.displayName()).isEqualTo("Мария Петрова");
        });
        // One set-based read each, whatever the number of Services; no clock, no engine, no lock.
        verify(services).findBookableServices(business, List.of(second, first));
        verify(staffAccess).findBookableStaffForServices(business, List.of(second, first));
        verifyNoInteractions(availability);
        assertThat(clockReads).hasValue(0);
    }

    @Test
    void nobodyEligibleIsAnEmptyListAndNotAnError() {
        when(staffAccess.findBookableStaffForServices(any(), anyCollection())).thenReturn(List.of());

        assertThat(service.options(context, List.of(first, second)).staffMembers()).isEmpty();
    }

    @Test
    void aMissingForeignOrInactiveServiceIsOneResult() {
        when(services.findBookableServices(any(), anyCollection()))
                .thenReturn(List.of(new BookableService(first, Duration.ofMinutes(30))));

        assertThatThrownBy(() -> service.options(context, List.of(first, second)))
                .isInstanceOf(AppointmentCreationOptionsException.ServiceUnavailable.class);
        verify(staffAccess, never()).findBookableStaffForServices(any(), anyCollection());
    }

    @Test
    void theSummedDurationIsBoundedAtFourHundredEightyMinutes() {
        when(services.findBookableServices(any(), anyCollection())).thenReturn(List.of(
                new BookableService(first, Duration.ofMinutes(300)),
                new BookableService(second, Duration.ofMinutes(180))));
        assertThat(service.options(context, List.of(first, second)).durationMinutes()).isEqualTo(480);

        when(services.findBookableServices(any(), anyCollection())).thenReturn(List.of(
                new BookableService(first, Duration.ofMinutes(300)),
                new BookableService(second, Duration.ofMinutes(181))));
        assertThatThrownBy(() -> service.options(context, List.of(first, second)))
                .isInstanceOf(AppointmentCreationOptionsException.InvalidInput.class);
    }

    @Test
    void theSelectionIsBoundedBeforeAnyRead() {
        List<UUID> six = java.util.stream.Stream.generate(UUID::randomUUID).limit(6).toList();
        for (java.util.Collection<UUID> bad : java.util.Arrays.<java.util.Collection<UUID>>asList(
                null, List.of(), six, List.of(first, first), java.util.Arrays.asList(first, null))) {
            assertThatThrownBy(() -> service.options(context, bad))
                    .isInstanceOf(AppointmentCreationOptionsException.InvalidInput.class);
        }
        verifyNoInteractions(services, staffAccess, availability);
    }

    // ---- authorization and lifecycle --------------------------------------------------------------------

    @Test
    void authorizationComesBeforeLifecycleAndBoundsAndNothingElseIsRead() {
        when(owners.authorize(user, business)).thenReturn(Authorization.DENIED);

        assertThatThrownBy(() -> service.options(context, List.of()))
                .isInstanceOf(AppointmentCreationOptionsException.AccessDenied.class);
        assertThatThrownBy(() -> service.availability(context, List.of(first), staff, DAY))
                .isInstanceOf(AppointmentCreationOptionsException.AccessDenied.class);
        verifyNoInteractions(services, staffAccess, availability);
    }

    @Test
    void anUnknownBusinessAndAMissingContextAreDenied() {
        when(businesses.findScheduleContext(business)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.options(context, List.of(first)))
                .isInstanceOf(AppointmentCreationOptionsException.AccessDenied.class);
        assertThatThrownBy(() -> service.options(null, List.of(first)))
                .isInstanceOf(AppointmentCreationOptionsException.AccessDenied.class);
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
        assertThatThrownBy(() -> service.options(none, List.of(first))).isInstanceOf(SelectedBusinessRequired.class);
    }

    @Test
    void aDraftAndASuspendedBusinessOfferNothingAndReadNothingMore() {
        when(businesses.findScheduleContext(business))
                .thenReturn(Optional.of(new BusinessScheduleContext(business, LifecycleStatus.DRAFT, SOFIA)));
        assertThatThrownBy(() -> service.options(context, List.of(first)))
                .isInstanceOf(AppointmentCreationOptionsException.BusinessNotActive.class);
        when(businesses.findScheduleContext(business))
                .thenReturn(Optional.of(new BusinessScheduleContext(business, LifecycleStatus.SUSPENDED, SOFIA)));
        assertThatThrownBy(() -> service.availability(context, List.of(first), staff, DAY))
                .isInstanceOf(AppointmentCreationOptionsException.BusinessSuspended.class);
        verifyNoInteractions(services, staffAccess, availability);
        assertThat(clockReads).hasValue(0);
    }

    // ---- availability -----------------------------------------------------------------------------------------

    @Test
    void theEngineIsCalledWithZeroNoticeTheSummedDurationTheOneStaffMemberOneDateAndNoIgnoredAppointment() {
        when(availability.calculateForBusiness(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(offered(slot("2026-11-10T07:00:00Z", staff)));

        CreationAvailability result = service.availability(context, List.of(first, second), staff, DAY);

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(availability).calculateForBusiness(
                org.mockito.ArgumentMatchers.eq(business),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(75)),
                org.mockito.ArgumentMatchers.eq(staff),
                org.mockito.ArgumentMatchers.eq(DAY),
                org.mockito.ArgumentMatchers.eq(DAY),
                now.capture(),
                org.mockito.ArgumentMatchers.isNull());
        // The single clock instant, truncated to what PostgreSQL stores, and read exactly once.
        assertThat(now.getValue()).isEqualTo(Instant.parse("2026-10-02T08:00:00.123456Z"));
        assertThat(clockReads).hasValue(1);
        assertThat(result.durationMinutes()).isEqualTo(75);
        assertThat(result.date()).isEqualTo(DAY);
        assertThat(result.starts()).hasSize(1);
        assertThat(result.starts().getFirst().start()).isEqualTo(Instant.parse("2026-11-10T07:00:00Z"));
    }

    @Test
    void onlyStartsOfTheRequestedStaffMemberAndDateAreReturned() {
        UUID other = UUID.randomUUID();
        when(availability.calculateForBusiness(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(offered(
                        slot("2026-11-10T07:00:00Z", staff),
                        slot("2026-11-10T07:15:00Z", other),
                        // 23:30 Sofia of the previous date (UTC+02:00)
                        slot("2026-11-09T21:30:00Z", staff)));

        assertThat(service.availability(context, List.of(first, second), staff, DAY).starts())
                .extracting(start -> start.start().toString())
                .containsExactly("2026-11-10T07:00:00Z");
    }

    @Test
    void aStaffMemberWhoIsNotInTheIntersectionNeverReachesTheEngine() {
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> service.availability(context, List.of(first, second), stranger, DAY))
                .isInstanceOf(AppointmentCreationOptionsException.StaffUnavailable.class);
        verifyNoInteractions(availability);
        assertThat(clockReads).hasValue(0);
    }

    @Test
    void anIneligibilityReportedByTheEngineIsTheSameResult() {
        when(availability.calculateForBusiness(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new StaffMemberNotEligible());

        assertThatThrownBy(() -> service.availability(context, List.of(first, second), staff, DAY))
                .isInstanceOf(AppointmentCreationOptionsException.StaffUnavailable.class);
    }

    @Test
    void theDateAndTheStaffMemberAreBounded() {
        for (LocalDate bad : java.util.Arrays.asList(null, LocalDate.of(1999, 12, 31), LocalDate.of(2101, 1, 1))) {
            assertThatThrownBy(() -> service.availability(context, List.of(first), staff, bad))
                    .isInstanceOf(AppointmentCreationOptionsException.InvalidInput.class);
        }
        assertThatThrownBy(() -> service.availability(context, List.of(first), null, DAY))
                .isInstanceOf(AppointmentCreationOptionsException.InvalidInput.class);
        verifyNoInteractions(availability);
    }

    @Test
    void anUnexpectedFailureIsAFixedFailureWithoutCauseOrDetail() {
        when(services.findBookableServices(any(), anyCollection()))
                .thenThrow(new IllegalStateException("SQL state 23P01 secret"));

        assertThatThrownBy(() -> service.options(context, List.of(first)))
                .isInstanceOf(AppointmentCreationOptionsException.Failure.class)
                .hasMessage("Creation options read failed")
                .hasNoCause();
    }
}
