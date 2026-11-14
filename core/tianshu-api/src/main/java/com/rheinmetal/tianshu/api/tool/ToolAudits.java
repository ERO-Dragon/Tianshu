package com.rheinmetal.tianshu.api.tool;

import java.util.Objects;

/** The audit sink holder and the one place that emits a write event. */
public final class ToolAudits {

    private static final System.Logger LOGGER = System.getLogger("tianshu.tools");
    private static final int MAX_ARGS = 512;

    private static final ToolAudit DEFAULT = event -> LOGGER.log(System.Logger.Level.INFO,
            "write tool {0} by {1} ({2}): ok={3} args={4}",
            event.toolId(), event.callerId(), event.callerKind(), event.ok(), event.args());

    private static volatile ToolAudit sink = DEFAULT;

    private ToolAudits() {
    }

    public static void install(ToolAudit audit) {
        sink = Objects.requireNonNull(audit, "audit");
    }

    public static void uninstall() {
        sink = DEFAULT;
    }

    static void write(ToolCaller caller, TianshuTool<?, ?> tool, String args, boolean ok) {
        String shown = args == null ? "{}" : args;
        if (shown.length() > MAX_ARGS) {
            shown = shown.substring(0, MAX_ARGS);
        }
        sink.record(new ToolAudit.Event(caller.id(), caller.kind(), tool.id(), tool.category(), shown, ok,
                System.currentTimeMillis()));
    }
}
