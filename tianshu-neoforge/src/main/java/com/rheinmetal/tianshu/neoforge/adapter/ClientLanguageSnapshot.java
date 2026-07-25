package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe language value captured from Minecraft on the client thread. */
public final class ClientLanguageSnapshot {
    private final AtomicReference<String> languageCode = new AtomicReference<>(AXPromptLanguage.EN_US.code());

    public void update(String code) {
        languageCode.set(normalize(code));
    }

    public String languageCode() {
        return languageCode.get();
    }

    public AXPromptLanguage promptLanguage() {
        return AXPromptLanguage.fromCode(languageCode());
    }

    private static String normalize(String code) {
        if (code == null || code.isBlank()) {
            return AXPromptLanguage.EN_US.code();
        }
        return code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
