package bg.spotyourslot.booking.configuration;

import bg.spotyourslot.booking.application.FingerprintKeyRing;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Builds the {@link FingerprintKeyRing} at startup from
 * {@code spotyourslot.booking.fingerprint.active-key-version} and
 * {@code spotyourslot.booking.fingerprint.keys.<version>} (an environment variable form is
 * {@code SPOTYOURSLOT_BOOKING_FINGERPRINT_KEYS_<version>}). Values are bound as plain text so that no
 * binding or conversion error can echo a configured key; an unusable configuration fails startup
 * with a fixed reason and nothing from the values.
 */
@Configuration(proxyBeanMethods = false)
class BookingFingerprintConfiguration {
    private static final String PREFIX = "spotyourslot.booking.fingerprint";

    @Bean
    FingerprintKeyRing fingerprintKeyRing(Environment environment) {
        Map<String, String> keys = Binder.get(environment)
                .bind(PREFIX + ".keys", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        return FingerprintKeyRing.create(
                environment.getProperty(PREFIX + ".active-key-version"),
                keys,
                environment.acceptsProfiles(Profiles.of("prod")));
    }
}
