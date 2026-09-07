package com.telecombridge.diameter;

/**
 * Diameter command codes used on the Ro/Gy interface.
 *
 * <p>The same code is used for the request and the answer; the R bit in the
 * command flags distinguishes them (RFC 6733 section 3).
 */
public final class CommandCode {

    /** Capabilities-Exchange-Request / -Answer (RFC 6733 section 5.3). */
    public static final int CAPABILITIES_EXCHANGE = 257;
    /** Device-Watchdog-Request / -Answer (RFC 6733 section 5.5). */
    public static final int DEVICE_WATCHDOG = 280;
    /** Disconnect-Peer-Request / -Answer (RFC 6733 section 5.4). */
    public static final int DISCONNECT_PEER = 282;
    /** Credit-Control-Request / -Answer (RFC 4006 section 3). */
    public static final int CREDIT_CONTROL = 272;

    private CommandCode() {
    }

    /** Human readable short name such as "CCR" or "CEA", used in logs. */
    public static String name(int code, boolean request) {
        String base = switch (code) {
            case CAPABILITIES_EXCHANGE -> "CE";
            case DEVICE_WATCHDOG -> "DW";
            case DISCONNECT_PEER -> "DP";
            case CREDIT_CONTROL -> "CC";
            default -> "CMD" + code;
        };
        return base + (request ? "R" : "A");
    }
}
