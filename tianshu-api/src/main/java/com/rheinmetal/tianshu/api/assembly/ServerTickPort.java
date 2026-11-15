package com.rheinmetal.tianshu.api.assembly;

/**
 * A server-tick heartbeat a game-side adapter can offer, so long-running body work can finish on the server thread
 * instead of blocking it.
 *
 * <p>One-shot: the task runs on the next tick and never again unless it schedules itself. The returned runnable
 * cancels a task that has not run yet.</p>
 */
public interface ServerTickPort {

    Runnable onNextTick(Runnable task);
}