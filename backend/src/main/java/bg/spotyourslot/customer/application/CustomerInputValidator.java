package bg.spotyourslot.customer.application;

import bg.spotyourslot.customer.application.CustomerAdministrationException.InputField;
import bg.spotyourslot.customer.application.CustomerAdministrationException.InvalidInput;
import bg.spotyourslot.customer.domain.CustomerField;
import bg.spotyourslot.customer.domain.CustomerProfile;
import bg.spotyourslot.customer.domain.CustomerSearchCriteria;
import bg.spotyourslot.customer.domain.CustomerSortField;
import bg.spotyourslot.customer.domain.InvalidCustomerData;
import bg.spotyourslot.shared.contact.ContactTextCanonicalizer;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Validates Customer administration input. Failures name only field identifiers, never a value.
 */
@Component
public class CustomerInputValidator {
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final Set<Integer> ALLOWED_PAGE_SIZES = Set.of(10, 25, 50);
    public static final int SEARCH_MAX_LENGTH = 100;

    public PageInput validatePage(Integer page, Integer size) {
        int validatedPage = page == null ? 0 : page;
        int validatedSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (validatedPage < 0) {
            throw new InvalidInput(InputField.PAGE);
        }
        if (!ALLOWED_PAGE_SIZES.contains(validatedSize)) {
            throw new InvalidInput(InputField.SIZE);
        }
        return new PageInput(validatedPage, validatedSize);
    }

    public CustomerSortField validateSort(String sort) {
        if (sort == null) {
            return CustomerSortField.NAME;
        }
        return switch (sort) {
            case "name" -> CustomerSortField.NAME;
            case "phone" -> CustomerSortField.PHONE;
            case "email" -> CustomerSortField.EMAIL;
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

    /**
     * A missing or blank term means no filter. Otherwise the trimmed term may have at most
     * {@value #SEARCH_MAX_LENGTH} code points.
     */
    public CustomerSearchCriteria validateSearch(String search) {
        String trimmed = ContactTextCanonicalizer.canonicalTrimmed(search);
        if (trimmed == null) {
            return CustomerSearchCriteria.none();
        }
        if (trimmed.codePointCount(0, trimmed.length()) > SEARCH_MAX_LENGTH) {
            throw new InvalidInput(InputField.SEARCH);
        }
        return CustomerSearchCriteria.fromTerm(trimmed);
    }

    public UUID validateBusinessId(UUID businessId) {
        if (businessId == null) {
            throw new InvalidInput(InputField.BUSINESS_ID);
        }
        return businessId;
    }

    public UUID validateCustomerId(UUID customerId) {
        if (customerId == null) {
            throw new InvalidInput(InputField.CUSTOMER_ID);
        }
        return customerId;
    }

    public long validateExpectedVersion(Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) {
            throw new InvalidInput(InputField.EXPECTED_VERSION);
        }
        return expectedVersion;
    }

    /**
     * Canonicalizes the three body fields with the shared contact policy and reports every invalid
     * field together.
     */
    public CustomerProfile validateProfile(String displayName, String phone, String email) {
        try {
            return CustomerProfile.fromInput(displayName, phone, email);
        } catch (InvalidCustomerData invalid) {
            Set<InputField> fields = EnumSet.noneOf(InputField.class);
            for (CustomerField field : invalid.fields()) {
                fields.add(switch (field) {
                    case DISPLAY_NAME -> InputField.DISPLAY_NAME;
                    case PHONE -> InputField.PHONE;
                    case EMAIL -> InputField.EMAIL;
                    case CONTACT -> InputField.CONTACT;
                });
            }
            throw new InvalidInput(fields);
        }
    }

    public record PageInput(int page, int size) {
    }
}
