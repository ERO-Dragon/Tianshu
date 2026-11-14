package com.rheinmetal.tianshu.function.auxilium.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class AXHashing {
    private AXHashing() {
    }

    public static String sha256Short(String value) {
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
