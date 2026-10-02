package bg.spotyourslot.architecture.customerconsumer;

import bg.spotyourslot.customer.CustomerIdentification;
import bg.spotyourslot.customer.CustomerIdentity;
import bg.spotyourslot.customer.CustomerMatchOutcome;
import bg.spotyourslot.customer.CustomerMatchOutcome.CreatedCustomer;
import bg.spotyourslot.customer.CustomerMatchOutcome.ExistingCustomer;
import bg.spotyourslot.customer.CustomerReferenceAccess;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A test-only stand-in for a future Booking consumer. It uses the published Customer contracts only
 * (the root package of {@code customer}) and writes an Appointment-like probe row inside the
 * caller-owned transaction. The probe table exists only while a test class that needs it runs; it is
 * created and dropped with explicit DDL, is never part of Flyway, and holds no personal data.
 */
public final class CustomerConsumerProbe {
    private final CustomerIdentification identification;
    private final CustomerReferenceAccess references;
    private final JdbcClient jdbc;

    public CustomerConsumerProbe(
            CustomerIdentification identification, CustomerReferenceAccess references, JdbcClient jdbc) {
        this.identification = identification;
        this.references = references;
        this.jdbc = jdbc;
    }

    public static void createTable(JdbcClient jdbc) {
        jdbc.sql("""
                        CREATE TABLE IF NOT EXISTS booking_probe (
                            id uuid PRIMARY KEY,
                            business_id uuid NOT NULL,
                            customer_id uuid NOT NULL,
                            FOREIGN KEY (business_id, customer_id) REFERENCES customer (business_id, id)
                        )
                        """)
                .update();
    }

    public static void dropTable(JdbcClient jdbc) {
        jdbc.sql("DROP TABLE IF EXISTS booking_probe").update();
    }

    public static long rows(JdbcClient jdbc) {
        return jdbc.sql("SELECT count(*) FROM booking_probe").query(Long.class).single();
    }

    /**
     * Identifies the Customer, then (for an existing or created Customer) validates the reference
     * and writes the probe row. It must run inside a caller-owned transaction.
     */
    public CustomerMatchOutcome identifyAndRecord(UUID businessId, CustomerIdentity identity) {
        CustomerMatchOutcome outcome = identification.findOrCreate(businessId, identity);
        UUID customerId = switch (outcome) {
            case ExistingCustomer existing -> existing.customerId();
            case CreatedCustomer created -> created.customerId();
            default -> null;
        };
        if (customerId != null) {
            if (references.find(businessId, customerId).isEmpty()) {
                throw new IllegalStateException("the identified Customer reference is missing");
            }
            record(businessId, customerId);
        }
        return outcome;
    }

    /** An Appointment-like write that needs a live transaction and a same-Business Customer. */
    public void record(UUID businessId, UUID customerId) {
        jdbc.sql("INSERT INTO booking_probe(id, business_id, customer_id) VALUES (:id, :businessId, :customerId)")
                .param("id", UUID.randomUUID())
                .param("businessId", businessId)
                .param("customerId", customerId)
                .update();
    }
}
