package com.telecombridge.gateway.web;

import com.telecombridge.gateway.charging.ChargeRequest;
import com.telecombridge.gateway.charging.ChargeResponse;
import com.telecombridge.gateway.charging.ChargingService;
import com.telecombridge.gateway.diameter.DiameterTimeoutException;
import com.telecombridge.gateway.diameter.DiameterUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** HTTP contract of the endpoint with the Diameter side mocked out. */
@WebFluxTest(ChargeController.class)
@Import(GatewayExceptionHandler.class)
class ChargeControllerTest {

    @Autowired
    WebTestClient web;

    @MockitoBean
    ChargingService chargingService;

    private static final String BODY = "{\"msisdn\":\"919876543210\",\"requestType\":\"INITIAL\",\"requestedOctets\":1048576}";

    @Test
    void successfulChargeReturns200WithGrant() {
        when(chargingService.charge(any(ChargeRequest.class))).thenReturn(Mono.just(
                new ChargeResponse("s;1;1", 2001, "DIAMETER_SUCCESS", true, 1048576L, 3600L, 42L, 61L)));

        web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON).bodyValue(BODY)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.resultCode").isEqualTo(2001)
                .jsonPath("$.resultText").isEqualTo("DIAMETER_SUCCESS")
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.grantedOctets").isEqualTo(1048576)
                .jsonPath("$.hopByHopId").isEqualTo(42)
                .jsonPath("$.sessionId").isEqualTo("s;1;1");
    }

    @Test
    void diameterFailureResultReturns422WithCode() {
        when(chargingService.charge(any())).thenReturn(Mono.just(
                new ChargeResponse("s", 4012, "DIAMETER_CREDIT_LIMIT_REACHED", false, null, null, 7L, 60L)));

        web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON).bodyValue(BODY)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.resultCode").isEqualTo(4012)
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.grantedOctets").doesNotExist();
    }

    @Test
    void peerDownReturns503() {
        when(chargingService.charge(any())).thenReturn(Mono.error(
                new DiameterUnavailableException("Diameter peer localhost:3868 not connected (state CLOSED)")));

        web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON).bodyValue(BODY)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "2")
                .expectBody()
                .jsonPath("$.error").isEqualTo("DIAMETER_PEER_UNAVAILABLE")
                .jsonPath("$.message").value(m -> org.assertj.core.api.Assertions.assertThat(m.toString())
                        .contains("not connected"));
    }

    @Test
    void noAnswerReturns504WithHopByHop() {
        when(chargingService.charge(any())).thenReturn(Mono.error(
                new DiameterTimeoutException(42, Duration.ofSeconds(2))));

        web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON).bodyValue(BODY)
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody()
                .jsonPath("$.error").isEqualTo("DIAMETER_TIMEOUT")
                .jsonPath("$.hopByHopId").isEqualTo(42);
    }

    @Test
    void invalidMsisdnReturns400WithFieldDetail() {
        web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"msisdn\":\"abc\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.details[0]").value(d -> org.assertj.core.api.Assertions.assertThat(d.toString())
                        .startsWith("msisdn"));
    }

    @Test
    void malformedJsonReturns400() {
        web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{not json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("MALFORMED_REQUEST");
    }
}
