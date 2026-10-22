package com.rheinmetal.tianshu.function.tts.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TTS 可朗读性清洗：<b>只删字符或换成标点，不插词</b>。
 *
 * <p>这里的每条用例都对应"引擎会怎么念"：符号当字念、冒号与逗号加停顿。两类约束被钉死：
 * ① 对本来干净的文本<b>零改动</b>；② 对所有输入<b>幂等</b>（跑两遍等于跑一遍）。</p>
 */
class TtsTextNormalizerTest {

    private final TtsTextNormalizer normalizer = new TtsTextNormalizer();

    @Test
    void cleanTextIsReturnedUnchanged() {
        String clean = "你背包里大概有三组铁锭，够修两把镐。铁砧可以把它们修好。";

        assertEquals(clean, normalizer.normalize(clean), "干净文本必须零改动");
        assertEquals(0, normalizer.normalizeWithReport(clean).unresolvedIdentifiers());
    }

    @Test
    void markdownEmphasisIsRemoved() {
        assertEquals("铁砧 可以修复 工具", normalizer.normalize("**铁砧** 可以修复 __工具__"));
        assertEquals("斜体 也算", normalizer.normalize("*斜体* 也算"));
    }

    @Test
    void headingsQuotesAndListMarkersAreRemoved() {
        assertEquals("先挖矿 再造工作台", normalizer.normalize("- 先挖矿\n- 再造工作台"));
        assertEquals("第一步", normalizer.normalize("1. 第一步"));
        assertEquals("标题 正文", normalizer.normalize("## 标题\n正文"));
        assertEquals("引用的话", normalizer.normalize("> 引用的话"));
    }

    @Test
    void codeFencesAndBackticksAreRemovedButTheirContentStays() {
        assertEquals("铁砧 可以修", normalizer.normalize("```\n铁砧\n```\n可以修"));
        assertEquals("用 anvil 修", normalizer.normalize("用 `anvil` 修"));
    }

    @Test
    void emojiIsRemoved() {
        assertEquals("好的 我去看看", normalizer.normalize("好的 😀 我去看看 ✅"));
    }

    @Test
    void urlIsRemovedInsteadOfBeingReadOut() {
        assertEquals("看这里 就懂了", normalizer.normalize("看这里 https://example.com/a?b=1 就懂了"));
        assertEquals("去这个站", normalizer.normalize("去这个站 www.example.com"));
    }

    @Test
    void markdownLinkKeepsItsVisibleText() {
        assertEquals("铁砧 很实用", normalizer.normalize("[铁砧](https://wiki.example/anvil) 很实用"));
    }

    @Test
    void tablePipesAreRemoved() {
        assertEquals("铁砧 工具", normalizer.normalize("| 铁砧 | 工具 |"));
    }

    @Test
    void itemIdLosesItsNamespaceAndSeparators() {
        assertEquals("用 anvil 修", normalizer.normalize("用 minecraft:anvil 修"));
        assertEquals("拿 diamond sword", normalizer.normalize("拿 minecraft:diamond_sword"));
    }

    @Test
    void anIdWithNothingReadableIsKeptAndCounted() {
        TtsTextNormalizer.Result result = normalizer.normalizeWithReport("看 a:__ 这个");

        assertTrue(result.text().contains("a:__"), "读不出来的 ID 原样保留：" + result.text());
        assertEquals(1, result.unresolvedIdentifiers());
    }

    @Test
    void coordinateCommasAreRemovedSoTheNumbersDoNotPause() {
        String spoken = normalizer.normalize("去 120,64,-300 那个箱子");

        assertEquals("去 120 64 -300 那个箱子", spoken, "逗号必须消失，否则会出现三次怪停顿");
        assertFalse(spoken.contains(","));
    }

    @Test
    void ellipsisAndDashesCollapseIntoOnePause() {
        String spoken = normalizer.normalize("我想想……再说——好吧");

        assertFalse(spoken.contains("…"), spoken);
        assertFalse(spoken.contains("—"), spoken);
        assertTrue(spoken.contains("，"), "长停顿收成一个逗号：" + spoken);
    }

    @Test
    void bracketsAreRemovedButTheirContentStays() {
        assertEquals("铁砧 仅创造模式 可以修", normalizer.normalize("铁砧（仅创造模式）可以修"));
        assertEquals("anvil optional 也能修", normalizer.normalize("anvil (optional) 也能修"));
    }

    @Test
    void thinkingBlocksNeverReachSpeech() {
        assertEquals("好了", normalizer.normalize("<think>我这里想了一堆</think>好了"));
        assertEquals("好了", normalizer.normalize("好了<think/>"));
    }

    @Test
    void nullAndBlankBecomeEmpty() {
        assertEquals("", normalizer.normalize(null));
        assertEquals("", normalizer.normalize("   "));
        assertEquals(0, normalizer.normalizeWithReport(null).unresolvedIdentifiers());
    }

    @Test
    void normalizingTwiceEqualsNormalizingOnce() {
        for (String raw : new String[]{
                "**结论**：去 [wiki](https://x) 看 😀（注：别念）120,64,-300",
                "- 第一步\n- 第二步……",
                "```\nminecraft:anvil\n```\n就这样",
                "a:__ 和 b:c_d",
                "干净的一句话，没有符号。",
                ""
        }) {
            String once = normalizer.normalize(raw);
            String twice = normalizer.normalize(once);

            assertEquals(once, twice, "幂等被破坏：" + raw);
        }
    }
}
