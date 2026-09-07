package com.telecombridge.diameter;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes Diameter messages and AVPs to and from bytes (RFC 6733 sections 3 and 4).
 *
 * <p>Rules implemented here that Wireshark's Diameter dissector will check:
 * <ul>
 *   <li>Header is exactly 20 bytes, version byte is 1.</li>
 *   <li>Message Length = 20 + sum of padded AVP lengths.</li>
 *   <li>AVP Length = 8 (or 12 with V bit) + data length, padding excluded.</li>
 *   <li>Every AVP is followed by zero bytes up to the next 4-byte boundary.</li>
 *   <li>Grouped AVP payload is a sequence of fully padded child AVPs.</li>
 * </ul>
 *
 * <p>Decoding is strict: a bad version, an AVP length shorter than its header or
 * longer than the remaining bytes all raise {@link DiameterDecodeException}.
 * Netty handlers treat that as a fatal peer error and close the connection.
 */
public final class DiameterCodec {

    private DiameterCodec() {
    }

    /** Rounds up to the next multiple of 4. */
    public static int pad4(int length) {
        return (length + 3) & ~3;
    }

    // ------------------------------------------------------------------ message

    public static byte[] encode(DiameterMessage message) {
        ByteBuf buf = Unpooled.buffer(message.encodedLength());
        try {
            encode(message, buf);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    public static void encode(DiameterMessage message, ByteBuf out) {
        int length = message.encodedLength();
        out.writeByte(DiameterMessage.VERSION);
        out.writeMedium(length);
        out.writeByte(message.flags());
        out.writeMedium(message.commandCode());
        out.writeInt((int) message.applicationId());
        out.writeInt(message.hopByHopId());
        out.writeInt(message.endToEndId());
        for (Avp avp : message.avps()) {
            encodeAvp(avp, out);
        }
    }

    public static DiameterMessage decode(byte[] bytes) {
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            DiameterMessage msg = decode(buf);
            if (buf.isReadable()) {
                throw new DiameterDecodeException(buf.readableBytes() + " trailing bytes after Diameter message");
            }
            return msg;
        } finally {
            buf.release();
        }
    }

    /** Decodes exactly one message from the buffer, advancing the reader index past it. */
    public static DiameterMessage decode(ByteBuf in) {
        if (in.readableBytes() < DiameterMessage.HEADER_LENGTH) {
            throw new DiameterDecodeException("Need 20 header bytes, have " + in.readableBytes());
        }
        int version = in.readUnsignedByte();
        if (version != DiameterMessage.VERSION) {
            throw new DiameterDecodeException("Unsupported Diameter version " + version);
        }
        int length = in.readUnsignedMedium();
        if (length < DiameterMessage.HEADER_LENGTH || (length & 3) != 0) {
            throw new DiameterDecodeException("Invalid message length " + length);
        }
        if (in.readableBytes() + 4 < length) {
            throw new DiameterDecodeException("Message length " + length + " exceeds available bytes");
        }
        int flags = in.readUnsignedByte();
        int commandCode = in.readUnsignedMedium();
        long applicationId = in.readUnsignedInt();
        int hopByHop = in.readInt();
        int endToEnd = in.readInt();

        ByteBuf avpBytes = in.readSlice(length - DiameterMessage.HEADER_LENGTH);
        List<Avp> avps = decodeAvps(avpBytes);

        return DiameterMessage.answer(commandCode, applicationId)
                .flags(flags)
                .hopByHopId(hopByHop)
                .endToEndId(endToEnd)
                .avps(avps)
                .build();
    }

    // ------------------------------------------------------------------ AVPs

    /** Sum of padded lengths, i.e. the bytes the list occupies on the wire. */
    public static int encodedLength(List<Avp> avps) {
        int total = 0;
        for (Avp avp : avps) {
            total += avp.paddedLength();
        }
        return total;
    }

    /** Encodes a list of AVPs, each padded, into a fresh byte array (used for Grouped payloads). */
    public static byte[] encodeAvps(List<Avp> avps) {
        ByteBuf buf = Unpooled.buffer(encodedLength(avps));
        try {
            for (Avp avp : avps) {
                encodeAvp(avp, buf);
            }
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    public static void encodeAvp(Avp avp, ByteBuf out) {
        out.writeInt(avp.code());
        out.writeByte(avp.flags());
        out.writeMedium(avp.length());
        if (avp.isVendorSpecific()) {
            out.writeInt((int) avp.vendorId());
        }
        out.writeBytes(avp.data());
        int padding = avp.paddedLength() - avp.length();
        for (int i = 0; i < padding; i++) {
            out.writeByte(0);
        }
    }

    public static List<Avp> decodeAvps(byte[] bytes) {
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            return decodeAvps(buf);
        } finally {
            buf.release();
        }
    }

    /** Decodes AVPs until the buffer is exhausted. */
    public static List<Avp> decodeAvps(ByteBuf in) {
        List<Avp> avps = new ArrayList<>();
        while (in.isReadable()) {
            avps.add(decodeAvp(in));
        }
        return avps;
    }

    public static Avp decodeAvp(ByteBuf in) {
        if (in.readableBytes() < Avp.HEADER_LENGTH) {
            throw new DiameterDecodeException("Need 8 AVP header bytes, have " + in.readableBytes());
        }
        int code = in.readInt();
        int flags = in.readUnsignedByte();
        int length = in.readUnsignedMedium();
        boolean vendor = (flags & Avp.FLAG_VENDOR) != 0;
        int headerLength = vendor ? Avp.VENDOR_HEADER_LENGTH : Avp.HEADER_LENGTH;
        if (length < headerLength) {
            throw new DiameterDecodeException("AVP " + code + " length " + length + " shorter than header");
        }
        long vendorId = 0;
        if (vendor) {
            if (in.readableBytes() < 4) {
                throw new DiameterDecodeException("AVP " + code + " truncated Vendor-Id");
            }
            vendorId = in.readUnsignedInt();
        }
        int dataLength = length - headerLength;
        int padding = pad4(length) - length;
        if (in.readableBytes() < dataLength) {
            throw new DiameterDecodeException("AVP " + code + " length " + length + " exceeds remaining "
                    + in.readableBytes() + " bytes");
        }
        byte[] data = new byte[dataLength];
        in.readBytes(data);
        // Padding is only present if this is not the last AVP in a stream whose
        // enclosing length already accounts for it; be lenient at the very end.
        in.skipBytes(Math.min(padding, in.readableBytes()));
        return Avp.decoded(code, flags, vendorId, data);
    }
}
