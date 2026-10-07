package bg.spotyourslot.booking.infrastructure;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Test support: builds the sealed {@link AppointmentPersistenceException} types, whose constructors
 * are package-private, by running a synthetic driver error through the store's own classifier, so
 * tests in other packages use exactly the production classification.
 */
public final class AppointmentStoreFailures {
    private AppointmentStoreFailures() {
    }

    public static AppointmentPersistenceException classify(String sqlState, String constraint) {
        StringBuilder fields = new StringBuilder();
        fields.append('S').append("ERROR").append('\0');
        fields.append('C').append(sqlState).append('\0');
        fields.append('M').append("synthetic driver message").append('\0');
        if (constraint != null) {
            fields.append('n').append(constraint).append('\0');
        }
        fields.append('\0');
        return AppointmentStore.translate(new PSQLException(new ServerErrorMessage(fields.toString())));
    }
}
