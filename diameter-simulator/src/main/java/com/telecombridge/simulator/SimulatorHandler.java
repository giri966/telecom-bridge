package com.telecombridge.simulator;

import com.telecombridge.diameter.ApplicationId;
import com.telecombridge.diameter.Avp;
import com.telecombridge.diameter.AvpCode;
import com.telecombridge.diameter.CommandCode;
import com.telecombridge.diameter.DiameterDecodeException;
import com.telecombridge.diameter.DiameterMessage;
import com.telecombridge.diameter.ResultCode;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * One instance per accepted connection. Implements the server side of the peer
 * state machine in a reduced form:
 *
 * <pre>
 *   CLOSED --CER(ok)--> OPEN --DWR--> DWA, --CCR--> (delay) CCA, --DPR--> DPA + close
 *   CLOSED --anything else--> close transport (RFC 6733 section 5.6: unknown peer)
 * </pre>
 *
 * <p>Test hooks keyed on the MSISDN suffix, documented in the README:
 * <ul>
 *   <li>{@code ...0000} answers 5030 DIAMETER_USER_UNKNOWN</li>
 *   <li>{@code ...9999} answers 4012 DIAMETER_CREDIT_LIMIT_REACHED</li>
 *   <li>{@code ...5555} never answers, to exercise the gateway's timeout path</li>
 * </ul>
 */
public class SimulatorHandler extends SimpleChannelInboundHandler<DiameterMessage> {

    private static final Logger log = LoggerFactory.getLogger(SimulatorHandler.class);

    static final String SUFFIX_USER_UNKNOWN = "0000";
    static final String SUFFIX_CREDIT_LIMIT = "9999";
    static final String SUFFIX_BLACKHOLE = "5555";

    private final SimulatorConfig config;
    private final SimulatorStats stats;
    private final ScheduledExecutorService delayScheduler;

    private String peerHost;
    private boolean open;

    /**
     * @param delayScheduler times the CCA delay; the write itself is always hopped back to
     *                       the channel's event loop so the channel is only touched from
     *                       its own thread. Pass {@code null} to schedule on the event loop
     *                       instead, which tests do so that {@code EmbeddedChannel} can
     *                       drive the clock.
     */
    public SimulatorHandler(SimulatorConfig config, SimulatorStats stats, ScheduledExecutorService delayScheduler) {
        this.config = config;
        this.stats = stats;
        this.delayScheduler = delayScheduler;
    }

