package com.telecombridge.diameter;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable Diameter Attribute-Value Pair (RFC 6733 section 4.1).
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           AVP Code                            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V M P r r r r r|                  AVP Length                   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                        Vendor-ID (opt)                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    Data ...
 * +-+-+-+-+-+-+-+-+
 * </pre>
 *
 * <p>The AVP Length field counts header plus data but <b>not</b> the padding.
 * On the wire every AVP is padded with zero bytes to a 4-byte boundary, so the
 * space it occupies is {@link #paddedLength()}.
 *
 * <p>The payload is kept as raw bytes; typed accessors ({@link #asUtf8()},
 * {@link #asUnsigned32()}, ...) interpret it on demand.
 */
public final class Avp {

    /** V bit: Vendor-Id field present. */
    public static final int FLAG_VENDOR = 0x80;
    /** M bit: receiver must understand this AVP or reject the message. */
    public static final int FLAG_MANDATORY = 0x40;
    /** P bit: end-to-end security (deprecated, never set here). */
    public static final int FLAG_PROTECTED = 0x20;

    public static final int HEADER_LENGTH = 8;
    public static final int VENDOR_HEADER_LENGTH = 12;

    private static final byte[] EMPTY = new byte[0];

    private final int code;
    private final int flags;
    private final long vendorId;
    private final byte[] data;

    private Avp(int code, int flags, long vendorId, byte[] data) {
        this.code = code;
        this.flags = flags & 0xFF;
        this.vendorId = vendorId;
        this.data = data == null ? EMPTY : data;
    }

    // ------------------------------------------------------------------ factories

    /** Raw AVP with explicit flags and payload. */
    public static Avp of(int code, int flags, byte[] data) {
        if ((flags & FLAG_VENDOR) != 0) {
            throw new IllegalArgumentException("Use vendorSpecific() when the V bit is set");
        }
        return new Avp(code, flags, 0, data.clone());
    }

    /** Vendor-specific AVP: sets the V bit and carries the Vendor-Id field. */
    public static Avp vendorSpecific(int code, int flags, long vendorId, byte[] data) {
        return new Avp(code, flags | FLAG_VENDOR, vendorId, data.clone());
    }

    /** Mandatory UTF8String / DiameterIdentity / DiameterURI AVP. */
    public static Avp utf8(int code, String value) {
        return utf8(code, FLAG_MANDATORY, value);
    }

    public static Avp utf8(int code, int flags, String value) {
        return new Avp(code, flags, 0, value.getBytes(StandardCharsets.UTF_8));
    }

    public static Avp octetString(int code, int flags, byte[] value) {
        return new Avp(code, flags, 0, value.clone());
    }

    /** Mandatory Unsigned32 AVP. */
    public static Avp unsigned32(int code, long value) {
        return unsigned32(code, FLAG_MANDATORY, value);
    }

    public static Avp unsigned32(int code, int flags, long value) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("Unsigned32 out of range: " + value);
        }
        return new Avp(code, flags, 0, ByteBuffer.allocate(4).putInt((int) value).array());
    }

    /** Mandatory Integer32 AVP (also used for Enumerated). */
    public static Avp integer32(int code, int value) {
        return integer32(code, FLAG_MANDATORY, value);
    }

    public static Avp integer32(int code, int flags, int value) {
        return new Avp(code, flags, 0, ByteBuffer.allocate(4).putInt(value).array());
    }

    /** Enumerated AVPs are encoded exactly like Integer32 (RFC 6733 section 4.3.1). */
    public static Avp enumerated(int code, int value) {
        return integer32(code, FLAG_MANDATORY, value);
    }

    /** Mandatory Unsigned64 AVP. */
    public static Avp unsigned64(int code, long value) {
        return unsigned64(code, FLAG_MANDATORY, value);
    }

    public static Avp unsigned64(int code, int flags, long value) {
        return new Avp(code, flags, 0, ByteBuffer.allocate(8).putLong(value).array());
    }

    /**
     * Address AVP: two-byte AddressType (1 = IPv4, 2 = IPv6 per the IANA
     * address family registry) followed by the raw address bytes.
     */
    public static Avp address(int code, InetAddress address) {
        byte[] raw = address.getAddress();
        int family = address instanceof Inet6Address ? 2 : 1;
        ByteBuffer buf = ByteBuffer.allocate(2 + raw.length);
        buf.putShort((short) family).put(raw);
        return new Avp(code, FLAG_MANDATORY, 0, buf.array());
    }

    /** Mandatory Grouped AVP whose payload is the concatenation of the encoded children. */
    public static Avp grouped(int code, Avp... children) {
        return grouped(code, FLAG_MANDATORY, Arrays.asList(children));
    }

    public static Avp grouped(int code, int flags, List<Avp> children) {
        return new Avp(code, flags, 0, DiameterCodec.encodeAvps(children));
    }

    /** Used by the decoder; the array is trusted not to be shared. */
    static Avp decoded(int code, int flags, long vendorId, byte[] data) {
        return new Avp(code, flags, vendorId, data);
    }

    // ------------------------------------------------------------------ header accessors

    public int code() {
        return code;
    }

    public int flags() {
        return flags;
    }

    public boolean isVendorSpecific() {
        return (flags & FLAG_VENDOR) != 0;
    }

    public boolean isMandatory() {
        return (flags & FLAG_MANDATORY) != 0;
    }

    public boolean isProtected() {
        return (flags & FLAG_PROTECTED) != 0;
    }

    public long vendorId() {
        return vendorId;
    }

    /** Copy of the raw payload without padding. */
    public byte[] data() {
        return data.clone();
    }

    public int dataLength() {
        return data.length;
    }

    public int headerLength() {
        return isVendorSpecific() ? VENDOR_HEADER_LENGTH : HEADER_LENGTH;
    }

    /** Value written into the AVP Length field: header + data, excluding padding. */
    public int length() {
        return headerLength() + data.length;
    }

    /** Bytes the AVP occupies on the wire, including zero padding to a 4-byte boundary. */
    public int paddedLength() {
        return DiameterCodec.pad4(length());
    }

    // ------------------------------------------------------------------ typed accessors

    public String asUtf8() {
        return new String(data, StandardCharsets.UTF_8);
    }

    public long asUnsigned32() {
        requireLength(4, "Unsigned32");
        return ByteBuffer.wrap(data).getInt() & 0xFFFFFFFFL;
    }

    public int asInteger32() {
        requireLength(4, "Integer32");
        return ByteBuffer.wrap(data).getInt();
    }

    public long asUnsigned64() {
        requireLength(8, "Unsigned64");
        return ByteBuffer.wrap(data).getLong();
    }

    public InetAddress asAddress() {
        if (data.length < 2) {
            throw new DiameterDecodeException("Address AVP " + code + " too short");
        }
        int family = ByteBuffer.wrap(data).getShort() & 0xFFFF;
        byte[] raw = Arrays.copyOfRange(data, 2, data.length);
        try {
            if (family == 1 && raw.length == 4) {
                return Inet4Address.getByAddress(raw);
            }
            if (family == 2 && raw.length == 16) {
                return Inet6Address.getByAddress(raw);
            }
        } catch (UnknownHostException e) {
            throw new DiameterDecodeException("Invalid address in AVP " + code);
        }
        throw new DiameterDecodeException("Unsupported address family " + family + " in AVP " + code);
    }

    /** Decodes the payload as a sequence of child AVPs. */
    public List<Avp> asGrouped() {
        return DiameterCodec.decodeAvps(data);
    }

    /** First child with the given code inside a Grouped AVP. */
    public Optional<Avp> child(int childCode) {
        return asGrouped().stream().filter(a -> a.code == childCode).findFirst();
    }

    private void requireLength(int expected, String type) {
        if (data.length != expected) {
            throw new DiameterDecodeException(
                    type + " AVP " + code + " must have " + expected + " data bytes, found " + data.length);
        }
    }

    // ------------------------------------------------------------------ object

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Avp other)) return false;
        return code == other.code && flags == other.flags && vendorId == other.vendorId
                && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, flags, vendorId, Arrays.hashCode(data));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Avp{code=").append(code);
        if (isVendorSpecific()) sb.append(", vendor=").append(vendorId);
        sb.append(", flags=");
        if (isVendorSpecific()) sb.append('V');
        if (isMandatory()) sb.append('M');
        if (isProtected()) sb.append('P');
        sb.append(", len=").append(length()).append('}');
        return sb.toString();
    }
}
