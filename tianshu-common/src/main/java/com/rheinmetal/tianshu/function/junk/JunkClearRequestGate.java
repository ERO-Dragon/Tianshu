package com.rheinmetal.tianshu.function.junk;

import com.rheinmetal.tianshu.core.FeatureManager;

import java.util.List;

public final class JunkClearRequestGate {
    private JunkClearRequestGate() {
    }

    public static Decision evaluate(List<String> itemIds) {
        if (!FeatureManager.isAutoTrashAllowed()) {
            return Decision.denied("服务器不支持自动清理");
        }
        if (itemIds == null || itemIds.isEmpty()) {
            return Decision.denied("垃圾清单为空");
        }
        List<String> sanitized = itemIds.stream()
                .filter(JunkItemIdPolicy::canPersistAsJunk)
                .distinct()
                .limit(256)
                .toList();
        if (sanitized.isEmpty()) {
            return Decision.denied("垃圾清单为空");
        }
        return Decision.allowed(sanitized);
    }

    public record Decision(boolean allowed, List<String> itemIds, String message) {
        public static Decision allowed(List<String> itemIds) {
            return new Decision(true, itemIds == null ? List.of() : itemIds, "");
        }

        public static Decision denied(String message) {
            return new Decision(false, List.of(), message == null ? "" : message);
        }
    }
}
