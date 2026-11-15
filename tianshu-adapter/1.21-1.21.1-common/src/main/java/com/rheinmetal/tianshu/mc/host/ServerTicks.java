package com.rheinmetal.tianshu.mc.host;

import com.rheinmetal.tianshu.api.assembly.ServerTickPort;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The adapter's tick source: one process-wide queue that the loader drains once per server tick.
 *
 * <p>Tasks scheduled while the queue is being drained wait for the next tick, so a self-rescheduling task can never
 * spin inside one tick.</p>
 */
public final class ServerTicks implements ServerTickPort {

    public static final ServerTicks INSTANCE = new ServerTicks();

    private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();

    private ServerTicks() {
    }

    @Override
    public Runnable onNextTick(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        pending.add(task);
        return () -> pending.remove(task);
    }

    /** Called by the loader once per server tick, on the server thread. */
    public void fire() {
        for (int remaining = pending.size(); remaining > 0; remaining--) {
            Runnable task = pending.poll();
            if (task != null) {
                task.run();
            }
        }
    }

    /** Drops everything queued; used when the server stops so a later server does not inherit stale work. */
    public void clear() {
        pending.clear();
    }
}