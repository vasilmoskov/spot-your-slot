package bg.spotyourslot.booking.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only place the booking module logs. A line carries a fixed event code and the class name of a
 * sanitized failure (and an SQLState where one is already exposed), never a message, request value,
 * Customer data, note, attempt identifier, fingerprint, key, or SQL.
 */
@Component
class BookingDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger(BookingDiagnostics.class);

    void event(String code, Throwable failure) {
        LOG.warn("booking event={} failure={}", code, failure.getClass().getName());
    }

    void event(String code) {
        LOG.warn("booking event={}", code);
    }
}
