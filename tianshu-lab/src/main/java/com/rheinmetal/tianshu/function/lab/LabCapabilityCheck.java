package com.rheinmetal.tianshu.function.lab;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The bench's capability self-check: counts what the table holds, reads the built-in skill/resource/prompt and calls
 * one server read tool. It bypasses the agent entirely, so it answers "does the capability layer work?" without
 * involving the Tianshu turn loop.
 */
public final class LabCapabilityCheck {

    private static final ToolId INVENTORY = ToolId.of("tianshu", "inventory.inspect");

    private volatile String report = "";

    public String report() {
        return report;
    }

    /** Blocking; run it off the UI thread. */
    public void run() {
        StringBuilder out = new StringBuilder();
        Map<CapabilityKind, Integer> counts = new LinkedHashMap<>();
        for (CapabilityDescriptor descriptor : CapabilityCatalog.list()) {
            counts.merge(descriptor.kind(), 1, Integer::sum);
        }
        out.append("capabilities: ")
                .append("tool=").append(counts.getOrDefault(CapabilityKind.TOOL, 0))
                .append(" skill=").append(counts.getOrDefault(CapabilityKind.SKILL, 0))
                .append(" resource=").append(counts.getOrDefault(CapabilityKind.RESOURCE, 0))
                .append(" prompt=").append(counts.getOrDefault(CapabilityKind.PROMPT, 0))
                .append('\n');
        out.append(read("skill", CapabilityId.of("tianshu", "tianshu-tools"), new JsonObject(), 200));
        out.append(read("resource", CapabilityId.of("tianshu", "game-basics"), new JsonObject(), 120));
        JsonObject promptArgs = new JsonObject();
        promptArgs.addProperty("lines", "3");
        out.append(read("prompt", CapabilityId.of("tianshu", "concise"), promptArgs, 120));
        out.append(tool());
        report = out.toString();
    }

    private static String read(String label, CapabilityId id, JsonObject args, int limit) {
        try {
            JsonObject envelope = CapabilityCatalog.invoke(id, args, ToolCaller.player("lab", true), ToolSide.CLIENT)
                    .get(5, TimeUnit.SECONDS);
            if (!envelope.has("ok") || !envelope.get("ok").getAsBoolean()) {
                return label + " " + id + ": FAILED " + shortText(envelope) + '\n';
            }
            String text = envelope.has("text") ? envelope.get("text").getAsString() : "";
            return label + " " + id + ": OK (" + Math.min(text.length(), limit) + " chars) "
                    + oneLine(text, limit) + '\n';
        } catch (Exception failure) {
            return label + " " + id + ": FAILED " + failure.getClass().getSimpleName() + '\n';
        }
    }

    private static String tool() {
        try {
            JsonObject envelope = TianshuTools.callJson(INVENTORY, new JsonObject(), ToolCaller.player("lab", true),
                            ToolSide.CLIENT)
                    .get(5, TimeUnit.SECONDS);
            boolean ok = envelope.has("ok") && envelope.get("ok").getAsBoolean();
            return "tool " + INVENTORY + ": " + (ok ? "OK" : "FAILED " + shortText(envelope)) + '\n';
        } catch (Exception failure) {
            return "tool " + INVENTORY + ": FAILED " + failure.getClass().getSimpleName() + '\n';
        }
    }

    private static String shortText(JsonObject envelope) {
        if (envelope.has("code")) {
            return envelope.get("code").getAsString();
        }
        return envelope.toString();
    }

    private static String oneLine(String text, int limit) {
        String line = text.replace('\n', ' ').strip();
        return line.length() <= limit ? line : line.substring(0, limit) + "…";
    }
}
