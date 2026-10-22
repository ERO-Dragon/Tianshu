package com.rheinmetal.tianshu.function.auxilium.module.system;

public interface AXPromptLanguageProvider {
    AXPromptLanguage currentLanguage();

    static AXPromptLanguageProvider fixed(AXPromptLanguage language) {
        AXPromptLanguage effectiveLanguage = language == null ? AXPromptLanguage.EN_US : language;
        return () -> effectiveLanguage;
    }
}
