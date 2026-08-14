package bg.spotyourslot.business.domain;

import java.util.Locale;
import java.util.regex.Pattern;

public record BusinessSlug(String value) {
    public static final int MAX_LENGTH = 100;

    private static final Pattern VALID_FORMAT =
            Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    public BusinessSlug {
        if (value == null) {
            throw new IllegalArgumentException("Business slug is required");
        }

        value = value.strip().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH || !VALID_FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Business slug is invalid");
        }
    }
}
