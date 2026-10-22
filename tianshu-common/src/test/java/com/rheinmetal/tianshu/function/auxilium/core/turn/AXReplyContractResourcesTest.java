package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXReplyTextRepository;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标签契约的两处资源落点必须同时存在，否则整条闭环会在真实部署里退化：
 *
 * <ul>
 *   <li>模型侧：S（{@code general_ax.*.default.json} 的 {@code systemPrompts.system}）必须讲清标签怎么用，
 *       否则模型永远不会挂标签；</li>
 *   <li>玩家侧：{@code ax_reply_texts.json} 必须有空答句兜底句，否则退化路径没有可播报的正文。</li>
 * </ul>
 *
 * <p>两者都是资源，因此它们的"存在性"只能靠断言资源来守——任何一边被误删，代码仍能编译、测试仍会绿到
 * 这里之前，问题要到线上才暴露。</p>
 */
class AXReplyContractResourcesTest {

    private static final String PROMPT_DIR = "/com/rheinmetal/tianshu/function/auxilium/prompts/";

    @Test
    void systemPromptStatesTheDelegateTagInBothLanguages() {
        for (String language : new String[]{"zh_cn", "en_us"}) {
            String system = systemPrompt(PROMPT_DIR + "general_ax." + language + ".default.json");
            assertTrue(system.contains(AXReplyContract.TAG_OPEN), language + " 的 S 缺少开标签");
            assertTrue(system.contains(AXReplyContract.TAG_CLOSE), language + " 的 S 缺少闭标签");
            assertTrue(system.contains("delegate"), language + " 的 S 没有提到委派标签");
        }
    }

    @Test
    void systemPromptNoLongerAsksForTheJsonContract() {
        for (String language : new String[]{"zh_cn", "en_us"}) {
            String system = systemPrompt(PROMPT_DIR + "general_ax." + language + ".default.json");
            assertFalse(system.contains("\"action\""), language + " 的 S 仍在要求 JSON 契约");
            assertFalse(system.contains("{\"action\""), language + " 的 S 仍带 JSON 示例");
            assertFalse(system.contains("\"query\""), language + " 的 S 仍带 query 字段");
        }
    }

    @Test
    void emptyAnswerFallbackSentenceExistsForBothLanguages() {
        String zh = replyTexts(AXPromptLanguage.ZH_CN);
        String en = replyTexts(AXPromptLanguage.EN_US);

        assertFalse(zh.isBlank(), "zh_cn 缺少空答句兜底句");
        assertFalse(en.isBlank(), "en_us 缺少空答句兜底句");
        assertNotEquals(zh, en, "两种语言的兜底句不应相同（读写错位时会撞成一样）");
    }

    private static String replyTexts(AXPromptLanguage language) {
        return new AXReplyTextRepository(AXPromptLanguageProvider.fixed(language))
                .text(AXReplyTextRepository.KEY_EMPTY_ANSWER);
    }

    private static String systemPrompt(String resource) {
        try (InputStream stream = AXReplyContractResourcesTest.class.getResourceAsStream(resource)) {
            assertTrue(stream != null, "缺少提示词资源：" + resource);
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement element = JsonParser.parseReader(reader);
                JsonObject prompts = element.getAsJsonObject()
                        .getAsJsonObject("systemPrompts");
                return prompts.get("system").getAsString();
            }
        } catch (Exception failure) {
            throw new AssertionError("读取提示词资源失败：" + resource, failure);
        }
    }
}
