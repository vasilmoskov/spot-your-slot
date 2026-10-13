package bg.spotyourslot.booking.domain;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Encoding version 2 (ADR-0027 section 5). Released layouts never change; any change is version 3.
 *
 * <pre>
 * bytes   := "SYSBFP" | uint16 version = 2 | field*8
 * field   := tag (1 byte) | length (uint32, big endian) | value
 * 0x01 BUSINESS          16 bytes (as v1)
 * 0x02 SERVICES          count (1 byte, 1..5) | reviewed flag (1 byte: 0x00 absent, 0x01 present) |
 *                        per Service in ascending unsigned UUID order:
 *                          service id (16 bytes, most significant half then least significant half,
 *                                      each big endian) |
 *                          [flag 0x01] reviewed duration minutes (uint32 big endian) |
 *                                      reviewed price cents (int64 big endian)
 * 0x03 STAFF_PREFERENCE  as v1
 * 0x04 START             as v1
 * 0x05 CUSTOMER_NAME     as v1
 * 0x06 CUSTOMER_PHONE    as v1
 * 0x07 CUSTOMER_EMAIL    as v1
 * 0x08 NOTE              as v1
 * </pre>
 *
 * <p>The Services are sorted here, by {@link UnsignedUuidOrder}, whatever order the request holds,
 * so the click order never reaches the bytes and a later rename cannot change them (no name is
 * encoded). The flag is 0x00 only for the legacy request shape and 0x01 only for the new shape,
 * never mixed. Neither the attempt identifier, a clock, nor any server-computed value is encoded.
 */
final class FingerprintEncodingV2 implements FingerprintEncoding {
    static final FingerprintEncodingV2 INSTANCE = new FingerprintEncodingV2();

    private static final byte[] MAGIC = "SYSBFP".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 2;
    private static final byte TAG_BUSINESS = 0x01;
    private static final byte TAG_SERVICES = 0x02;
    private static final byte TAG_STAFF_PREFERENCE = 0x03;
    private static final byte TAG_START = 0x04;
    private static final byte TAG_NAME = 0x05;
    private static final byte TAG_PHONE = 0x06;
    private static final byte TAG_EMAIL = 0x07;
    private static final byte TAG_NOTE = 0x08;
    private static final byte ABSENT = 0x00;
    private static final byte PRESENT = 0x01;

    private FingerprintEncodingV2() {
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public boolean supports(NormalizedBookingRequest request) {
        return true;
    }

    @Override
    public byte[] encode(UUID businessId, NormalizedBookingRequest request) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(320);
        out.writeBytes(MAGIC);
        out.write((VERSION >>> 8) & 0xFF);
        out.write(VERSION & 0xFF);
        field(out, TAG_BUSINESS, uuid(businessId));
        field(out, TAG_SERVICES, services(request));
        field(out, TAG_STAFF_PREFERENCE, preference(request.requestedStaffMemberId()));
        field(out, TAG_START, ByteBuffer.allocate(Long.BYTES).putLong(request.start().getEpochSecond()).array());
        field(out, TAG_NAME, utf8(request.customerName()));
        field(out, TAG_PHONE, optional(request.customerPhone()));
        field(out, TAG_EMAIL, optional(request.customerEmail()));
        field(out, TAG_NOTE, optional(request.note()));
        return out.toByteArray();
    }

    private static byte[] services(NormalizedBookingRequest request) {
        List<RequestedService> sorted = new ArrayList<>(request.services());
        sorted.sort((first, second) ->
                UnsignedUuidOrder.INSTANCE.compare(first.serviceId(), second.serviceId()));
        boolean reviewed = request.reviewed();
        int perService = 16 + (reviewed ? Integer.BYTES + Long.BYTES : 0);
        ByteBuffer buffer = ByteBuffer.allocate(2 + sorted.size() * perService);
        buffer.put((byte) sorted.size());
        buffer.put(reviewed ? PRESENT : ABSENT);
        for (RequestedService service : sorted) {
            buffer.put(uuid(service.serviceId()));
            if (reviewed) {
                buffer.putInt(service.reviewed().durationMinutes());
                buffer.putLong(service.reviewed().priceCents());
            }
        }
        return buffer.array();
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
