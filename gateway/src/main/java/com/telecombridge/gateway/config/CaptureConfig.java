package com.telecombridge.gateway.config;

import com.telecombridge.diameter.pcap.PcapTapHandler;
import com.telecombridge.diameter.pcap.PcapWriter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Records the REST and Diameter traffic into a libpcap file, so the exchange can be opened
 * in Wireshark. Enabled only when {@code capture.file} is set, for instance:
 *
 * <pre>
 * java -jar gateway.jar --capture.file=transaction_flow.pcap
 * </pre>
 *
 * <p>The tap sits first in both pipelines, so it sees exactly the bytes the sockets carried.
 * See {@link PcapWriter} for what is real in the resulting file and what is reconstructed.
 */
@Configuration
@ConditionalOnProperty(name = "capture.file")
public class CaptureConfig {

    private static final Logger log = LoggerFactory.getLogger(CaptureConfig.class);

    private final PcapWriter writer;
    private final Path file;

    public CaptureConfig(org.springframework.core.env.Environment env) throws IOException {
        this.file = Path.of(env.getRequiredProperty("capture.file"));
        this.writer = new PcapWriter(file);
        log.info("Packet capture enabled, writing to {}", file.toAbsolutePath());
    }

    @Bean
    public PcapWriter pcapWriter() {
        return writer;
    }

    /**
     * Taps the HTTP side: the REST call from the client to this gateway.
     *
     * <p>Uses {@code doOnChannelInit} rather than {@code doOnConnection}. The latter runs
     * only once Reactor Netty has a connection to hand out, by which point the first request
     * on that socket has already been read and decoded, so every inbound POST was missing
     * from the capture while the responses were all present. {@code doOnChannelInit} runs
     * while the pipeline is being built, before a single byte is read.
     */
    @Bean
    public NettyServerCustomizer captureHttpTraffic() {
        return httpServer -> httpServer.doOnChannelInit((observer, channel, remoteAddress) ->
                channel.pipeline().addFirst("pcapTap", new PcapTapHandler(writer)));
    }

    @PreDestroy
    public void closeCapture() {
        try {
            writer.close();
            log.info("Packet capture closed: {}", file.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Could not close the capture file cleanly: {}", e.toString());
        }
    }
}
