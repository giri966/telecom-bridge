package com.telecombridge.simulator;

import com.telecombridge.diameter.netty.DiameterPipeline;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Standalone Diameter server that plays the role of the Online Charging System.
 *
 * <p>Run with {@code java -jar diameter-simulator.jar [--port=3868] [--min-delay=50] [--max-delay=100]}.
 * Also usable in-process from tests: {@link #start()} returns the bound port when 0 was requested.
 */
public final class DiameterSimulator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DiameterSimulator.class);

    private final SimulatorConfig config;
    private final SimulatorStats stats = new SimulatorStats();

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ScheduledExecutorService delayScheduler;
    private Channel serverChannel;
    private ScheduledFuture<?> statsTask;

    public DiameterSimulator(SimulatorConfig config) {
        this.config = config;
    }

    public static void main(String[] args) throws Exception {
        SimulatorConfig config = SimulatorConfig.fromArgs(args);
        DiameterSimulator simulator = new DiameterSimulator(config);
        simulator.start();
        Runtime.getRuntime().addShutdownHook(new Thread(simulator::close, "simulator-shutdown"));
        simulator.serverChannel.closeFuture().sync();
    }

    /** Binds the listening socket and returns the actual port. */
    public synchronized int start() throws InterruptedException {
        if (serverChannel != null) {
            throw new IllegalStateException("Already started");
        }
        // Times the CCA delay. A ScheduledThreadPoolExecutor parks with nanosecond
        // precision, whereas Netty's event loop waits on the selector with a timeout it
        // rounds up to a whole millisecond, so this is the more accurate clock on any host
        // that has a fine-grained timer. On Windows neither is accurate; see SimulatorStats.
        delayScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "sim-delay");
            t.setDaemon(true);
            return t;
        });
        bossGroup =new NioEventLoopGroup(1, new DefaultThreadFactory("sim-boss"));
        workerGroup = new NioEventLoopGroup(config.workerThreads(), new DefaultThreadFactory("sim-worker"));

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        DiameterPipeline.installCodec(ch.pipeline());
                        ch.pipeline().addLast("simulator", new SimulatorHandler(config, stats, delayScheduler));
                    }
                });

        serverChannel = bootstrap.bind(config.port()).sync().channel();
        int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        log.info("Diameter simulator listening on port {} as {} (realm {}), CCR delay {}..{} ms, {} workers",
                port, config.originHost(), config.originRealm(), config.minDelayMs(), config.maxDelayMs(),
                config.workerThreads());

        statsTask = workerGroup.next().scheduleAtFixedRate(() -> {
            if (stats.changedSinceLastLog()) {
                log.info("stats {}", stats);
            }
        }, 10, 10, TimeUnit.SECONDS);
        return port;
    }

    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public SimulatorStats stats() {
        return stats;
    }

    /** Closes the listening socket, every client connection and the event loops. Safe to call twice. */
    @Override
    public synchronized void close() {
        if (serverChannel == null) {
            return;
        }
        log.info("Shutting down simulator; final {}", stats);
        if (statsTask != null) {
            statsTask.cancel(false);
        }
        serverChannel.close().syncUninterruptibly();
        serverChannel = null;
        delayScheduler.shutdownNow();
        workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
}
