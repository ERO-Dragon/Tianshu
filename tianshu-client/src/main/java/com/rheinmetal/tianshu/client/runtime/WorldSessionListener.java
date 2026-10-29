package com.rheinmetal.tianshu.client.runtime;

/**
 * Something that opens and closes with a world.
 *
 * <p>The kernel drives the session boundary; what happens inside it is the participant's business. A module
 * registers one of these through the composition root, so the runtime never names a module and adding or removing
 * a feature never edits it.</p>
 *
 * <p>Only ever called on the client thread, and always in pairs: an accepted start is followed by exactly one stop,
 * including the stop that rolls a failed start back.</p>
 */
public interface WorldSessionListener {

    /** Called when the world session is requested, before the core is ready: modules publish their startup state here. */
    void startWorldSession();

    /** Called when the world ends, and to roll back a start that could not complete. */
    void stopWorldSession();
}
