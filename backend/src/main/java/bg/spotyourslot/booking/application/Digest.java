package bg.spotyourslot.booking.application;

/** An irreversible 128-bit limiter key. It is the only form in which any request value is retained. */
record Digest(long high, long low) {
    /** Never prints the value, so a key cannot reach a log line. */
    @Override
    public String toString() {
        return "Digest[redacted]";
    }
}
