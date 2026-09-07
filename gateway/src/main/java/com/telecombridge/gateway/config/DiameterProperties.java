package com.telecombridge.gateway.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Everything about the Diameter peer, bound from the {@code diameter.*} keys in application.yml.
 *
 * @param host               peer address (the simulator)
 * @param port               peer port, 3868 by IANA assignment
 * @param originHost         our DiameterIdentity, sent in every Origin-Host
 * @param originRealm        our realm
 * @param destinationRealm   Destination-Realm placed in every CCR
 * @param productName        Product-Name advertised in the CER
 * @param vendorId           Vendor-Id advertised in the CER (0 = IETF)
 * @param serviceContextId   default Service-Context-Id when the REST caller does not supply one
 * @param requestTimeout     how long a CCR may wait for its CCA before the REST call gets a 504
 * @param ceaTimeout         how long to wait for the CEA after sending the CER before giving up
 * @param watchdogInterval   idle time after which a DWR is sent; a second idle period without DWA closes the link
 * @param reconnectDelay     pause between reconnect attempts while the peer is down
 * @param maxPendingRequests upper bound on in-flight CCRs; above it the REST call gets a 503 immediately
 * @param ioThreads          Netty event-loop threads for the single Diameter connection (1 is enough, 2 is safe)
 */
@Validated
@ConfigurationProperties(prefix = "diameter")
public record DiameterProperties(
        @DefaultValue("localhost") @NotBlank String host,
        @DefaultValue("3868") @Min(1) int port,
        @DefaultValue("gateway.telecom-bridge.local") @NotBlank String originHost,
        @DefaultValue("telecom-bridge.local") @NotBlank String originRealm,
        @DefaultValue("telecom-bridge.local") @NotBlank String destinationRealm,
        @DefaultValue("TelecomBridge") @NotBlank String productName,
        @DefaultValue("0") @Min(0) long vendorId,
        @DefaultValue("32251@3gpp.org") @NotBlank String serviceContextId,
        @DefaultValue("2s") Duration requestTimeout,
        @DefaultValue("5s") Duration ceaTimeout,
        @DefaultValue("30s") Duration watchdogInterval,
        @DefaultValue("2s") Duration reconnectDelay,
        @DefaultValue("10000") @Min(1) int maxPendingRequests,
        @DefaultValue("2") @Min(1) int ioThreads) {
}
