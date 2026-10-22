package com.rheinmetal.tianshu.client.settings.module.llm;

import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.function.llm.account.LlmAccountQueryState;
import com.rheinmetal.tianshu.function.llm.account.LlmAccountSnapshot;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The single place where the LLM usage statistics become text.
 *
 * <p>Every row of the statistics column formats through here, so a token figure or an amount can
 * never be rendered two different ways in two groups. The accounting rules of the roadmap are
 * encoded once and reused everywhere:
 * <ul>
 *   <li>Token counts use the K/M abbreviation ({@code 1.2K}, {@code 3.4M}), or a plain integer below
 *       one thousand. No thousands separators anywhere.</li>
 *   <li>Money is integer micro units of one currency unit and is always shown with two decimals;
 *       the currency symbol comes from the language file so the denomination is not baked into
 *       code.</li>
 *   <li>Sentinel values are translated, never rounded: a hit ratio of {@code -1} means the provider
 *       reported no measurement and renders as "no data" rather than {@code 0%}. The only exception
 *       is the local price estimate, where {@code -1} means no price is configured and gets its own
 *       wording.</li>
 *   <li>Provider-reported amounts carry the currency the provider stated. Whether a figure was computed
 *       locally or answered by the provider is an implementation detail the player does not need, so no
 *       row annotates its source; the two are simply never added together or used to correct each other.</li>
 * </ul>
 */
final class LlmUsageValueFormat {
    private static final String KEY_PREFIX = "tianshu.gui.llm.usage.";
    private static final long KILO = 1_000L;
    private static final long MEGA = 1_000_000L;
    private static final long MICROS_PER_UNIT = 1_000_000L;
    private static final String ONE_DECIMAL = "%.1f";
    private static final String TWO_DECIMALS = "%.2f";
    private static final String CURRENCY_CNY = "CNY";
    private static final String CURRENCY_USD = "USD";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private LlmUsageValueFormat() {
    }

    /** Token count as the column shows it: plain below one thousand, then {@code K}, then {@code M}. */
    static UiText tokens(long tokens) {
        long value = Math.max(0L, tokens);
        if (value >= MEGA) {
            return key("value.tokens_m", number(ONE_DECIMAL, value / (double) MEGA));
        }
        if (value >= KILO) {
            return key("value.tokens_k", number(ONE_DECIMAL, value / (double) KILO));
        }
        return UiText.literal(Long.toString(value));
    }

    /** Request counts, which are small enough that an abbreviation would only lose precision. */
    static UiText count(long count) {
        return UiText.literal(Long.toString(Math.max(0L, count)));
    }

    /**
     * Cache hit ratio in the range 0..1.
     *
     * @param ratio {@code -1} (or any negative / non-finite value) means "not measured" and renders
     *              as "no data", never as {@code 0%}
     */
    static UiText ratio(double ratio) {
        if (!Double.isFinite(ratio) || ratio < 0D) {
            return key("value.no_data");
        }
        double clamped = Math.min(1D, ratio);
        return key("value.percent", number(ONE_DECIMAL, clamped * 100D));
    }

    /**
     * Amount in micro units with two decimals.
     *
     * @param micros {@code -1} means the provider did not report this figure and renders as "no
     *               data"; use {@link #localEstimate(long)} for the locally converted path
     */
    static UiText money(long micros) {
        if (micros < 0L) {
            return key("value.no_data");
        }
        return key("value.money", number(TWO_DECIMALS, micros / (double) MICROS_PER_UNIT));
    }

    /**
     * Locally converted amount in micro units.
     *
     * @param micros {@code -1} means no price is configured, which is a different fact from an
     *               unreported provider figure and gets its own wording
     */
    static UiText localEstimate(long micros) {
        return micros < 0L ? key("value.price_missing") : money(micros);
    }

    /**
     * One provider-reported amount, in the currency the provider stated.
     *
     * <p>The currency is part of the figure, not decoration: the same endpoint can answer a yuan balance
     * for one account and a dollar balance for another, and a bare number would make them look equal. A
     * currency this build has no symbol for is written next to the number, and an amount with no stated
     * currency keeps only its digits rather than borrowing a symbol.
     *
     * @param micros {@code -1} means the provider did not report this figure and renders as "no data"
     */
    static UiText amount(long micros, String currencyCode) {
        if (micros < 0L) {
            return key("value.no_data");
        }
        String amount = number(TWO_DECIMALS, micros / (double) MICROS_PER_UNIT);
        String code = currencyCode == null ? "" : currencyCode.trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            return UiText.literal(amount);
        }
        return switch (code) {
            case CURRENCY_CNY -> key("value.money", amount);
            case CURRENCY_USD -> key("value.money_usd", amount);
            default -> key("value.money_code", amount, UiText.literal(code));
        };
    }

    /**
     * One provider-answered amount, including the state of the query that produced it.
     *
     * <p>An unwired probe, an in-flight query and a failed query each get their own wording, so a
     * missing answer never looks like a reported zero. A failure names its cause — a rejected key reads
     * differently from an unreadable answer — and falls back to a timestamped "query failed" only when
     * the probe did not say why.
     */
    static UiText providerAmount(LlmAccountSnapshot account, long micros) {
        LlmAccountSnapshot snapshot = account == null ? LlmAccountSnapshot.unwired(null) : account;
        LlmAccountQueryState state = snapshot.state();
        return switch (state) {
            case UNWIRED -> key("value.pending_wiring");
            case QUERYING -> key("value.querying");
            case FAILED -> failure(snapshot);
            case AVAILABLE -> amount(micros, snapshot.currencyCode());
        };
    }

    private static UiText failure(LlmAccountSnapshot snapshot) {
        String failureKey = snapshot.failureKey();
        if (failureKey != null && !failureKey.isBlank()) {
            return UiText.key(failureKey);
        }
        return key("value.query_failed", clock(snapshot.fetchedAtMillis()));
    }

    /** Wall-clock time of a provider answer, used to date a failure instead of hiding it. */
    static UiText clock(long epochMillis) {
        if (epochMillis <= 0L) {
            return key("value.unknown");
        }
        return UiText.literal(CLOCK.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())));
    }

    /**
     * Attribution key of one usage record as the player sees it.
     *
     * <p>The ledger keys usage by protocol source id ({@code module.ax}, {@code core.runtime}, ...).
     * A known id maps to a language key; an id this build does not name is shown verbatim rather than
     * hidden, so a new source is visible before it is translated.
     */
    static UiText sourceLabel(String sourceId) {
        String id = sourceId == null ? "" : sourceId.trim().toLowerCase(Locale.ROOT);
        return switch (id) {
            case ProtocolModuleIds.ASR -> key("source.module_asr");
            case ProtocolModuleIds.AX -> key("source.module_ax");
            case ProtocolModuleIds.IA -> key("source.module_ia");
            case ProtocolModuleIds.IR -> key("source.module_ir");
            case ProtocolModuleIds.LLM -> key("source.module_llm");
            case ProtocolModuleIds.TTS -> key("source.module_tts");
            case "core.runtime" -> key("source.core_runtime");
            case "" -> key("source.unknown");
            default -> UiText.literal(sourceId);
        };
    }

    static UiText key(String suffix, Object... arguments) {
        return UiText.key(KEY_PREFIX + suffix, arguments);
    }

    private static String number(String pattern, double value) {
        return String.format(Locale.ROOT, pattern, value);
    }
}
