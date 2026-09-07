package com.telecombridge.gateway.diameter;

import java.time.Duration;

/**
 * The CCR was written to the peer but no CCA with the same Hop-by-Hop identifier
 * arrived within the configured timeout. Maps to HTTP 504 Gateway Timeout.
 */
public class DiameterTimeoutException extends DiameterException {

    private final long hopByHopId;

    public DiameterTimeoutException(int hopByHopId, Duration timeout) {
        super(String.format("No answer received for hop-by-hop 0x%08x within %d ms", hopByHopId,
                timeout.toMillis()));
        this.hopByHopId = Integer.toUnsignedLong(hopByHopId);
    }

    /** Unsigned Hop-by-Hop identifier of the request that timed out. */
    public long hopByHopId() {
        return hopByHopId;
    }
}
