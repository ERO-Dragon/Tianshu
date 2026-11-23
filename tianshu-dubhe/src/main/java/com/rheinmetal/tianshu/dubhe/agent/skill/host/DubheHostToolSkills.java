package com.rheinmetal.tianshu.dubhe.agent.skill.host;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.api.tool.ToolSpec;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkill;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillBudget;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillLane;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillSpec;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillVisibility;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 把舟楫的宿主工具接进代理目录：一个工具 = 一个带 schema 的代理工具。
 *
 * <p>为什么走 {@link DubheSkill} 这一层：代理循环只认一种"工具"——目录项 + executor。宿主工具天生就是这个形状，
 * 适配器只做三件事：把 {@code tianshu:recipe.query} 的 id 换成 provider 合法的工具名 {@code tianshu_recipe_query}、把
 * schema/描述带进目录、把调用转发给 {@link TianshuTools#callJson}。没有为任何具体工具写分支。</p>
 *
 * <p>档位过滤由 {@link TianshuTools#catalog} 完成：目录只包含当前 caller 档位允许、且已启用的工具。
 * caller 由装配处按设置构造，因此代理永远不会看到高于自己档位的工具。</p>
 */
public final class DubheHostToolSkills {

    private static final int MAX_DESCRIPTION = 200;
    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    private DubheHostToolSkills() {
    }

    /** The host tools this caller may use, as agent tools. Disabled tools are omitted (they cannot be called). */
    public static List<DubheSkill> fromCatalog(ToolCaller caller) {
        List<DubheSkill> skills = new ArrayList<>();
        for (ToolSpec tool : TianshuTools.catalog(caller, ToolSide.CLIENT)) {
            if (!tool.enabled()) {
                continue;
            }
            skills.add(toSkill(tool, caller));
        }
        return List.copyOf(skills);
    }

    /** Tool id → a provider-safe agent tool name: {@code tianshu:recipe.query} becomes {@code tianshu_recipe_query}. */
    public static String agentToolName(ToolId id) {
        return id.toString().replace(':', '_').replace('.', '_');
    }

    private static DubheSkill toSkill(ToolSpec tool, ToolCaller caller) {
        String description = describe(tool.description());
        DubheSkillSpec spec = DubheSkillSpec.builder(agentToolName(tool.id()), description)
                .inputSchema(tool.argsSchema() == null ? DubheSkillSpec.emptySchema() : tool.argsSchema())
                .visibility(DubheSkillVisibility.INTERNAL)
                .autonomy(ToolAutonomy.READ_ONLY)
                .budget(DubheSkillBudget.of(DEFAULT_TIMEOUT_MILLIS, 1))
                .reportSchema(List.of("outcome"))
                .retry(false)
                .lane(DubheSkillLane.PLAYER)
                .build();
        return new DubheSkill(spec, (context, args) -> invoke(tool.id(), args, caller));
    }

    /**
     * The real call. The envelope ({@code ok/result} or {@code ok:false/code/message}) is passed back verbatim:
     * the model reads the tool's own outcome, and the skill layer does not invent a second result shape.
     */
    private static CompletableFuture<JsonObject> invoke(ToolId id, JsonObject args, ToolCaller caller) {
        JsonObject safeArgs = args == null ? new JsonObject() : args;
        return TianshuTools.callJson(id, safeArgs, caller, ToolSide.CLIENT);
    }

    private static String describe(String description) {
        String text = description == null ? "" : description.trim();
        if (text.isBlank()) {
            text = "host tool";
        }
        return text.length() <= MAX_DESCRIPTION ? text : text.substring(0, MAX_DESCRIPTION - 3) + "...";
    }
}