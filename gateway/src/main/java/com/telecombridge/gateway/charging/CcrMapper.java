package com.telecombridge.gateway.charging;

import com.telecombridge.diameter.ApplicationId;
import com.telecombridge.diameter.Avp;
import com.telecombridge.diameter.AvpCode;
import com.telecombridge.diameter.CommandCode;
import com.telecombridge.diameter.DiameterMessage;
import com.telecombridge.diameter.ResultCode;
import com.telecombridge.diameter.SubscriptionIdType;
import com.telecombridge.gateway.config.DiameterProperties;
import org.springframework.stereotype.Component;

/**
 * Translates between the REST payloads and Credit-Control messages (RFC 4006 section 3).
 *
 * <p>CCR AVP order follows the RFC's ABNF: Session-Id first, then the routing AVPs,
 * then the application AVPs. Identifiers are left for {@code DiameterClient.send}.
 */
@Component
public class CcrMapper {

    private final DiameterProperties props;

    public CcrMapper(DiameterProperties props) {
        this.props = props;
    }

    public DiameterMessage.Builder toCcr(ChargeRequest request, String sessionId) {
        String serviceContext = request.serviceContextId() == null || request.serviceContextId().isBlank()
                ? props.serviceContextId()
                : request.serviceContextId();

        DiameterMessage.Builder ccr = DiameterMessage.request(CommandCode.CREDIT_CONTROL, ApplicationId.CREDIT_CONTROL)
                .proxiable()
                .avp(Avp.utf8(AvpCode.SESSION_ID, sessionId))
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, props.originHost()))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, props.originRealm()))
                .avp(Avp.utf8(AvpCode.DESTINATION_REALM, props.destinationRealm()))
                .avp(Avp.unsigned32(AvpCode.AUTH_APPLICATION_ID, ApplicationId.CREDIT_CONTROL))
                .avp(Avp.utf8(AvpCode.SERVICE_CONTEXT_ID, serviceContext))
                .avp(Avp.enumerated(AvpCode.CC_REQUEST_TYPE, request.requestTypeOrDefault().toDiameter().code()))
                .avp(Avp.unsigned32(AvpCode.CC_REQUEST_NUMBER, request.requestNumberOrDefault()))
                .avp(Avp.grouped(AvpCode.SUBSCRIPTION_ID,
                        Avp.enumerated(AvpCode.SUBSCRIPTION_ID_TYPE, SubscriptionIdType.END_USER_E164.code()),
                        Avp.utf8(AvpCode.SUBSCRIPTION_ID_DATA, request.msisdn())));

        if (request.requestedOctets() != null) {
            ccr.avp(Avp.grouped(AvpCode.REQUESTED_SERVICE_UNIT,
                    Avp.unsigned64(AvpCode.CC_TOTAL_OCTETS, request.requestedOctets())));
        }
        return ccr;
    }

    public ChargeResponse toResponse(DiameterMessage cca, String sessionId, long latencyMs) {
        long rc = cca.resultCode().orElse(ResultCode.DIAMETER_UNABLE_TO_COMPLY);
        Long granted = cca.avp(AvpCode.GRANTED_SERVICE_UNIT)
                .flatMap(gsu -> gsu.child(AvpCode.CC_TOTAL_OCTETS))
                .map(Avp::asUnsigned64)
                .orElse(null);
        Long validity = cca.avp(AvpCode.VALIDITY_TIME).map(Avp::asUnsigned32).orElse(null);
        return new ChargeResponse(
                cca.sessionId().orElse(sessionId),
                rc,
                ResultCode.name(rc),
                ResultCode.isSuccess(rc),
                granted,
                validity,
                Integer.toUnsignedLong(cca.hopByHopId()),
                latencyMs);
    }
}
