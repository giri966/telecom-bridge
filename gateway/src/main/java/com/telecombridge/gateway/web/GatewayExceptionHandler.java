package com.telecombridge.gateway.web;

import com.telecombridge.gateway.diameter.DiameterException;
import com.telecombridge.gateway.diameter.DiameterTimeoutException;
import com.telecombridge.gateway.diameter.DiameterUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.util.List;

/** Turns Diameter-side failures and bad input into the documented HTTP statuses. */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(DiameterUnavailableException.class)
    public ResponseEntity<ErrorResponse> unavailable(DiameterUnavailableException e) {
        log.warn("503: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "2")
                .body(ErrorResponse.of("DIAMETER_PEER_UNAVAILABLE", e.getMessage()));
    }

    @ExceptionHandler(DiameterTimeoutException.class)
    public ResponseEntity<ErrorResponse> timeout(DiameterTimeoutException e) {
        log.warn("504: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(new ErrorResponse("DIAMETER_TIMEOUT", e.getMessage(), e.hopByHopId(), null));
    }

    @ExceptionHandler(DiameterException.class)
    public ResponseEntity<ErrorResponse> diameter(DiameterException e) {
        log.error("502: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of("DIAMETER_ERROR", e.getMessage()));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> validation(WebExchangeBindException e) {
        List<String> details = e.getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_FAILED", "Request body is invalid", null, details));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ErrorResponse> malformed(ServerWebInputException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("MALFORMED_REQUEST", e.getReason()));
    }

    /** 404s, 405s, 415s and friends raised by the framework keep their own status. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(ErrorResponse.of(HttpStatus.valueOf(e.getStatusCode().value()).name(), e.getReason()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("500: unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "Unexpected error"));
    }
}
