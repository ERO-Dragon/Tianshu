package com.rheinmetal.tianshu.api.tool;

/**
 * How much an agent may do on its own. It is a <b>risk tier</b>, orthogonal to {@link ToolCategory} (what the tool
 * touches) and {@link ToolPermission} (who may call it).
 *
 * <ul>
 *   <li>{@link #READ_ONLY}: reads only; writes are hidden and refused.</li>
 *   <li>{@link #CONSERVATIVE}: actions a player could perform in survival without cheats — press a button, open a
 *       door, move items between a container and the inventory, craft at a table, feed a furnace. No commands, no
 *       creative-only effects, no teleport or item grants.</li>
 *   <li>{@link #FULL}: the privileged end — commands, teleport, spawning, item grants and other creative-only
 *       effects. It still requires {@link ToolPermission#OPERATOR} and the per-tool switch.</li>
 * </ul>
 */
public enum ToolAutonomy {

    READ_ONLY(0),
    CONSERVATIVE(1),
    FULL(2);

    private final int rank;

    ToolAutonomy(int rank) {
        this.rank = rank;
    }

    /** Whether this mode permits a tool that requires {@code required}. */
    public boolean permits(ToolAutonomy required) {
        return rank >= required.rank;
    }

    public boolean mayWrite() {
        return this != READ_ONLY;
    }
}