    public SimulatorHandler(SimulatorConfig config, SimulatorStats stats) {
        this(config, stats, null);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        stats.activeConnections.incrementAndGet();
        stats.connectionsAccepted.incrementAndGet();
        log.info("Transport connection from {} (active={})", ctx.channel().remoteAddress(),
                stats.activeConnections.get());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        stats.activeConnections.decrementAndGet();
        log.info("Connection closed peer={} remote={} (active={})", peerHost, ctx.channel().remoteAddress(),
                stats.activeConnections.get());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DiameterMessage msg) {
        if (log.isDebugEnabled()) {
            log.debug("<- {} from {}", msg, peerHost != null ? peerHost : ctx.channel().remoteAddress());
        }
        if (!msg.isRequest()) {
            // The simulator never sends requests, so an answer here is a peer bug. Ignore it.
            log.warn("Ignoring unexpected answer {} from {}", msg, peerHost);
            return;
        }
        if (msg.commandCode() == CommandCode.CAPABILITIES_EXCHANGE) {
            handleCer(ctx, msg);
            return;
        }
        if (!open) {
            stats.protocolErrors.incrementAndGet();
            log.warn("{} received before CER from {}; closing transport", msg.commandName(),
                    ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        switch (msg.commandCode()) {
            case CommandCode.DEVICE_WATCHDOG -> handleDwr(ctx, msg);
            case CommandCode.CREDIT_CONTROL -> handleCcr(ctx, msg);
            case CommandCode.DISCONNECT_PEER -> handleDpr(ctx, msg);
            default -> handleUnsupported(ctx, msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        stats.protocolErrors.incrementAndGet();
        if (cause instanceof DiameterDecodeException || cause.getCause() instanceof DiameterDecodeException) {
            log.warn("Malformed Diameter message from {}: {}; closing", ctx.channel().remoteAddress(),
                    cause.getMessage());
        } else {
            log.warn("I/O error on connection from {}: {}", ctx.channel().remoteAddress(), cause.toString());
        }
        ctx.close();
    }

    // ------------------------------------------------------------------ CER / CEA

    private void handleCer(ChannelHandlerContext ctx, DiameterMessage cer) {
        stats.cerReceived.incrementAndGet();
        peerHost = cer.originHost().orElse("<no Origin-Host>");
        boolean supportsCreditControl = cer.avps(AvpCode.AUTH_APPLICATION_ID).stream()
                .anyMatch(a -> a.asUnsigned32() == ApplicationId.CREDIT_CONTROL);

        DiameterMessage.Builder cea = cer.createAnswer();
        if (!supportsCreditControl) {
            log.warn("CER from {} does not advertise Auth-Application-Id 4; rejecting with 5010", peerHost);
            cea.avp(Avp.unsigned32(AvpCode.RESULT_CODE, ResultCode.DIAMETER_NO_COMMON_APPLICATION));
            addIdentity(cea);
            addCapabilities(cea);
            ctx.writeAndFlush(cea.build()).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        cea.avp(Avp.unsigned32(AvpCode.RESULT_CODE, ResultCode.DIAMETER_SUCCESS));
        addIdentity(cea);
        addCapabilities(cea);
        open = true;
        ctx.writeAndFlush(cea.build());
        log.info("Peer {} OPEN (CER/CEA complete, hbh=0x{})", peerHost, Integer.toHexString(cer.hopByHopId()));
    }

    private void addIdentity(DiameterMessage.Builder b) {
        b.avp(Avp.utf8(AvpCode.ORIGIN_HOST, config.originHost()))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, config.originRealm()));
    }

    private void addCapabilities(DiameterMessage.Builder b) {
        b.avp(Avp.address(AvpCode.HOST_IP_ADDRESS, InetAddress.getLoopbackAddress()))
                .avp(Avp.unsigned32(AvpCode.VENDOR_ID, 0))
                .avp(Avp.utf8(AvpCode.PRODUCT_NAME, 0, config.productName()))
                .avp(Avp.unsigned32(AvpCode.AUTH_APPLICATION_ID, ApplicationId.CREDIT_CONTROL));
    }

    // ------------------------------------------------------------------ DWR / DWA

    private void handleDwr(ChannelHandlerContext ctx, DiameterMessage dwr) {
        stats.dwrReceived.incrementAndGet();
        DiameterMessage.Builder dwa = dwr.createAnswer()
                .avp(Avp.unsigned32(AvpCode.RESULT_CODE, ResultCode.DIAMETER_SUCCESS));
        addIdentity(dwa);
        ctx.writeAndFlush(dwa.build());
        log.debug("-> DWA to {} hbh=0x{}", peerHost, Integer.toHexString(dwr.hopByHopId()));
    }

    // ------------------------------------------------------------------ DPR / DPA

    private void handleDpr(ChannelHandlerContext ctx, DiameterMessage dpr) {
        int cause = dpr.avp(AvpCode.DISCONNECT_CAUSE).map(Avp::asInteger32).orElse(-1);
        log.info("DPR from {} (Disconnect-Cause={}); answering DPA and closing", peerHost, cause);
        DiameterMessage.Builder dpa = dpr.createAnswer()
                .avp(Avp.unsigned32(AvpCode.RESULT_CODE, ResultCode.DIAMETER_SUCCESS));
        addIdentity(dpa);
        open = false;
        ctx.writeAndFlush(dpa.build()).addListener(ChannelFutureListener.CLOSE);
    }

    // ------------------------------------------------------------------ CCR / CCA

    private void handleCcr(ChannelHandlerContext ctx, DiameterMessage ccr) {
        stats.ccrReceived.incrementAndGet();

        Optional<Avp> missing = firstMissing(ccr, AvpCode.SESSION_ID, AvpCode.AUTH_APPLICATION_ID,
                AvpCode.CC_REQUEST_TYPE, AvpCode.CC_REQUEST_NUMBER);
        if (missing.isPresent()) {
            stats.protocolErrors.incrementAndGet();
            log.warn("CCR hbh=0x{} from {} missing mandatory AVP {}; answering 5005",
                    Integer.toHexString(ccr.hopByHopId()), peerHost, missing.get().code());
            DiameterMessage cca = baseCca(ccr, ResultCode.DIAMETER_MISSING_AVP)
                    .avp(Avp.grouped(AvpCode.FAILED_AVP, missing.get()))
                    .build();
            writeCca(ctx, cca);
            return;
        }

        String msisdn = ccr.avp(AvpCode.SUBSCRIPTION_ID)
                .flatMap(sid -> sid.child(AvpCode.SUBSCRIPTION_ID_DATA))
                .map(Avp::asUtf8)
                .orElse("");

        if (msisdn.endsWith(SUFFIX_BLACKHOLE)) {
            stats.ccrDropped.incrementAndGet();
            log.warn("CCR hbh=0x{} for {} deliberately dropped (blackhole test hook)",
                    Integer.toHexString(ccr.hopByHopId()), msisdn);
            return;
        }

        long resultCode = ResultCode.DIAMETER_SUCCESS;
        if (msisdn.endsWith(SUFFIX_USER_UNKNOWN)) {
            resultCode = ResultCode.DIAMETER_USER_UNKNOWN;
        } else if (msisdn.endsWith(SUFFIX_CREDIT_LIMIT)) {
            resultCode = ResultCode.DIAMETER_CREDIT_LIMIT_REACHED;
        }

        DiameterMessage.Builder cca = baseCca(ccr, resultCode);
        if (resultCode == ResultCode.DIAMETER_SUCCESS) {
            long requested = ccr.avp(AvpCode.REQUESTED_SERVICE_UNIT)
                    .flatMap(rsu -> rsu.child(AvpCode.CC_TOTAL_OCTETS))
                    .map(Avp::asUnsigned64)
                    .orElse(config.defaultGrantOctets());
            cca.avp(Avp.grouped(AvpCode.GRANTED_SERVICE_UNIT, Avp.unsigned64(AvpCode.CC_TOTAL_OCTETS, requested)))
                    .avp(Avp.unsigned32(AvpCode.VALIDITY_TIME, config.validityTimeSeconds()));
        }
        DiameterMessage answer = cca.build();

        int delay = config.maxDelayMs() == config.minDelayMs()
                ? config.minDelayMs()
                : ThreadLocalRandom.current().nextInt(config.minDelayMs(), config.maxDelayMs() + 1);
        if (log.isDebugEnabled()) {
            log.debug("CCR hbh=0x{} msisdn={} -> CCA {} after {} ms", Integer.toHexString(ccr.hopByHopId()),
                    msisdn, resultCode, delay);
        }
        long scheduledAt = System.nanoTime();
        // Runs on the event loop, so the channel is only ever touched from its own thread.
        Runnable emit = () -> {
            long overshootMicros = (System.nanoTime() - scheduledAt) / 1000 - delay * 1000L;
            stats.recordDelayOvershoot(Math.max(0, overshootMicros));
            if (ctx.channel().isActive()) {
                writeCca(ctx, answer);
            } else {
                stats.ccrDropped.incrementAndGet();
            }
        };
        if (delayScheduler == null) {
            ctx.executor().schedule(emit, delay, TimeUnit.MILLISECONDS);
        } else {
            delayScheduler.schedule(() -> ctx.executor().execute(emit), delay, TimeUnit.MILLISECONDS);
        }
    }

    private DiameterMessage.Builder baseCca(DiameterMessage ccr, long resultCode) {
        DiameterMessage.Builder cca = ccr.createAnswer();
        // Session-Id must be the first AVP (RFC 6733 section 8.8)
        ccr.avp(AvpCode.SESSION_ID).ifPresent(cca::avp);
        cca.avp(Avp.unsigned32(AvpCode.RESULT_CODE, resultCode));
        addIdentity(cca);
        cca.avp(Avp.unsigned32(AvpCode.AUTH_APPLICATION_ID, ApplicationId.CREDIT_CONTROL));
        ccr.avp(AvpCode.CC_REQUEST_TYPE).ifPresent(cca::avp);
        ccr.avp(AvpCode.CC_REQUEST_NUMBER).ifPresent(cca::avp);
        return cca;
    }

    private void writeCca(ChannelHandlerContext ctx, DiameterMessage cca) {
        ctx.writeAndFlush(cca);
        stats.ccaSent.incrementAndGet();
        long rc = cca.resultCode().orElse(0L);
        if (ResultCode.isSuccess(rc)) {
            stats.ccaSuccess.incrementAndGet();
        } else {
            stats.ccaFailure.incrementAndGet();
        }
        if (log.isDebugEnabled()) {
            log.debug("-> {} result={}", cca, rc);
        }
    }

    private static Optional<Avp> firstMissing(DiameterMessage msg, int... codes) {
        for (int code : codes) {
            if (msg.avp(code).isEmpty()) {
                return Optional.of(Avp.of(code, Avp.FLAG_MANDATORY, new byte[0]));
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ anything else

    private void handleUnsupported(ChannelHandlerContext ctx, DiameterMessage request) {
        stats.protocolErrors.incrementAndGet();
        log.warn("Unsupported command {} from {}; answering 3001 with E bit", request.commandCode(), peerHost);
        DiameterMessage.Builder answer = request.createAnswer()
                .error()
                .avp(Avp.unsigned32(AvpCode.RESULT_CODE, ResultCode.DIAMETER_COMMAND_UNSUPPORTED));
        addIdentity(answer);
        ctx.writeAndFlush(answer.build());
    }

    static String describe(InetSocketAddress address) {
        return address == null ? "?" : address.getHostString() + ":" + address.getPort();
    }

    static List<Integer> mandatoryCcrAvps() {
        return List.of(AvpCode.SESSION_ID, AvpCode.AUTH_APPLICATION_ID, AvpCode.CC_REQUEST_TYPE,
                AvpCode.CC_REQUEST_NUMBER);
    }
}
