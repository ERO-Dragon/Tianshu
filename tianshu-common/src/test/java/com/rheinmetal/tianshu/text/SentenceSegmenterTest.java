package com.rheinmetal.tianshu.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentenceSegmenterTest {
    @Test
    void flushesShortCompleteSentenceAtStrongBoundary() {
        SentenceSegmenter segmenter = new SentenceSegmenter();

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
}
