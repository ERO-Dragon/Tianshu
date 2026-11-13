package com.rheinmetal.tianshu.api.tool;

/**
 * How much an agent may do on its own. It is orthogonal to {@link ToolCategory} (what the tool touches) and
 * {@link ToolPermission} (who may call it): autonomy only decides whether a write still needs the player.
 *
 * <ul>
 *   <li>{@link #READ_ONLY}: write tools are hidden and refused; the agent may only suggest.</li>
 *   <li>{@link #CONFIRM}: the agent may ask; every write is confirmed by the player before it runs.</li>
 *   <li>{@link #FULL}: the agent may run a write without a per-call prompt. Operator and the per-tool switch still
 *       apply — "full" is full autonomy, not full permission.</li>
 * </ul>
 */
public enum ToolAutonomy {

    READ_ONLY,
    CONFIRM,
    FULL;

    public boolean mayWrite() {
        return this != READ_ONLY;
    }
}
