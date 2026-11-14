package com.rheinmetal.tianshu.protocol;

/**
 * The topics the kernel itself produces.
 *
 * <p>Only platform vocabulary lives here. A feature's topics belong to the module that produces them —
 * the module that produces them —
 * so deleting a module deletes its topic names too, and adding one never edits the kernel.</p>
 */
public final class ProtocolTopics {

    public static final String SYSTEM_RUNTIME_INTERRUPT = "SYSTEM.RUNTIME_INTERRUPT";
    public static final String MODULE_STATUS = "MODULE.STATUS";
    /** Raw world events as the platform observed them; the aggregation into windows belongs to the consumer. */
    public static final String WORLD_EVENT = "GAME.WORLD_EVENT";

    private ProtocolTopics() {
    }
}
