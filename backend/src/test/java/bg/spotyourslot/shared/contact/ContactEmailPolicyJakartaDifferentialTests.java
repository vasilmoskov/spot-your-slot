package bg.spotyourslot.shared.contact;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.shared.contact.ContactPolicyVectors.Accepted;
import bg.spotyourslot.shared.contact.ContactPolicyVectors.Rejected;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Email;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Characterization of the former StaffMember validation path during the extraction: the removed
 * {@code StaffMemberEmailPolicy} dotted-domain rules followed by the Jakarta {@code @Email}
 * constraint. It is a regression detector only: the permanent product policy is
 * {@link ContactEmailPolicy}, which stands alone and is exercised by
 * {@link ContactEmailPolicyTests} and the golden vectors. The legacy rules below are reproduced
 * from the removed class solely for this comparison.
 */
class ContactEmailPolicyJakartaDifferentialTests {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void theFormerPathStillAcceptsEveryAddressTheSharedPolicyAccepts() {
        List<String> acceptedByPolicyRejectedByFormerPath = ContactPolicyVectors.accepted("email").stream()
                .map(Accepted::canonical)
                .filter(address -> !formerPathAccepts(address))
                .toList();

        assertThat(acceptedByPolicyRejectedByFormerPath).isEmpty();
    }

    @Test
    void theOnlyRejectedVectorsTheFormerPathAcceptedAreTheDocumentedStricterSharedRules() {
        List<String> acceptedByFormerPathRejectedByPolicy = ContactPolicyVectors.rejected("email").stream()
                .filter(vector -> formerPathAccepts(vector.input().strip().toLowerCase(Locale.ROOT)))
                .map(Rejected::id)
                .toList();

        // The shared policy deliberately does not support quoted-string local parts, which the
        // Jakarta constraint allowed. Every other previously accepted shape is unchanged.
        assertThat(acceptedByFormerPathRejectedByPolicy).containsExactly("quoted-local-part");
    }

    private static boolean formerPathAccepts(String address) {
        return legacyDottedDomainRules(address)
                && FACTORY.getValidator().validate(new Candidate(address)).isEmpty();
    }

    // The removed StaffMemberEmailPolicy: one @, non-empty parts, ASCII, no whitespace, and a
    // dotted domain of ASCII letter/digit/hyphen labels without edge hyphens.
    private static boolean legacyDottedDomainRules(String email) {
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
            return false;
        }
        if (email.codePoints().anyMatch(codePoint -> codePoint > 127 || Character.isWhitespace(codePoint))) {
            return false;
        }
        String[] labels = email.substring(at + 1).split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (label.isEmpty()
                    || label.startsWith("-")
                    || label.endsWith("-")
                    || !label.chars().allMatch(c -> c == '-' || Character.isLetterOrDigit(c) && c < 128)) {
                return false;
            }
        }
        return true;
    }

    private record Candidate(@Email String value) {
    }
}
