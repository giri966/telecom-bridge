package com.telecombridge.diameter;

/**
 * Result-Code values (RFC 6733 section 7.1, RFC 4006 section 9.1).
 *
 * <ul>
 *   <li>1xxx informational, 2xxx success</li>
 *   <li>3xxx protocol errors (E bit set, may be handled hop-by-hop)</li>
 *   <li>4xxx transient failures (retry later may succeed)</li>
 *   <li>5xxx permanent failures</li>
 * </ul>
 */
public final class ResultCode {

    public static final long DIAMETER_SUCCESS = 2001;
    public static final long DIAMETER_LIMITED_SUCCESS = 2002;

    public static final long DIAMETER_COMMAND_UNSUPPORTED = 3001;
    public static final long DIAMETER_UNABLE_TO_DELIVER = 3002;
    public static final long DIAMETER_TOO_BUSY = 3004;
    public static final long DIAMETER_APPLICATION_UNSUPPORTED = 3007;
    public static final long DIAMETER_UNKNOWN_PEER = 3010;

    public static final long DIAMETER_CREDIT_LIMIT_REACHED = 4012;

    public static final long DIAMETER_MISSING_AVP = 5005;
    public static final long DIAMETER_NO_COMMON_APPLICATION = 5010;
    public static final long DIAMETER_UNABLE_TO_COMPLY = 5012;
    public static final long DIAMETER_USER_UNKNOWN = 5030;
    public static final long DIAMETER_RATING_FAILED = 5031;

    private ResultCode() {
    }

    public static boolean isSuccess(long code) {
        return code >= 2000 && code < 3000;
    }

    public static boolean isProtocolError(long code) {
        return code >= 3000 && code < 4000;
    }

    public static boolean isTransientFailure(long code) {
        return code >= 4000 && code < 5000;
    }

    public static boolean isPermanentFailure(long code) {
        return code >= 5000 && code < 6000;
    }

    /** Symbolic name for logs and REST responses, or "DIAMETER_RESULT_&lt;code&gt;" if unknown. */
    public static String name(long code) {
        if (code == DIAMETER_SUCCESS) return "DIAMETER_SUCCESS";
        if (code == DIAMETER_LIMITED_SUCCESS) return "DIAMETER_LIMITED_SUCCESS";
        if (code == DIAMETER_COMMAND_UNSUPPORTED) return "DIAMETER_COMMAND_UNSUPPORTED";
        if (code == DIAMETER_UNABLE_TO_DELIVER) return "DIAMETER_UNABLE_TO_DELIVER";
        if (code == DIAMETER_TOO_BUSY) return "DIAMETER_TOO_BUSY";
        if (code == DIAMETER_APPLICATION_UNSUPPORTED) return "DIAMETER_APPLICATION_UNSUPPORTED";
        if (code == DIAMETER_UNKNOWN_PEER) return "DIAMETER_UNKNOWN_PEER";
        if (code == DIAMETER_CREDIT_LIMIT_REACHED) return "DIAMETER_CREDIT_LIMIT_REACHED";
        if (code == DIAMETER_MISSING_AVP) return "DIAMETER_MISSING_AVP";
        if (code == DIAMETER_NO_COMMON_APPLICATION) return "DIAMETER_NO_COMMON_APPLICATION";
        if (code == DIAMETER_UNABLE_TO_COMPLY) return "DIAMETER_UNABLE_TO_COMPLY";
        if (code == DIAMETER_USER_UNKNOWN) return "DIAMETER_USER_UNKNOWN";
        if (code == DIAMETER_RATING_FAILED) return "DIAMETER_RATING_FAILED";
        return "DIAMETER_RESULT_" + code;
    }
}
