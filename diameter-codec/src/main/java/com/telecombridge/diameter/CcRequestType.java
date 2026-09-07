package com.telecombridge.diameter;

/** CC-Request-Type enumerated values (RFC 4006 section 8.3). */
public enum CcRequestType {
    INITIAL_REQUEST(1),
    UPDATE_REQUEST(2),
    TERMINATION_REQUEST(3),
    EVENT_REQUEST(4);

    private final int code;

    CcRequestType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static CcRequestType fromCode(int code) {
        for (CcRequestType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown CC-Request-Type " + code);
    }
}
