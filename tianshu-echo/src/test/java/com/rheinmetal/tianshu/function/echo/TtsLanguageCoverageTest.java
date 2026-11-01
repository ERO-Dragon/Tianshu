package com.rheinmetal.tianshu.function.echo;

import com.rheinmetal.tianshu.function.echo.model.TtsModelInfo;
import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.testkit.LangShards;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings page renders keys and lets the host resolve them, so a key the module never ships
 * reaches the player as its raw id. These checks tie the key set to the code that computes it:
 * adding a failure code or an engine type without its text fails here instead of in the UI, and a
 * description key whose engine no longer exists is reported instead of being shipped forever.
 */
class TtsLanguageCoverageTest {
    private static final String FAILURE_PREFIX = "tianshu.gui.tts.failure.";
    private static final String ENGINE_PREFIX = "tianshu.gui.tts.model.description.";
    private static final String ENGINE_CONSTANT_PREFIX = "ENGINE_";

    @Test
    void everyFailureCodeHasTextInBothLanguages() {
        Set<String> missing = new TreeSet<>();
        for (String language : List.of(LangShards.ZH_CN, LangShards.EN_US)) {
            Set<String> keys = LangShards.keys(language);
            for (TtsFailureCode code : TtsFailureCode.values()) {
                String key = FAILURE_PREFIX + code.name().toLowerCase(Locale.ROOT);
                if (!keys.contains(key)) {
                    missing.add(language + ": " + key);
                }
            }
        }
        assertTrue(missing.isEmpty(), "Failure codes without text: " + missing);
    }

    @Test
    void everyEngineTypeHasDescriptionInBothLanguages() {
        Set<String> missing = new TreeSet<>();
        for (String language : List.of(LangShards.ZH_CN, LangShards.EN_US)) {
            Set<String> keys = LangShards.keys(language);
            for (String engine : engineTypes()) {
                String key = ENGINE_PREFIX + engine;
                if (!keys.contains(key)) {
                    missing.add(language + ": " + key);
                }
            }
        }
        assertTrue(missing.isEmpty(), "Engine types without a description key: " + missing);
    }

    @Test
    void noDescriptionKeyWithoutAMatchingEngineType() {
        Set<String> engines = engineTypes();
        Set<String> orphaned = new TreeSet<>();
        for (String language : List.of(LangShards.ZH_CN, LangShards.EN_US)) {
            for (String key : LangShards.keys(language)) {
                if (key.startsWith(ENGINE_PREFIX) && !engines.contains(key.substring(ENGINE_PREFIX.length()))) {
                    orphaned.add(language + ": " + key);
                }
            }
        }
        assertTrue(orphaned.isEmpty(), "Description keys for engines that no longer exist: " + orphaned);
    }

    /** The engine values {@link TtsModelInfo} can report, read from the class instead of restated here. */
    private static Set<String> engineTypes() {
        Set<String> engines = new TreeSet<>();
        for (Field field : TtsModelInfo.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != String.class
                    || !Modifier.isStatic(modifiers)
                    || !Modifier.isFinal(modifiers)
                    || !field.getName().startsWith(ENGINE_CONSTANT_PREFIX)) {
                continue;
            }
            try {
                field.setAccessible(true);
                engines.add((String) field.get(null));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("cannot read engine constant " + field.getName(), exception);
            }
        }
        if (engines.isEmpty()) {
            throw new IllegalStateException("no engine constant found on " + TtsModelInfo.class.getName());
        }
        return engines;
    }
}
