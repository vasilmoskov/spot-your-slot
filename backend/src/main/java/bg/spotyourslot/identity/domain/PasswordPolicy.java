package bg.spotyourslot.identity.domain;

public final class PasswordPolicy {
    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    public void validate(String password) {
        int length = password == null ? 0 : password.codePointCount(0, password.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException("Паролата трябва да бъде между 12 и 128 знака.");
        }
    }
}
