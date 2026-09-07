package com.telecombridge.gateway.web;

import com.telecombridge.gateway.charging.ChargeRequest;
import com.telecombridge.gateway.charging.ChargeResponse;
import com.telecombridge.gateway.charging.ChargingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * HTTP status mapping:
 * <ul>
 *   <li>200 the peer answered with a 2xxx Result-Code</li>
 *   <li>422 the peer answered, but with a 3xxx/4xxx/5xxx Result-Code (body carries the code)</li>
 *   <li>400 payload failed validation</li>
 *   <li>503 no OPEN Diameter connection, or too many requests in flight</li>
 *   <li>504 the CCR was sent but no CCA arrived in time</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class ChargeController {

    private final ChargingService chargingService;

    public ChargeController(ChargingService chargingService) {
        this.chargingService = chargingService;
    }

    @PostMapping(value = "/charge", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ChargeResponse>> charge(@Valid @RequestBody ChargeRequest request) {
        return chargingService.charge(request)
                .map(response -> ResponseEntity
                        .status(response.success() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(response));
    }
}
