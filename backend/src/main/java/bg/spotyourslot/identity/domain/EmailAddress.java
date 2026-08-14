package bg.spotyourslot.identity.domain;

import java.text.Normalizer;
import java.util.Locale;

public final class EmailAddress {
    private EmailAddress() {}

    public static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
