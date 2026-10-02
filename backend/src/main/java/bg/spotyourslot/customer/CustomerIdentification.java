package bg.spotyourslot.customer;

import java.util.UUID;

/**
 * Finds or creates the Customer of one Business that a submitted identity refers to, conservatively
 * (ADR-0020). The display name is validated but never used to match. A matched Customer is never
 * changed, and an identifier is never added, moved, or merged.
 *
 * <p>The operation requires a caller-owned transaction and never opens one. It is documented and
 * tested under {@code READ_COMMITTED}; stronger isolation is accepted. A failure is never an
 * outcome: {@link CustomerConcurrentConflict} and {@link CustomerOperationFailure} are thrown and
 * both mark the caller's transaction rollback-only, so the caller must abandon the current
 * transaction. Only {@link CustomerConcurrentConflict} is retryable, in a completely new
 * transaction; {@link CustomerOperationFailure} is not declared retryable by this capability and the
 * caller applies its own higher-level failure policy.
 */
public interface CustomerIdentification {
    CustomerMatchOutcome findOrCreate(UUID businessId, CustomerIdentity identity);
}
