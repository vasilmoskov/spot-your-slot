package bg.spotyourslot.booking.configuration;

import bg.spotyourslot.booking.PublicBookingRateLimiter;
import bg.spotyourslot.booking.application.BookingRateLimiter;
import bg.spotyourslot.booking.application.RateLimitSettings;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the public booking limiter from {@code spotyourslot.booking.rate-limit.*} (ADR-0026). The
 * defaults are the approved budgets; an invalid value fails startup with a fixed message that does
 * not echo it. The key secret is random per process and never configured or logged.
 */
@Configuration(proxyBeanMethods = false)
class BookingRateLimitConfiguration {
    private static final int SECRET_BYTES = 32;

    @Bean
    RateLimitSettings rateLimitSettings(
            @Value("${spotyourslot.booking.rate-limit.window-seconds:900}") long windowSeconds,
            @Value("${spotyourslot.booking.rate-limit.booking-per-address-business:10}")
                    int bookingPerAddressAndBusiness,
            @Value("${spotyourslot.booking.rate-limit.booking-per-contact:5}") int bookingPerContact,
            @Value("${spotyourslot.booking.rate-limit.booking-per-address:30}") int bookingPerAddress,
            @Value("${spotyourslot.booking.rate-limit.availability-per-address-business:300}")
                    int availabilityPerAddressAndBusiness,
            @Value("${spotyourslot.booking.rate-limit.availability-per-address:600}")
                    int availabilityPerAddress,
            @Value("${spotyourslot.booking.rate-limit.max-entries:50000}") int maxEntries) {
        return new RateLimitSettings(
                Duration.ofSeconds(windowSeconds),
                bookingPerAddressAndBusiness,
                bookingPerContact,
                bookingPerAddress,
                availabilityPerAddressAndBusiness,
                availabilityPerAddress,
                maxEntries);
    }

    @Bean
    PublicBookingRateLimiter publicBookingRateLimiter(Clock clock, RateLimitSettings settings) {
        byte[] secret = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(secret);
        return new BookingRateLimiter(clock, settings, secret);
    }
}
