package bg.spotyourslot.booking.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.application.FingerprintConfigurationException;
import bg.spotyourslot.booking.application.FingerprintConfigurationException.Reason;
import bg.spotyourslot.booking.application.FingerprintKeyRing;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Startup validation of the fingerprint key configuration with synthetic keys: the Spring context
 * fails to start with a fixed reason that never contains a configured value.
 */
class BookingFingerprintConfigurationTests {
    private static final String PREFIX = "spotyourslot.booking.fingerprint.";
    private static final String SECRET = key("synthetic-production-style-key-0123456789");
    private static final String SECOND = key("synthetic-second-key-0123456789-abcdefgh");
    private static final String NON_SECRET = key(FingerprintKeyRing.NON_SECRET_MARKER + ":synthetic-dev-0001");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BookingFingerprintConfiguration.class);

    private static String key(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Reason startupFailureReason(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof FingerprintConfigurationException configuration) {
                return configuration.reason();
            }
        }
        throw new AssertionError("The failure was not a fingerprint configuration failure", failure);
    }

    @Test
    void aValidConfigurationWithAHistoricalKeyStartsAndExposesBothKeys() {
        runner.withPropertyValues(
                        PREFIX + "active-key-version=2",
                        PREFIX + "keys.1=" + SECRET,
                        PREFIX + "keys.2=" + SECOND)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FingerprintKeyRing ring = context.getBean(FingerprintKeyRing.class);
                    assertThat(ring.activeVersion()).isEqualTo(2);
                    assertThat(ring.find(1)).isPresent();
                    assertThat(ring.find(2)).isPresent();
                });
    }

    @Test
    void aMissingConfigurationFailsStartupWithAFixedReason() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(startupFailureReason(context.getStartupFailure()))
                    .isEqualTo(Reason.ACTIVE_VERSION_MISSING);
        });
        runner.withPropertyValues(PREFIX + "active-key-version=1").run(context -> {
            assertThat(context).hasFailed();
            assertThat(startupFailureReason(context.getStartupFailure())).isEqualTo(Reason.NO_KEYS);
        });
    }

    @Test
    void aMalformedActiveVersionAMissingActiveKeyAShortKeyAndNonBase64FailStartupWithoutEchoingValues() {
        String leaked = "do-not-print-this-configured-value";
        runner.withPropertyValues(PREFIX + "active-key-version=oops", PREFIX + "keys.1=" + SECRET)
                .run(context -> assertThat(startupFailureReason(context.getStartupFailure()))
                        .isEqualTo(Reason.ACTIVE_VERSION_INVALID));
        runner.withPropertyValues(PREFIX + "active-key-version=3", PREFIX + "keys.1=" + SECRET)
                .run(context -> assertThat(startupFailureReason(context.getStartupFailure()))
                        .isEqualTo(Reason.ACTIVE_KEY_MISSING));
        runner.withPropertyValues(PREFIX + "active-key-version=1", PREFIX + "keys.1=" + key("short"))
                .run(context -> assertThat(startupFailureReason(context.getStartupFailure()))
                        .isEqualTo(Reason.KEY_TOO_SHORT));
        runner.withPropertyValues(PREFIX + "active-key-version=1", PREFIX + "keys.1=" + leaked)
                .run(context -> {
                    assertThat(startupFailureReason(context.getStartupFailure()))
                            .isEqualTo(Reason.KEY_NOT_BASE64);
                    assertThat(allMessages(context.getStartupFailure())).doesNotContain(leaked);
                });
    }

    @Test
    void theProductionProfileRejectsAPublishedNonSecretKeyWhileOtherProfilesAcceptIt() {
        runner.withPropertyValues(PREFIX + "active-key-version=1", PREFIX + "keys.1=" + NON_SECRET)
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        PREFIX + "active-key-version=1",
                        PREFIX + "keys.1=" + NON_SECRET)
                .run(context -> {
                    assertThat(startupFailureReason(context.getStartupFailure()))
                            .isEqualTo(Reason.NON_SECRET_KEY_IN_PRODUCTION);
                    assertThat(allMessages(context.getStartupFailure())).doesNotContain(NON_SECRET);
                });
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        PREFIX + "active-key-version=1",
                        PREFIX + "keys.1=" + SECRET)
                .run(context -> assertThat(context).hasNotFailed());
    }

    private static String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
