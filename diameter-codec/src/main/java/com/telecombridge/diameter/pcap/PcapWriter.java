package com.telecombridge.diameter.pcap;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Writes a libpcap file that Wireshark can open and dissect.
 *
 * <p><b>What this is, and what it is not.</b> The payload bytes are the real ones: exactly
 * what the socket carried, captured in the Netty pipeline, with real timestamps. The
 * Ethernet, IPv4 and TCP headers around them are synthesised here, because capturing the
 * genuine headers on Windows loopback needs Npcap or pktmon, and both require administrator
 * rights that were not available. So this is an accurate record of the protocol exchange but
 * a reconstruction at the link and transport layers. It is labelled that way in the README
 * rather than passed off as a `tcpdump` capture. The sequence numbers, ACKs, checksums and
 * lengths are all consistent, so Wireshark reassembles the streams and the Diameter and HTTP
 * dissectors work normally.
 *
 * <p>Frames are written as LINKTYPE_ETHERNET (1) with locally-administered MAC addresses.
 * Each TCP flow gets a SYN, SYN-ACK and ACK when it is first seen, so the capture contains
 * complete streams rather than starting mid-conversation.
 *
 * <p>Thread-safe: every write is serialised on this object.
 */
public final class PcapWriter implements Closeable {

    private static final int MAGIC = 0xA1B2C3D4;
    private static final int LINKTYPE_ETHERNET = 1;
    private static final int SNAPLEN = 262144;

    private static final byte[] MAC_A = {0x02, 0, 0, 0, 0, 0x01};
    private static final byte[] MAC_B = {0x02, 0, 0, 0, 0, 0x02};

    /** TCP flags. */
    private static final int FIN = 0x01, SYN = 0x02, PSH = 0x08, ACK = 0x10;

    /** Largest payload put in one TCP segment; larger writes are split across segments. */
    private static final int MSS = 1460;

    private final OutputStream out;
    private final Map<String, Flow> flows = new HashMap<>();
    private boolean closed;

    /** Per-direction sequence state for one TCP connection. */
    private static final class Flow {
        /**
         * Whichever end sent the first byte is treated as the client. Guessing from the port
         * numbers does not work: an ephemeral source port is often lower than the service
         * port it connects to (54321 to 3868, but also 2796 to 8082), which got the direction
         * backwards and lost every inbound HTTP request. The first sender is unambiguous, and
         * true for both sides here: the REST client sends its request first and the gateway
         * sends the CER first.
         */
        final String clientEndpoint;
        long clientSeq = 1;
        long serverSeq = 1;

        Flow(String clientEndpoint) {
            this.clientEndpoint = clientEndpoint;
        }
    }

    public PcapWriter(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
        writeGlobalHeader();
    }

