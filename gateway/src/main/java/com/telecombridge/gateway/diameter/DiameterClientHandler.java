package com.telecombridge.gateway.diameter;

import com.telecombridge.diameter.DiameterDecodeException;
import com.telecombridge.diameter.DiameterMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin Netty adapter: forwards channel events to {@link DiameterClient}, which owns
 * the peer state. Everything here runs on the channel's event-loop thread.
 */
final class DiameterClientHandler extends SimpleChannelInboundHandler<DiameterMessage> {

    private static final Logger log = LoggerFactory.getLogger(DiameterClientHandler.class);

    private final DiameterClient client;

    DiameterClientHandler(DiameterClient client) {
        this.client = client;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        client.onConnected(ctx.channel());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DiameterMessage msg) {
        client.onMessage(ctx.channel(), msg);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            client.onIdle(ctx.channel());
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        client.onDisconnected(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof DiameterDecodeException || cause.getCause() instanceof DiameterDecodeException) {
            log.error("Malformed Diameter message from peer, closing connection: {}", cause.getMessage());
        } else {
            log.error("Diameter connection error, closing: {}", cause.toString());
        }
        ctx.close();
    }
}
