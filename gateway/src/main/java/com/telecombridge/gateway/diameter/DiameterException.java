package com.telecombridge.gateway.diameter;

/** Base class for failures between the gateway and the Diameter peer. */
public class DiameterException extends RuntimeException {

    public DiameterException(String message) {
        super(message);
    }

    public DiameterException(String message, Throwable cause) {
        super(message, cause);
    }
}
