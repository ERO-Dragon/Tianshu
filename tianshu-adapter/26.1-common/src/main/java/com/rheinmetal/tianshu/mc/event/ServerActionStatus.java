package com.rheinmetal.tianshu.mc.event;

/**
 * What a loader adapter can say about the server's action channel.
 *
 * <p>The action client itself is loader-bound — one loader sends a payload through its own registrar — but the
 * event layer only needs three facts from it: what the server announced, whether it announced anything, and a
 * reset when the world goes away. Keeping those three behind an interface is what lets the event layer live in
 * the shared adapter instead of being written once per loader.</p>
 */
public interface ServerActionStatus {

    /** The enabled action ids the connected server announced, in a stable order; empty when none were announced. */
    String enabledActionsSnapshot();

    /** Whether the connected server announced an action catalog at all. */
    boolean catalogReceivedSnapshot();

    /** Drops what the previous session learned; called when the world goes away. */
    void reset();
}
