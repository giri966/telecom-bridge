package com.telecombridge.gateway.charging;

import com.telecombridge.diameter.DiameterMessage;
import com.telecombridge.gateway.diameter.DiameterClient;
import com.telecombridge.gateway.diameter.IdentifierGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

/**
 * One REST charge call = one CCR/CCA exchange. Fully non-blocking: the returned Mono
 * is backed by the CompletableFuture that the Diameter event loop completes.
 */
@Service
public class ChargingService {

    private static final Logger log = LoggerFactory.getLogger(ChargingService.class);

    private final DiameterClient client;
    private final CcrMapper mapper;
    private final IdentifierGenerator ids;

    public ChargingService(DiameterClient client, CcrMapper mapper, IdentifierGenerator ids) {
        this.client = client;
        this.mapper = mapper;
        this.ids = ids;
    }

    public Mono<ChargeResponse> charge(ChargeRequest request) {
        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? ids.newSessionId()
                : request.sessionId();
        DiameterMessage.Builder ccr = mapper.toCcr(request, sessionId);
        long start = System.nanoTime();

        return Mono.fromFuture(() -> client.send(ccr))
                .map(cca -> {
                    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    ChargeResponse response = mapper.toResponse(cca, sessionId, latencyMs);
                    if (log.isDebugEnabled()) {
                        log.debug("charge msisdn={} type={} session={} hbh=0x{} result={} in {} ms",
                                request.msisdn(), request.requestTypeOrDefault(), sessionId,
                                Long.toHexString(response.hopByHopId()), response.resultCode(), latencyMs);
                    }
                    return response;
                });
    }
}