    private void writeGlobalHeader() throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);
        b.putInt(MAGIC);
        b.putShort((short) 2);           // version major
        b.putShort((short) 4);           // version minor
        b.putInt(0);                     // thiszone
        b.putInt(0);                     // sigfigs
        b.putInt(SNAPLEN);
        b.putInt(LINKTYPE_ETHERNET);
        out.write(b.array());
    }

    /**
     * Records one chunk of application data.
     *
     * @param from        the socket end the bytes came from
     * @param to          the socket end the bytes went to
     * @param payload     the application bytes exactly as they crossed the socket
     * @param epochMicros capture timestamp
     */
    public synchronized void record(InetSocketAddress from, InetSocketAddress to, byte[] payload, long epochMicros)
            throws IOException {
        if (closed || payload.length == 0) {
            return;
        }
        String key = flowKey(from, to);
        Flow flow = flows.get(key);
        if (flow == null) {
            flow = new Flow(endpoint(from));
            flows.put(key, flow);
            handshake(from, to, true, epochMicros);
        }
        boolean clientToServer = flow.clientEndpoint.equals(endpoint(from));
        // Split oversized writes the way a real stack would.
        for (int offset = 0; offset < payload.length; offset += MSS) {
            int len = Math.min(MSS, payload.length - offset);
            byte[] segment = new byte[len];
            System.arraycopy(payload, offset, segment, 0, len);
            long seq = clientToServer ? flow.clientSeq : flow.serverSeq;
            long ack = clientToServer ? flow.serverSeq : flow.clientSeq;
            writeFrame(from, to, seq, ack, PSH | ACK, segment, epochMicros);
            if (clientToServer) {
                flow.clientSeq += len;
            } else {
                flow.serverSeq += len;
            }
        }
        // Flush every frame. A capture is worthless if the process is killed and the last
        // buffer never reaches disk, and on Windows a console-less JVM can only be stopped
        // with taskkill /F, which runs no shutdown hook at all. The cost is irrelevant here:
        // captures are short, deliberate runs, not something left on under load.
        out.flush();
    }

    /** Emits SYN, SYN-ACK and ACK so the capture holds a complete stream. */
    private void handshake(InetSocketAddress from, InetSocketAddress to, boolean clientToServer, long micros)
            throws IOException {
        InetSocketAddress client = clientToServer ? from : to;
        InetSocketAddress server = clientToServer ? to : from;
        writeFrame(client, server, 0, 0, SYN, new byte[0], micros);
        writeFrame(server, client, 0, 1, SYN | ACK, new byte[0], micros);
        writeFrame(client, server, 1, 1, ACK, new byte[0], micros);
    }

    /** Emits a FIN/ACK pair to close a flow cleanly, if it was ever seen. */
    public synchronized void recordClose(InetSocketAddress from, InetSocketAddress to, long epochMicros)
            throws IOException {
        if (closed) {
            return;
        }
        Flow flow = flows.get(flowKey(from, to));
        if (flow == null) {
            return;
        }
        boolean clientToServer = flow.clientEndpoint.equals(endpoint(from));
        long seq = clientToServer ? flow.clientSeq : flow.serverSeq;
        long ack = clientToServer ? flow.serverSeq : flow.clientSeq;
        writeFrame(from, to, seq, ack, FIN | ACK, new byte[0], epochMicros);
        writeFrame(to, from, ack, seq + 1, ACK, new byte[0], epochMicros);
        out.flush();
    }

    private void writeFrame(InetSocketAddress src, InetSocketAddress dst, long seq, long ack, int flags,
                            byte[] payload, long epochMicros) throws IOException {
        byte[] srcIp = ipv4(src.getAddress());
        byte[] dstIp = ipv4(dst.getAddress());

        int tcpLen = 20 + payload.length;
        int ipLen = 20 + tcpLen;
        int ethLen = 14 + ipLen;

        ByteBuffer f = ByteBuffer.allocate(ethLen).order(ByteOrder.BIG_ENDIAN);

        // Ethernet II. Direction only decides which fake MAC leads; Wireshark keys on IP/port.
        boolean forward = compare(srcIp, dstIp) <= 0;
        f.put(forward ? MAC_B : MAC_A);
        f.put(forward ? MAC_A : MAC_B);
        f.putShort((short) 0x0800);

        // IPv4
        int ipStart = f.position();
        f.put((byte) 0x45);              // version 4, IHL 5
        f.put((byte) 0);                 // DSCP/ECN
        f.putShort((short) ipLen);
        f.putShort((short) 0);           // identification
        f.putShort((short) 0x4000);      // don't fragment
        f.put((byte) 64);                // TTL
        f.put((byte) 6);                 // TCP
        int ipChecksumAt = f.position();
        f.putShort((short) 0);           // checksum placeholder
        f.put(srcIp);
        f.put(dstIp);

        // TCP
        int tcpStart = f.position();
        f.putShort((short) src.getPort());
        f.putShort((short) dst.getPort());
        f.putInt((int) seq);
        f.putInt((int) ack);
        f.put((byte) 0x50);              // data offset 5 words, no options
        f.put((byte) flags);
        f.putShort((short) 65535);       // window
        int tcpChecksumAt = f.position();
        f.putShort((short) 0);           // checksum placeholder
        f.putShort((short) 0);           // urgent pointer
        f.put(payload);

        byte[] frame = f.array();
        putShort(frame, ipChecksumAt, checksum(frame, ipStart, 20));
        putShort(frame, tcpChecksumAt, tcpChecksum(frame, tcpStart, tcpLen, srcIp, dstIp));

        ByteBuffer rec = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        rec.putInt((int) (epochMicros / 1_000_000L));
        rec.putInt((int) (epochMicros % 1_000_000L));
        rec.putInt(frame.length);
        rec.putInt(frame.length);
        out.write(rec.array());
        out.write(frame);
    }

    // ------------------------------------------------------------------ helpers

    private static String endpoint(InetSocketAddress a) {
        return a.getAddress().getHostAddress() + ":" + a.getPort();
    }

    private static String flowKey(InetSocketAddress a, InetSocketAddress b) {
        String x = endpoint(a);
        String y = endpoint(b);
        return x.compareTo(y) <= 0 ? x + "|" + y : y + "|" + x;
    }

    private static byte[] ipv4(InetAddress address) {
        if (address instanceof Inet4Address) {
            return address.getAddress();
        }
        // IPv6 loopback shows up on Windows; map it onto 127.0.0.1 so the capture stays IPv4.
        return new byte[] {127, 0, 0, 1};
    }

    private static int compare(byte[] a, byte[] b) {
        for (int i = 0; i < a.length && i < b.length; i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        return 0;
    }

    private static void putShort(byte[] buf, int at, int value) {
        buf[at] = (byte) (value >>> 8);
        buf[at + 1] = (byte) value;
    }

    /** Standard one's-complement checksum over a byte range. */
    static int checksum(byte[] buf, int offset, int length) {
        int sum = 0;
        for (int i = 0; i < length - 1; i += 2) {
            sum += ((buf[offset + i] & 0xFF) << 8) | (buf[offset + i + 1] & 0xFF);
        }
        if ((length & 1) != 0) {
            sum += (buf[offset + length - 1] & 0xFF) << 8;
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }
        return (~sum) & 0xFFFF;
    }

    /** TCP checksum, which covers the pseudo-header as well as the segment. */
    private static int tcpChecksum(byte[] frame, int tcpStart, int tcpLen, byte[] srcIp, byte[] dstIp) {
        int sum = 0;
        for (int i = 0; i < 4; i += 2) {
            sum += ((srcIp[i] & 0xFF) << 8) | (srcIp[i + 1] & 0xFF);
            sum += ((dstIp[i] & 0xFF) << 8) | (dstIp[i + 1] & 0xFF);
        }
        sum += 6;            // protocol
        sum += tcpLen;
        for (int i = 0; i < tcpLen - 1; i += 2) {
            sum += ((frame[tcpStart + i] & 0xFF) << 8) | (frame[tcpStart + i + 1] & 0xFF);
        }
        if ((tcpLen & 1) != 0) {
            sum += (frame[tcpStart + tcpLen - 1] & 0xFF) << 8;
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }
        return (~sum) & 0xFFFF;
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            out.flush();
            out.close();
        }
    }
}
