package bg.spotyourslot.booking.application;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** Draws public references from a {@link SecureRandom}. */
@Component
class SecurePublicReferenceSource implements PublicReferenceSource {
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int LENGTH = 10;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String next() {
        StringBuilder reference = new StringBuilder(LENGTH);
        for (int index = 0; index < LENGTH; index++) {
            reference.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return reference.toString();
    }
}
