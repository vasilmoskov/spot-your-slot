package bg.spotyourslot.identity.application;

import bg.spotyourslot.identity.domain.EmailAddress;
import bg.spotyourslot.identity.domain.PasswordPolicy;
import bg.spotyourslot.identity.infrastructure.IdentityStore;
import java.time.Clock;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InitialPlatformAdmin implements ApplicationRunner {
    private final IdentityStore store;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final Clock clock;
    private final Environment environment;
    private final boolean enabled;
    private final String email;
    private final String name;
    private final String password;

    public InitialPlatformAdmin(
            IdentityStore store,
            PasswordEncoder encoder,
            PasswordPolicy policy,
            Clock clock,
            Environment environment,
            @Value("${spotyourslot.security.bootstrap-admin.enabled:false}") boolean enabled,
            @Value("${spotyourslot.security.bootstrap-admin.email:}") String email,
            @Value("${spotyourslot.security.bootstrap-admin.display-name:}") String name,
            @Value("${spotyourslot.security.bootstrap-admin.password:}") String password) {
        this.store = store;
        this.encoder = encoder;
        this.policy = policy;
        this.clock = clock;
        this.environment = environment;
        this.enabled = enabled;
        this.email = email;
        this.name = name;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("Administrator bootstrap is disabled in production");
        }
        policy.validate(password);
        String normalized = EmailAddress.normalize(email);
        if (normalized.isBlank() || name.isBlank()) {
            throw new IllegalStateException("Administrator bootstrap values are incomplete");
        }
        var user = store.userByEmail(normalized);
        var id = user.map(candidate -> candidate.id())
                .orElseGet(() -> store.createUser(
                        normalized, name, encoder.encode(password), clock.instant()));
        store.grantPlatformAdmin(id, clock.instant());
    }
}
