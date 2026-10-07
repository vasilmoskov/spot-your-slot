package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.BookingResult.Created;
import bg.spotyourslot.booking.BookingResult.Replayed;
import bg.spotyourslot.booking.domain.BookingAttempt;
import bg.spotyourslot.booking.domain.FingerprintEncodings;
import bg.spotyourslot.booking.domain.NormalizedBookingRequest;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Key rotation with real PostgreSQL: the active key is version 2 and the historical key 1 stays
 * configured. An Appointment stored under key 1 (written by a test-side HMAC implementation, as an
 * earlier deployment would have) still replays and still detects a mismatch, while every new booking
 * is stored under key 2. All keys are synthetic.
 */
@TestPropertySource(properties = {
    "spotyourslot.booking.fingerprint.active-key-version=2",
    "spotyourslot.booking.fingerprint.keys.1=" + GuestBookingKeyRotationIntegrationTests.OLD_KEY,
    "spotyourslot.booking.fingerprint.keys.2=" + GuestBookingKeyRotationIntegrationTests.NEW_KEY
})
class GuestBookingKeyRotationIntegrationTests extends BookingIntegrationTest {
    // Base64 of "synthetic-historical-key-0123456789-abcd" and "synthetic-active-key-0123456789-abcdefgh".
    static final String OLD_KEY = "c3ludGhldGljLWhpc3RvcmljYWwta2V5LTAxMjM0NTY3ODktYWJjZA==";
    static final String NEW_KEY = "c3ludGhldGljLWFjdGl2ZS1rZXktMDEyMzQ1Njc4OS1hYmNkZWZnaA==";

    @Autowired AppointmentStore store;

    @Test
    void aNewBookingIsStoredUnderTheActiveKeyVersion() {
        BookedAppointment booked = ((Created) result(submit("a", req().build()))).appointment();

        assertThat(appointmentRow(booked.reference()).keyVersion()).isEqualTo(2);
        assertThat(appointmentRow(booked.reference()).encodingVersion()).isEqualTo(1);
    }

    @Test
    void anAppointmentStoredUnderTheHistoricalKeyStillReplaysAndStillDetectsAMismatch() {
        Req request = req().staff(tenant.staff()).phone("+359888333444").note("Бележка");
        NormalizedBookingRequest normalized = NormalizedBookingRequest.normalize(
                request.attemptId, request.service, request.staff, request.start,
                request.name, request.phone, request.email, request.note);
        byte[] fingerprint = hmac(OLD_KEY, FingerprintEncodings.CURRENT.encode(tenant.business(), normalized));
        var appointment = AppointmentFixtures.appointment(
                new AppointmentFixtures.Tenant(tenant.business(), tenant.service(), tenant.staff(),
                        fixtures.customer(tenant.business())),
                bg.spotyourslot.booking.domain.AppointmentSource.ONLINE, request.start, 30,
                new BookingAttempt(normalized.attemptId().hash(), fingerprint, 1, 1));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> store.insert(appointment));

        BookingResult replay = result(submit("replay", request.copy().build()));
        BookingResult changed = result(submit("changed", request.copy().note("Друга").build()));

        assertThat(replay).isInstanceOf(Replayed.class);
        assertThat(((Replayed) replay).appointment().reference()).isEqualTo(appointment.publicReference());
        assertThat(changed).isEqualTo(new BookingResult.AttemptMismatch());
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void anAppointmentStoredUnderAnUnconfiguredKeyIsAnUncertainFailureNotAMismatch() {
        Req request = req().staff(tenant.staff());
        BookedAppointment booked = ((Created) result(submit("a", request.build()))).appointment();
        jdbc.sql("UPDATE appointment SET fingerprint_key_version = 3 WHERE public_reference = :r")
                .param("r", booked.reference()).update();

        assertThat(result(submit("replay", request.copy().build()))).isEqualTo(new BookingResult.OutcomeUncertain());
        assertThat(result(submit("changed", request.copy().note("x").build())))
                .isEqualTo(new BookingResult.OutcomeUncertain());
    }

    private static byte[] hmac(String base64Key, byte[] bytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Key), "HmacSHA256"));
            return mac.doFinal(bytes);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
