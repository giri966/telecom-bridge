package com.telecombridge.gateway.charging;

import com.telecombridge.diameter.CcRequestType;

/** REST-facing request type, mapped one-to-one onto CC-Request-Type. */
public enum ChargeRequestType {
    INITIAL(CcRequestType.INITIAL_REQUEST),
    UPDATE(CcRequestType.UPDATE_REQUEST),
    TERMINATION(CcRequestType.TERMINATION_REQUEST),
    EVENT(CcRequestType.EVENT_REQUEST);

    private final CcRequestType diameter;

    ChargeRequestType(CcRequestType diameter) {
        this.diameter = diameter;
    }

    public CcRequestType toDiameter() {
        return diameter;
    }
}
