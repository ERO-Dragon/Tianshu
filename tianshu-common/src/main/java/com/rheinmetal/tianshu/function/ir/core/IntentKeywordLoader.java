package com.rheinmetal.tianshu.function.ir.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class IntentKeywordLoader {

    private static final Set<String> META_KEYS = Set.of(
        "PRIORITY_SPLITTERS", "PARALLEL_SPLITTERS", "NEGATIONS", "FILLER_WORDS", "ENTITY_BOUNDARY_WORDS"
    );
    private static final Gson GSON = new Gson();
    private static volatile Map<String, String[]> cachedKeywords;

    private IntentKeywordLoader() {
    }

    public static void reload(InputStream inputStream) {
        cachedKeywords = parseJson(inputStream);
    }

    public static Map<String, String[]> load() {
        Map<String, String[]> cached = cachedKeywords;
        if (cached != null) {
            return cached;
        }
        synchronized (IntentKeywordLoader.class) {
            cached = cachedKeywords;
            if (cached != null) {
                return cached;
            }
            Map<String, String[]> loaded = Collections.emptyMap();
            cachedKeywords = loaded;
            return loaded;
        }
    }

    public static String[] getKeywords(String key) {
        String[] values = load().get(key);
        return values != null ? values : new String[0];
    }

    public static String[] getKeywords(Intent intent) {
        return getKeywords(intent.name());
    }

    public static String[] getEntityBoundaryWords() {
        LinkedHashSet<String> words = new LinkedHashSet<>();
        for (Map.Entry<String, String[]> entry : load().entrySet()) {
            if (!META_KEYS.contains(entry.getKey())) {
                Collections.addAll(words, entry.getValue());
            }
        }
        Collections.addAll(words, getKeywords("ENTITY_BOUNDARY_WORDS"));
        return words.toArray(new String[0]);
    }

    public static Set<Intent> getDetectableIntents() {
        return Collections.emptySet();
    }

    private static Map<String, String[]> parseJson(InputStream inputStream) {
        if (inputStream == null) {
            return Collections.emptyMap();
        }
        try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<LinkedHashMap<String, String[]>>() {}.getType();
            Map<String, String[]> result = GSON.fromJson(reader, type);
            return result != null ? result : Collections.emptyMap();
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }
}
