package com.rheinmetal.tianshu.api.tool;

/**
 * What a tool does and where it has to run.
 *
 * <p>The category decides the default policy: read-only capabilities may be on by default, while anything that
 * changes the world starts disabled and needs an explicit opt-in. See {@link ToolPolicy}.</p>
 */
public enum ToolCategory {

    /** Pure client-side query; no world change and no server round trip. */
    LOCAL,

    /** Read-only query that only the authoritative server can answer. */
    SERVER_READ,

    /** A world change, executed by the server component. Disabled by default. */
    SERVER_WRITE;

    public boolean isServerSide() {
        return this != LOCAL;
    }
}