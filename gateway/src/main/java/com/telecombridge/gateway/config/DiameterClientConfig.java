package com.telecombridge.gateway.config;

import com.telecombridge.gateway.diameter.DiameterClient;
import com.telecombridge.gateway.diameter.IdentifierGenerator;
import com.telecombridge.diameter.pcap.PcapWriter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the single Diameter peer connection. Spring calls start/stop through SmartLifecycle. */
@Configuration
public class DiameterClientConfig {

    @Bean
    public IdentifierGenerator identifierGenerator(DiameterProperties properties) {
        return new IdentifierGenerator(properties.originHost());
    }

    @Bean
    public DiameterClient diameterClient(DiameterProperties properties, IdentifierGenerator ids,
                                         MeterRegistry meterRegistry,
                                         ObjectProvider<PcapWriter> pcapWriter) {
        return new DiameterClient(properties, ids, meterRegistry, pcapWriter.getIfAvailable());
    }
}
