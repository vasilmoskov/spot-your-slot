package bg.spotyourslot.identity.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bg.spotyourslot.identity.domain.PasswordPolicy;
import bg.spotyourslot.identity.infrastructure.IdentityStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

class InitialPlatformAdminTests {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void rejectsSevenCodePointBootstrapPassword() {
        IdentityStore store = mock(IdentityStore.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        Environment environment = testEnvironment();
        var bootstrap = bootstrap(store, encoder, environment, "абвгдеж");

        assertThatThrownBy(() -> bootstrap.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Паролата трябва да бъде между 8 и 128 знака.");

        verifyNoInteractions(store, encoder);
    }

    @Test
    void acceptsEightCodePointBootstrapPassword() {
        IdentityStore store = mock(IdentityStore.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        Environment environment = testEnvironment();
        UUID userId = UUID.fromString("a3d81d22-6147-47da-84d7-5bbdbb838637");
        String password = "абвгдежз";
        when(store.userByEmail("admin@example.invalid")).thenReturn(Optional.empty());
        when(encoder.encode(password)).thenReturn("argon2id-test-hash");
        when(store.createUser(
                        "admin@example.invalid",
                        "Local Administrator",
                        "argon2id-test-hash",
                        CLOCK.instant()))
                .thenReturn(userId);

        bootstrap(store, encoder, environment, password)
                .run(mock(ApplicationArguments.class));

        verify(store).grantPlatformAdmin(userId, CLOCK.instant());
        verify(store, never()).userByEmail("different@example.invalid");
    }

    private InitialPlatformAdmin bootstrap(
            IdentityStore store,
            PasswordEncoder encoder,
            Environment environment,
            String password) {
        return new InitialPlatformAdmin(
                store,
                encoder,
                new PasswordPolicy(),
                CLOCK,
                environment,
                true,
                "admin@example.invalid",
                "Local Administrator",
                password);
    }

    private Environment testEnvironment() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"test"});
        return environment;
    }
}
