package com.telecombridge.loadtest;

import java.util.concurrent.TimeUnit;

/**
 * Decides whether a scheduler tick should actually send a request.
 *
 * <p>The load generator paces itself with {@code scheduleAtFixedRate}, which keeps the
 * long-run average exactly on target because it anchors every tick to an absolute time
 * rather than to the end of the previous one. That is the behaviour we want, but it has a
 * dangerous edge: if the process is paused, the executor replays <b>every</b> missed tick
 * back to back as fast as it can, to "catch up".
 *
 * <p>That is not theoretical. An 83-minute run of this tool was left going overnight, the
 * laptop hit its 15-minute idle sleep timer after 34 minutes, and on wake the executor
 * fired roughly 296 000 missed ticks in a burst. In-flight requests went from 9 to 294 993
 * and the run was worthless. A load generator that turns a pause into a stampede measures
 * nothing useful and can take the system under test down with it.
 *
 * <p>So a tick is dropped when it is more than {@code maxLag} late. During a catch-up burst
 * every replayed tick is hugely late and gets dropped; as the tick index advances, its
 * scheduled time walks forward to the present, lag falls back under the threshold, and
 * normal pacing resumes on its own. The dropped slots are counted and reported, because a
 * run that paused is not a valid run at the requested rate and should never quietly report
 * a healthy-looking percentile.
 */
final class Pacer {

    /** A tick later than this is a catch-up replay, not a genuinely due request. */
    static final long DEFAULT_MAX_LAG_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final long startNanos;
    private final long periodNanos;
    private final long maxLagNanos;

    Pacer(long startNanos, long periodNanos, long maxLagNanos) {
        if (periodNanos <= 0) {
            throw new IllegalArgumentException("period must be positive");
        }
        this.startNanos = startNanos;
        this.periodNanos = periodNanos;
        this.maxLagNanos = maxLagNanos;
    }

    /** The wall-clock instant tick {@code index} was supposed to fire at. */
    long scheduledNanos(long index) {
        return startNanos + index * periodNanos;
    }

    /** How late tick {@code index} is; negative if it somehow ran early. */
    long lagNanos(long index, long nowNanos) {
        return nowNanos - scheduledNanos(index);
    }

    /**
     * True when this tick should send a request, false when it is a replay of a slot missed
     * during a stall and must be dropped.
     */
    boolean shouldFire(long index, long nowNanos) {
        return lagNanos(index, nowNanos) <= maxLagNanos;
    }
}
