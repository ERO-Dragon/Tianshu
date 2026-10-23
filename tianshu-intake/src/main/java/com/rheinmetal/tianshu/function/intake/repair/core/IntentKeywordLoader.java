package com.rheinmetal.tianshu.function.intake.repair.core;

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

    /**
     * The keyword table this module ships, next to this class.
     *
     * <p>It lives here rather than in the client's resource-pack assets because the reader is shared with
     * the platform: one json file is shipped beside the code that owns it, and the resource-pack file of the
     * same name is an optional override on top.</p>
     */
    private static final String BUNDLED_RESOURCE = "ir_intent_keywords.json";
    private static final Set<String> META_KEYS = Set.of(
        "PRIORITY_SPLITTERS", "PARALLEL_SPLITTERS", "NEGATIONS", "FILLER_WORDS", "ENTITY_BOUNDARY_WORDS"
    );
    private static final Gson GSON = new Gson();
    private static volatile Map<String, String[]> cachedKeywords;

    private IntentKeywordLoader() {
    }

    /**
     * Replaces the table with a resource-pack override.
     *
     * <p>An override that fails to parse or parses to nothing is ignored instead of installed: silently
     * clearing the table would turn every filler and boundary word off while the lane still looked healthy.
     */
    public static void reload(InputStream inputStream) {
        Map<String, String[]> parsed = parseJson(inputStream);
        if (!parsed.isEmpty()) {
            cachedKeywords = parsed;
        }
    }

    /**
     * The keyword table in force.
     *
     * <p>The bundled file is read on first use. It used to arrive only from the platform's resource reload,
     * so anything that read the table before the first reload — command parsing among them — saw no
     * keywords at all and simply did not strip filler or boundary words.</p>
     */
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
            cachedKeywords = readBundled();
            return cachedKeywords;
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

    /** The bundled table beside this class; an unreadable or absent file leaves the lane without keywords. */
    private static Map<String, String[]> readBundled() {
        try (InputStream input = IntentKeywordLoader.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            return parseJson(input);
        } catch (IOException unreadable) {
            return Collections.emptyMap();
        }
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
