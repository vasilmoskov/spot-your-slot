package bg.spotyourslot.workforce.application;

import bg.spotyourslot.shared.contact.ContactEmailPolicy;
import bg.spotyourslot.shared.contact.ContactPhoneNumbers;
import bg.spotyourslot.shared.contact.ContactTextCanonicalizer;
import bg.spotyourslot.workforce.StaffMemberApplicationException.InputField;
import bg.spotyourslot.workforce.StaffMemberApplicationException.InvalidInput;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.ReplaceServiceAssignmentsCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberSortField;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.UpdateStaffMemberCommand;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class StaffMemberInputValidator {
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 50;
    public static final Set<Integer> ALLOWED_PAGE_SIZES = Set.of(10, 25, 50);
    static final int DISPLAY_NAME_MAX_LENGTH = 200;


    public PageInput validatePage(int page, int size) {
        if (page < 0) {
            throw new InvalidInput(InputField.PAGE);
        }
        if (!ALLOWED_PAGE_SIZES.contains(size)) {
            throw new InvalidInput(InputField.SIZE);
        }
        return new PageInput(page, size);
    }

    public StaffMemberSortField validateSort(String sort) {
        if (sort == null) {
            return StaffMemberSortField.NAME;
        }
        return switch (sort) {
            case "name" -> StaffMemberSortField.NAME;
            case "status" -> StaffMemberSortField.STATUS;
            case "phone" -> StaffMemberSortField.PHONE;
            case "email" -> StaffMemberSortField.EMAIL;
            default -> throw new InvalidInput(InputField.SORT);
        };
    }

    public boolean validateAscending(String direction) {
        if (direction == null) {
            return true;
        }
        return switch (direction) {
            case "asc" -> true;
            case "desc" -> false;
            default -> throw new InvalidInput(InputField.DIRECTION);
        };
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
        String canonical = ContactTextCanonicalizer.canonicalDisplayName(value);
        if (canonical == null
                || canonical.isEmpty()
                || codePointLength(canonical) > DISPLAY_NAME_MAX_LENGTH) {
            throw new InvalidInput(InputField.DISPLAY_NAME);
        }
        return canonical;
    }

    private String contactEmail(String value) {
        String trimmed = ContactTextCanonicalizer.canonicalTrimmed(value);
        if (trimmed == null) {
            return null;
        }
        return ContactEmailPolicy.canonicalize(trimmed)
                .orElseThrow(() -> new InvalidInput(InputField.CONTACT_EMAIL));
    }

    private String contactPhone(String value) {
        String trimmed = ContactTextCanonicalizer.canonicalTrimmed(value);
        if (trimmed == null) {
            return null;
        }

        return ContactPhoneNumbers.canonicalize(trimmed)
                .orElseThrow(() -> new InvalidInput(InputField.CONTACT_PHONE));
    }

    /*
     * ContactPhoneNumbers is the single backend component that
     * interprets a telephone candidate's prefix ('+', '00', or a bare '0'
     * with the default BG region), parses it with libphonenumber, and
     * requires full validity (not merely a possible-length check) before
     * returning a canonical E.164 form. An ambiguous value without a
     * recognizable prefix is rejected there rather than silently assumed to
     * be Bulgarian.
     */

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
}
