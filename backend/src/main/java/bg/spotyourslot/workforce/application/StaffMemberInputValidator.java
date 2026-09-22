package bg.spotyourslot.workforce.application;

import bg.spotyourslot.workforce.StaffMemberApplicationException.InputField;
import bg.spotyourslot.workforce.StaffMemberApplicationException.InvalidInput;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.ReplaceServiceAssignmentsCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.UpdateStaffMemberCommand;
import bg.spotyourslot.workforce.domain.StaffMemberTextCanonicalizer;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class StaffMemberInputValidator {
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 100;
    static final int DISPLAY_NAME_MAX_LENGTH = 200;
    static final int CONTACT_EMAIL_MAX_LENGTH = 320;
    static final int CONTACT_PHONE_MAX_LENGTH = 50;
    static final int CONTACT_PHONE_MIN_DIGITS = 3;
    static final int CONTACT_PHONE_MAX_DIGITS = 20;

    private static final Pattern CONTACT_PHONE_PATTERN =
            Pattern.compile("\\+?[0-9 ()./-]+");

    private final Validator validator;

    public StaffMemberInputValidator(Validator validator) {
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

    public UUID validateStaffMemberId(UUID staffMemberId) {
        if (staffMemberId == null) {
            throw new InvalidInput(InputField.STAFF_MEMBER_ID);
        }
        return staffMemberId;
    }

    public CreateStaffMemberCommand validateCreate(CreateStaffMemberCommand command) {
        if (command == null) {
            throw new InvalidInput(InputField.COMMAND);
        }
        return new CreateStaffMemberCommand(
                displayName(command.displayName()),
                contactEmail(command.contactEmail()),
                contactPhone(command.contactPhone()));
    }

    public UpdateStaffMemberCommand validateUpdate(UpdateStaffMemberCommand command) {
        if (command == null) {
            throw new InvalidInput(InputField.COMMAND);
        }
        return new UpdateStaffMemberCommand(
                displayName(command.displayName()),
                contactEmail(command.contactEmail()),
                contactPhone(command.contactPhone()),
                expectedVersion(command.expectedVersion()));
    }

    public long validateVersion(StaffMemberVersionCommand command) {
        if (command == null) {
            throw new InvalidInput(InputField.COMMAND);
        }
        return expectedVersion(command.expectedVersion());
    }

    public ReplaceServiceAssignmentsCommand validateAssignments(
            ReplaceServiceAssignmentsCommand command) {
        if (command == null) {
            throw new InvalidInput(InputField.COMMAND);
        }
        List<UUID> serviceIds = command.serviceIds();
        if (serviceIds == null
                || serviceIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(serviceIds).size() != serviceIds.size()) {
            throw new InvalidInput(InputField.SERVICE_IDS);
        }
        return new ReplaceServiceAssignmentsCommand(
                serviceIds, expectedVersion(command.expectedVersion()));
    }

    private String displayName(String value) {
        String canonical = StaffMemberTextCanonicalizer.canonicalDisplayName(value);
        if (canonical == null
                || canonical.isEmpty()
                || codePointLength(canonical) > DISPLAY_NAME_MAX_LENGTH) {
            throw new InvalidInput(InputField.DISPLAY_NAME);
        }
        return canonical;
    }

    private String contactEmail(String value) {
        String canonical = StaffMemberTextCanonicalizer.canonicalContactEmail(value);
        if (canonical != null
                && (codePointLength(canonical) > CONTACT_EMAIL_MAX_LENGTH
                        || !validator.validate(new ContactEmailCandidate(canonical)).isEmpty())) {
            throw new InvalidInput(InputField.CONTACT_EMAIL);
        }
        return canonical;
    }

    private String contactPhone(String value) {
        String canonical = StaffMemberTextCanonicalizer.canonicalContactPhone(value);
        if (canonical == null) {
            return null;
        }

        long digitCount = canonical.codePoints()
                .filter(codePoint -> codePoint >= '0' && codePoint <= '9')
                .count();
        if (codePointLength(canonical) > CONTACT_PHONE_MAX_LENGTH
                || !CONTACT_PHONE_PATTERN.matcher(canonical).matches()
                || digitCount < CONTACT_PHONE_MIN_DIGITS
                || digitCount > CONTACT_PHONE_MAX_DIGITS) {
            throw new InvalidInput(InputField.CONTACT_PHONE);
        }
        return canonical;
    }

    private long expectedVersion(Long value) {
        if (value == null || value < 0) {
            throw new InvalidInput(InputField.EXPECTED_VERSION);
        }
        return value;
    }

    private int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    public record PageInput(int page, int size) {
    }

    private record ContactEmailCandidate(@Email String value) {
    }
}
