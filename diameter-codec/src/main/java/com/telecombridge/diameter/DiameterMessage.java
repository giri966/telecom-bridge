package com.telecombridge.diameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * An immutable Diameter message: 20-byte header plus an ordered list of AVPs (RFC 6733 section 3).
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    Version    |                 Message Length                |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |R P E T r r r r|                  Command Code                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Application-ID                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      Hop-by-Hop Identifier                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      End-to-End Identifier                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  AVPs ...
 * +-+-+-+-+-+-+-+-+-+-+-+-+-
 * </pre>
 *
 * <p>Message Length counts the header and every AVP <b>including</b> AVP padding,
 * i.e. the exact number of bytes on the wire.
 */
public final class DiameterMessage {

    public static final int HEADER_LENGTH = 20;
    public static final int VERSION = 1;

    /** R bit: this is a request (clear = answer). */
    public static final int FLAG_REQUEST = 0x80;
    /** P bit: message may be proxied, relayed or redirected. */
    public static final int FLAG_PROXIABLE = 0x40;
    /** E bit: answer contains a protocol error (Result-Code 3xxx). */
    public static final int FLAG_ERROR = 0x20;
    /** T bit: potentially retransmitted after link failover. */
    public static final int FLAG_RETRANSMITTED = 0x10;

    private final int flags;
    private final int commandCode;
    private final long applicationId;
    private final int hopByHopId;
    private final int endToEndId;
    private final List<Avp> avps;

    private DiameterMessage(Builder b) {
        this.flags = b.flags & 0xFF;
        this.commandCode = b.commandCode;
        this.applicationId = b.applicationId;
        this.hopByHopId = b.hopByHopId;
        this.endToEndId = b.endToEndId;
        this.avps = Collections.unmodifiableList(new ArrayList<>(b.avps));
    }

    /** Starts a request (R bit set) for the given command and application. */
    public static Builder request(int commandCode, long applicationId) {
        return new Builder(commandCode, applicationId).flags(FLAG_REQUEST);
    }

    /** Starts an answer (R bit clear) for the given command and application. */
    public static Builder answer(int commandCode, long applicationId) {
        return new Builder(commandCode, applicationId);
    }

    /**
     * Builds the answer skeleton for this request: same command code, application,
     * Hop-by-Hop and End-to-End identifiers; R, E and T bits cleared, P bit copied.
     * The caller adds the answer AVPs (Result-Code, Origin-Host, ...).
     */
    public Builder createAnswer() {
        if (!isRequest()) {
            throw new IllegalStateException("Cannot create an answer to an answer");
        }
        return new Builder(commandCode, applicationId)
                .flags(flags & FLAG_PROXIABLE)
                .hopByHopId(hopByHopId)
                .endToEndId(endToEndId);
    }

    // ------------------------------------------------------------------ header

    public int flags() {
        return flags;
    }

    public boolean isRequest() {
        return (flags & FLAG_REQUEST) != 0;
    }

    public boolean isProxiable() {
        return (flags & FLAG_PROXIABLE) != 0;
    }

    public boolean isError() {
        return (flags & FLAG_ERROR) != 0;
    }

    public boolean isRetransmitted() {
        return (flags & FLAG_RETRANSMITTED) != 0;
    }

    public int commandCode() {
        return commandCode;
    }

    public long applicationId() {
        return applicationId;
    }

    /** Hop-by-Hop identifier as a signed int (the raw 32 bits). */
    public int hopByHopId() {
        return hopByHopId;
    }

    public int endToEndId() {
        return endToEndId;
    }

    /** Short name such as "CCR" or "DWA" for logging. */
    public String commandName() {
        return CommandCode.name(commandCode, isRequest());
    }

    /** Total encoded length in bytes: header plus padded AVPs. */
    public int encodedLength() {
        return HEADER_LENGTH + DiameterCodec.encodedLength(avps);
    }

    // ------------------------------------------------------------------ AVPs

    public List<Avp> avps() {
        return avps;
    }

    /** First AVP with the given code, if present. */
    public Optional<Avp> avp(int code) {
        for (Avp a : avps) {
            if (a.code() == code) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /** All AVPs with the given code (e.g. multiple Auth-Application-Id in a CER). */
    public List<Avp> avps(int code) {
        List<Avp> out = new ArrayList<>();
        for (Avp a : avps) {
            if (a.code() == code) {
                out.add(a);
            }
        }
        return out;
    }

    public Optional<Long> resultCode() {
        return avp(AvpCode.RESULT_CODE).map(Avp::asUnsigned32);
    }

    public Optional<String> sessionId() {
        return avp(AvpCode.SESSION_ID).map(Avp::asUtf8);
    }

    public Optional<String> originHost() {
        return avp(AvpCode.ORIGIN_HOST).map(Avp::asUtf8);
    }

    @Override
    public String toString() {
        return String.format("%s(cmd=%d app=%d hbh=0x%08x e2e=0x%08x flags=%s avps=%d len=%d)",
                commandName(), commandCode, applicationId, hopByHopId, endToEndId, flagString(), avps.size(),
                encodedLength());
    }

    private String flagString() {
        StringBuilder sb = new StringBuilder(4);
        if (isRequest()) sb.append('R');
        if (isProxiable()) sb.append('P');
        if (isError()) sb.append('E');
        if (isRetransmitted()) sb.append('T');
        return sb.length() == 0 ? "-" : sb.toString();
    }

    // ------------------------------------------------------------------ builder

    public static final class Builder {
        private int flags;
        private final int commandCode;
        private final long applicationId;
        private int hopByHopId;
        private int endToEndId;
        private final List<Avp> avps = new ArrayList<>();

        Builder(int commandCode, long applicationId) {
            if (commandCode < 0 || commandCode > 0xFFFFFF) {
                throw new IllegalArgumentException("Command code must fit in 24 bits: " + commandCode);
            }
            if (applicationId < 0 || applicationId > 0xFFFFFFFFL) {
                throw new IllegalArgumentException("Application id must fit in 32 bits: " + applicationId);
            }
            this.commandCode = commandCode;
            this.applicationId = applicationId;
        }

        public Builder flags(int flags) {
            this.flags = flags;
            return this;
        }

        public Builder proxiable() {
            this.flags |= FLAG_PROXIABLE;
            return this;
        }

        public Builder error() {
            this.flags |= FLAG_ERROR;
            return this;
        }

        public Builder hopByHopId(int hopByHopId) {
            this.hopByHopId = hopByHopId;
            return this;
        }

        public Builder endToEndId(int endToEndId) {
            this.endToEndId = endToEndId;
            return this;
        }

        public Builder avp(Avp avp) {
            this.avps.add(avp);
            return this;
        }

        public Builder avps(List<Avp> list) {
            this.avps.addAll(list);
            return this;
        }

        public DiameterMessage build() {
            return new DiameterMessage(this);
        }
    }
}
