package com.telecombridge.diameter;

/** Subscription-Id-Type enumerated values (RFC 4006 section 8.47). */
public enum SubscriptionIdType {
    /** E.164 number, i.e. an MSISDN. */
    END_USER_E164(0),
    END_USER_IMSI(1),
    END_USER_SIP_URI(2),
    END_USER_NAI(3),
    END_USER_PRIVATE(4);

    private final int code;

    SubscriptionIdType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static SubscriptionIdType fromCode(int code) {
        for (SubscriptionIdType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown Subscription-Id-Type " + code);
    }
}
