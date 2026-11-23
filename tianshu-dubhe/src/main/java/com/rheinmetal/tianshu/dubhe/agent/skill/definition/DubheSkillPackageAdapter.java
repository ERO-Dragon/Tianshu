package com.rheinmetal.tianshu.dubhe.agent.skill.definition;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkill;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillBudget;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillLane;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillSpec;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillVisibility;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 把技能包接成代理可调用的技能：目录只读元数据，调用才返回正文，带 reference 参数时按需读参考。
 *
 * <p>这正是渐进式披露在代理循环里的形态：`tools[]` 里只有 name + description + 最小 schema；
 * 模型决定使用时调用本技能（空参数）拿正文；需要细节再带 {@code reference} 调用一次。</p>
 */
public final class DubheSkillPackageAdapter {

    private static final int MAX_DESCRIPTION = 200;

    private DubheSkillPackageAdapter() {
    }

    public static DubheSkill toSkill(DubheSkillDefinition definition) {
        DubheSkillMetadata metadata = definition.metadata();
        if (metadata.description().length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException(
                    "skill " + metadata.name() + " description exceeds " + MAX_DESCRIPTION + " characters");
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        properties.add("reference", property("string", "relative path of a reference to read, for example references/tools.md"));
        schema.add("properties", properties);

        DubheSkillSpec spec = DubheSkillSpec.builder(metadata.name(), metadata.description())
                .inputSchema(schema)
                .visibility(DubheSkillVisibility.INTERNAL)
                .budget(DubheSkillBudget.of(15_000L, 1))
                .reportSchema(List.of("outcome"))
                .retry(false)
                .lane(DubheSkillLane.PLAYER)
                .build();
        return new DubheSkill(spec, (context, args) -> {
            String reference = text(args, "reference");
            JsonObject result = new JsonObject();
            if (reference.isBlank()) {
                result.addProperty("outcome", "OK");
                result.addProperty("body", definition.body());
                return CompletableFuture.completedFuture(result);
            }
            String content = definition.reference(reference);
            if (content.isBlank()) {
                result.addProperty("outcome", "FAILED");
                result.addProperty("reason", "UNKNOWN_REFERENCE");
                return CompletableFuture.completedFuture(result);
            }
            result.addProperty("outcome", "OK");
            result.addProperty("reference", reference);
            result.addProperty("body", content);
            return CompletableFuture.completedFuture(result);
        });
    }

    private static JsonObject property(String type, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", type);
        property.addProperty("description", description);
        return property;
    }

    private static String text(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return "";
        }
        return object.get(name).getAsString().trim();
    }
}