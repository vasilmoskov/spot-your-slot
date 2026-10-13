package bg.spotyourslot.booking.domain;

import java.util.Comparator;
import java.util.UUID;

/**
 * The identifier order of ADR-0027 section 2: the unsigned lexicographic order of the 16 bytes of a
 * UUID in big-endian layout. It is the order of the canonical lowercase hexadecimal text and
 * PostgreSQL's {@code uuid} order, and equals comparing the most significant half and then the least
 * significant half with {@link Long#compareUnsigned}.
 *
 * <p>It is deliberately <b>not</b> {@link UUID#compareTo}, which compares the halves as signed
 * {@code long} values and therefore sorts every identifier with a first byte of {@code 0x80} or
 * higher before every identifier below {@code 0x80}. Request identity (the fingerprint) uses this
 * order; lock order is decided by the locking statement itself, never by this comparator.
 */
public final class UnsignedUuidOrder implements Comparator<UUID> {
    public static final UnsignedUuidOrder INSTANCE = new UnsignedUuidOrder();

    private UnsignedUuidOrder() {
    }

    @Override
    public int compare(UUID first, UUID second) {
        int byHigh = Long.compareUnsigned(first.getMostSignificantBits(), second.getMostSignificantBits());
        if (byHigh != 0) {
            return byHigh;
        }
        return Long.compareUnsigned(first.getLeastSignificantBits(), second.getLeastSignificantBits());
    }
}
