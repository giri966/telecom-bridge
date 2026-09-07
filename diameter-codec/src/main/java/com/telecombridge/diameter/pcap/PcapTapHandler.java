package com.telecombridge.diameter.pcap;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Copies every byte crossing a channel into a {@link PcapWriter}.
 *
 * <p>Install it as the <b>first</b> handler in the pipeline, so it sees raw bytes before any
 * codec has touched them and after any codec has produced them. It never modifies or
 * consumes anything: buffers are read from a duplicate index and passed straight on.
 *
 * <p>Intended for producing a capture on demand, not for running in production. It copies
 * every payload onto the heap and serialises on the writer, so it is off unless a capture
 * file is configured.
 */
public class PcapTapHandler extends ChannelDuplexHandler {

    private static final Logger log = LoggerFactory.getLogger(PcapTapHandler.class);

    private final PcapWriter writer;

    public PcapTapHandler(PcapWriter writer) {
        this.writer = writer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf buf) {
            capture(remote(ctx), local(ctx), buf);
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf buf) {
            capture(local(ctx), remote(ctx), buf);
        }
        super.write(ctx, msg, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            writer.recordClose(local(ctx), remote(ctx), System.currentTimeMillis() * 1000L);
        } catch (Exception e) {
            log.warn("Could not record connection close in the capture: {}", e.toString());
        }
        super.channelInactive(ctx);
    }

    private void capture(InetSocketAddress from, InetSocketAddress to, ByteBuf buf) {
        if (from == null || to == null || !buf.isReadable()) {
            return;
        }
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        try {
            writer.record(from, to, bytes, System.currentTimeMillis() * 1000L);
        } catch (Exception e) {
            log.warn("Could not write {} captured bytes: {}", bytes.length, e.toString());
        }
    }

    private static InetSocketAddress local(ChannelHandlerContext ctx) {
        return ctx.channel().localAddress() instanceof InetSocketAddress a ? a : null;
    }

    private static InetSocketAddress remote(ChannelHandlerContext ctx) {
        return ctx.channel().remoteAddress() instanceof InetSocketAddress a ? a : null;
    }
}
