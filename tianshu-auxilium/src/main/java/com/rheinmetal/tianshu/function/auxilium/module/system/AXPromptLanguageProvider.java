package com.rheinmetal.tianshu.function.auxilium.module.system;

import java.util.Locale;
import java.util.function.Supplier;

public interface AXPromptLanguageProvider {

    AXPromptLanguage currentLanguage();

    static AXPromptLanguageProvider fixed(AXPromptLanguage language) {
        AXPromptLanguage effectiveLanguage = language == null ? AXPromptLanguage.EN_US : language;
        return () -> effectiveLanguage;
    }

    /**
     * Maps the platform's language code to the prompt language.
     *
     * <p>The platform reports a locale string; which prompt language that means is this module's
     * decision, so the mapping lives here instead of the adapter having to know these constants.</p>
     */
    static AXPromptLanguageProvider ofCode(Supplier<String> languageCode) {
        Supplier<String> effective = languageCode == null ? () -> "" : languageCode;
        return () -> AXPromptLanguage.fromCode(normalize(effective.get()));
    }

    private static String normalize(String code) {
        if (code == null || code.isBlank()) {
            return AXPromptLanguage.EN_US.code();
        }
        return code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
