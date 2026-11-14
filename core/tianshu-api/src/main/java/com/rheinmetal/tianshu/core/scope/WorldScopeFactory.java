package com.rheinmetal.tianshu.core.scope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class WorldScopeFactory {
    private WorldScopeFactory() {
    }

    public static WorldScope fromSnapshot(WorldIdentitySnapshot snapshot) {
        if (snapshot == null || !snapshot.writable()) {
            return WorldScope.unknown();
        }
        String prefix = switch (snapshot.kind()) {
            case SERVER_WORLD -> "server_";
            case REALMS_WORLD -> "realms_";
            case LOCAL_WORLD -> "local_";
            case SHARED -> "shared_";
            default -> "unknown_";
        };
        String scopeMaterial = snapshot.kind().name() + "|" + snapshot.stableIdentity();
        String worldId = prefix + sha256Short(scopeMaterial);
        return new WorldScope("default_user", worldId, snapshot.displayName(), snapshot.kind(), true);
    }

    private static String sha256Short(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < Math.min(8, bytes.length); i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString((value == null ? "" : value).hashCode());
        }
    }
}
