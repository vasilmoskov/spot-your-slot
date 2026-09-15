package bg.spotyourslot.catalog.domain;

import java.text.Normalizer;

public final class ServiceTextCanonicalizer {
    private ServiceTextCanonicalizer() {
    }

    public static String canonicalName(String value) {
        if (value == null) {
            return null;
        }

        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        var canonical = new StringBuilder(normalized.length());
        boolean pendingSpace = false;

        for (int index = 0; index < normalized.length(); ) {
            int codePoint = normalized.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isApprovedWhitespace(codePoint)) {
                pendingSpace = canonical.length() > 0;
            } else {
                if (pendingSpace) {
                    canonical.append(' ');
                    pendingSpace = false;
                }
                canonical.appendCodePoint(codePoint);
            }
        }

        return canonical.toString();
    }

    public static String canonicalDescription(String value) {
        if (value == null) {
            return null;
        }

        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        int start = 0;
        while (start < normalized.length()) {
            int codePoint = normalized.codePointAt(start);
            if (!isApprovedWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }

        int end = normalized.length();
        while (end > start) {
            int codePoint = normalized.codePointBefore(end);
            if (!isApprovedWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }

        return start == end ? null : normalized.substring(start, end);
    }

    public static boolean isApprovedWhitespace(int codePoint) {
        return codePoint >= 0x0009 && codePoint <= 0x000D
                || codePoint == 0x0020
                || codePoint == 0x0085
                || codePoint == 0x00A0
                || codePoint == 0x1680
                || codePoint >= 0x2000 && codePoint <= 0x200A
                || codePoint == 0x2028
                || codePoint == 0x2029
                || codePoint == 0x202F
                || codePoint == 0x205F
                || codePoint == 0x3000;
    }
}
