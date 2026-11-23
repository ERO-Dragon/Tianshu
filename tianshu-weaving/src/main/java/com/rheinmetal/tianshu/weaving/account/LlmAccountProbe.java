package com.rheinmetal.tianshu.weaving.account;

import java.util.function.Consumer;

/**
 * Async source of provider-side account figures (remaining balance, today's and total cost).
 *
 * <p>This is the seam the HUD reads through: the panel never calls a provider itself and never
 * blocks the render thread, so a slow or hanging endpoint can only delay the numbers, not the
 * frame.
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li>{@link #query} must return without blocking the caller and must do the network work on its
 *       own thread.</li>
 *   <li>{@link #query} must invoke the sink exactly once per call, from any thread; a failure is
 *       reported as {@link LlmAccountSnapshot#failed}, never as a numeric fallback.</li>
 *   <li>Amounts the provider does not expose are reported as
 *       {@link LlmAccountSnapshot#UNKNOWN_AMOUNT}; there is no local estimate on this path.</li>
 * </ul>
 *
 * <p>Upstream endpoints belong to each provider, so the shape and the path are entry data
 * ({@link com.rheinmetal.tianshu.weaving.settings.LlmBalanceSpec}) rather than a code path per
 * provider. The documented shapes are, checked 2026-11:
 * <ul>
 *   <li>DeepSeek — {@code GET https://api.deepseek.com/user/balance} answers
 *       {@code {"is_available": boolean, "balance_infos": [{"currency": "CNY"|"USD",
 *       "total_balance": "110.00", "granted_balance": "10.00", "topped_up_balance": "100.00"}]}}
 *       (https://api-docs.deepseek.com/zh-cn/api/get-user-balance). The amounts are decimal strings
 *       and one answer may carry several currencies.</li>
 *   <li>Moonshot/Kimi — {@code GET {baseUrl}/users/me/balance} answers
 *       {@code {"code": 0, "data": {"available_balance": 49.58894, ...}, "status": true}} in USD
 *       (https://platform.kimi.ai/docs/api/balance).</li>
 *   <li>OpenAI billing shape — {@code /dashboard/billing/subscription} states the cap and
 *       {@code /dashboard/billing/usage} the spend in cents; the difference is the remainder. Relays
 *       that speak OpenAI's billing endpoints answer the same pair, which is why a custom endpoint may
 *       select this shape.</li>
 * </ul>
 * <p>None of these providers publishes a cost figure for today or for the account's lifetime, so an
 * implementation keeps {@link LlmAccountSnapshot#todayCostMicros()} and
 * {@link LlmAccountSnapshot#totalCostMicros()} at {@link LlmAccountSnapshot#UNKNOWN_AMOUNT} rather
 * than deriving them locally — the display layer keeps the locally converted estimate in its own group
 * for exactly that reason. A provider that publishes no balance endpoint is reported as "unsupported",
 * never as a zero.
 *
 * <p>{@link ProviderBalanceProbe} is the implementation this build binds. {@link #NOT_WIRED} remains
 * the null object an unbound host starts from, so a query never silently reports a zero.
 */
@FunctionalInterface
public interface LlmAccountProbe {
    /**
     * Null object for a host that bound no implementation yet. It answers immediately with an
     * {@link LlmAccountQueryState#UNWIRED} snapshot and is never the production probe.
     */
    LlmAccountProbe NOT_WIRED = new LlmAccountProbe() {
        @Override
        public boolean wired() {
            return false;
        }

        @Override
        public void query(LlmAccountKey key, Consumer<LlmAccountSnapshot> sink) {
            if (sink != null) {
                sink.accept(LlmAccountSnapshot.unwired(key));
            }
        }
    };

    /**
     * Whether an implementation is bound. A query against an unwired probe is skipped entirely so
     * the placeholder state never flickers into "querying".
     */
    default boolean wired() {
        return true;
    }

    void query(LlmAccountKey key, Consumer<LlmAccountSnapshot> sink);
}
