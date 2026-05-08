package com.rheinmetal.tianshu.function.junk;

import java.util.Locale;

public final class JunkTextNormalizer {
    private JunkTextNormalizer() {
    }

    public static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace(" ", "").trim();
    }
}
