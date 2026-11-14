package com.rheinmetal.tianshu.function.echo.synthesis.pocket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PocketTextNormalizerTest {
    @Test
    void readsSerialNumbersDigitByDigit() {
        String normalized = PocketTextNormalizer.normalize("订单5242720213目前还在仓库备货");

        assertTrue(normalized.contains("五二四二七二零二一三"), normalized);
    }

    @Test
    void widensHalfWidthPunctuationAndReadsNumbers() {
        String normalized = PocketTextNormalizer.normalize("您好,退款368.5元,预计3个工作日到账");

        assertTrue(normalized.contains("您好，退款"), normalized);
        assertTrue(normalized.contains("三百六十八点五元"), normalized);
        assertTrue(normalized.contains("三个工作日"), normalized);
        assertTrue(normalized.contains("到账。"), normalized);
    }

    @Test
    void readsDatesAndClockTimes() {
        assertEquals("二零二六年九月十五日十六点十五分。",
                PocketTextNormalizer.normalize("2026年9月15日16点15分"));
        assertEquals("二零二六年九月十五日。",
                PocketTextNormalizer.normalize("2026-09-15"));
    }

    @Test
    void readsPhoneLikeNumbersDigitByDigit() {
        assertEquals("尾号八六二一。", PocketTextNormalizer.normalize("尾号8621"));
        assertEquals("验证码一零二四。", PocketTextNormalizer.normalize("验证码1024"));
    }

    @Test
    void readsPercentagesAndKeepsEnglishLetterNumberTerms() {
        assertEquals("百分之五十。", PocketTextNormalizer.normalize("50%"));
        assertEquals("5G。", PocketTextNormalizer.normalize("5G"));
    }

    @Test
    void readsFlightNumberAfterAirlineCode() {
        String normalized = PocketTextNormalizer.normalize("您乘坐的MU4180航班将于明天起飞");

        assertTrue(normalized.contains("MU四一八零航班"), normalized);
    }

    @Test
    void appendsTerminalPunctuationOnlyWhenMissing() {
        assertEquals("今天天气很好。", PocketTextNormalizer.normalize("今天天气很好"));
        assertEquals("今天天气很好。", PocketTextNormalizer.normalize("今天天气很好。"));
    }
}
