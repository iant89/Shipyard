package dev.shipyard.common;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Cooperative cancellation for long-running deployment steps. */
public final class CancellationToken {
    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Cancelled");
        }
    }

    private volatile boolean cancelled;
    private final List<Runnable> hooks = new CopyOnWriteArrayList<>();

    public void cancel() {
        cancelled = true;
        hooks.forEach(h -> {
            try { h.run(); } catch (RuntimeException ignored) { }
        });
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void onCancel(Runnable hook) {
        hooks.add(hook);
        if (cancelled) hook.run();
    }

    public void throwIfCancelled() {
        if (cancelled) throw new CancelledException();
    }
}
