package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

public final class AXMemoryRagUids {
    private static final String PREFIX = "module.ax.memory";

    private AXMemoryRagUids() {
    }

    public static String l1(AXScope scope) {
        return PREFIX + "." + safeWorld(scope) + ".l1";
    }

    public static String l2(AXScope scope, String l2ClusterId) {
        return PREFIX + "." + safeWorld(scope) + ".l2." + AXStorageLayout.safeName(l2ClusterId);
    }

    public static String l2ClusterId(String uid) {
        if (uid == null || uid.isBlank()) {
            return "";
        }
        String marker = ".l2.";
        int index = uid.indexOf(marker);
        return index < 0 ? "" : uid.substring(index + marker.length()).trim();
    }

    private static String safeWorld(AXScope scope) {
        return AXStorageLayout.safeName(scope == null ? "unknown_world" : scope.worldId());
    }
}
