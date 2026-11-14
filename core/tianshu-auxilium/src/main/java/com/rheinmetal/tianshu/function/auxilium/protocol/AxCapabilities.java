package com.rheinmetal.tianshu.function.auxilium.protocol;

/**
 * The capabilities the assistant provides.
 *
 * <p>Owned by this module: an event producer that talks to the assistant hard-depends on it and imports this
 * class, while a mod that only detects its presence uses the id as a string.</p>
 */
public final class AxCapabilities {

    public static final String AX_DIALOGUE_INPUT = "AX.DIALOGUE_INPUT";
    public static final String AX_CONTEXT_INPUT = "AX.CONTEXT_INPUT";
    public static final String AX_EVENT_INPUT = "AX.EVENT_INPUT";

    private AxCapabilities() {
    }
}
