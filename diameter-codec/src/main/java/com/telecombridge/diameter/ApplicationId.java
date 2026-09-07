package com.telecombridge.diameter;

/** Diameter application identifiers carried in the message header and in Auth-Application-Id. */
public final class ApplicationId {

    /** Diameter common messages (CER/CEA, DWR/DWA, DPR/DPA). */
    public static final long BASE = 0;
    /** Diameter Credit-Control application (RFC 4006), i.e. the Ro/Gy interface. */
    public static final long CREDIT_CONTROL = 4;

    private ApplicationId() {
    }
}
