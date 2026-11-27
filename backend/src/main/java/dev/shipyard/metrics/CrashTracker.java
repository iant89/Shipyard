package dev.shipyard.metrics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether a container is crash-looping from successive observations
 * of Docker's (cumulative) restart count.
 *
 * <ul>
 *   <li>Crashed: the container is gone or has exited, or it restarted at
 *       least {@code threshold} times within {@code window}.</li>
 *   <li>Recovered: it is running and has not restarted for {@code stableFor}.
 *       Without this hysteresis a crash loop with Docker's restart backoff
 *       would flip back to RUNNING between crashes.</li>
 * </ul>
 */
public final class CrashTracker {
    public enum Verdict { HEALTHY, CRASHED, UNCHANGED }

    private record State(int lastCount, Deque<Long> restarts, long lastRestartAt) {}

    private final int threshold;
    private final long windowMillis;
    private final long stableForMillis;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public CrashTracker(int threshold, long windowMillis, long stableForMillis) {
        this.threshold = threshold;
        this.windowMillis = windowMillis;
        this.stableForMillis = stableForMillis;
    }

    /**
     * @param key          deployment id
     * @param present      whether the container exists
     * @param running      whether it is running
     * @param exited       whether it has exited for good (not restarting)
     * @param restartCount Docker's cumulative restart count
     * @param currentlyCrashed whether the application is currently marked CRASHED
     */
    public Verdict observe(String key, boolean present, boolean running, boolean exited, int restartCount,
                           boolean currentlyCrashed, long now) {
        State prev = states.get(key);
        Deque<Long> restarts = prev == null ? new ArrayDeque<>() : prev.restarts();
        long lastRestartAt = prev == null ? 0 : prev.lastRestartAt();
        if (prev != null && restartCount > prev.lastCount()) {
            for (int i = 0; i < restartCount - prev.lastCount(); i++) restarts.addLast(now);
            lastRestartAt = now;
        }
        while (!restarts.isEmpty() && now - restarts.peekFirst() > windowMillis) restarts.removeFirst();
        states.put(key, new State(restartCount, restarts, lastRestartAt));

        if (currentlyCrashed && present && running && now - lastRestartAt >= stableForMillis) {
            restarts.clear();
            return Verdict.HEALTHY;
        }
        if (!present || (!running && exited) || restarts.size() >= threshold) {
            return currentlyCrashed ? Verdict.UNCHANGED : Verdict.CRASHED;
        }
        return Verdict.UNCHANGED;
    }

    public void forget(String key) {
        states.remove(key);
    }
}
