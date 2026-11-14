package com.rheinmetal.tianshu.api.assembly;

/**
 * Runtime states the platform needs to know about but does not own.
 *
 * <p>The platform dispatches key input; whether input should be swallowed right now is a product answer that a
 * module produces (it is "starting up, cannot serve yet"), and the platform must not learn that module's name to
 * ask. So the module writes the state here and the platform reads it — same direction as the signal buses, one
 * value instead of a stream.</p>
 *
 * <p>The state is global because there is one client and one input stream. {@link #clear()} exists for tests and
 * for session teardown.</p>
 */
public final class RuntimeGates {

    private static volatile boolean inputBlocked;

    private RuntimeGates() {
    }

    public static void setInputBlocked(boolean blocked) {
        inputBlocked = blocked;
    }

    public static boolean inputBlocked() {
        return inputBlocked;
    }

    public static void clear() {
        inputBlocked = false;
    }
}