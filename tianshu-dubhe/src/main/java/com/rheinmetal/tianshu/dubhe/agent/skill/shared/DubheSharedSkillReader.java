package com.rheinmetal.tianshu.dubhe.agent.skill.shared;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkill;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillBudget;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillLane;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillSpec;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillVisibility;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 读取舟楫上的**共享技能**：技能正文或其中一篇 reference。
 *
 * <p>三档说明、工具清单这类"平台公共知识"由舟楫注册成 SKILL，任何模组都能读；天枢不在本地保存副本，
 * 每次需要时通过保留工具 {@code tianshu:capability.read} 去舟楫取。这样平台改了说明，所有模组下一次读取即生效。</p>
 *
 * <p>参数与 {@code capability.read} 一致：{@code id}（必填，namespace:name）、可选 {@code file}（references 路径）、
 * 可选 {@code page}（长文分页）。</p>
 */
public final class DubheSharedSkillReader {

    public static final String ID = "read-shared-skill";
    private static final ToolId CAPABILITY_READ = ToolId.of("tianshu", "capability.read");

    private DubheSharedSkillReader() {
    }

    public static DubheSkill skill() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        properties.add("id", property("string", "共享技能 id，例如 tianshu:minecraft-tools"));
        properties.add("file", property("string", "技能内的参考路径，例如 references/tiers.md"));
        properties.add("page", property("integer", "长文分页，从 1 开始"));
        schema.add("properties", properties);
        schema.add("required", stringArray("id"));

        DubheSkillSpec spec = DubheSkillSpec.builder(ID, "读取舟楫上发布的共享技能正文或参考（平台公共知识）")
                .inputSchema(schema)
                .visibility(DubheSkillVisibility.INTERNAL)
                .budget(DubheSkillBudget.of(15_000L, 1))
                .reportSchema(List.of("outcome"))
                .retry(false)
                .lane(DubheSkillLane.PLAYER)
                .build();
        return new DubheSkill(spec, (context, args) -> read(args));
    }

    private static CompletableFuture<JsonObject> read(JsonObject args) {
        JsonObject request = new JsonObject();
        copy(args, request, "id");
        copy(args, request, "file");
        copy(args, request, "page");
        ToolCaller caller = ToolCaller.agent("dubhe", false, ToolAutonomy.READ_ONLY);
        return TianshuTools.callJson(CAPABILITY_READ, request, caller, ToolSide.CLIENT)
                .handle((envelope, error) -> {
                    JsonObject result = new JsonObject();
                    if (error != null || envelope == null) {
                        result.addProperty("outcome", "FAILED");
                        result.addProperty("reason", "CALL_FAILED");
                        return result;
                    }
                    if (envelope.has("ok") && !envelope.get("ok").getAsBoolean()) {
                        result.addProperty("outcome", "FAILED");
                        result.addProperty("reason", text(envelope, "code"));
                        return result;
                    }
                    JsonElement payload = envelope.has("result") ? envelope.get("result") : envelope;
                    result.addProperty("outcome", "OK");
                    result.addProperty("text", payload != null && payload.isJsonObject()
                            ? text(payload.getAsJsonObject(), "text") : "");
                    return result;
                });
    }

    private static void copy(JsonObject from, JsonObject to, String name) {
        if (from != null && from.has(name) && !from.get(name).isJsonNull()) {
            to.add(name, from.get(name));
        }
    }

    private static String text(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return "";
        }
        return object.get(name).getAsString();
    }

    private static JsonObject property(String type, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", type);
        property.addProperty("description", description);
        return property;
    }

    private static com.google.gson.JsonArray stringArray(String... values) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}