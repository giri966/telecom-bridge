package com.telecombridge.gateway.diameter;

import com.telecombridge.diameter.ApplicationId;
import com.telecombridge.diameter.Avp;
import com.telecombridge.diameter.AvpCode;
import com.telecombridge.diameter.CommandCode;
import com.telecombridge.diameter.DiameterMessage;
import com.telecombridge.diameter.ResultCode;
import com.telecombridge.diameter.netty.DiameterPipeline;
import com.telecombridge.diameter.pcap.PcapTapHandler;
import com.telecombridge.diameter.pcap.PcapWriter;
import com.telecombridge.gateway.config.DiameterProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous Diameter client that keeps one persistent TCP connection to the peer.
 *
 * <p><b>Connection management.</b> On {@link #start()} the client connects, sends a CER
 * and waits for a CEA with Result-Code 2001 before it accepts traffic. While idle it
 * exchanges DWR/DWA at {@code watchdogInterval}; a missed DWA closes the socket.
 * Any close triggers a reconnect after {@code reconnectDelay} for as long as the
 * client is running. On {@link #stop()} it sends a DPR, waits briefly for the DPA and
 * shuts the event loop down.
 *
 * <p><b>Request correlation.</b> {@link #send(DiameterMessage.Builder)} assigns a fresh
 * Hop-by-Hop and End-to-End identifier, stores a {@link CompletableFuture} in the
 * {@link PendingRequests} table keyed by the Hop-by-Hop id, arms a timeout on the event
 * loop and writes the message. When an answer arrives, its Hop-by-Hop id looks the
 * future up and completes it. No thread ever blocks waiting for the peer.
 *
 * <p><b>Threading.</b> All channel callbacks run on the channel's event loop. {@code send}
 * is called from WebFlux threads and only touches volatile fields, the concurrent map
 * and Netty's thread-safe write path.
 */
public class DiameterClient implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DiameterClient.class);

    private static final int DISCONNECT_CAUSE_REBOOTING = 0;

    private final DiameterProperties props;
    private final IdentifierGenerator ids;
    private final PendingRequests pending;
    private final EventLoopGroup group;
    private final Bootstrap bootstrap;

    private final Timer requestTimer;
    private final Counter timeouts;
    private final Counter unmatchedAnswers;
    private final Counter reconnects;
    private final Counter rejectedBusy;
    private final AtomicLong stateGauge = new AtomicLong();

    private volatile PeerState state = PeerState.CLOSED;
    private volatile Channel channel;
    private volatile boolean running;
    private volatile String peerHost = "?";

    // Only touched on the event loop
    private boolean watchdogOutstanding;
    private ScheduledFuture<?> ceaTimeoutTask;
    private ScheduledFuture<?> reconnectTask;
    private CompletableFuture<Void> disconnectAck;

    public DiameterClient(DiameterProperties props, IdentifierGenerator ids, MeterRegistry registry) {
        this(props, ids, registry, null);
    }

    /**
     * @param pcapWriter when non-null, every byte on the Diameter connection is copied into
     *                   this capture. Supplied only when {@code capture.file} is configured.
     */
    public DiameterClient(DiameterProperties props, IdentifierGenerator ids, MeterRegistry registry,
                          PcapWriter pcapWriter) {
        this.props = props;
        this.ids = ids;
        this.pending = new PendingRequests(props.maxPendingRequests());
        this.group = new NioEventLoopGroup(props.ioThreads(), new DefaultThreadFactory("diameter-io"));
        this.bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (pcapWriter != null) {
                            ch.pipeline().addLast("pcapTap", new PcapTapHandler(pcapWriter));
                        }
                        long idleMillis = props.watchdogInterval().toMillis();
                        ch.pipeline().addLast("idle", new IdleStateHandler(0, 0, idleMillis, TimeUnit.MILLISECONDS));
                        DiameterPipeline.installCodec(ch.pipeline());
                        ch.pipeline().addLast("client", new DiameterClientHandler(DiameterClient.this));
                    }
                });

        this.requestTimer = Timer.builder("diameter.request")
                .description("CCR to CCA round trip as seen by the gateway")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.timeouts = registry.counter("diameter.request.timeouts");
        this.unmatchedAnswers = registry.counter("diameter.answers.unmatched");
        this.reconnects = registry.counter("diameter.reconnects");
        this.rejectedBusy = registry.counter("diameter.request.rejected.busy");
        Gauge.builder("diameter.pending.requests", pending, PendingRequests::size)
                .description("CCRs waiting for a CCA").register(registry);
        Gauge.builder("diameter.peer.open", stateGauge, AtomicLong::get)
                .description("1 when the peer connection is OPEN").register(registry);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        log.info("Starting Diameter client {} -> {}:{} (timeout {} ms, watchdog {} s, maxPending {})",
                props.originHost(), props.host(), props.port(), props.requestTimeout().toMillis(),
                props.watchdogInterval().toSeconds(), props.maxPendingRequests());
        connect();
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        Channel ch = channel;
        if (ch != null && ch.isActive() && state == PeerState.OPEN) {
            CompletableFuture<Void> ack = new CompletableFuture<>();
            ch.eventLoop().execute(() -> {
                disconnectAck = ack;
                setState(PeerState.CLOSING);
                DiameterMessage dpr = DiameterMessage.request(CommandCode.DISCONNECT_PEER, ApplicationId.BASE)
                        .hopByHopId(ids.nextHopByHop()).endToEndId(ids.nextEndToEnd())
                        .avp(Avp.utf8(AvpCode.ORIGIN_HOST, props.originHost()))
                        .avp(Avp.utf8(AvpCode.ORIGIN_REALM, props.originRealm()))
                        .avp(Avp.enumerated(AvpCode.DISCONNECT_CAUSE, DISCONNECT_CAUSE_REBOOTING))
                        .build();
                log.info("Sending DPR (REBOOTING) to {}", peerHost);
                ch.writeAndFlush(dpr);
            });
            try {
                ack.get(1, TimeUnit.SECONDS);
                log.info("DPA received from {}", peerHost);
            } catch (Exception e) {
                log.warn("No DPA within 1 s; closing anyway");
            }
        }
        if (ch != null) {
            ch.close().syncUninterruptibly();
        }
        pending.failAll(new DiameterUnavailableException("Gateway shutting down"));
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        log.info("Diameter client stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Start after the web server so health checks see a consistent picture; stop before it. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }

    // ------------------------------------------------------------------ public API

    public PeerState state() {
        return state;
    }

    public String peerHost() {
        return peerHost;
    }

    public int pendingCount() {
        return pending.size();
    }

    /**
     * Sends a request and returns a future completed with the matching answer.
     *
     * <p>The builder must carry every AVP except the identifiers; this method sets the
     * Hop-by-Hop and End-to-End ids so the caller cannot accidentally reuse one.
     * The future fails with {@link DiameterUnavailableException} when the peer is not
     * OPEN or the pending table is full, and with {@link DiameterTimeoutException} when
     * no answer arrives in time.
     */
    public CompletableFuture<DiameterMessage> send(DiameterMessage.Builder request) {
        Channel ch = channel;
        if (state != PeerState.OPEN || ch == null || !ch.isActive()) {
            return CompletableFuture.failedFuture(new DiameterUnavailableException(
                    "Diameter peer " + props.host() + ":" + props.port() + " not connected (state " + state + ")"));
        }
        if (pending.isFull()) {
            rejectedBusy.increment();
            return CompletableFuture.failedFuture(new DiameterUnavailableException(
                    "Too many in-flight Diameter requests (" + props.maxPendingRequests() + ")"));
        }

        int hop = ids.nextHopByHop();
        DiameterMessage message = request.hopByHopId(hop).endToEndId(ids.nextEndToEnd()).build();
        CompletableFuture<DiameterMessage> future = new CompletableFuture<>();
        long start = System.nanoTime();

        ScheduledFuture<?> timeout = ch.eventLoop().schedule(() -> onTimeout(hop),
                props.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!pending.register(hop, new PendingRequests.Pending(future, timeout, start))) {
            timeout.cancel(false);
            return CompletableFuture.failedFuture(new DiameterException("Hop-by-Hop id collision " + hop));
        }

        if (log.isDebugEnabled()) {
            log.debug("-> {} session={}", message, message.sessionId().orElse("-"));
        }
        ch.writeAndFlush(message).addListener(f -> {
            if (!f.isSuccess()) {
                PendingRequests.Pending p = pending.remove(hop);
                if (p != null) {
                    p.future().completeExceptionally(new DiameterUnavailableException(
                            "Failed to write request to peer", f.cause()));
                }
            }
        });
        return future;
    }

    // ------------------------------------------------------------------ event-loop callbacks

    void onConnected(Channel ch) {
        channel = ch;
        watchdogOutstanding = false;
        setState(PeerState.WAIT_CEA);
        DiameterMessage cer = DiameterMessage.request(CommandCode.CAPABILITIES_EXCHANGE, ApplicationId.BASE)
                .hopByHopId(ids.nextHopByHop()).endToEndId(ids.nextEndToEnd())
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, props.originHost()))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, props.originRealm()))
                .avp(Avp.address(AvpCode.HOST_IP_ADDRESS, localAddress(ch)))
                .avp(Avp.unsigned32(AvpCode.VENDOR_ID, props.vendorId()))
                .avp(Avp.utf8(AvpCode.PRODUCT_NAME, 0, props.productName()))
                .avp(Avp.unsigned32(AvpCode.AUTH_APPLICATION_ID, ApplicationId.CREDIT_CONTROL))
                .build();
        log.info("TCP connected to {}; sending CER hbh=0x{}", ch.remoteAddress(),
                Integer.toHexString(cer.hopByHopId()));
        ch.writeAndFlush(cer);
        ceaTimeoutTask = ch.eventLoop().schedule(() -> {
            if (state == PeerState.WAIT_CEA) {
                log.error("No CEA within {} ms; closing connection", props.ceaTimeout().toMillis());
                ch.close();
            }
        }, props.ceaTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    void onMessage(Channel ch, DiameterMessage msg) {
        if (log.isDebugEnabled()) {
            log.debug("<- {}", msg);
        }
        if (msg.isRequest()) {
            onPeerRequest(ch, msg);
            return;
        }
        switch (msg.commandCode()) {
            case CommandCode.CAPABILITIES_EXCHANGE -> onCea(ch, msg);
            case CommandCode.DEVICE_WATCHDOG -> onDwa(msg);
            case CommandCode.DISCONNECT_PEER -> onDpa(ch);
            default -> onAnswer(msg);
        }
    }

    private void onCea(Channel ch, DiameterMessage cea) {
        if (ceaTimeoutTask != null) {
            ceaTimeoutTask.cancel(false);
        }
        long rc = cea.resultCode().orElse(-1L);
        peerHost = cea.originHost().orElse("<unknown>");
        boolean creditControl = cea.avps(AvpCode.AUTH_APPLICATION_ID).stream()
                .anyMatch(a -> a.asUnsigned32() == ApplicationId.CREDIT_CONTROL);
        if (rc != ResultCode.DIAMETER_SUCCESS || !creditControl) {
            log.error("CEA from {} rejected: Result-Code={} creditControlAdvertised={}; closing", peerHost, rc,
                    creditControl);
            ch.close();
            return;
        }
        setState(PeerState.OPEN);
        log.info("Peer {} OPEN (CEA Result-Code {}, product {})", peerHost, rc,
                cea.avp(AvpCode.PRODUCT_NAME).map(Avp::asUtf8).orElse("-"));
    }

    private void onDwa(DiameterMessage dwa) {
        watchdogOutstanding = false;
        log.debug("DWA from {} hbh=0x{} rc={}", peerHost, Integer.toHexString(dwa.hopByHopId()),
                dwa.resultCode().orElse(-1L));
    }

    private void onDpa(Channel ch) {
        if (disconnectAck != null) {
            disconnectAck.complete(null);
        }
        ch.close();
    }

    private void onAnswer(DiameterMessage answer) {
        PendingRequests.Pending p = pending.remove(answer.hopByHopId());
        if (p == null) {
            unmatchedAnswers.increment();
            log.warn("Unmatched answer {} (late after timeout, or unknown hop-by-hop)", answer);
            return;
        }
        long elapsed = System.nanoTime() - p.startNanos();
        requestTimer.record(elapsed, TimeUnit.NANOSECONDS);
        p.future().complete(answer);
    }

    private void onPeerRequest(Channel ch, DiameterMessage request) {
        switch (request.commandCode()) {
            case CommandCode.DEVICE_WATCHDOG -> {
                DiameterMessage dwa = request.createAnswer()
                        .avp(Avp.unsigned32(AvpCode.RESULT_CODE, ResultCode.DIAMETER_SUCCESS))
                        .avp(Avp.utf8(AvpCode.ORIGIN_HOST, props.originHost()))
                        .avp(Avp.utf8(AvpCode.ORIGIN_REALM, props.originRealm()))
                        .build();
                ch.writeAndFlush(dwa);
            }
            case CommandCode.DISCONNECT_PEER -> {
                log.warn("Peer {} sent DPR (cause {}); answering DPA and reconnecting", peerHost,
                        request.avp(AvpCode.DISCONNECT_CAUSE).map(Avp::asInteger32).orElse(-1));
                DiameterMessage dpa = request.createAnswer()
                        .avp(Avp.unsigned32(AvpCode.RESULT_CODE, ResultCode.DIAMETER_SUCCESS))
                        .avp(Avp.utf8(AvpCode.ORIGIN_HOST, props.originHost()))
                        .avp(Avp.utf8(AvpCode.ORIGIN_REALM, props.originRealm()))
                        .build();
                ch.writeAndFlush(dpa).addListener(f -> ch.close());
            }
            default -> {
                log.warn("Unsupported request {} from peer; answering 3001", request);
                DiameterMessage err = request.createAnswer().error()
                        .avp(Avp.unsigned32(AvpCode.RESULT_CODE, ResultCode.DIAMETER_COMMAND_UNSUPPORTED))
                        .avp(Avp.utf8(AvpCode.ORIGIN_HOST, props.originHost()))
                        .avp(Avp.utf8(AvpCode.ORIGIN_REALM, props.originRealm()))
                        .build();
                ch.writeAndFlush(err);
            }
        }
    }

    void onIdle(Channel ch) {
        if (state != PeerState.OPEN) {
            return;
        }
        if (watchdogOutstanding) {
            log.error("Peer {} did not answer DWR within {} ms; closing connection", peerHost,
                    props.watchdogInterval().toMillis());
            ch.close();
            return;
        }
        DiameterMessage dwr = DiameterMessage.request(CommandCode.DEVICE_WATCHDOG, ApplicationId.BASE)
                .hopByHopId(ids.nextHopByHop()).endToEndId(ids.nextEndToEnd())
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, props.originHost()))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, props.originRealm()))
                .build();
        watchdogOutstanding = true;
        log.debug("Idle; sending DWR hbh=0x{}", Integer.toHexString(dwr.hopByHopId()));
        ch.writeAndFlush(dwr);
    }

    void onDisconnected(Channel ch) {
        PeerState previous = state;
        setState(PeerState.CLOSED);
        channel = null;
        if (ceaTimeoutTask != null) {
            ceaTimeoutTask.cancel(false);
        }
        int failed = pending.failAll(new DiameterUnavailableException("Diameter connection to " + peerHost
                + " lost while request in flight"));
        if (failed > 0) {
            log.warn("Connection to {} closed with {} requests in flight; all failed with 503", peerHost, failed);
        } else {
            log.info("Connection to {} closed (was {})", peerHost, previous);
        }
        if (disconnectAck != null) {
            disconnectAck.complete(null);
        }
        scheduleReconnect();
    }

    private void onTimeout(int hop) {
        PendingRequests.Pending p = pending.remove(hop);
        if (p != null) {
            timeouts.increment();
            log.warn("Request hbh=0x{} timed out after {} ms", Integer.toHexString(hop),
                    props.requestTimeout().toMillis());
            p.future().completeExceptionally(new DiameterTimeoutException(hop, props.requestTimeout()));
        }
    }

    // ------------------------------------------------------------------ connection

    private void connect() {
        if (!running) {
            return;
        }
        setState(PeerState.CONNECTING);
        bootstrap.connect(props.host(), props.port()).addListener(f -> {
            if (!f.isSuccess()) {
                setState(PeerState.CLOSED);
                log.warn("Cannot connect to Diameter peer {}:{} ({}); retrying in {} ms", props.host(), props.port(),
                        f.cause().getMessage(), props.reconnectDelay().toMillis());
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        reconnects.increment();
        reconnectTask = group.schedule(this::connect, props.reconnectDelay().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void setState(PeerState newState) {
        state = newState;
        stateGauge.set(newState == PeerState.OPEN ? 1 : 0);
    }

    private static InetAddress localAddress(Channel ch) {
        if (ch.localAddress() instanceof java.net.InetSocketAddress isa && isa.getAddress() != null) {
            return isa.getAddress();
        }
        return InetAddress.getLoopbackAddress();
    }
}
