package com.telecombridge.gateway.diameter;

import com.telecombridge.diameter.ApplicationId;
import com.telecombridge.diameter.Avp;
import com.telecombridge.diameter.AvpCode;
import com.telecombridge.diameter.CcRequestType;
import com.telecombridge.diameter.CommandCode;
import com.telecombridge.diameter.DiameterMessage;
import com.telecombridge.diameter.ResultCode;
import com.telecombridge.diameter.SubscriptionIdType;
import com.telecombridge.gateway.config.DiameterProperties;
import com.telecombridge.simulator.DiameterSimulator;
import com.telecombridge.simulator.SimulatorConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real sockets against the real simulator, in-process on a random port: handshake,
 * correlation under concurrency, timeout, peer-down and reconnect behaviour.
 */
class DiameterClientTest {

    private DiameterSimulator simulator;
    private DiameterClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.stop();
        }
        if (simulator != null) {
            simulator.close();
        }
    }

    private static DiameterProperties props(int port, Duration requestTimeout, int maxPending) {
        return new DiameterProperties("127.0.0.1", port, "gateway.test", "test", "test", "TelecomBridge", 0,
                "32251@3gpp.org", requestTimeout, Duration.ofSeconds(2), Duration.ofSeconds(30),
                Duration.ofMillis(200), maxPending, 1);
    }

    private DiameterSimulator startSimulator(int port) throws InterruptedException {
        DiameterSimulator sim = new DiameterSimulator(SimulatorConfig.defaults().withPort(port).withDelay(1, 3));
        sim.start();
        return sim;
    }

    private DiameterClient startClient(int port, Duration timeout, int maxPending) {
        DiameterClient c = new DiameterClient(props(port, timeout, maxPending), new IdentifierGenerator("gateway.test"),
                new SimpleMeterRegistry());
        c.start();
        return c;
    }

    private static void await(BooleanSupplier condition, Duration max) throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + max);
            }
            Thread.sleep(10);
        }
    }

    private static DiameterMessage.Builder ccr(String msisdn, String session) {
        return DiameterMessage.request(CommandCode.CREDIT_CONTROL, ApplicationId.CREDIT_CONTROL)
                .proxiable()
                .avp(Avp.utf8(AvpCode.SESSION_ID, session))
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, "gateway.test"))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, "test"))
                .avp(Avp.utf8(AvpCode.DESTINATION_REALM, "test"))
                .avp(Avp.unsigned32(AvpCode.AUTH_APPLICATION_ID, ApplicationId.CREDIT_CONTROL))
                .avp(Avp.enumerated(AvpCode.CC_REQUEST_TYPE, CcRequestType.INITIAL_REQUEST.code()))
                .avp(Avp.unsigned32(AvpCode.CC_REQUEST_NUMBER, 0))
                .avp(Avp.grouped(AvpCode.SUBSCRIPTION_ID,
                        Avp.enumerated(AvpCode.SUBSCRIPTION_ID_TYPE, SubscriptionIdType.END_USER_E164.code()),
                        Avp.utf8(AvpCode.SUBSCRIPTION_ID_DATA, msisdn)));
    }

    @Test
    void handshakeReachesOpenAndCcrGetsMatchingCca() throws Exception {
        simulator = startSimulator(0);
        client = startClient(simulator.port(), Duration.ofSeconds(2), 100);
        await(() -> client.state() == PeerState.OPEN, Duration.ofSeconds(5));
        assertThat(client.peerHost()).isEqualTo("ocs.telecom-bridge.local");

        DiameterMessage cca = client.send(ccr("919876543210", "gateway.test;1;1")).get(2, TimeUnit.SECONDS);

        assertThat(cca.commandName()).isEqualTo("CCA");
        assertThat(cca.resultCode()).contains(ResultCode.DIAMETER_SUCCESS);
        assertThat(cca.sessionId()).contains("gateway.test;1;1");
        assertThat(client.pendingCount()).as("table drained after answer").isZero();
        assertThat(simulator.stats().cerReceived.get()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsAreCorrelatedByHopByHop() throws Exception {
        simulator = startSimulator(0);
        client = startClient(simulator.port(), Duration.ofSeconds(5), 1000);
        await(() -> client.state() == PeerState.OPEN, Duration.ofSeconds(5));

        int n = 300;
        List<CompletableFuture<DiameterMessage>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(client.send(ccr("9198765" + String.format("%05d", i), "gateway.test;7;" + i)));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);

        for (int i = 0; i < n; i++) {
            assertThat(futures.get(i).get().sessionId()).contains("gateway.test;7;" + i);
        }
        assertThat(client.pendingCount()).isZero();
        assertThat(simulator.stats().ccrReceived.get()).isEqualTo(n);
    }

    @Test
    void blackholedRequestTimesOutAndLeavesNoPendingEntry() throws Exception {
        simulator = startSimulator(0);
        client = startClient(simulator.port(), Duration.ofMillis(150), 100);
        await(() -> client.state() == PeerState.OPEN, Duration.ofSeconds(5));

        CompletableFuture<DiameterMessage> f = client.send(ccr("919876545555", "gateway.test;2;1"));

        assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(DiameterTimeoutException.class);
        assertThat(client.pendingCount()).isZero();
        assertThat(client.state()).as("a timeout does not tear the connection down").isEqualTo(PeerState.OPEN);
    }

    @Test
    void peerDownFailsFastAndReconnectsWhenPeerReturns() throws Exception {
        simulator = startSimulator(0);
        int port = simulator.port();
        client = startClient(port, Duration.ofSeconds(1), 100);
        await(() -> client.state() == PeerState.OPEN, Duration.ofSeconds(5));

        simulator.close();
        await(() -> client.state() != PeerState.OPEN, Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.send(ccr("919876543210", "s")).get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(DiameterUnavailableException.class);

        simulator = startSimulator(port);
        await(() -> client.state() == PeerState.OPEN, Duration.ofSeconds(10));

        DiameterMessage cca = client.send(ccr("919876543210", "gateway.test;3;1")).get(2, TimeUnit.SECONDS);
        assertThat(cca.resultCode()).contains(ResultCode.DIAMETER_SUCCESS);
    }

    @Test
    void inFlightRequestsFailWith503WhenConnectionDrops() throws Exception {
        simulator = startSimulator(0);
        client = startClient(simulator.port(), Duration.ofSeconds(5), 100);
        await(() -> client.state() == PeerState.OPEN, Duration.ofSeconds(5));

        CompletableFuture<DiameterMessage> f = client.send(ccr("919876545555", "blackhole"));
        await(() -> client.pendingCount() == 1, Duration.ofSeconds(1));
        simulator.close();

        assertThatThrownBy(() -> f.get(3, TimeUnit.SECONDS))
                .hasCauseInstanceOf(DiameterUnavailableException.class);
        assertThat(client.pendingCount()).isZero();
    }

    @Test
    void pendingLimitRejectsExcessRequestsImmediately() throws Exception {
        simulator = startSimulator(0);
        client = startClient(simulator.port(), Duration.ofSeconds(5), 2);
        await(() -> client.state() == PeerState.OPEN, Duration.ofSeconds(5));

        CompletableFuture<DiameterMessage> a = client.send(ccr("919876545555", "bh1"));
        CompletableFuture<DiameterMessage> b = client.send(ccr("919876545555", "bh2"));
        CompletableFuture<DiameterMessage> c = client.send(ccr("919876543210", "ok"));

        assertThat(c).isCompletedExceptionally();
        assertThatThrownBy(c::get).hasCauseInstanceOf(DiameterUnavailableException.class)
                .hasMessageContaining("Too many in-flight");
        assertThat(a).isNotDone();
        assertThat(b).isNotDone();
    }

    @Test
    void gracefulStopSendsDprAndPeerSeesCleanClose() throws Exception {
        simulator = startSimulator(0);
        client = startClient(simulator.port(), Duration.ofSeconds(1), 100);
        await(() -> client.state() == PeerState.OPEN, Duration.ofSeconds(5));

        client.stop();
        client = null;

        await(() -> simulator.stats().activeConnections.get() == 0, Duration.ofSeconds(3));
    }
}
