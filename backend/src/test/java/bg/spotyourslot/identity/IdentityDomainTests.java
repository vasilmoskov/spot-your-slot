package bg.spotyourslot.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.identity.application.AuthenticationRateLimiter;
import bg.spotyourslot.identity.domain.EmailAddress;
import bg.spotyourslot.identity.domain.MembershipRole;
import bg.spotyourslot.identity.domain.PasswordPolicy;
import bg.spotyourslot.identity.domain.SessionPolicy;
import bg.spotyourslot.identity.domain.TokenCodec;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class IdentityDomainTests {
    @Test
    void normalizesEmailDeterministically() {
        assertThat(EmailAddress.normalize("  USER@Example.COM  ")).isEqualTo("user@example.com");
    }

    @Test
    void passwordPolicyUsesLengthWithoutCompositionRules() {
        var policy = new PasswordPolicy();
        assertThatCode(() -> policy.validate("дълга парола 123")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validate("short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tokensAreRandomAndStoredAsStableHashes() {
        var codec = new TokenCodec(new SecureRandom());
        var first = codec.create();
        var second = codec.create();
        assertThat(first).isNotEqualTo(second);
        assertThat(codec.hash(first)).hasSize(64).isEqualTo(codec.hash(first));
        assertThat(codec.hash(first)).isNotEqualTo(first);
    }

    @Test
    void sessionHasAbsoluteAndIdleExpiry() {
        var policy = new SessionPolicy();
        var now = Instant.parse("2026-08-13T10:00:00Z");
        assertThat(policy.expired(now, now.minus(Duration.ofHours(1)), now.plusSeconds(1)))
                .isFalse();
        assertThat(policy.expired(now, now.minus(Duration.ofHours(2)), now.plusSeconds(1)))
                .isTrue();
        assertThat(policy.expired(now, now, now)).isTrue();
    }

    @Test
    void roleBoundariesIncludeManager() {
        assertThat(MembershipRole.BUSINESS_OWNER.canManageMemberships()).isTrue();
        assertThat(MembershipRole.MANAGER.canManageMemberships()).isFalse();
        assertThat(MembershipRole.MANAGER.canManageOperations()).isTrue();
        assertThat(MembershipRole.STAFF.canManageOperations()).isFalse();
    }

    @Test
    void rateLimiterRejectsTheEleventhAttempt() {
        var limiter = new AuthenticationRateLimiter(
                Clock.fixed(Instant.parse("2026-08-13T10:00:00Z"), ZoneOffset.UTC), 2);
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(limiter.allow("login", "127.0.0.1", "person@example.invalid")).isTrue();
        }
        assertThat(limiter.allow("login", "127.0.0.1", "person@example.invalid")).isFalse();
    }

    @Test
    void rateLimiterFailsClosedAtCapacityAndRetainsOnlyDigests() {
        var limiter = new AuthenticationRateLimiter(
                Clock.fixed(Instant.parse("2026-08-13T10:00:00Z"), ZoneOffset.UTC), 2);
        assertThat(limiter.allow("login", "127.0.0.1", "first@example.invalid")).isTrue();
        assertThat(limiter.allow("login", "127.0.0.1", "second@example.invalid")).isTrue();
        assertThat(limiter.retainedEntries()).isEqualTo(2);
        assertThat(limiter.allow("login", "127.0.0.1", "third@example.invalid")).isFalse();
        assertThat(limiter.retainedEntries()).isEqualTo(2);
    }

    @Test
    void rateLimiterRemovesExpiredCountersBeforeCapacityDecision() {
        var clock = new MutableClock(Instant.parse("2026-08-13T10:00:00Z"));
        var limiter = new AuthenticationRateLimiter(clock, 1);
        assertThat(limiter.allow("login", "127.0.0.1", "first@example.invalid")).isTrue();
        clock.advance(Duration.ofMinutes(15));
        assertThat(limiter.allow("login", "127.0.0.1", "second@example.invalid")).isTrue();
        assertThat(limiter.retainedEntries()).isEqualTo(1);
    }

    @Test
    void rateLimiterResetRemovesOnlyTheExactFingerprint() {
        var limiter = new AuthenticationRateLimiter(
                Clock.fixed(Instant.parse("2026-08-13T10:00:00Z"), ZoneOffset.UTC), 4);

        exhaust(limiter, "login", "192.0.2.1", "person@example.invalid");
        exhaust(limiter, "login", "192.0.2.1", "other@example.invalid");
        exhaust(limiter, "login", "192.0.2.2", "person@example.invalid");
        exhaust(limiter, "forgot", "192.0.2.1", "person@example.invalid");

        limiter.reset("login", "192.0.2.1", "person@example.invalid");

        assertThat(limiter.allow("login", "192.0.2.1", "person@example.invalid"))
                .isTrue();
        assertThat(limiter.allow("login", "192.0.2.1", "other@example.invalid"))
                .isFalse();
        assertThat(limiter.allow("login", "192.0.2.2", "person@example.invalid"))
                .isFalse();
        assertThat(limiter.allow("forgot", "192.0.2.1", "person@example.invalid"))
                .isFalse();
    }

    private void exhaust(
            AuthenticationRateLimiter limiter,
            String flow,
            String clientAddress,
            String sensitiveInput) {
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(limiter.allow(flow, clientAddress, sensitiveInput)).isTrue();
        }
        assertThat(limiter.allow(flow, clientAddress, sensitiveInput)).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

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
            return instant;
        }
    }
}
