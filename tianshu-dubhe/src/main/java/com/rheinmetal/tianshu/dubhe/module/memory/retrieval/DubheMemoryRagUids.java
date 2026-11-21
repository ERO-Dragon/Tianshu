package com.rheinmetal.tianshu.dubhe.module.memory.retrieval;

import com.rheinmetal.tianshu.dubhe.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.storage.DubheStorageLayout;

public final class DubheMemoryRagUids {
    private static final String PREFIX = "module.dubhe.memory";

    private DubheMemoryRagUids() {
    }

    public static String l1(DubheScope scope) {
        return PREFIX + "." + safeWorld(scope) + ".l1";
    }

    public static String l2(DubheScope scope, String l2ClusterId) {
        return PREFIX + "." + safeWorld(scope) + ".l2." + DubheStorageLayout.safeName(l2ClusterId);
    }

    public static String l2ClusterId(String uid) {
        if (uid == null || uid.isBlank()) {
            return "";
        }
        String marker = ".l2.";
        int index = uid.indexOf(marker);
        return index < 0 ? "" : uid.substring(index + marker.length()).trim();
    }

    private static String safeWorld(DubheScope scope) {
        return DubheStorageLayout.safeName(scope == null ? "unknown_world" : scope.worldId());
    }
}
