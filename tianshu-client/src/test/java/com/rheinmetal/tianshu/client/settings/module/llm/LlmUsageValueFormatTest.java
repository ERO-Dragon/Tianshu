package com.rheinmetal.tianshu.client.settings.module.llm;

import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.function.llm.account.LlmAccountKey;
import com.rheinmetal.tianshu.function.llm.account.LlmAccountSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LlmUsageValueFormatTest {
    private static final String PREFIX = "tianshu.gui.llm.usage.value.";
    /** Source labels live in their own key space, not under {@code value.}. */
    private static final String SOURCE_PREFIX = "tianshu.gui.llm.usage.source.";
    private static final LlmAccountKey KEY = LlmAccountKey.of("https://api.deepseek.com/v1", "sk-test");

    @Test
    void smallTokenCountsStayPlainAndLargeOnesAreAbbreviated() {
        assertEquals("999", LlmUsageValueFormat.tokens(999L).value());
        assertFalse(LlmUsageValueFormat.tokens(999L).translatable());

        assertKey(LlmUsageValueFormat.tokens(1_500L), "tokens_k", "1.5");
        assertKey(LlmUsageValueFormat.tokens(2_500_000L), "tokens_m", "2.5");
        assertEquals("0", LlmUsageValueFormat.tokens(-12L).value());
    }

    @Test
    void aNegativeHitRatioIsNoDataAndNeverZeroPercent() {
        assertKey(LlmUsageValueFormat.ratio(-1D), "no_data");
        assertKey(LlmUsageValueFormat.ratio(0.8732D), "percent", "87.3");
        assertKey(LlmUsageValueFormat.ratio(0D), "percent", "0.0");
    }

    @Test
    void moneyUsesTwoDecimalsAndTranslatesBothSentinels() {
        assertKey(LlmUsageValueFormat.money(1_234_567L), "money", "1.23");
        assertKey(LlmUsageValueFormat.money(-1L), "no_data");
        assertKey(LlmUsageValueFormat.localEstimate(-1L), "price_missing");
        assertKey(LlmUsageValueFormat.localEstimate(2_000_000L), "money", "2.00");
    }

    @Test
    void aProviderAmountKeepsTheQueryStateVisible() {
        assertKey(LlmUsageValueFormat.providerAmount(LlmAccountSnapshot.unwired(KEY), 0L), "pending_wiring");
        assertKey(LlmUsageValueFormat.providerAmount(LlmAccountSnapshot.querying(KEY), 0L), "querying");

        UiText failed = LlmUsageValueFormat.providerAmount(LlmAccountSnapshot.failed(KEY, "", 0L), 0L);
        assertEquals(PREFIX + "query_failed", failed.value());
        assertEquals(1, failed.arguments().size());
        assertKey((UiText) failed.arguments().get(0), "unknown");

        // A probe that says why it failed gets its own sentence instead of the timestamped fallback.
        UiText named = LlmUsageValueFormat.providerAmount(
                LlmAccountSnapshot.failed(KEY, "tianshu.gui.llm.balance.unauthorized", 0L), 0L);
        assertEquals("tianshu.gui.llm.balance.unauthorized", named.value());
        assertTrue(named.translatable());
    }

    @Test
    void aProviderAmountCarriesTheCurrencyItWasReportedIn() {
        assertKey(providerAmount("CNY", 110_000_000L), "money", "110.00");
        assertKey(providerAmount("USD", 20_000_000L), "money_usd", "20.00");
        assertKey(providerAmount(" eur ", 7_500_000L), "money_code", "7.50", UiText.literal("EUR"));

        UiText noCurrency = providerAmount("", 5_000_000L);
        assertFalse(noCurrency.translatable(), "an amount without a stated currency borrows no symbol");
        assertEquals("5.00", noCurrency.value());
    }

    @Test
    void anAmountTheProviderDidNotReportIsNoDataAndNotZero() {
        UiText unavailable = LlmUsageValueFormat.providerAmount(
                LlmAccountSnapshot.available(KEY, 1_000_000L, 2_000_000L, LlmAccountSnapshot.UNKNOWN_AMOUNT, "CNY", 1L),
                LlmAccountSnapshot.UNKNOWN_AMOUNT);
        assertKey(unavailable, "no_data");
    }

    @Test
    void aKnownSourceIsTranslatedAndAnUnknownOneStaysReadable() {
        assertSourceKey(LlmUsageValueFormat.sourceLabel("module.ax"), "module_ax");
        assertSourceKey(LlmUsageValueFormat.sourceLabel(" CORE.RUNTIME "), "core_runtime");
        assertSourceKey(LlmUsageValueFormat.sourceLabel(""), "unknown");

        UiText unknown = LlmUsageValueFormat.sourceLabel("module.new");
        assertFalse(unknown.translatable());
        assertEquals("module.new", unknown.value());
    }

    @Test
    void anUnknownAnswerTimeRendersAPlaceholder() {
        assertKey(LlmUsageValueFormat.clock(0L), "unknown");
        assertFalse(LlmUsageValueFormat.clock(1_700_000_000_000L).value().isBlank());
    }

    private static UiText providerAmount(String currencyCode, long micros) {
        return LlmUsageValueFormat.providerAmount(
                LlmAccountSnapshot.available(KEY, LlmAccountSnapshot.UNKNOWN_AMOUNT,
                        LlmAccountSnapshot.UNKNOWN_AMOUNT, micros, currencyCode, 1L),
                micros);
    }

    private static void assertSourceKey(UiText text, String suffix) {
        assertEquals(SOURCE_PREFIX + suffix, text.value());
        assertTrue(text.translatable());
    }

    private static void assertKey(UiText text, String suffix, Object... arguments) {
        assertEquals(PREFIX + suffix, text.value());
        assertTrue(text.translatable());
        assertEquals(arguments.length, text.arguments().size());
        for (int index = 0; index < arguments.length; index++) {
            assertEquals(arguments[index], text.arguments().get(index));
        }
    }
}
