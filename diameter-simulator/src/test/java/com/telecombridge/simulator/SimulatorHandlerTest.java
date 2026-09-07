package com.telecombridge.simulator;

import com.telecombridge.diameter.ApplicationId;
import com.telecombridge.diameter.Avp;
import com.telecombridge.diameter.AvpCode;
import com.telecombridge.diameter.CcRequestType;
import com.telecombridge.diameter.CommandCode;
import com.telecombridge.diameter.DiameterMessage;
import com.telecombridge.diameter.ResultCode;
import com.telecombridge.diameter.SubscriptionIdType;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Drives the handler through an EmbeddedChannel so no sockets or real delays are involved. */
class SimulatorHandlerTest {

    private static final SimulatorConfig CONFIG = SimulatorConfig.defaults().withPort(0).withDelay(5, 5);

    private EmbeddedChannel channel;
    private SimulatorStats stats;

    @BeforeEach
    void setUp() {
        stats = new SimulatorStats();
        channel = new EmbeddedChannel(new SimulatorHandler(CONFIG, stats));
    }

    private static DiameterMessage cer(long... apps) {
        DiameterMessage.Builder b = DiameterMessage.request(CommandCode.CAPABILITIES_EXCHANGE, ApplicationId.BASE)
                .hopByHopId(1).endToEndId(1)
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, "gateway.test"))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, "test"))
                .avp(Avp.unsigned32(AvpCode.VENDOR_ID, 0));
        for (long app : apps) {
            b.avp(Avp.unsigned32(AvpCode.AUTH_APPLICATION_ID, app));
        }
        return b.build();
    }

    private static DiameterMessage ccr(int hop, String msisdn) {
        return DiameterMessage.request(CommandCode.CREDIT_CONTROL, ApplicationId.CREDIT_CONTROL)
                .proxiable().hopByHopId(hop).endToEndId(hop)
                .avp(Avp.utf8(AvpCode.SESSION_ID, "gateway.test;1;" + hop))
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, "gateway.test"))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, "test"))
                .avp(Avp.utf8(AvpCode.DESTINATION_REALM, "test"))
                .avp(Avp.unsigned32(AvpCode.AUTH_APPLICATION_ID, ApplicationId.CREDIT_CONTROL))
                .avp(Avp.enumerated(AvpCode.CC_REQUEST_TYPE, CcRequestType.INITIAL_REQUEST.code()))
                .avp(Avp.unsigned32(AvpCode.CC_REQUEST_NUMBER, 0))
                .avp(Avp.grouped(AvpCode.SUBSCRIPTION_ID,
                        Avp.enumerated(AvpCode.SUBSCRIPTION_ID_TYPE, SubscriptionIdType.END_USER_E164.code()),
                        Avp.utf8(AvpCode.SUBSCRIPTION_ID_DATA, msisdn)))
                .avp(Avp.grouped(AvpCode.REQUESTED_SERVICE_UNIT, Avp.unsigned64(AvpCode.CC_TOTAL_OCTETS, 4096)))
                .build();
    }

    private DiameterMessage open() {
        channel.writeInbound(cer(ApplicationId.CREDIT_CONTROL));
        return channel.readOutbound();
    }

    private DiameterMessage answerAfterDelay() {
        channel.advanceTimeBy(10, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        return channel.readOutbound();
    }

    @Test
    void cerIsAnsweredWithSuccessfulCeaAdvertisingCreditControl() {
        DiameterMessage cea = open();

        assertThat(cea.commandName()).isEqualTo("CEA");
        assertThat(cea.hopByHopId()).isEqualTo(1);
        assertThat(cea.resultCode()).contains(ResultCode.DIAMETER_SUCCESS);
        assertThat(cea.originHost()).contains(CONFIG.originHost());
        assertThat(cea.avp(AvpCode.AUTH_APPLICATION_ID).orElseThrow().asUnsigned32()).isEqualTo(4L);
        assertThat(cea.avp(AvpCode.HOST_IP_ADDRESS)).isPresent();
        assertThat(cea.avp(AvpCode.VENDOR_ID).orElseThrow().asUnsigned32()).isZero();
        assertThat(cea.avp(AvpCode.PRODUCT_NAME).orElseThrow().asUtf8()).isEqualTo(CONFIG.productName());
        assertThat(channel.isOpen()).isTrue();
        assertThat(stats.cerReceived.get()).isEqualTo(1);
    }

    @Test
    void cerWithoutCreditControlIsRejectedAndClosed() {
        channel.writeInbound(cer(1L));
        DiameterMessage cea = channel.readOutbound();

        assertThat(cea.resultCode()).contains(ResultCode.DIAMETER_NO_COMMON_APPLICATION);
        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void requestBeforeCerClosesTransport() {
        channel.writeInbound(ccr(5, "919876543210"));

        assertThat((Object) channel.readOutbound()).isNull();
        assertThat(channel.isOpen()).isFalse();
        assertThat(stats.protocolErrors.get()).isEqualTo(1);
    }

    @Test
    void dwrIsAnsweredWithDwa() {
        open();
        channel.writeInbound(DiameterMessage.request(CommandCode.DEVICE_WATCHDOG, ApplicationId.BASE)
                .hopByHopId(77).endToEndId(77)
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, "gateway.test"))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, "test"))
                .build());

        DiameterMessage dwa = channel.readOutbound();
        assertThat(dwa.commandName()).isEqualTo("DWA");
        assertThat(dwa.hopByHopId()).isEqualTo(77);
        assertThat(dwa.resultCode()).contains(ResultCode.DIAMETER_SUCCESS);
    }

    @Test
    void ccrIsAnsweredAfterDelayWithGrantMatchingRequest() {
        open();
        channel.writeInbound(ccr(42, "919876543210"));

        assertThat((Object) channel.readOutbound()).as("no answer before the delay elapses").isNull();
        DiameterMessage cca = answerAfterDelay();

        assertThat(cca.commandName()).isEqualTo("CCA");
        assertThat(cca.isProxiable()).isTrue();
        assertThat(cca.hopByHopId()).isEqualTo(42);
        assertThat(cca.endToEndId()).isEqualTo(42);
        assertThat(cca.avps().get(0).code()).as("Session-Id first").isEqualTo(AvpCode.SESSION_ID);
        assertThat(cca.sessionId()).contains("gateway.test;1;42");
        assertThat(cca.resultCode()).contains(ResultCode.DIAMETER_SUCCESS);
        assertThat(cca.avp(AvpCode.CC_REQUEST_TYPE).orElseThrow().asInteger32()).isEqualTo(1);
        assertThat(cca.avp(AvpCode.CC_REQUEST_NUMBER).orElseThrow().asUnsigned32()).isZero();
        assertThat(cca.avp(AvpCode.GRANTED_SERVICE_UNIT).orElseThrow()
                .child(AvpCode.CC_TOTAL_OCTETS).orElseThrow().asUnsigned64()).isEqualTo(4096L);
        assertThat(cca.avp(AvpCode.VALIDITY_TIME).orElseThrow().asUnsigned32()).isEqualTo(3600L);
        assertThat(stats.ccaSuccess.get()).isEqualTo(1);
    }

    @Test
    void unknownUserSuffixYields5030WithoutGrant() {
        open();
        channel.writeInbound(ccr(8, "919876540000"));
        DiameterMessage cca = answerAfterDelay();

        assertThat(cca.resultCode()).contains(ResultCode.DIAMETER_USER_UNKNOWN);
        assertThat(cca.avp(AvpCode.GRANTED_SERVICE_UNIT)).isEmpty();
        assertThat(stats.ccaFailure.get()).isEqualTo(1);
    }

    @Test
    void creditLimitSuffixYields4012() {
        open();
        channel.writeInbound(ccr(9, "919876549999"));
        assertThat(answerAfterDelay().resultCode()).contains(ResultCode.DIAMETER_CREDIT_LIMIT_REACHED);
    }

    @Test
    void blackholeSuffixIsNeverAnswered() {
        open();
        channel.writeInbound(ccr(10, "919876545555"));
        assertThat((Object) answerAfterDelay()).isNull();
        assertThat(stats.ccrDropped.get()).isEqualTo(1);
    }

    @Test
    void ccrMissingMandatoryAvpGets5005WithFailedAvp() {
        open();
        channel.writeInbound(DiameterMessage.request(CommandCode.CREDIT_CONTROL, ApplicationId.CREDIT_CONTROL)
                .hopByHopId(11).endToEndId(11)
                .avp(Avp.utf8(AvpCode.SESSION_ID, "s"))
                .avp(Avp.unsigned32(AvpCode.AUTH_APPLICATION_ID, 4))
                .build());

        DiameterMessage cca = channel.readOutbound();
        assertThat(cca.resultCode()).contains(ResultCode.DIAMETER_MISSING_AVP);
        assertThat(cca.avp(AvpCode.FAILED_AVP).orElseThrow().child(AvpCode.CC_REQUEST_TYPE)).isPresent();
    }

    @Test
    void dprIsAnsweredAndConnectionClosed() {
        open();
        channel.writeInbound(DiameterMessage.request(CommandCode.DISCONNECT_PEER, ApplicationId.BASE)
                .hopByHopId(12).endToEndId(12)
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, "gateway.test"))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, "test"))
                .avp(Avp.enumerated(AvpCode.DISCONNECT_CAUSE, 0))
                .build());

        DiameterMessage dpa = channel.readOutbound();
        assertThat(dpa.commandName()).isEqualTo("DPA");
        assertThat(dpa.resultCode()).contains(ResultCode.DIAMETER_SUCCESS);
        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void unsupportedCommandGets3001WithErrorBit() {
        open();
        channel.writeInbound(DiameterMessage.request(999, ApplicationId.BASE).hopByHopId(13).build());

        DiameterMessage answer = channel.readOutbound();
        assertThat(answer.isError()).isTrue();
        assertThat(answer.resultCode()).contains(ResultCode.DIAMETER_COMMAND_UNSUPPORTED);
    }
}
