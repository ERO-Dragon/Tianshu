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
    void widensHalfWidthPunctuationButKeepsDecimalPoint() {
        String normalized = PocketTextNormalizer.normalize("您好,退款368.5元,预计3个工作日到账");

        assertTrue(normalized.contains("您好，退款"), normalized);
        assertTrue(normalized.contains("368.5元"), normalized);
        assertTrue(normalized.contains("到账。"), normalized);
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
