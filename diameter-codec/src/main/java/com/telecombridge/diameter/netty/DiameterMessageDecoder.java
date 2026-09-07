package com.telecombridge.diameter.netty;

import com.telecombridge.diameter.DiameterCodec;
import com.telecombridge.diameter.DiameterMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 * Turns one framed ByteBuf into a {@link DiameterMessage}. Must sit after
 * {@link DiameterFrameDecoder}. A malformed frame raises
 * {@link com.telecombridge.diameter.DiameterDecodeException}, which reaches
 * {@code exceptionCaught} of the application handler.
 */
public class DiameterMessageDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        out.add(DiameterCodec.decode(in));
    }
}
