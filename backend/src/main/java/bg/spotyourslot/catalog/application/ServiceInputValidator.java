package bg.spotyourslot.catalog.application;

import bg.spotyourslot.catalog.ServiceApplicationException.InputField;
import bg.spotyourslot.catalog.ServiceApplicationException.InvalidInput;
import bg.spotyourslot.catalog.ServiceRecords.CreateServiceCommand;
import bg.spotyourslot.catalog.ServiceRecords.ServiceVersionCommand;
import bg.spotyourslot.catalog.ServiceRecords.UpdateServiceCommand;
import bg.spotyourslot.catalog.domain.ServiceTextCanonicalizer;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ServiceInputValidator {
    public static final int MAX_PAGE_SIZE = 100;
    static final int NAME_MAX_LENGTH = 200;
    static final int DESCRIPTION_MAX_LENGTH = 2_000;
    static final int MIN_DURATION_MINUTES = 1;
    static final int MAX_DURATION_MINUTES = 480;
    static final int MAX_PRICE_INTEGER_DIGITS = 10;
    static final int MAX_PRICE_FRACTIONAL_DIGITS = 2;

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

    public UUID validateServiceId(UUID serviceId) {
        if (serviceId == null) {
            throw new InvalidInput(InputField.SERVICE_ID);
        }
        return serviceId;
    }

    public CreateServiceCommand validateCreate(CreateServiceCommand command) {
        if (command == null) {
            throw new InvalidInput(InputField.COMMAND);
        }
        return new CreateServiceCommand(
                name(command.name()),
                description(command.description()),
                duration(command.durationMinutes()),
                price(command.price()));
    }

    public UpdateServiceCommand validateUpdate(UpdateServiceCommand command) {
        if (command == null) {
            throw new InvalidInput(InputField.COMMAND);
        }
        return new UpdateServiceCommand(
                name(command.name()),
                description(command.description()),
                duration(command.durationMinutes()),
                price(command.price()),
                expectedVersion(command.expectedVersion()));
    }

    public long validateVersion(ServiceVersionCommand command) {
        if (command == null) {
            throw new InvalidInput(InputField.COMMAND);
        }
        return expectedVersion(command.expectedVersion());
    }

    private String name(String value) {
        String canonical = ServiceTextCanonicalizer.canonicalName(value);
        if (canonical == null
                || canonical.isEmpty()
                || codePointLength(canonical) > NAME_MAX_LENGTH) {
            throw new InvalidInput(InputField.NAME);
        }
        return canonical;
    }

    private String description(String value) {
        String canonical = ServiceTextCanonicalizer.canonicalDescription(value);
        if (canonical != null && codePointLength(canonical) > DESCRIPTION_MAX_LENGTH) {
            throw new InvalidInput(InputField.DESCRIPTION);
        }
        return canonical;
    }

    private int duration(Integer value) {
        if (value == null || value < MIN_DURATION_MINUTES || value > MAX_DURATION_MINUTES) {
            throw new InvalidInput(InputField.DURATION_MINUTES);
        }
        return value;
    }

    private BigDecimal price(BigDecimal value) {
        if (value == null
                || value.signum() < 0
                || value.scale() > MAX_PRICE_FRACTIONAL_DIGITS
                || integerDigits(value) > MAX_PRICE_INTEGER_DIGITS) {
            throw new InvalidInput(InputField.PRICE);
        }
        return value;
    }

    private long expectedVersion(Long value) {
        if (value == null || value < 0) {
            throw new InvalidInput(InputField.EXPECTED_VERSION);
        }
        return value;
    }

    private int integerDigits(BigDecimal value) {
        if (value.signum() == 0) {
            return 0;
        }
        return Math.max(0, value.precision() - value.scale());
    }

    private int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    public record PageInput(int page, int size) {
    }
}
