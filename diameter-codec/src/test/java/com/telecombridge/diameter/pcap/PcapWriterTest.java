package com.telecombridge.diameter.pcap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parses the written file back and checks it is a structurally valid libpcap capture.
 *
 * <p>This stands in for opening it in Wireshark, which was not available on the machine that
 * produced the capture. If the magic number, frame lengths, IP and TCP checksums and stream
 * sequencing are all right, Wireshark's dissectors have everything they need.
 */
class PcapWriterTest {

    private static final InetSocketAddress CLIENT = new InetSocketAddress("127.0.0.1", 54321);
    private static final InetSocketAddress SERVER = new InetSocketAddress("127.0.0.1", 3868);

    /** One parsed frame. */
    private record Frame(int srcPort, int dstPort, long seq, long ack, int flags, byte[] payload,
                         boolean ipChecksumOk, boolean tcpChecksumOk, long micros) {
    }

    private static List<Frame> parse(Path file) throws IOException {
        byte[] all = Files.readAllBytes(file);
        ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN);

        assertThat(b.getInt()).as("libpcap magic").isEqualTo(0xA1B2C3D4);
        assertThat(b.getShort()).isEqualTo((short) 2);
        assertThat(b.getShort()).isEqualTo((short) 4);
        b.getInt();
        b.getInt();
        assertThat(b.getInt()).as("snaplen").isEqualTo(262144);
        assertThat(b.getInt()).as("LINKTYPE_ETHERNET").isEqualTo(1);

