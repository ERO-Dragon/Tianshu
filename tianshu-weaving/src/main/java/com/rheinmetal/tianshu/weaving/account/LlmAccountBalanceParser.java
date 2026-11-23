package com.rheinmetal.tianshu.weaving.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.weaving.settings.LlmBalanceSpec.BalanceWire;

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
            return microsOf(new BigDecimal(text));
        } catch (NumberFormatException unreadable) {
            return LlmAccountSnapshot.UNKNOWN_AMOUNT;
        }
    }

    /** One amount in micro units, exactly; see {@link #microsOf(BigDecimal, int)}. */
    private static long microsOf(BigDecimal amount) {
        return microsOf(amount, MICRO_DIGITS);
    }

    /**
     * An amount shifted into micro units, exactly.
     *
     * @param shiftDigits decimals to move right: 6 for a currency unit, 4 for a cent
     * @return {@link LlmAccountSnapshot#UNKNOWN_AMOUNT} for a negative or out-of-range amount
     */
    private static long microsOf(BigDecimal amount, int shiftDigits) {
        if (amount == null || amount.signum() < 0) {
            return LlmAccountSnapshot.UNKNOWN_AMOUNT;
        }
        BigDecimal micros = amount.movePointRight(shiftDigits).setScale(0, RoundingMode.DOWN);
        if (micros.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) >= 0) {
            return LlmAccountSnapshot.UNKNOWN_AMOUNT;
        }
        return micros.longValue();
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

    /**
 * One provider document read into the two facts the snapshot needs.
 *
 * @param amountMicros remaining amount in micro units, or {@link LlmAccountSnapshot#UNKNOWN_AMOUNT}
 *                     when a valid document carried no figure
 * @param currencyCode currency the provider stated, empty when it stated none
 */
    record DocumentBalance(long amountMicros, String currencyCode) {
    }

    /**
     * Reads whichever balance document the entry's wire names.
     *
     * <p>This is where every answer shape lives, so the probe stays transport: it decides which
     * endpoint to call and what a status code means, never how a body is spelled.</p>
     *
     * @return {@link Optional#empty()} when the body is not that document at all
     */
    static Optional<DocumentBalance> readDocument(BalanceWire wire, String body) {
        if (wire == null) {
            return Optional.empty();
        }
        return switch (wire) {
            case DEEPSEEK -> parseBalances(body).map(balances -> preferred(balances)
                    .map(balance -> new DocumentBalance(balance.totalBalanceMicros(), balance.currencyCode()))
                    // A valid answer that carried no figure: unknown, not zero.
                    .orElseGet(() -> new DocumentBalance(LlmAccountSnapshot.UNKNOWN_AMOUNT, "")));
            case MOONSHOT -> parseAvailableMicros(body)
                    .map(micros -> new DocumentBalance(micros, USD));
            case OPENAI_BILLING, NONE -> Optional.empty();
        };
    }

    /**
     * The billing pair read into one figure: the subscription's cap minus the reported spend.
     *
     * <p>Two documents for one answer, so neither is a balance on its own and the pair is judged
     * together: one unreadable half makes the whole remainder unknown rather than guessed. A provider
     * that spent past its cap reports a negative remainder; that is clamped to zero, which is the
     * readable form of "nothing left" and never a negative amount.</p>
     */
    static Optional<DocumentBalance> readBilling(String subscriptionBody, String usageBody) {
        Optional<Long> limit = parseBillingLimitMicros(subscriptionBody);
        Optional<Long> usage = parseBillingUsageMicros(usageBody);
        if (limit.isEmpty() || usage.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DocumentBalance(Math.max(0L, limit.get() - usage.get()), USD));
    }

    // --- other documented answer shapes ------------------------------------------------------------

    private static final String MOONSHOT_DATA_FIELD = "data";
    private static final String MOONSHOT_AVAILABLE_FIELD = "available_balance";
    private static final String BILLING_HARD_LIMIT_FIELD = "hard_limit_usd";
    private static final String BILLING_SYSTEM_LIMIT_FIELD = "system_hard_limit_usd";
    private static final String BILLING_USAGE_FIELD = "total_usage";
    /** One cent is 1e-2 of a dollar, i.e. 1e4 micro units. */
    private static final int CENT_DIGITS = 4;
    /** The currency of the Moonshot and OpenAI-billing documents; both state USD by documentation. */
    private static final String USD = "USD";

    /**
     * Moonshot/Kimi's documented answer: {@code {"data":{"available_balance":49.58894,...}}}.
     *
     * <p>The figure is in USD by documentation, so no currency is read from the body — there is none
     * to read. The available balance already includes the voucher part, which is the number that
     * matters: it is what the endpoint stops accepting requests at zero.</p>
     *
     * @return {@link Optional#empty()} when the body carries no readable amount
     */
    static Optional<Long> parseAvailableMicros(String body) {
        JsonObject root = objectOf(body);
        if (root == null) {
            return Optional.empty();
        }
        JsonElement data = root.get(MOONSHOT_DATA_FIELD);
        if (data == null || !data.isJsonObject()) {
            return Optional.empty();
        }
        return decimalOf(data.getAsJsonObject(), MOONSHOT_AVAILABLE_FIELD)
                .map(LlmAccountBalanceParser::microsOf)
                .filter(micros -> micros != LlmAccountSnapshot.UNKNOWN_AMOUNT);
    }

    /**
     * The OpenAI billing subscription document, which states the account's cap in dollars.
     *
     * <p>{@code hard_limit_usd} is the documented field; relays that only state the system default
     * send {@code system_hard_limit_usd}. Either is the cap the remaining amount is measured against.</p>
     *
     * @return {@link Optional#empty()} when neither field carries an amount
     */
    static Optional<Long> parseBillingLimitMicros(String body) {
        JsonObject root = objectOf(body);
        if (root == null) {
            return Optional.empty();
        }
        Optional<BigDecimal> limit = decimalOf(root, BILLING_HARD_LIMIT_FIELD);
        if (limit.isEmpty()) {
            limit = decimalOf(root, BILLING_SYSTEM_LIMIT_FIELD);
        }
        return limit.map(LlmAccountBalanceParser::microsOf)
                .filter(micros -> micros != LlmAccountSnapshot.UNKNOWN_AMOUNT);
    }

    /**
     * The OpenAI billing usage document, which states the spend in <em>cents</em>.
     *
     * <p>The unit is the trap in this pair: the field is {@code total_usage} and it is cents, not
     * dollars, which is why the conversion is its own named constant rather than a copy of the
     * six-digit rule used for dollar amounts.</p>
     *
     * @return {@link Optional#empty()} when the body carries no readable amount
     */
    static Optional<Long> parseBillingUsageMicros(String body) {
        JsonObject root = objectOf(body);
        if (root == null) {
            return Optional.empty();
        }
        return decimalOf(root, BILLING_USAGE_FIELD)
                .map(cents -> microsOf(cents, CENT_DIGITS))
                .filter(micros -> micros != LlmAccountSnapshot.UNKNOWN_AMOUNT);
    }

    private static JsonObject objectOf(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (JsonParseException unreadable) {
            return null;
        }
    }

    /**
     * A decimal field of any JSON shape: a number as sent by most providers, or a numeric string as
     * DeepSeek sends its amounts.
     */
    private static Optional<BigDecimal> decimalOf(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return Optional.empty();
        }
        try {
            return Optional.of(element.getAsBigDecimal());
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
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
