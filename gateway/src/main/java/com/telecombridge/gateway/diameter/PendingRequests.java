package com.telecombridge.gateway.diameter;

import com.telecombridge.diameter.DiameterMessage;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * The correlation table: outstanding Hop-by-Hop identifier to the future that the
 * REST layer is waiting on. Every entry leaves the map by exactly one of three
 * routes: the matching answer arrives, its timeout fires, or the connection drops.
 * That invariant is what keeps memory flat under sustained load.
 */
final class PendingRequests {

    /** One in-flight request. */
    record Pending(CompletableFuture<DiameterMessage> future, ScheduledFuture<?> timeout, long startNanos) {
    }

    private final ConcurrentHashMap<Integer, Pending> table;
    private final int capacity;

    PendingRequests(int capacity) {
        this.capacity = capacity;
        this.table = new ConcurrentHashMap<>(Math.min(capacity, 1 << 14));
    }

    int size() {
        return table.size();
    }

    boolean isFull() {
        return table.size() >= capacity;
    }

    /** Registers the request; returns false (and leaves the map untouched) if the id is already in use. */
    boolean register(int hopByHop, Pending pending) {
        return table.putIfAbsent(hopByHop, pending) == null;
    }

    /** Removes and returns the entry, or null when the answer is late (already timed out) or unknown. */
    Pending remove(int hopByHop) {
        Pending p = table.remove(hopByHop);
        if (p != null) {
            p.timeout().cancel(false);
        }
        return p;
    }

    /** Fails every outstanding request, e.g. when the socket closes. */
    int failAll(Throwable cause) {
        int failed = 0;
        Iterator<Map.Entry<Integer, Pending>> it = table.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Pending> e = it.next();
            it.remove();
            e.getValue().timeout().cancel(false);
            e.getValue().future().completeExceptionally(cause);
            failed++;
        }
        return failed;
    }
}
