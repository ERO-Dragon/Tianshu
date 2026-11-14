package com.rheinmetal.tianshu.function.lab;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;

/**
 * The bench's tool console: type a tool id and a JSON argument object, run it, see the envelope.
 *
 * <p>It is the one place a write tool can be exercised without the agent. Calls travel the normal path — the
 * caller is a player identity, the server still decides permission and the per-tool switch — so what the console
 * shows is what production would do.</p>
 */
public final class LabToolConsole {

    private volatile String toolId = "tianshu:inventory.inspect";
    private volatile String argsJson = "{}";
    private volatile String result = "";
    private volatile boolean running;

    public String toolId() {
        return toolId;
    }

    public void setToolId(String value) {
        toolId = value == null ? "" : value.trim();
    }

    public String argsJson() {
        return argsJson;
    }

    public void setArgsJson(String value) {
        argsJson = value == null || value.isBlank() ? "{}" : value.trim();
    }

    public String result() {
        return result;
    }

    public boolean running() {
        return running;
    }

    /** Runs once; the status row shows the envelope (or why it could not be parsed). */
    public void run() {
        if (running) {
            return;
        }
        ToolId id;
        try {
            id = ToolId.parse(toolId);
        } catch (RuntimeException malformed) {
            result = "malformed tool id: " + toolId;
            return;
        }
        JsonElement args;
        try {
            args = JsonParser.parseString(argsJson);
        } catch (RuntimeException malformed) {
            result = "malformed arguments: " + malformed.getMessage();
            return;
        }
        running = true;
        result = "running " + id + "…";
        TianshuTools.callJson(id, args, ToolCaller.player("lab", true), ToolSide.CLIENT)
                .whenComplete((envelope, failure) -> {
                    result = failure == null ? envelope.toString() : String.valueOf(failure);
                    running = false;
                });
    }
}
