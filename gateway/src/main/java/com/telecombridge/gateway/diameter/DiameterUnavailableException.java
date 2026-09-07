package com.telecombridge.gateway.diameter;

/**
 * The request could not be handed to the peer at all: no OPEN connection, the
 * connection dropped while the request was in flight, or the in-flight limit is
 * reached. Maps to HTTP 503 Service Unavailable.
 */
public class DiameterUnavailableException extends DiameterException {

    public DiameterUnavailableException(String message) {
        super(message);
    }

    public DiameterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
