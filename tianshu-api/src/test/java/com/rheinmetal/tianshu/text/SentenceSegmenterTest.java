package com.rheinmetal.tianshu.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentenceSegmenterTest {
    @Test
    void flushesShortCompleteSentenceAtStrongBoundary() {
        SentenceSegmenter segmenter = SentenceSegmenter.spokenDialogue();

        SentenceSegmenter.SegmentBoundary boundary = segmenter.nextBoundary("OK.");

        assertTrue(boundary.shouldFlush());
        assertEquals("OK.".length(), boundary.endIndex());
    }

    @Test
    void flushesOnChineseStrongBoundaryAfterMinimumLength() {
        SentenceSegmenter segmenter = new SentenceSegmenter(8, 16, 40);

        SentenceSegmenter.SegmentBoundary boundary = segmenter.nextBoundary("这是一个足够长的句子。");

        assertTrue(boundary.shouldFlush());
        assertEquals("这是一个足够长的句子。".length(), boundary.endIndex());
    }

    @Test
    void doesNotSplitDecimalPoint() {
        SentenceSegmenter segmenter = new SentenceSegmenter(8, 20, 80);

        assertFalse(segmenter.nextBoundary("当前版本是 3.14 还没有说完").shouldFlush());
    }

    @Test
    void doesNotSplitCommonLatinAbbreviation() {
        SentenceSegmenter segmenter = new SentenceSegmenter(8, 20, 80);

        assertFalse(segmenter.nextBoundary("Dr. Smith 正在准备继续说话").shouldFlush());
    }

    @Test
    void forcesBoundaryWhenTextExceedsMaximumLength() {
        SentenceSegmenter segmenter = new SentenceSegmenter(8, 16, 24);

        SentenceSegmenter.SegmentBoundary boundary = segmenter.nextBoundary("这是一段没有明显标点但是长度已经超过限制的文本内容");

        assertTrue(boundary.shouldFlush());
        assertTrue(boundary.endIndex() <= 24);
    }

    // ------------------------------------------------------------ 语音对话的统一参数（唯一来源）

    @Test
    void spokenDialogueUsesTheSharedVoiceParameters() {
        assertEquals(12, SentenceSegmenter.SPOKEN_MIN_LENGTH);
        assertEquals(60, SentenceSegmenter.SPOKEN_PREFERRED_LENGTH);
        assertEquals(120, SentenceSegmenter.SPOKEN_MAX_LENGTH);

        // 参数被真正用上：60 字之前遇到逗号不切，超过 60 字才切。
        assertFalse(SentenceSegmenter.spokenDialogue().nextBoundary(shorterThanPreferred()).shouldFlush(),
                "60 字之前的逗号不该切，否则一句自然话会被切成碎块");
        assertTrue(SentenceSegmenter.spokenDialogue().nextBoundary(longerThanPreferred()).shouldFlush(),
                "超过 60 字后遇到逗号必须切");
    }

    @Test
    void spokenDialogueForcesABoundaryAtTheMaximumLength() {
        String runOn = "甲".repeat(SentenceSegmenter.SPOKEN_MAX_LENGTH + 5);

        SentenceSegmenter.SegmentBoundary boundary = SentenceSegmenter.spokenDialogue().nextBoundary(runOn);

        assertTrue(boundary.shouldFlush(), "没有标点的长跑必须在上限处切断");
        assertTrue(boundary.endIndex() <= SentenceSegmenter.SPOKEN_MAX_LENGTH);
    }

    @Test
    void shortInterjectionIsNotSplitAtItsComma() {
        // "嗯，好的" 只有 5 个字：软标点不得把一句招呼切成两条语音。
        assertFalse(SentenceSegmenter.spokenDialogue().nextBoundary("嗯，好的").shouldFlush());
    }

    private static String shorterThanPreferred() {
        return "甲".repeat(SentenceSegmenter.SPOKEN_PREFERRED_LENGTH - 20) + "，后面还有话";
    }

    private static String longerThanPreferred() {
        return "甲".repeat(SentenceSegmenter.SPOKEN_PREFERRED_LENGTH + 2) + "，后面还有话";
    }
}
