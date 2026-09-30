package bg.spotyourslot.publicprofile.web;

/**
 * Raised for every case that is not a public ACTIVE Business: unknown, DRAFT, SUSPENDED,
 * malformed, and reserved. It carries no cause, slug, or state so no caller can tell them apart.
 */
final class PublicBusinessUnavailable extends RuntimeException {
    PublicBusinessUnavailable() {
        super("Public Business page is unavailable");
    }
}
