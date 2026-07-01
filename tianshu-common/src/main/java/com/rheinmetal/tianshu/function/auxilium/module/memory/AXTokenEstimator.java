package com.rheinmetal.tianshu.function.auxilium.module.memory;

public final class AXTokenEstimator {
    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int ascii = 0;
        int nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c <= 127) {
                ascii++;
            } else {
                nonAscii++;
            }
        }
        return Math.max(1, nonAscii + (ascii + 3) / 4);
    }
}
