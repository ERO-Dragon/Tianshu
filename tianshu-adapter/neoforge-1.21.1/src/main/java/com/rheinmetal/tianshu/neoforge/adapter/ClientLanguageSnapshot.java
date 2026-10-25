package com.rheinmetal.tianshu.neoforge.adapter;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe language value captured from Minecraft on the client thread.
 *
 * <p>It holds the platform's own locale code. Which prompt language that maps to is a module decision,
 * so this class never names one.</p>
 */
public final class ClientLanguageSnapshot {

    private static final String DEFAULT_LANGUAGE_CODE = "en_us";

    private final AtomicReference<String> languageCode = new AtomicReference<>(DEFAULT_LANGUAGE_CODE);

    public void update(String code) {
        languageCode.set(normalize(code));
    }

    public String languageCode() {
        return languageCode.get();
    }

    private static String normalize(String code) {
        if (code == null || code.isBlank()) {
            return DEFAULT_LANGUAGE_CODE;
        }
        return code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
