package com.rheinmetal.tianshu.lab;

import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.api.entity.BodyFailure;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
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

    private volatile String toolId = "minecraft:inventory.inspect";
    private volatile String argsJson = "{}";
    private final DiagnosticSink diagnostics;
    private volatile String result = "";
    private volatile boolean running;
    private volatile BodyFailure bodyFailure;

    public LabToolConsole(DiagnosticSink diagnostics) {
        this.diagnostics = diagnostics == null ? DiagnosticSink.NOOP : diagnostics;
    }

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

    public UiText displayResult() {
        BodyFailure reason = bodyFailure;
        return reason == null ? UiText.literal(result) : UiText.key(reason.translationKey());
    }

    public boolean running() {
        return running;
    }

    private void publish(ToolId id, JsonElement envelope, Throwable failure) {
        var attributes = new java.util.LinkedHashMap<String, String>();
        attributes.put("tool", id.toString());
        if (failure != null) {
            attributes.put("ok", "false");
            attributes.put("error", failure.getClass().getSimpleName());
            String message = failure.getMessage();
            attributes.put("message", message == null ? "" : truncate(message));
        } else if (envelope != null && envelope.isJsonObject()) {
            var object = envelope.getAsJsonObject();
            attributes.put("ok", object.has("ok") ? object.get("ok").getAsString() : "?");
            if (object.has("code")) {
                attributes.put("code", object.get("code").getAsString());
            }
            if (object.has("message")) {
                attributes.put("message", truncate(object.get("message").getAsString()));
            }
            attributes.put("result", truncate(object.toString()));
        }
        try {
            diagnostics.publish(DiagnosticEvent.now(LabModule.MODULE_ID, "tool.console",
                    DiagnosticSeverity.INFO, DiagnosticPrivacy.REDACTED, attributes));
        } catch (RuntimeException ignored) {
            // Diagnostics must not change the tool result.
        }
    }

    private static String truncate(String value) {
        return value.length() <= 2000 ? value : value.substring(0, 2000) + "…";
    }

    /** Runs once; the status row shows the envelope (or why it could not be parsed). */
    public void run() {
        if (running) {
            return;
        }
        bodyFailure = null;
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
                    if (failure == null && envelope.has("message") && envelope.has("ok") && !envelope.get("ok").getAsBoolean()) {
                        bodyFailure = BodyFailure.fromId(envelope.get("message").getAsString()).orElse(null);
                    }
                    running = false;
                    publish(id, envelope, failure);
                });
    }
}
