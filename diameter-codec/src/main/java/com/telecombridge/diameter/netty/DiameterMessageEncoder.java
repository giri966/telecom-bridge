package com.telecombridge.diameter.netty;

import com.telecombridge.diameter.DiameterCodec;
import com.telecombridge.diameter.DiameterMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/** Serialises outbound {@link DiameterMessage}s. Stateless, so one instance can be shared. */
@ChannelHandler.Sharable
public class DiameterMessageEncoder extends MessageToByteEncoder<DiameterMessage> {

    public static final DiameterMessageEncoder INSTANCE = new DiameterMessageEncoder();

    @Override
    protected void encode(ChannelHandlerContext ctx, DiameterMessage msg, ByteBuf out) {
        DiameterCodec.encode(msg, out);
    }

    @Override
    protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, DiameterMessage msg, boolean preferDirect) {
        int size = msg.encodedLength();
        return preferDirect ? ctx.alloc().ioBuffer(size) : ctx.alloc().heapBuffer(size);
    }
}
