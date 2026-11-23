package com.rheinmetal.tianshu.dubhe.external;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegatePromptRepository;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部调用方视角的往返：别人 → 天枢发布的搜索**技能** → 读回一段给模型用的正文。
 *
 * <p>技能不是执行入口：调用方 {@code read} 它；天枢返回文本，调用方的模型据此决定下一步。
 * 没有 RELAY 能力，也没有"提交任务给我规划"的入口。</p>
 */
class DubheExternalRoundTripTest {

    private static final ToolCaller OTHER_MOD = ToolCaller.mod("other.mod");

    @AfterEach
    void cleanUp() {
        DubheExternalCapabilities.unregister();
    }

    @Test
    void anExternalCallerCanReadTheSearchSkill() {
        DubheExternalCapabilities.register((payload, handler, timeout) ->
                handler.onResult(LLMPromptResultPayload.completed(payload.requestId(), "先做暮色森林传送门。")), prompts());

        JsonObject args = new JsonObject();
        args.addProperty("intent", "怎么去暮色森林");
        JsonObject read = CapabilityCatalog
                .invoke(DubheExternalCapabilities.SEARCH, args, OTHER_MOD, ToolSide.CLIENT).join();

        assertEquals("先做暮色森林传送门。", read.get("text").getAsString());
        assertTrue(read.getAsJsonArray("sources").isEmpty());
    }

    private static DubheDelegatePromptRepository prompts() {
        return new DubheDelegatePromptRepository(
                DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN));
    }
}