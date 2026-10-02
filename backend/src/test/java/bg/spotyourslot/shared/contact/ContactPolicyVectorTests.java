package bg.spotyourslot.shared.contact;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.shared.contact.ContactPolicyVectors.Accepted;
import bg.spotyourslot.shared.contact.ContactPolicyVectors.Rejected;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the one shared golden-vector file that the frontend contactPolicy.test.ts also runs, so
 * the backend and frontend policies cannot drift apart without a failing test.
 */
class ContactPolicyVectorTests {
    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedPhones")
    void canonicalizesEveryAcceptedPhoneVector(Accepted vector) {
        String trimmed = ContactTextCanonicalizer.canonicalTrimmed(vector.input());

        assertThat(trimmed).isNotNull();
        assertThat(ContactPhoneNumbers.canonicalize(trimmed)).contains(vector.canonical());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedPhones")
    void rejectsEveryRejectedPhoneVector(Rejected vector) {
        String trimmed = ContactTextCanonicalizer.canonicalTrimmed(vector.input());

        assertThat(trimmed == null ? java.util.Optional.empty() : ContactPhoneNumbers.canonicalize(trimmed))
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedEmails")
    void canonicalizesEveryAcceptedEmailVector(Accepted vector) {
        assertThat(ContactEmailPolicy.canonicalize(vector.input())).contains(vector.canonical());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedEmails")
    void rejectsEveryRejectedEmailVector(Rejected vector) {
        assertThat(ContactEmailPolicy.canonicalize(vector.input())).isEmpty();
    }

    @Test
    void treatsEveryBlankVectorAsAbsent() {
        for (String blank : ContactPolicyVectors.blank("phone")) {
            assertThat(ContactTextCanonicalizer.canonicalTrimmed(blank)).isNull();
        }
        for (String blank : ContactPolicyVectors.blank("email")) {
            assertThat(ContactTextCanonicalizer.canonicalTrimmed(blank)).isNull();
            assertThat(ContactEmailPolicy.canonicalize(blank)).isEmpty();
        }
    }

    @Test
    void everyAcceptedCanonicalValueIsAlreadyCanonical() {
        for (Accepted vector : ContactPolicyVectors.accepted("phone")) {
            assertThat(ContactPhoneNumbers.canonicalize(vector.canonical()))
                    .contains(vector.canonical());
        }
        for (Accepted vector : ContactPolicyVectors.accepted("email")) {
            assertThat(ContactEmailPolicy.canonicalize(vector.canonical()))
                    .contains(vector.canonical());
        }
    }

    @Test
    void theVectorFileIsNotEmpty() {
        assertThat(ContactPolicyVectors.accepted("phone")).hasSizeGreaterThan(10);
        assertThat(ContactPolicyVectors.rejected("phone")).hasSizeGreaterThan(20);
        assertThat(ContactPolicyVectors.accepted("email")).hasSizeGreaterThan(10);
        assertThat(ContactPolicyVectors.rejected("email")).hasSizeGreaterThan(20);
    }

    private static List<Accepted> acceptedPhones() {
        return ContactPolicyVectors.accepted("phone");
    }

    private static List<Rejected> rejectedPhones() {
        return ContactPolicyVectors.rejected("phone");
    }

    private static List<Accepted> acceptedEmails() {
        return ContactPolicyVectors.accepted("email");
    }

    private static List<Rejected> rejectedEmails() {
        return ContactPolicyVectors.rejected("email");
    }
}
