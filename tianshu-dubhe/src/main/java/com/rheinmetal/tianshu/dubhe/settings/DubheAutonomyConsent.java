package com.rheinmetal.tianshu.dubhe.settings;

import com.rheinmetal.tianshu.api.tool.ToolAutonomy;

import java.util.Objects;

/**
 * 天枢自己的三档授权记录（DEC-1）：每个档位第一次使用其中的功能前，先问玩家一次。
 *
 * <p>状态只有两种：某档已确认，或未确认。未确认时使用该档的功能会把档位记为"待确认"并返回
 * {@code NEEDS_CONSENT}，由对话层问玩家；玩家同意后调用 {@link #confirm(ToolAutonomy)}，该档不再询问。</p>
 *
 * <p>存储键按档位分开（{@code autonomy.consent.<tier>}），所以"确认只读"不会顺带确认保守档。</p>
 */
public final class DubheAutonomyConsent {

    /** The two storage operations this class needs; keeps it independent of the config implementation. */
    public interface Storage {
        String get(String key, String fallback);

        void set(String key, String value);
    }

    private static final String KEY_PREFIX = "autonomy.consent.";
    private static final String KEY_PENDING = "autonomy.consent.pending";

    private final Storage storage;

    public DubheAutonomyConsent(Storage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /** True when this tier has not been confirmed by the player yet. */
    public boolean needsConsent(ToolAutonomy tier) {
        ToolAutonomy effective = tier == null ? ToolAutonomy.READ_ONLY : tier;
        return !"true".equalsIgnoreCase(storage.get(KEY_PREFIX + effective.name(), "false"));
    }

    /** Records that the player allowed this tier. Idempotent. */
    public void confirm(ToolAutonomy tier) {
        ToolAutonomy effective = tier == null ? ToolAutonomy.READ_ONLY : tier;
        storage.set(KEY_PREFIX + effective.name(), "true");
        if (effective == pendingTier()) {
            clearPending();
        }
    }

    /** The tier whose first use is waiting for the player's answer, or empty when nothing is pending. */
    public ToolAutonomy pendingTier() {
        return ToolAutonomy.parse(storage.get(KEY_PENDING, ""), null);
    }

    /** Remembers which tier the question was about; called by the gate, not by the player. */
    public void markPending(ToolAutonomy tier) {
        if (tier != null) {
            storage.set(KEY_PENDING, tier.name());
        }
    }

    public void clearPending() {
        storage.set(KEY_PENDING, "");
    }
}