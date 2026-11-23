package com.rheinmetal.tianshu.dubhe.external;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegatePromptRepository;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部调用方视角的完整往返：别人 → 天枢的两个能力 → 目标模组。
 *
 * <p>验收台（§6 第 8 条）要的就是这条路径：RESEARCH 拿结论，RELAY 投递并查进度，调用方身份是
 * {@code ToolCaller.mod(...)}，不经过任何"提交任务给我规划"的入口。</p>
 */
class DubheExternalRoundTripTest {

    private static final ToolCaller OTHER_MOD = ToolCaller.mod("other.mod");
    private static final CapabilityId TARGET = CapabilityId.of("maid", "task");
    private static final ToolId READER = ToolId.of("maid", "reader");

    @AfterEach
    void cleanUp() {
        DubheExternalCapabilities.unregister();
        CapabilityCatalog.unregister(TARGET);
        TianshuTools.unregister(READER);
    }

    @Test
    void anExternalCallerCanResearchAndRelay() {
        DubheExternalCapabilities.register((payload, handler, timeout) ->
                handler.onResult(LLMPromptResultPayload.completed(payload.requestId(), "先做传送门。")), prompts());
        CapabilityCatalog.register(new CapabilityDescriptor(
                TARGET, CapabilityKind.SKILL, CapabilityBinding.LOCAL, "maid task sink",
                ToolSide.CLIENT, ToolPermission.EVERYONE, "maid", new JsonObject()), (context, args) -> {
            JsonObject answer = new JsonObject();
            answer.addProperty("taskId", "t-1");
            return CompletableFuture.completedFuture(answer);
        });
        TianshuTools.register(new TianshuTool<JsonObject, JsonObject>() {
            @Override public ToolId id() { return READER; }
            @Override public ToolCategory category() { return ToolCategory.LOCAL; }
            @Override public String description() { return "maid task state"; }
            @Override public Type argsType() { return JsonObject.class; }
            @Override public Type resultType() { return JsonObject.class; }
            @Override public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
                JsonObject answer = new JsonObject();
                answer.addProperty("state", "RUNNING");
                return CompletableFuture.completedFuture(answer);
            }
        });

        JsonObject researchArgs = new JsonObject();
        researchArgs.addProperty("intent", "怎么去暮色森林");
        JsonObject research = CapabilityCatalog
                .invoke(DubheExternalCapabilities.RESEARCH, researchArgs, OTHER_MOD, ToolSide.CLIENT).join();

        assertEquals("先做传送门。", research.get("conclusion").getAsString());
        assertTrue(research.getAsJsonArray("sources").isEmpty());

        JsonObject submitArgs = new JsonObject();
        submitArgs.addProperty("target", "maid:task");
        submitArgs.addProperty("task", "把木头搬进箱子");
        JsonObject submitted = CapabilityCatalog
                .invoke(DubheExternalCapabilities.RELAY, submitArgs, OTHER_MOD, ToolSide.CLIENT).join();
        assertEquals("t-1", submitted.get("taskId").getAsString());

        JsonObject progressArgs = new JsonObject();
        progressArgs.addProperty("target", "maid:reader");
        progressArgs.addProperty("taskId", "t-1");
        JsonObject progress = CapabilityCatalog
                .invoke(DubheExternalCapabilities.RELAY, progressArgs, OTHER_MOD, ToolSide.CLIENT).join();
        assertEquals("RUNNING", progress.get("state").getAsString());
    }

    private static DubheDelegatePromptRepository prompts() {
        return new DubheDelegatePromptRepository(
                DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN));
    }
}