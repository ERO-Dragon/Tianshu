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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * S 的改版契约：三条 {@code <delegate>} 示例必须在位，中英两套结构对称，中文版不超过 1200 字。
     *
     * <p>"示例在位"只能靠数标记：说明句一次 + 正例一次 + 位置反例一次 + 单独提到标签名一次。任何一条被删，
     * 模型就退回"只有规则没有样例"的老状态。</p>
     */
    @Test
    void systemPromptCarriesTheDelegateExamplesAndBothLanguagesMatch() {
        String zh = systemPrompt(PROMPT_DIR + "general_ax.zh_cn.default.json");
        String en = systemPrompt(PROMPT_DIR + "general_ax.en_us.default.json");

        for (String system : new String[]{zh, en}) {
            assertTrue(count(system, AXReplyContract.TAG_OPEN) >= 4,
                    "S 里的开标签少于 4 处，示例被删了：" + count(system, AXReplyContract.TAG_OPEN));
            assertTrue(count(system, AXReplyContract.TAG_CLOSE) >= 3,
                    "S 里的闭标签少于 3 处，示例被删了：" + count(system, AXReplyContract.TAG_CLOSE));
        }
        assertEquals(5, paragraphs(zh), "zh_cn 的 S 应为 5 段：身份/语音准则/边界/标签示例/复述修复");
        assertEquals(paragraphs(zh), paragraphs(en), "中英两套的分段数必须一致，否则一改就漂");
        assertTrue(zh.length() <= 1200, "zh_cn 的 S 超过 1200 字目标：" + zh.length());
    }

    private static int count(String text, String token) {
        int hits = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(token, from);
            if (index < 0) {
                return hits;
            }
            hits++;
            from = index + token.length();
        }
    }

    private static int paragraphs(String text) {
        return text.split("\n\n", -1).length;
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
