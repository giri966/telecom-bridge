package com.telecombridge.diameter.netty;

import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * Splits the TCP byte stream into one ByteBuf per Diameter message.
 *
 * <p>The Message Length field sits at byte offset 1, is 3 bytes wide and counts
 * the whole message including the 4 bytes that precede the end of the length
 * field. {@code lengthAdjustment = -4} tells Netty that, so the frame is exactly
 * {@code Message Length} bytes. Nothing is stripped: the decoder downstream
 * re-reads the full header.
 */
public class DiameterFrameDecoder extends LengthFieldBasedFrameDecoder {

    /** Diameter messages on Ro/Gy are a few hundred bytes; 64 KiB is a generous safety cap. */
    public static final int DEFAULT_MAX_FRAME_LENGTH = 64 * 1024;

    public DiameterFrameDecoder() {
        this(DEFAULT_MAX_FRAME_LENGTH);
    }

    public DiameterFrameDecoder(int maxFrameLength) {
        super(maxFrameLength, 1, 3, -4, 0);
    }
}
