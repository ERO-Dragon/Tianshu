package com.rheinmetal.tianshu.dubhe.dialogue.system;

import java.util.Locale;
import java.util.function.Supplier;

public interface DubhePromptLanguageProvider {

    DubhePromptLanguage currentLanguage();

    static DubhePromptLanguageProvider fixed(DubhePromptLanguage language) {
        DubhePromptLanguage effectiveLanguage = language == null ? DubhePromptLanguage.EN_US : language;
        return () -> effectiveLanguage;
    }

    /**
     * Maps the platform's language code to the prompt language.
     *
     * <p>The platform reports a locale string; which prompt language that means is this module's
     * decision, so the mapping lives here instead of the adapter having to know these constants.</p>
     */
    static DubhePromptLanguageProvider ofCode(Supplier<String> languageCode) {
        Supplier<String> effective = languageCode == null ? () -> "" : languageCode;
        return () -> DubhePromptLanguage.fromCode(normalize(effective.get()));
    }

    private static String normalize(String code) {
        if (code == null || code.isBlank()) {
            return DubhePromptLanguage.EN_US.code();
        }
        return code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
