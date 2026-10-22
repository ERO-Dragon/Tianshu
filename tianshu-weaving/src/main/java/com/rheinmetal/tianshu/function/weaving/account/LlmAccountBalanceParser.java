package com.rheinmetal.tianshu.function.weaving.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the documented balance document of an OpenAI-compatible provider.
 *
 * <p>The shape is {@code {"is_available": boolean, "balance_infos": [{"currency": "CNY",
 * "total_balance": "110.00", "granted_balance": "10.00", "topped_up_balance": "100.00"}]}}; see
 * https://api-docs.deepseek.com/api/get-user-balance (read 2026-11). Amounts arrive as decimal strings
 * and one answer may carry several currencies, so the value is converted here — exactly, through
 * {@link BigDecimal} — and never through {@code double}.
 *
 * <p>{@code is_available} is not a failure: it states whether the account may spend right now, while
 * the amounts stay the authoritative balance either way.
 */
final class LlmAccountBalanceParser {
    private static final String BALANCES_FIELD = "balance_infos";
    private static final String CURRENCY_FIELD = "currency";
    private static final String TOTAL_FIELD = "total_balance";
    private static final String PREFERRED_CURRENCY = "CNY";
    private static final int MICRO_DIGITS = 6;

    private LlmAccountBalanceParser() {
    }

    /**
     * One currency line of an answer.
     *
     * @param currencyCode      the provider's currency code, upper case
     * @param totalBalanceMicros remaining balance in micro units, always a known amount here
     */
    record Balance(String currencyCode, long totalBalanceMicros) {
    }

    /**
     * Every usable currency line of one answer, in answer order.
     *
     * <p>A line whose currency or amount cannot be read is skipped rather than failing the whole
     * answer: a provider that adds a field or a currency this build does not know must not make its
     * balance unreadable. An answer whose lines are all unusable is reported as unreadable, and an
     * empty {@code balance_infos} is a valid answer that simply carries no figure.
     *
     * @return {@link Optional#empty()} when the body is not a balance document at all
     */
    static Optional<List<Balance>> parseBalances(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (JsonParseException unreadable) {
            return Optional.empty();
        }
        if (root == null || !root.isJsonObject()) {
            return Optional.empty();
        }
        JsonElement infos = root.getAsJsonObject().get(BALANCES_FIELD);
        if (infos == null || !infos.isJsonArray()) {
            return Optional.empty();
        }
        JsonArray entries = infos.getAsJsonArray();
        List<Balance> balances = new ArrayList<>(entries.size());
        for (JsonElement entry : entries) {
            Balance balance = toBalance(entry);
            if (balance != null) {
                balances.add(balance);
            }
        }
        if (!entries.isEmpty() && balances.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(balances));
    }

    /**
     * The line the UI shows: the provider's CNY balance when it has one, otherwise its first line.
     *
     * @return {@link Optional#empty()} when the answer carried no usable line
     */
    static Optional<Balance> preferred(List<Balance> balances) {
        if (balances == null || balances.isEmpty()) {
            return Optional.empty();
        }
        for (Balance balance : balances) {
            if (PREFERRED_CURRENCY.equals(balance.currencyCode())) {
                return Optional.of(balance);
            }
        }
        return Optional.of(balances.get(0));
    }

    /**
     * A decimal amount as integer micro units, exactly: {@code "110.00"} becomes {@code 110000000}.
     *
     * <p>Digits beyond the sixth decimal are dropped rather than rounded up, so a displayed amount can
     * never be larger than the provider's figure.
     *
     * @return {@link LlmAccountSnapshot#UNKNOWN_AMOUNT} when the text is not a non-negative decimal
     */
    static long microsOf(String decimal) {
        String text = decimal == null ? "" : decimal.trim();
        if (text.isEmpty()) {
            return LlmAccountSnapshot.UNKNOWN_AMOUNT;
        }
        try {
            BigDecimal amount = new BigDecimal(text);
            if (amount.signum() < 0) {
                return LlmAccountSnapshot.UNKNOWN_AMOUNT;
            }
            BigDecimal micros = amount.movePointRight(MICRO_DIGITS).setScale(0, RoundingMode.DOWN);
            if (micros.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) >= 0) {
                return LlmAccountSnapshot.UNKNOWN_AMOUNT;
            }
            return micros.longValue();
        } catch (NumberFormatException unreadable) {
            return LlmAccountSnapshot.UNKNOWN_AMOUNT;
        }
    }

    private static Balance toBalance(JsonElement entry) {
        if (entry == null || !entry.isJsonObject()) {
            return null;
        }
        JsonObject object = entry.getAsJsonObject();
        String currency = stringField(object, CURRENCY_FIELD).toUpperCase(java.util.Locale.ROOT);
        if (currency.isEmpty()) {
            return null;
        }
        long micros = microsOf(stringField(object, TOTAL_FIELD));
        if (micros == LlmAccountSnapshot.UNKNOWN_AMOUNT) {
            return null;
        }
        return new Balance(currency, micros);
    }

    private static String stringField(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return "";
        }
        String value = element.getAsString();
        return value == null ? "" : value.trim();
    }
}
