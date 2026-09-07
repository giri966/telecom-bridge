package com.telecombridge.gateway.charging;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Body of {@code POST /api/v1/charge}.
 *
 * @param msisdn           subscriber number in E.164 digits without the plus sign; becomes
 *                         Subscription-Id (type END_USER_E164)
 * @param requestType      INITIAL (default), UPDATE, TERMINATION or EVENT; becomes CC-Request-Type
 * @param requestNumber    sequence within the session, 0 for INITIAL; becomes CC-Request-Number
 * @param sessionId        optional; supply the value returned by INITIAL when sending UPDATE or
 *                         TERMINATION. Generated when absent.
 * @param requestedOctets  optional quota to reserve; becomes Requested-Service-Unit / CC-Total-Octets
 * @param serviceContextId optional override of the configured Service-Context-Id
 */
public record ChargeRequest(
        @NotBlank(message = "msisdn is required")
        @Pattern(regexp = "\\d{5,15}", message = "msisdn must be 5 to 15 digits")
        String msisdn,

        ChargeRequestType requestType,

        @Min(value = 0, message = "requestNumber must be >= 0")
        Integer requestNumber,

        String sessionId,

        @PositiveOrZero(message = "requestedOctets must be >= 0")
        Long requestedOctets,

        String serviceContextId) {

    public ChargeRequestType requestTypeOrDefault() {
        return requestType == null ? ChargeRequestType.INITIAL : requestType;
    }

    public int requestNumberOrDefault() {
        return requestNumber == null ? 0 : requestNumber;
    }
}
