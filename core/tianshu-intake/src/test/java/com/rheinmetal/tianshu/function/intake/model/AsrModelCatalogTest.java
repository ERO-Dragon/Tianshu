package com.rheinmetal.tianshu.function.intake.model;

import com.rheinmetal.tianshu.testkit.LangShards;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ASR catalog is curated, not mirrored from the upstream repository: every entry has to earn a role,
 * and the language cards can only be labelled when the shard carries the text. These checks tie that
 * curation to what the settings page actually renders, so the two facts that follow from it — which
 * model a fresh client lands on, and which language labels exist — fail here instead of in the UI.
 */
class AsrModelCatalogTest {
    private static final String MODULE_DIRECTORY = "tianshu-intake";
    private static final String LANGUAGE_PREFIX = "tianshu.gui.asr.lang.";

    /**
     * {@code IntakeConfig.defaultModelName} falls back to the first catalog entry listing the client
     * language, and push-to-talk only runs complete recognition. A streaming entry at the top of the
     * catalog would therefore hand every fresh Chinese or English client the weaker model for the one
     * path the runtime uses.
     */
    @Test
    void theClientLanguageDefaultIsAnOfflineModel() {
        for (String language : List.of("zh", "en")) {
            AsrModelInfo first = firstEntryListing(language);
            assertFalse(first.isStreamingModel(),
                    "the default for a " + language + " client must be an offline model: " + first.localKey());
        }
    }

    @Test
    void everyCatalogLanguageHasALabelInBothShards() {
        Set<String> missing = new TreeSet<>();
        for (String language : List.of(LangShards.ZH_CN, LangShards.EN_US)) {
            Set<String> keys = LangShards.keysIn(MODULE_DIRECTORY, language);
            for (String code : catalogLanguages()) {
                String key = LANGUAGE_PREFIX + code;
                if (!keys.contains(key)) {
                    missing.add(language + ": " + key);
                }
            }
        }
        assertTrue(missing.isEmpty(), "Catalog languages without a label: " + missing);
    }

    @Test
    void noLanguageLabelWithoutAMatchingCatalogEntry() {
        Set<String> languages = catalogLanguages();
        Set<String> orphaned = new TreeSet<>();
        for (String language : List.of(LangShards.ZH_CN, LangShards.EN_US)) {
            for (String key : LangShards.keysIn(MODULE_DIRECTORY, language)) {
                if (key.startsWith(LANGUAGE_PREFIX)
                        && !languages.contains(key.substring(LANGUAGE_PREFIX.length()))) {
                    orphaned.add(language + ": " + key);
                }
            }
        }
        assertTrue(orphaned.isEmpty(), "Language labels for codes the catalog no longer serves: " + orphaned);
    }

    private static AsrModelInfo firstEntryListing(String language) {
        return AsrModelManager.getAllModels().stream()
                .filter(info -> info.getLang().stream()
                        .anyMatch(code -> code != null && code.trim().toLowerCase(Locale.ROOT).startsWith(language)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no catalog entry lists " + language));
    }

    private static Set<String> catalogLanguages() {
        Set<String> languages = new LinkedHashSet<>();
        for (AsrModelInfo info : AsrModelManager.getAllModels()) {
            for (String code : info.getLang()) {
                if (code != null && !code.isBlank()) {
                    languages.add(code.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return languages;
    }
}