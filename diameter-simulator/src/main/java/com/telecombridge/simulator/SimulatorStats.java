package com.telecombridge.simulator;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Lock-free counters, logged periodically so a long load run can be eyeballed from the console. */
public final class SimulatorStats {

    public final AtomicInteger activeConnections = new AtomicInteger();
    public final AtomicLong connectionsAccepted = new AtomicLong();
    public final AtomicLong cerReceived = new AtomicLong();
    public final AtomicLong dwrReceived = new AtomicLong();
    public final AtomicLong ccrReceived = new AtomicLong();
    public final AtomicLong ccaSent = new AtomicLong();
    public final AtomicLong ccaSuccess = new AtomicLong();
    public final AtomicLong ccaFailure = new AtomicLong();
    public final AtomicLong ccrDropped = new AtomicLong();
    public final AtomicLong protocolErrors = new AtomicLong();

    /**
     * Actual minus scheduled CCA delay, in microseconds: how faithfully this host is
     * honouring the configured 50–100 ms delay.
     *
     * <p>Worth watching, because it is a property of the operating system and not of this
     * code. Windows ticks its scheduler every 15.6 ms and rounds timed waits up to the next
     * tick, so on Windows 11 with JDK 21 this reads about 8 ms on average and 16 ms at p95,
     * no matter whether the delay is timed on a {@code ScheduledExecutorService} or on the
     * Netty event loop, and regardless of the widely repeated {@code Thread.sleep} trick for
     * raising timer resolution, which does nothing on this JDK (the A/B is in the README).
     * The peer therefore answers a little later than configured on Windows, which inflates
     * the peer half of the load-test breakdown but not the gateway's own overhead. On Linux,
     * including in the Docker image, this stays well under a millisecond.
     */
    public final AtomicLong delayOvershootSumMicros = new AtomicLong();
    public final AtomicLong delayOvershootMaxMicros = new AtomicLong();
    public final AtomicLong delaySamples = new AtomicLong();

    private long lastLoggedCcr = -1;

    public void recordDelayOvershoot(long micros) {
        delayOvershootSumMicros.addAndGet(micros);
        delaySamples.incrementAndGet();
        delayOvershootMaxMicros.accumulateAndGet(micros, Math::max);
    }

    /** True when something changed since the previous call, so idle periods stay quiet in the log. */
    public synchronized boolean changedSinceLastLog() {
        long now = ccrReceived.get() + connectionsAccepted.get();
        boolean changed = now != lastLoggedCcr;
        lastLoggedCcr = now;
        return changed;
    }

    @Override
    public String toString() {
        return "connections(active=" + activeConnections.get() + ", total=" + connectionsAccepted.get()
                + ") cer=" + cerReceived.get() + " dwr=" + dwrReceived.get()
                + " ccr=" + ccrReceived.get() + " cca=" + ccaSent.get()
                + " (ok=" + ccaSuccess.get() + ", fail=" + ccaFailure.get() + ", dropped=" + ccrDropped.get()
                + ") protocolErrors=" + protocolErrors.get()
                + " delayOvershoot(avg=" + String.format("%.1f", avgOvershootMillis())
                + "ms, max=" + String.format("%.1f", delayOvershootMaxMicros.get() / 1000.0) + "ms)";
    }

    public double avgOvershootMillis() {
        long n = delaySamples.get();
        return n == 0 ? 0 : delayOvershootSumMicros.get() / 1000.0 / n;
    }
}
