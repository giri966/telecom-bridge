package com.telecombridge.diameter;

/** Thrown when bytes on the wire do not form a valid Diameter message or AVP. */
public class DiameterDecodeException extends RuntimeException {

    public DiameterDecodeException(String message) {
        super(message);
    }
}
