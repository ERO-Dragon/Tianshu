package com.rheinmetal.tianshu.function.lab;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The bench's capability self-check: counts what the table holds, reads the built-in skill/resource/prompt and calls
 * one server read tool. It bypasses the agent entirely, so it answers "does the capability layer work?" without
 * involving the Tianshu turn loop.
 */
public final class LabCapabilityCheck {

    private static final System.Logger LOGGER = System.getLogger("tianshu.lab");
    private static final ToolId INVENTORY = ToolId.of("tianshu", "inventory.inspect");

    private final DiagnosticSink diagnostics;
    private volatile String report = "";

    public LabCapabilityCheck(DiagnosticSink diagnostics) {
        this.diagnostics = diagnostics == null ? DiagnosticSink.NOOP : diagnostics;
    }

    public String report() {
        return report;
    }

    /** Blocking; run it off the UI thread. */
    public void run() {
        Map<CapabilityKind, Integer> counts = new LinkedHashMap<>();
        for (CapabilityDescriptor descriptor : CapabilityCatalog.list()) {
            counts.merge(descriptor.kind(), 1, Integer::sum);
        }
        String countLine = "capabilities: "
                + "tool=" + counts.getOrDefault(CapabilityKind.TOOL, 0)
                + " skill=" + counts.getOrDefault(CapabilityKind.SKILL, 0)
                + " resource=" + counts.getOrDefault(CapabilityKind.RESOURCE, 0)
                + " prompt=" + counts.getOrDefault(CapabilityKind.PROMPT, 0);
        String skill = read("skill", CapabilityId.of("tianshu", "tianshu-tools"), new JsonObject(), 200);
        String runtimeSkill = runtimeSkill();
        String resource = read("resource", CapabilityId.of("tianshu", "game-basics"), new JsonObject(), 120);
        JsonObject promptArgs = new JsonObject();
        promptArgs.addProperty("lines", "3");
        String prompt = read("prompt", CapabilityId.of("tianshu", "concise"), promptArgs, 120);
        String tool = tool();
        report = String.join("\n", List.of(countLine, skill, resource, prompt, tool, runtimeSkill));

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("counts", countLine);
        attributes.put("skill", skill);
        attributes.put("resource", resource);
        attributes.put("prompt", prompt);
        attributes.put("tool", tool);
        attributes.put("runtimeSkill", runtimeSkill);
        try {
            diagnostics.publish(DiagnosticEvent.now(LabModule.MODULE_ID, "capability.self_check",
                    DiagnosticSeverity.INFO, DiagnosticPrivacy.PUBLIC, attributes));
        } catch (RuntimeException ignored) {
            // Diagnostics is observability; a broken sink must not hide the UI result.
        }
        LOGGER.log(System.Logger.Level.INFO, "capability self-check:\n{0}", report);
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

    private static String runtimeSkill() {
        CapabilityId id = CapabilityId.of("lab", "runtime-skill");
        CapabilityCatalog.unregister(id);
        try {
            JsonObject attributes = new JsonObject();
            attributes.addProperty("name", "Runtime skill");
            CapabilityCatalog.register(new CapabilityDescriptor(id, CapabilityKind.SKILL,
                            CapabilityBinding.LOCAL, "Registered by the lab self-check", ToolSide.CLIENT,
                            ToolPermission.EVERYONE, "lab", attributes),
                    (context, args) -> {
                        JsonObject envelope = new JsonObject();
                        envelope.addProperty("ok", true);
                        envelope.addProperty("text", "registered skill body");
                        return java.util.concurrent.CompletableFuture.completedFuture(envelope);
                    });
            JsonObject envelope = CapabilityCatalog.invoke(id, new JsonObject(), ToolCaller.player("lab", true),
                    ToolSide.CLIENT).get(5, TimeUnit.SECONDS);
            boolean ok = envelope.has("ok") && envelope.get("ok").getAsBoolean()
                    && envelope.has("text") && "registered skill body".equals(envelope.get("text").getAsString());
            return "skill " + id + ": " + (ok ? "OK" : "FAILED " + shortText(envelope));
        } catch (Exception failure) {
            return "skill " + id + ": FAILED " + failure.getClass().getSimpleName();
        } finally {
            CapabilityCatalog.unregister(id);
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