        List<Frame> frames = new ArrayList<>();
        while (b.remaining() > 0) {
            long sec = Integer.toUnsignedLong(b.getInt());
            long usec = Integer.toUnsignedLong(b.getInt());
            int captured = b.getInt();
            int original = b.getInt();
            assertThat(captured).isEqualTo(original);

            int frameStart = b.position();
            byte[] frame = new byte[captured];
            b.get(frame);
            assertThat(frameStart).isPositive();

            assertThat(((frame[12] & 0xFF) << 8) | (frame[13] & 0xFF)).as("ethertype IPv4").isEqualTo(0x0800);
            int ipStart = 14;
            int ihl = (frame[ipStart] & 0x0F) * 4;
            assertThat(ihl).isEqualTo(20);
            int totalLength = ((frame[ipStart + 2] & 0xFF) << 8) | (frame[ipStart + 3] & 0xFF);
            assertThat(14 + totalLength).as("frame length matches IP total length").isEqualTo(captured);
            assertThat(frame[ipStart + 9]).as("protocol TCP").isEqualTo((byte) 6);
            // A correct checksum re-checksums to zero over the same range.
            boolean ipOk = PcapWriter.checksum(frame, ipStart, 20) == 0;

            int tcpStart = ipStart + ihl;
            int srcPort = ((frame[tcpStart] & 0xFF) << 8) | (frame[tcpStart + 1] & 0xFF);
            int dstPort = ((frame[tcpStart + 2] & 0xFF) << 8) | (frame[tcpStart + 3] & 0xFF);
            long seq = Integer.toUnsignedLong(ByteBuffer.wrap(frame, tcpStart + 4, 4).getInt());
            long ack = Integer.toUnsignedLong(ByteBuffer.wrap(frame, tcpStart + 8, 4).getInt());
            int dataOffset = ((frame[tcpStart + 12] & 0xF0) >> 4) * 4;
            int flags = frame[tcpStart + 13] & 0xFF;
            int tcpLen = totalLength - ihl;
            byte[] payload = new byte[tcpLen - dataOffset];
            System.arraycopy(frame, tcpStart + dataOffset, payload, 0, payload.length);

            boolean tcpOk = tcpChecksumOf(frame, ipStart, tcpStart, tcpLen) == 0;
            frames.add(new Frame(srcPort, dstPort, seq, ack, flags, payload, ipOk, tcpOk, sec * 1_000_000 + usec));
        }
        return frames;
    }

    /** Recomputes the TCP checksum including the pseudo-header; zero means the stored one was right. */
    private static int tcpChecksumOf(byte[] frame, int ipStart, int tcpStart, int tcpLen) {
        int sum = 0;
        for (int i = 0; i < 4; i += 2) {
            sum += ((frame[ipStart + 12 + i] & 0xFF) << 8) | (frame[ipStart + 13 + i] & 0xFF);
            sum += ((frame[ipStart + 16 + i] & 0xFF) << 8) | (frame[ipStart + 17 + i] & 0xFF);
        }
        sum += 6;
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

    @Test
    void writesAValidCaptureWithAHandshakeAndTheRealPayload(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("capture.pcap");
        byte[] request = "CCR-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] answer = "CCA-bytes".getBytes(StandardCharsets.UTF_8);

        try (PcapWriter writer = new PcapWriter(file)) {
            writer.record(CLIENT, SERVER, request, 1_700_000_000_000_000L);
            writer.record(SERVER, CLIENT, answer, 1_700_000_000_050_000L);
        }

        List<Frame> frames = parse(file);

        // three-way handshake, then one segment each way
        assertThat(frames).hasSize(5);
        assertThat(frames.get(0).flags()).isEqualTo(0x02);            // SYN
        assertThat(frames.get(1).flags()).isEqualTo(0x12);            // SYN|ACK
        assertThat(frames.get(2).flags()).isEqualTo(0x10);            // ACK
        assertThat(frames.get(3).flags()).isEqualTo(0x18);            // PSH|ACK
        assertThat(frames.get(3).payload()).isEqualTo(request);
        assertThat(frames.get(3).srcPort()).isEqualTo(54321);
        assertThat(frames.get(3).dstPort()).isEqualTo(3868);
        assertThat(frames.get(4).payload()).isEqualTo(answer);
        assertThat(frames.get(4).srcPort()).isEqualTo(3868);
        assertThat(frames).allMatch(Frame::ipChecksumOk, "IP checksum valid");
        assertThat(frames).allMatch(Frame::tcpChecksumOk, "TCP checksum valid");
    }

    @Test
    void sequenceNumbersAdvanceByPayloadLengthInEachDirection(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("seq.pcap");
        try (PcapWriter writer = new PcapWriter(file)) {
            writer.record(CLIENT, SERVER, new byte[] {1, 2, 3}, 1_000_000L);
            writer.record(CLIENT, SERVER, new byte[] {4, 5}, 1_000_100L);
            writer.record(SERVER, CLIENT, new byte[] {9}, 1_000_200L);
            writer.record(CLIENT, SERVER, new byte[] {6}, 1_000_300L);
        }

        List<Frame> data = parse(file).stream().filter(f -> f.payload().length > 0).toList();

        assertThat(data).hasSize(4);
        assertThat(data.get(0).seq()).isEqualTo(1);
        assertThat(data.get(1).seq()).as("advanced by the 3 bytes already sent").isEqualTo(4);
        assertThat(data.get(2).seq()).as("server direction has its own sequence").isEqualTo(1);
        assertThat(data.get(2).ack()).as("server acknowledges 5 client bytes").isEqualTo(6);
        assertThat(data.get(3).seq()).isEqualTo(6);
        assertThat(data.get(3).ack()).as("client acknowledges the 1 server byte").isEqualTo(2);
    }

    @Test
    void largePayloadIsSplitIntoSegmentsThatReassemble(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("big.pcap");
        byte[] big = new byte[3500];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) i;
        }

        try (PcapWriter writer = new PcapWriter(file)) {
            writer.record(CLIENT, SERVER, big, 2_000_000L);
        }

        List<Frame> data = parse(file).stream().filter(f -> f.payload().length > 0).toList();

        assertThat(data).hasSize(3);
        assertThat(data.get(0).payload()).hasSize(1460);
        assertThat(data.get(2).payload()).hasSize(3500 - 2920);
        ByteBuffer rejoined = ByteBuffer.allocate(3500);
        data.forEach(f -> rejoined.put(f.payload()));
        assertThat(rejoined.array()).isEqualTo(big);
    }

    @Test
    void separateConnectionsKeepIndependentStreams(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("two.pcap");
        InetSocketAddress otherClient = new InetSocketAddress("127.0.0.1", 54322);

        try (PcapWriter writer = new PcapWriter(file)) {
            writer.record(CLIENT, SERVER, new byte[] {1, 2, 3, 4}, 3_000_000L);
            writer.record(otherClient, SERVER, new byte[] {7}, 3_000_100L);
        }

        List<Frame> frames = parse(file);
        assertThat(frames.stream().filter(f -> f.flags() == 0x02)).as("one SYN per connection").hasSize(2);
        assertThat(frames.stream().filter(f -> f.srcPort() == 54322 && f.payload().length > 0))
                .allMatch(f -> f.seq() == 1);
    }

    @Test
    void closingTheFlowEmitsFinAndAck(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("close.pcap");
        try (PcapWriter writer = new PcapWriter(file)) {
            writer.record(CLIENT, SERVER, new byte[] {1}, 4_000_000L);
            writer.recordClose(CLIENT, SERVER, 4_000_500L);
        }

        List<Frame> frames = parse(file);
        assertThat(frames.stream().filter(f -> (f.flags() & 0x01) != 0)).as("a FIN was written").hasSize(1);
        assertThat(frames).allMatch(Frame::tcpChecksumOk);
    }

    @Test
    void timestampsArePreservedToTheMicrosecond(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("time.pcap");
        long micros = 1_725_523_200_123_456L;
        try (PcapWriter writer = new PcapWriter(file)) {
            writer.record(CLIENT, SERVER, new byte[] {1}, micros);
        }

        Frame data = parse(file).stream().filter(f -> f.payload().length > 0).findFirst().orElseThrow();
        assertThat(data.micros()).isEqualTo(micros);
    }

    @Test
    void directionIsTakenFromWhoSpokeFirstNotFromPortNumbers() throws Exception {
        // A Windows ephemeral port is often lower than the service port it dials, so any
        // "lower port is the server" rule gets the direction backwards. Here the client's
        // 2796 is below the server's 8082, exactly the case that lost inbound HTTP requests.
        Path file = Files.createTempFile("direction", ".pcap");
        InetSocketAddress lowPortClient = new InetSocketAddress("127.0.0.1", 2796);
        InetSocketAddress highPortServer = new InetSocketAddress("127.0.0.1", 8082);

        try (PcapWriter writer = new PcapWriter(file)) {
            writer.record(lowPortClient, highPortServer, "POST /api/v1/charge".getBytes(StandardCharsets.UTF_8), 1L);
            writer.record(highPortServer, lowPortClient, "HTTP/1.1 200 OK".getBytes(StandardCharsets.UTF_8), 2L);
            writer.record(lowPortClient, highPortServer, "POST again".getBytes(StandardCharsets.UTF_8), 3L);
        }

        List<Frame> data = parse(file).stream().filter(f -> f.payload().length > 0).toList();
        assertThat(data).hasSize(3);
        assertThat(data.get(0).seq()).as("first talker starts the client stream at 1").isEqualTo(1);
        assertThat(data.get(1).seq()).as("the responder has its own stream").isEqualTo(1);
        assertThat(data.get(2).seq()).as("client stream advanced past its 19 bytes").isEqualTo(20);
        assertThat(data.get(2).ack()).as("client acknowledges the 15-byte response").isEqualTo(16);

        // The SYN must come from the first talker, not from whichever port is numerically lower.
        Frame syn = parse(file).stream().filter(f -> f.flags() == 0x02).findFirst().orElseThrow();
        assertThat(syn.srcPort()).isEqualTo(2796);
        Files.deleteIfExists(file);
    }

    @Test
    void emptyPayloadsAreIgnored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("empty.pcap");
        try (PcapWriter writer = new PcapWriter(file)) {
            writer.record(CLIENT, SERVER, new byte[0], 5_000_000L);
        }
        assertThat(parse(file)).isEmpty();
    }
}
