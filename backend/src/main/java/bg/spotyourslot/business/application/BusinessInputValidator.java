package bg.spotyourslot.business.application;

import bg.spotyourslot.business.BusinessApplicationException.InputField;
import bg.spotyourslot.business.BusinessApplicationException.InvalidInput;
import bg.spotyourslot.business.BusinessRecords.CreateBusinessCommand;
import bg.spotyourslot.business.BusinessRecords.UpdateBusinessCommand;
import bg.spotyourslot.business.domain.BusinessSlug;
import bg.spotyourslot.business.domain.BusinessTimezone;
import bg.spotyourslot.business.domain.BusinessType;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class BusinessInputValidator {
    static final int MAX_PAGE_SIZE = 100;
    static final int DISPLAY_NAME_MAX_LENGTH = 200;
    static final int DESCRIPTION_MAX_LENGTH = 2_000;
    static final int ADDRESS_MAX_LENGTH = 500;
    static final int PHONE_MAX_LENGTH = 50;
    static final int CONTACT_EMAIL_MAX_LENGTH = 320;

    private final Validator validator;

    public BusinessInputValidator(Validator validator) {
        this.validator = validator;
    }

    public PageInput validatePage(int page, int size) {
        if (page < 0) {
            throw new InvalidInput(InputField.PAGE);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidInput(InputField.SIZE);
        }
        return new PageInput(page, size);
    }

    public UUID validateBusinessId(UUID businessId) {
        if (businessId == null) {
            throw new InvalidInput(InputField.BUSINESS_ID);
        }
        return businessId;
    }

    public long validateExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new InvalidInput(InputField.EXPECTED_VERSION);
        }
        return expectedVersion;
    }

    public CreateBusinessCommand validateCreate(CreateBusinessCommand command) {
        if (command == null) {
            throw new InvalidInput(InputField.COMMAND);
        }

        return new CreateBusinessCommand(
                slug(command.slug()),
                requiredText(
                        command.displayName(),
                        DISPLAY_NAME_MAX_LENGTH,
                        InputField.DISPLAY_NAME),
                requiredBusinessType(command.businessType()),
                creationTimezone(command.timezone()),
                optionalText(
                        command.description(),
                        DESCRIPTION_MAX_LENGTH,
                        InputField.DESCRIPTION),
                optionalText(command.address(), ADDRESS_MAX_LENGTH, InputField.ADDRESS),
                optionalText(command.phone(), PHONE_MAX_LENGTH, InputField.PHONE),
                contactEmail(command.contactEmail()));
    }

    public UpdateBusinessCommand validateUpdate(UpdateBusinessCommand command) {
        if (command == null) {
            throw new InvalidInput(InputField.COMMAND);
        }

        return new UpdateBusinessCommand(
                slug(command.slug()),
                requiredText(
                        command.displayName(),
                        DISPLAY_NAME_MAX_LENGTH,
                        InputField.DISPLAY_NAME),
                requiredBusinessType(command.businessType()),
                updateTimezone(command.timezone()),
                optionalText(
                        command.description(),
                        DESCRIPTION_MAX_LENGTH,
                        InputField.DESCRIPTION),
                optionalText(command.address(), ADDRESS_MAX_LENGTH, InputField.ADDRESS),
                optionalText(command.phone(), PHONE_MAX_LENGTH, InputField.PHONE),
                contactEmail(command.contactEmail()),
                validateExpectedVersion(command.expectedVersion()));
    }

    private String slug(String value) {
        try {
            return new BusinessSlug(value).value();
        } catch (IllegalArgumentException exception) {
            throw new InvalidInput(InputField.SLUG);
        }
    }

    private String requiredText(String value, int maximumLength, InputField field) {
        if (value == null) {
            throw new InvalidInput(field);
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || codePointLength(normalized) > maximumLength) {
            throw new InvalidInput(field);
        }
        return normalized;
    }

    private String optionalText(String value, int maximumLength, InputField field) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (codePointLength(normalized) > maximumLength) {
            throw new InvalidInput(field);
        }
        return normalized;
    }

    private String contactEmail(String value) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        if (codePointLength(normalized) > CONTACT_EMAIL_MAX_LENGTH
                || !validator.validate(new ContactEmailCandidate(normalized)).isEmpty()) {
            throw new InvalidInput(InputField.CONTACT_EMAIL);
        }
        return normalized;
    }

    private BusinessType requiredBusinessType(BusinessType businessType) {
        if (businessType == null) {
            throw new InvalidInput(InputField.BUSINESS_TYPE);
        }
        return businessType;
    }

    private String creationTimezone(String value) {
        String zoneId = value == null ? BusinessTimezone.DEFAULT_ZONE_ID : value;
        return timezone(zoneId);
    }

    private String updateTimezone(String value) {
        if (value == null) {
            throw new InvalidInput(InputField.TIMEZONE);
        }
        return timezone(value);
    }

    private String timezone(String value) {
        try {
            return new BusinessTimezone(value).value();
        } catch (IllegalArgumentException exception) {
            throw new InvalidInput(InputField.TIMEZONE);
        }
    }

    private int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    public record PageInput(int page, int size) {}

    private record ContactEmailCandidate(@Email String value) {}
}
