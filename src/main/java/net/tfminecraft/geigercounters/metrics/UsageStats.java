package net.tfminecraft.geigercounters.metrics;

import java.util.concurrent.atomic.AtomicInteger;

// ====================================
// Tracks in-game usage counters for bStats charts.
// Counters are drained (reset to zero) each time bStats submits,
// so every chart value is the delta for that submission interval.
// ====================================
public class UsageStats {

    private static final UsageStats instance = new UsageStats();

    private final AtomicInteger sourcesCollected = new AtomicInteger();

    private UsageStats() {
    }

    public static UsageStats getInstance() {
        return instance;
    }

    public void recordSourceCollected() {
        sourcesCollected.incrementAndGet();
    }

    // Called by bStats: returns the count since the last submission and resets
    public int drainSourcesCollected() {
        return sourcesCollected.getAndSet(0);
    }
}
