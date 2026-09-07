package com.telecombridge.gateway.charging;

import com.telecombridge.diameter.ApplicationId;
import com.telecombridge.diameter.Avp;
import com.telecombridge.diameter.AvpCode;
import com.telecombridge.diameter.CommandCode;
import com.telecombridge.diameter.DiameterCodec;
import com.telecombridge.diameter.DiameterMessage;
import com.telecombridge.diameter.ResultCode;
import com.telecombridge.gateway.config.DiameterProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CcrMapperTest {

    private static final DiameterProperties PROPS = new DiameterProperties(
            "localhost", 3868, "gateway.test", "test.realm", "ocs.realm", "TelecomBridge", 0,
            "32251@3gpp.org", Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(30),
            Duration.ofSeconds(2), 1000, 1);

    private final CcrMapper mapper = new CcrMapper(PROPS);

    @Test
    void initialRequestProducesRfc4006Ccr() {
        ChargeRequest req = new ChargeRequest("919876543210", ChargeRequestType.INITIAL, 0, null, 1_048_576L, null);

        DiameterMessage ccr = mapper.toCcr(req, "gateway.test;1;1").hopByHopId(1).endToEndId(1).build();

        assertThat(ccr.isRequest()).isTrue();
        assertThat(ccr.isProxiable()).isTrue();
        assertThat(ccr.commandCode()).isEqualTo(CommandCode.CREDIT_CONTROL);
        assertThat(ccr.applicationId()).isEqualTo(ApplicationId.CREDIT_CONTROL);
        assertThat(ccr.avps().get(0).code()).as("Session-Id must be first").isEqualTo(AvpCode.SESSION_ID);
        assertThat(ccr.sessionId()).contains("gateway.test;1;1");
        assertThat(ccr.avp(AvpCode.ORIGIN_HOST).orElseThrow().asUtf8()).isEqualTo("gateway.test");
        assertThat(ccr.avp(AvpCode.ORIGIN_REALM).orElseThrow().asUtf8()).isEqualTo("test.realm");
        assertThat(ccr.avp(AvpCode.DESTINATION_REALM).orElseThrow().asUtf8()).isEqualTo("ocs.realm");
        assertThat(ccr.avp(AvpCode.AUTH_APPLICATION_ID).orElseThrow().asUnsigned32()).isEqualTo(4L);
        assertThat(ccr.avp(AvpCode.SERVICE_CONTEXT_ID).orElseThrow().asUtf8()).isEqualTo("32251@3gpp.org");
        assertThat(ccr.avp(AvpCode.CC_REQUEST_TYPE).orElseThrow().asInteger32()).isEqualTo(1);
        assertThat(ccr.avp(AvpCode.CC_REQUEST_NUMBER).orElseThrow().asUnsigned32()).isZero();

        Avp sub = ccr.avp(AvpCode.SUBSCRIPTION_ID).orElseThrow();
        assertThat(sub.child(AvpCode.SUBSCRIPTION_ID_TYPE).orElseThrow().asInteger32()).isZero(); // END_USER_E164
        assertThat(sub.child(AvpCode.SUBSCRIPTION_ID_DATA).orElseThrow().asUtf8()).isEqualTo("919876543210");

        Avp rsu = ccr.avp(AvpCode.REQUESTED_SERVICE_UNIT).orElseThrow();
        assertThat(rsu.child(AvpCode.CC_TOTAL_OCTETS).orElseThrow().asUnsigned64()).isEqualTo(1_048_576L);

        // every mandatory AVP carries the M bit
        assertThat(ccr.avps()).allMatch(Avp::isMandatory);
        // and the whole thing survives the wire
        assertThat(DiameterCodec.decode(DiameterCodec.encode(ccr)).avps()).containsExactlyElementsOf(ccr.avps());
    }

    @Test
    void defaultsApplyWhenOptionalFieldsAbsent() {
        ChargeRequest req = new ChargeRequest("919876543210", null, null, null, null, null);

        DiameterMessage ccr = mapper.toCcr(req, "s").build();

        assertThat(ccr.avp(AvpCode.CC_REQUEST_TYPE).orElseThrow().asInteger32()).isEqualTo(1);
        assertThat(ccr.avp(AvpCode.CC_REQUEST_NUMBER).orElseThrow().asUnsigned32()).isZero();
        assertThat(ccr.avp(AvpCode.REQUESTED_SERVICE_UNIT)).isEmpty();
    }

    @Test
    void updateRequestCarriesTypeNumberAndOverrides() {
        ChargeRequest req = new ChargeRequest("919876543210", ChargeRequestType.UPDATE, 3, "s;1;9", 512L, "custom@ctx");

        DiameterMessage ccr = mapper.toCcr(req, "s;1;9").build();

        assertThat(ccr.avp(AvpCode.CC_REQUEST_TYPE).orElseThrow().asInteger32()).isEqualTo(2);
        assertThat(ccr.avp(AvpCode.CC_REQUEST_NUMBER).orElseThrow().asUnsigned32()).isEqualTo(3L);
        assertThat(ccr.avp(AvpCode.SERVICE_CONTEXT_ID).orElseThrow().asUtf8()).isEqualTo("custom@ctx");
    }

    @Test
    void successfulCcaMapsToSuccessResponseWithGrant() {
        DiameterMessage cca = DiameterMessage.answer(CommandCode.CREDIT_CONTROL, ApplicationId.CREDIT_CONTROL)
                .hopByHopId(0x2A)
                .avp(Avp.utf8(AvpCode.SESSION_ID, "s;1;42"))
                .avp(Avp.unsigned32(AvpCode.RESULT_CODE, ResultCode.DIAMETER_SUCCESS))
                .avp(Avp.grouped(AvpCode.GRANTED_SERVICE_UNIT, Avp.unsigned64(AvpCode.CC_TOTAL_OCTETS, 2048)))
                .avp(Avp.unsigned32(AvpCode.VALIDITY_TIME, 3600))
                .build();

        ChargeResponse r = mapper.toResponse(cca, "fallback", 63);

        assertThat(r.success()).isTrue();
        assertThat(r.resultCode()).isEqualTo(2001);
        assertThat(r.resultText()).isEqualTo("DIAMETER_SUCCESS");
        assertThat(r.sessionId()).isEqualTo("s;1;42");
        assertThat(r.grantedOctets()).isEqualTo(2048L);
        assertThat(r.validitySeconds()).isEqualTo(3600L);
        assertThat(r.hopByHopId()).isEqualTo(42L);
        assertThat(r.latencyMs()).isEqualTo(63L);
    }

    @Test
    void failureCcaMapsToUnsuccessfulResponseWithoutGrant() {
        DiameterMessage cca = DiameterMessage.answer(CommandCode.CREDIT_CONTROL, ApplicationId.CREDIT_CONTROL)
                .hopByHopId(-1)
                .avp(Avp.unsigned32(AvpCode.RESULT_CODE, ResultCode.DIAMETER_CREDIT_LIMIT_REACHED))
                .build();

        ChargeResponse r = mapper.toResponse(cca, "fallback", 5);

        assertThat(r.success()).isFalse();
        assertThat(r.resultCode()).isEqualTo(4012);
        assertThat(r.resultText()).isEqualTo("DIAMETER_CREDIT_LIMIT_REACHED");
        assertThat(r.sessionId()).isEqualTo("fallback");
        assertThat(r.grantedOctets()).isNull();
        assertThat(r.hopByHopId()).as("unsigned rendering").isEqualTo(4294967295L);
    }
}
