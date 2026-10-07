package bg.spotyourslot.booking.domain;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Encoding version 1.
 *
 * <pre>
 * bytes   := "SYSBFP" (6 ASCII bytes) | version (uint16 = 1) | field*8
 * field   := tag (1 byte) | length (uint32, big endian) | value (length bytes)
 * fields in this exact order, each appearing exactly once:
 *   0x01 BUSINESS            16 bytes: most significant, then least significant UUID half, big endian
 *   0x02 SERVICE             16 bytes, the same layout
 *   0x03 STAFF_PREFERENCE    1 byte 0x00 (no preference) or 0x01 followed by the 16 UUID bytes
 *   0x04 START               8 bytes: epoch seconds, signed, big endian (the start is whole seconds)
 *   0x05 CUSTOMER_NAME       the canonical display name, UTF-8
 *   0x06 CUSTOMER_PHONE      0x00 (absent) or 0x01 followed by the canonical E.164 phone, UTF-8
 *   0x07 CUSTOMER_EMAIL      0x00 (absent) or 0x01 followed by the canonical email, UTF-8
 *   0x08 NOTE                0x00 (absent) or 0x01 followed by the normalized note, UTF-8
 * </pre>
 *
 * <p>Every field carries its tag and an explicit length, optional values carry an explicit
 * presence marker, and the order and field set are fixed, so two different requests can never
 * encode to the same bytes (an empty present value differs from an absent one, and a value cannot
 * shift into its neighbour). The StaffMember field holds the guest's requested preference, never
 * the StaffMember the server assigns. Neither the attempt identifier nor any clock is encoded.
 */
final class FingerprintEncodingV1 implements FingerprintEncoding {
    static final FingerprintEncodingV1 INSTANCE = new FingerprintEncodingV1();

    private static final byte[] MAGIC = "SYSBFP".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final byte TAG_BUSINESS = 0x01;
    private static final byte TAG_SERVICE = 0x02;
    private static final byte TAG_STAFF_PREFERENCE = 0x03;
    private static final byte TAG_START = 0x04;
    private static final byte TAG_NAME = 0x05;
    private static final byte TAG_PHONE = 0x06;
    private static final byte TAG_EMAIL = 0x07;
    private static final byte TAG_NOTE = 0x08;
    private static final byte ABSENT = 0x00;
    private static final byte PRESENT = 0x01;

    private FingerprintEncodingV1() {
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public byte[] encode(UUID businessId, NormalizedBookingRequest request) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        out.writeBytes(MAGIC);
        out.write((VERSION >>> 8) & 0xFF);
        out.write(VERSION & 0xFF);
        field(out, TAG_BUSINESS, uuid(businessId));
        field(out, TAG_SERVICE, uuid(request.serviceId()));
        field(out, TAG_STAFF_PREFERENCE, preference(request.requestedStaffMemberId()));
        field(out, TAG_START, ByteBuffer.allocate(Long.BYTES).putLong(request.start().getEpochSecond()).array());
        field(out, TAG_NAME, utf8(request.customerName()));
        field(out, TAG_PHONE, optional(request.customerPhone()));
        field(out, TAG_EMAIL, optional(request.customerEmail()));
        field(out, TAG_NOTE, optional(request.note()));
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, byte tag, byte[] value) {
        out.write(tag);
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        out.writeBytes(value);
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static byte[] preference(UUID staffMemberId) {
        if (staffMemberId == null) {
            return new byte[] {ABSENT};
        }
        return ByteBuffer.allocate(17).put(PRESENT).put(uuid(staffMemberId)).array();
    }

    private static byte[] optional(String value) {
        if (value == null) {
            return new byte[] {ABSENT};
        }
        byte[] bytes = utf8(value);
        return ByteBuffer.allocate(1 + bytes.length).put(PRESENT).put(bytes).array();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
