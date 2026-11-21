package com.rheinmetal.tianshu.dubhe.protocol;

/**
 * The capabilities the assistant provides.
 *
 * <p>Owned by this module: an event producer that talks to the assistant hard-depends on it and imports this
 * class, while a mod that only detects its presence uses the id as a string.</p>
 */
public final class DubheCapabilities {

    public static final String DUBHE_DIALOGUE_INPUT = "DUBHE.DIALOGUE_INPUT";
    public static final String DUBHE_CONTEXT_INPUT = "DUBHE.CONTEXT_INPUT";
    public static final String DUBHE_EVENT_INPUT = "DUBHE.EVENT_INPUT";

    private DubheCapabilities() {
    }
}
