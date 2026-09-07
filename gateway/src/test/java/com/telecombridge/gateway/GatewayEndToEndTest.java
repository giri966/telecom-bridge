package com.telecombridge.gateway;

import com.telecombridge.gateway.diameter.DiameterClient;
import com.telecombridge.gateway.diameter.PeerState;
import com.telecombridge.simulator.DiameterSimulator;
import com.telecombridge.simulator.SimulatorConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

/** Whole stack: HTTP in, Diameter out to the in-process simulator, HTTP back. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayEndToEndTest {

    private static DiameterSimulator simulator;

    @DynamicPropertySource
    static void diameterPort(DynamicPropertyRegistry registry) throws InterruptedException {
        simulator = new DiameterSimulator(SimulatorConfig.defaults().withPort(0).withDelay(5, 10));
        int port = simulator.start();
        registry.add("diameter.port", () -> port);
        registry.add("diameter.host", () -> "127.0.0.1");
        registry.add("diameter.request-timeout", () -> "300ms");
        registry.add("diameter.reconnect-delay", () -> "200ms");
        registry.add("diameter.watchdog-interval", () -> "5s");
    }

    @AfterAll
    static void stopSimulator() {
        simulator.close();
    }

    @Autowired
    WebTestClient web;

    @Autowired
    DiameterClient client;

    @BeforeEach
    void waitForPeer() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (client.state() != PeerState.OPEN) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Diameter peer never reached OPEN: " + client.state());
            }
            Thread.sleep(20);
        }
    }

    @Test
    void chargeRoundTripsThroughDiameter() {
        web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"msisdn\":\"919876543210\",\"requestType\":\"INITIAL\",\"requestedOctets\":1048576}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.resultCode").isEqualTo(2001)
                .jsonPath("$.resultText").isEqualTo("DIAMETER_SUCCESS")
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.grantedOctets").isEqualTo(1048576)
                .jsonPath("$.validitySeconds").isEqualTo(3600)
                .jsonPath("$.sessionId").value(s -> org.assertj.core.api.Assertions.assertThat(s.toString())
                        .startsWith("gateway.telecom-bridge.local;"))
                .jsonPath("$.hopByHopId").isNumber()
                .jsonPath("$.latencyMs").isNumber();
    }

    @Test
    void unknownSubscriberReturns422WithDiameterCode() {
        web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"msisdn\":\"919876540000\"}")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.resultCode").isEqualTo(5030)
                .jsonPath("$.resultText").isEqualTo("DIAMETER_USER_UNKNOWN")
                .jsonPath("$.success").isEqualTo(false);
    }

    @Test
    void silentPeerReturns504() {
        web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"msisdn\":\"919876545555\"}")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody()
                .jsonPath("$.error").isEqualTo("DIAMETER_TIMEOUT");
    }

    @Test
    void badPayloadReturns400() {
        web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"msisdn\":\"12\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void healthReportsDiameterPeerOpen() {
        web.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.diameter.status").isEqualTo("UP")
                .jsonPath("$.components.diameter.details.state").isEqualTo("OPEN")
                .jsonPath("$.components.diameter.details.peer").isEqualTo("ocs.telecom-bridge.local");
    }

    @Test
    void metricsExposePendingGaugeAndRequestTimer() {
        web.get().uri("/actuator/metrics/diameter.pending.requests")
                .exchange()
                .expectStatus().isOk();
        web.get().uri("/actuator/metrics/diameter.request")
                .exchange()
                .expectStatus().isOk();
    }
}
