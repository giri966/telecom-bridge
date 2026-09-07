package com.telecombridge.gateway.charging;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body returned for a completed Diameter exchange, success or not.
 *
 * @param sessionId       Session-Id used on the wire; echo it back for UPDATE/TERMINATION
 * @param resultCode      Diameter Result-Code from the CCA (2001 = success)
 * @param resultText      symbolic name of the Result-Code
 * @param success         true for 2xxx result codes
 * @param grantedOctets   Granted-Service-Unit / CC-Total-Octets, present on success
 * @param validitySeconds Validity-Time, present on success
 * @param hopByHopId      Hop-by-Hop identifier of the CCR, handy for matching a PCAP
 * @param latencyMs       CCR-to-CCA round trip measured inside the gateway
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChargeResponse(
        String sessionId,
        long resultCode,
        String resultText,
        boolean success,
        Long grantedOctets,
        Long validitySeconds,
        long hopByHopId,
        long latencyMs) {
}
