package com.rheinmetal.tianshu.function.weaving.account;

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
 * <p>Upstream endpoints, checked against https://api-docs.deepseek.com/zh-cn/api/get-user-balance
 * (DeepSeek API reference, read 2026-11):
 * <ul>
 *   <li>{@code GET https://api.deepseek.com/user/balance} with the usual bearer credential answers
 *       {@code {"is_available": boolean, "balance_infos": [{"currency": "CNY"|"USD",
 *       "total_balance": "110.00", "granted_balance": "10.00", "topped_up_balance": "100.00"}]}}.
 *       The amounts are decimal strings and one answer may carry several currencies. That single
 *       figure is what {@link LlmAccountSnapshot#balanceMicros()} can be filled from.</li>
 *   <li>The provider publishes <em>no</em> cost or usage endpoint: there is no authoritative source
 *       for today's or the total spend. An implementation must therefore keep
 *       {@link LlmAccountSnapshot#todayCostMicros()} and
 *       {@link LlmAccountSnapshot#totalCostMicros()} at {@link LlmAccountSnapshot#UNKNOWN_AMOUNT}
 *       rather than deriving them locally — the display layer keeps the locally converted amount in
 *       its own group for exactly that reason.</li>
 * </ul>
 * {@link DeepSeekBalanceProbe} is the implementation this build binds: it reads the documented balance
 * endpoint and leaves the cost figures unknown, because the provider publishes none. {@link #NOT_WIRED}
 * remains the null object an unbound host starts from, so a query never silently reports a zero.
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
