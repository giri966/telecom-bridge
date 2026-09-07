package com.telecombridge.loadtest;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pacing rule that stops a paused generator from replaying every missed slot at once.
 * Reproduces, in pure arithmetic, the overnight failure described in {@link Pacer}.
 */
class PacerTest {

    private static final long PERIOD = TimeUnit.MILLISECONDS.toNanos(10);   // 100 TPS
    private static final long START = 1_000_000_000L;

    private final Pacer pacer = new Pacer(START, PERIOD, Pacer.DEFAULT_MAX_LAG_NANOS);

    @Test
    void ticksThatArriveOnTimeFire() {
        for (long i = 0; i < 1000; i++) {
            assertThat(pacer.shouldFire(i, pacer.scheduledNanos(i))).isTrue();
        }
    }

    @Test
    void ordinarySchedulerJitterStillFires() {
        // Windows quantises timed waits to a ~15.6 ms tick, so a slot routinely runs tens of
        // milliseconds late in normal operation. That must not be mistaken for a stall.
        long jitter = TimeUnit.MILLISECONDS.toNanos(40);
        assertThat(pacer.shouldFire(500, pacer.scheduledNanos(500) + jitter)).isTrue();
    }

    @Test
    void slotsMissedDuringALongPauseAreDropped() {
        // The real incident: the laptop slept for five hours after ~34 minutes of running.
        long sleepNanos = TimeUnit.HOURS.toNanos(5);
        long wakeMoment = pacer.scheduledNanos(204_000) + sleepNanos;

        // Every slot that came due during the sleep is hugely late and must not be replayed.
        for (long i = 204_000; i < 204_000 + 100_000; i++) {
            assertThat(pacer.shouldFire(i, wakeMoment))
                    .as("slot %d fell inside the pause", i)
                    .isFalse();
        }
    }

    @Test
    void pacingResumesByItselfOnceTheBacklogIsWalkedThrough() {
        long sleepNanos = TimeUnit.HOURS.toNanos(5);
        long wakeMoment = pacer.scheduledNanos(204_000) + sleepNanos;

        // Walk forward until a slot's own scheduled time reaches the moment we woke up.
        long firstSlotThatFires = -1;
        for (long i = 204_000; i < 204_000 + 3_000_000; i++) {
            if (pacer.shouldFire(i, wakeMoment)) {
                firstSlotThatFires = i;
                break;
            }
        }

        assertThat(firstSlotThatFires).isNotEqualTo(-1);
        // 5 hours at 100 TPS is 1.8 million slots; recovery lands there, within the 1 s tolerance.
        assertThat(firstSlotThatFires).isBetween(204_000L + 1_799_900L, 204_000L + 1_800_000L);
        assertThat(pacer.shouldFire(firstSlotThatFires + 1, wakeMoment + PERIOD)).isTrue();
    }

    @Test
    void lagIsReportedSoTheStallCanBeMeasured() {
        long lateBy = TimeUnit.MINUTES.toNanos(7);
        long lag = pacer.lagNanos(42, pacer.scheduledNanos(42) + lateBy);

        assertThat(TimeUnit.NANOSECONDS.toMinutes(lag)).isEqualTo(7);
    }

    @Test
    void rejectsANonPositivePeriod() {
        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> new Pacer(0, 0, Pacer.DEFAULT_MAX_LAG_NANOS)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
