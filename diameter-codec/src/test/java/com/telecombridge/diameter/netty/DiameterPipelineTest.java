package com.telecombridge.diameter.netty;

import com.telecombridge.diameter.ApplicationId;
import com.telecombridge.diameter.Avp;
import com.telecombridge.diameter.AvpCode;
import com.telecombridge.diameter.CommandCode;
import com.telecombridge.diameter.DiameterCodec;
import com.telecombridge.diameter.DiameterDecodeException;
import com.telecombridge.diameter.DiameterMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Framing over a TCP byte stream: fragmented and coalesced reads must still yield whole messages. */
class DiameterPipelineTest {

    private static DiameterMessage dwr(int hop) {
        return DiameterMessage.request(CommandCode.DEVICE_WATCHDOG, ApplicationId.BASE)
                .hopByHopId(hop).endToEndId(hop)
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, "gateway.telecom-bridge.local"))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, "telecom-bridge.local"))
                .build();
    }

    private static EmbeddedChannel channel() {
        EmbeddedChannel ch = new EmbeddedChannel();
        DiameterPipeline.installCodec(ch.pipeline());
        return ch;
    }

    @Test
    void twoMessagesArrivingInOneReadAreSplit() {
        EmbeddedChannel ch = channel();
        ByteBuf both = Unpooled.wrappedBuffer(DiameterCodec.encode(dwr(1)), DiameterCodec.encode(dwr(2)));

        ch.writeInbound(both);

        DiameterMessage first = ch.readInbound();
        DiameterMessage second = ch.readInbound();
        assertThat(first.hopByHopId()).isEqualTo(1);
        assertThat(second.hopByHopId()).isEqualTo(2);
        assertThat((Object) ch.readInbound()).isNull();
    }

    @Test
    void messageArrivingInThreeFragmentsIsReassembled() {
        EmbeddedChannel ch = channel();
        byte[] bytes = DiameterCodec.encode(dwr(7));

        ch.writeInbound(Unpooled.wrappedBuffer(bytes, 0, 3));          // inside the length field
        assertThat((Object) ch.readInbound()).isNull();
        ch.writeInbound(Unpooled.wrappedBuffer(bytes, 3, 25));         // header done, AVPs partial
        assertThat((Object) ch.readInbound()).isNull();
        ch.writeInbound(Unpooled.wrappedBuffer(bytes, 28, bytes.length - 28));

        DiameterMessage msg = ch.readInbound();
        assertThat(msg.hopByHopId()).isEqualTo(7);
        assertThat(msg.originHost()).contains("gateway.telecom-bridge.local");
    }

    @Test
    void encoderProducesSameBytesAsCodec() {
        EmbeddedChannel ch = channel();
        DiameterMessage msg = dwr(9);

        ch.writeOutbound(msg);
        ByteBuf out = ch.readOutbound();
        byte[] wire = new byte[out.readableBytes()];
        out.readBytes(wire);
        out.release();

        assertThat(wire).isEqualTo(DiameterCodec.encode(msg));
    }

    @Test
    void malformedFrameRaisesDecodeException() {
        EmbeddedChannel ch = channel();
        byte[] bytes = DiameterCodec.encode(dwr(3));
        bytes[0] = 9; // bad version

        assertThatThrownBy(() -> ch.writeInbound(Unpooled.wrappedBuffer(bytes)))
                .hasCauseInstanceOf(DiameterDecodeException.class);
    }
}
