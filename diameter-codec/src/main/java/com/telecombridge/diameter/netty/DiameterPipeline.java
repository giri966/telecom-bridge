package com.telecombridge.diameter.netty;

import io.netty.channel.ChannelPipeline;

/** Installs the three codec handlers in the right order on a client or server channel. */
public final class DiameterPipeline {

    public static final String FRAME_DECODER = "diameterFrameDecoder";
    public static final String MESSAGE_DECODER = "diameterMessageDecoder";
    public static final String MESSAGE_ENCODER = "diameterMessageEncoder";

    private DiameterPipeline() {
    }

    public static void installCodec(ChannelPipeline pipeline) {
        pipeline.addLast(FRAME_DECODER, new DiameterFrameDecoder());
        pipeline.addLast(MESSAGE_DECODER, new DiameterMessageDecoder());
        pipeline.addLast(MESSAGE_ENCODER, DiameterMessageEncoder.INSTANCE);
    }
}
