package com.rheinmetal.tianshu.api.tool;

/**
 * Records a write that actually executed. The default sink writes one line to the platform log; a server or a
 * mod can install another sink (a file, a database, a test double).
 *
 * <p>Only server-side writes are recorded: they are the ones that change the world. Reads are not audited, and a
 * call that was refused before execution never reaches the sink.</p>
 */
public interface ToolAudit {

    record Event(String callerId, ToolCaller.Kind callerKind, ToolId toolId, ToolCategory category,
                 String args, boolean ok, long epochMillis) {
    }

    void record(Event event);
}
