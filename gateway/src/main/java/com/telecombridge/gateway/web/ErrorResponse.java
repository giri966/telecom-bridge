package com.telecombridge.gateway.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Uniform error body for 4xx/5xx responses. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String error, String message, Long hopByHopId, List<String> details) {

    static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, null, null);
    }
}
