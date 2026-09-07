package com.telecombridge.gateway.diameter;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Produces the three identifiers a Diameter client must mint (RFC 6733 sections 3 and 8.8).
 *
 * <ul>
 *   <li><b>Hop-by-Hop</b>: unique per outstanding request on this connection. A random
 *       start plus a monotonically increasing counter, so ids never collide with a
 *       previous process instance's still-in-flight requests after a restart.</li>
 *   <li><b>End-to-End</b>: upper 12 bits = low 12 bits of the current time in seconds,
 *       lower 20 bits = random start plus counter. Must stay unique for at least 4 minutes.</li>
 *   <li><b>Session-Id</b>: {@code <originHost>;<high32>;<low32>}, where high32 is the
 *       boot time in seconds and low32 a counter. Globally unique and eternally unique.</li>
 * </ul>
 */
public final class IdentifierGenerator {

    private final String originHost;
    private final AtomicInteger hopByHop;
    private final AtomicInteger endToEnd;
    private final AtomicLong sessionCounter = new AtomicLong();
    private final long bootSeconds = System.currentTimeMillis() / 1000L;

    public IdentifierGenerator(String originHost) {
        this.originHost = originHost;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        this.hopByHop = new AtomicInteger(rnd.nextInt());
        int timeBits = (int) (bootSeconds & 0xFFF) << 20;
        this.endToEnd = new AtomicInteger(timeBits | (rnd.nextInt() & 0xFFFFF));
    }

    public int nextHopByHop() {
        return hopByHop.incrementAndGet();
    }

    public int nextEndToEnd() {
        return endToEnd.incrementAndGet();
    }

    public String newSessionId() {
        return originHost + ";" + bootSeconds + ";" + sessionCounter.incrementAndGet();
    }
}
