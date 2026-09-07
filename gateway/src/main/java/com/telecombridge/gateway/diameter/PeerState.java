package com.telecombridge.gateway.diameter;

/**
 * Reduced client-side peer state machine (RFC 6733 section 5.6).
 *
 * <pre>
 *   CLOSED --connect()--> CONNECTING --TCP up, CER sent--> WAIT_CEA --CEA 2001--> OPEN
 *   any state --socket closed / CEA failure / watchdog miss--> CLOSED --reconnectDelay--> CONNECTING
 *   OPEN --stop(), DPR sent--> CLOSING --DPA or timeout--> CLOSED (no reconnect)
 * </pre>
 */
public enum PeerState {
    CLOSED,
    CONNECTING,
    WAIT_CEA,
    OPEN,
    CLOSING
}
