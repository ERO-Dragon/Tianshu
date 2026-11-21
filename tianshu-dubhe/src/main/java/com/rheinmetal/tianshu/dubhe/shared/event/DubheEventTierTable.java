package com.rheinmetal.tianshu.dubhe.shared.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Built-in tier table and attention list for event kinds.
 *
 * <p>The table is data, not code: it ships as {@code dubhe_event_tiers.json} next to this package so the built-in kinds
 * can be audited against the design document without reading Java. It holds two separate things, because they
 * answer two separate questions:
 *
 * <ul>
 *   <li>{@code kinds} — how often each kind happens ({@link DubheEventTier});</li>
 *   <li>{@code list} — where the player put it on the attention list ({@link DubheEventList}); a kind that is absent is
 *       {@link DubheEventList#NEUTRAL}.</li>
 * </ul>
 *
 * <p>Lookups stay permissive on purpose: an unknown kind has no tier and is neutral, and the dispatcher turns that
 * into the most conservative fallback.
 *
 * <p>The table says nothing about wording. How a folded window reads is declared by the descriptor's
 * {@link DubheEventFold}, so a kind unknown to this table can still fold as long as its producer names a fold shape and
 * its template resolves through the session's text provider.
 */
public final class DubheEventTierTable {
    private static final String BUILTIN_RESOURCE =
            "/com/rheinmetal/tianshu/dubhe/shared/event/dubhe_event_tiers.json";
    private static final int SCHEMA_VERSION = 2;
    private static final String KINDS_FIELD = "kinds";
    private static final String LIST_FIELD = "list";

    private static final DubheEventTierTable BUILTIN = loadBuiltin();

    private final Map<String, Entry> entries;

    private DubheEventTierTable(Map<String, Entry> entries) {
        // Insertion order is kept so entries() reads in table order, which mirrors the design doc.
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** One table row: the tier of a kind and where it sits on the attention list. */
    public record Entry(String kind, DubheEventTier tier, DubheEventList list) {
    }

    /** Table shipped with the mod. */
    public static DubheEventTierTable builtin() {
        return BUILTIN;
    }

    public Optional<DubheEventTier> tierOf(String kind) {
        Entry entry = entry(kind);
        return entry == null || entry.tier() == null ? Optional.empty() : Optional.of(entry.tier());
    }

    /** Where the given kind sits on the attention list; a kind the table does not mention is neutral. */
    public DubheEventList listOf(String kind) {
        Entry entry = entry(kind);
        return entry == null ? DubheEventList.NEUTRAL : entry.list();
    }

    public boolean blacklisted(String kind) {
        return listOf(kind) == DubheEventList.BLACKLIST;
    }

    public boolean whitelisted(String kind) {
        return listOf(kind) == DubheEventList.WHITELIST;
    }

    public Collection<Entry> entries() {
        return entries.values();
    }

    public int size() {
        return entries.size();
    }

    public long count(DubheEventTier tier) {
        return entries.values().stream().filter(entry -> entry.tier() == tier).count();
    }

    public long count(DubheEventList list) {
        return entries.values().stream().filter(entry -> entry.list() == list).count();
    }

    /**
     * Parses a table document.
     *
     * @throws IllegalStateException when the document is malformed; a broken built-in table is a deployment error
     *                               that must fail fast instead of degrading
     */
    public static DubheEventTierTable fromJson(JsonObject json) {
        if (json == null || !json.has(KINDS_FIELD) || !json.get(KINDS_FIELD).isJsonObject()) {
            throw new IllegalStateException("event tier table has no " + KINDS_FIELD);
        }
        Map<String, Entry> parsed = new LinkedHashMap<>();
        JsonObject kinds = json.getAsJsonObject(KINDS_FIELD);
        for (Map.Entry<String, JsonElement> raw : kinds.entrySet()) {
            String kind = raw.getKey();
            if (kind == null || kind.isBlank()) {
                throw new IllegalStateException("event tier table contains a blank kind");
            }
            if (!raw.getValue().isJsonPrimitive()) {
                throw new IllegalStateException("event tier entry is not a tier value: " + kind);
            }
            DubheEventTier tier = DubheEventTier.fromId(raw.getValue().getAsString());
            if (tier == null) {
                throw new IllegalStateException("event tier entry has no valid tier: " + kind);
            }
            parsed.put(kind.trim(), new Entry(kind.trim(), tier, DubheEventList.NEUTRAL));
        }
        if (parsed.isEmpty()) {
            throw new IllegalStateException("event tier table is empty");
        }
        Map<String, Entry> withLists = new LinkedHashMap<>(parsed);
        if (json.has(LIST_FIELD) && json.get(LIST_FIELD).isJsonObject()) {
            for (Map.Entry<String, JsonElement> raw : json.getAsJsonObject(LIST_FIELD).entrySet()) {
                String kind = raw.getKey() == null ? "" : raw.getKey().trim();
                if (kind.isEmpty()) {
                    throw new IllegalStateException("event list contains a blank kind");
                }
                if (!raw.getValue().isJsonPrimitive()) {
                    throw new IllegalStateException("event list entry is not a list value: " + kind);
                }
                DubheEventList list = DubheEventList.fromId(raw.getValue().getAsString());
                Entry existing = withLists.get(kind);
                if (existing == null) {
                    // A listed kind with no tier is legal: the tier falls back to the most conservative one, while the
                    // list still moves it. Keeping the row makes the audit read the file, not guess.
                    withLists.put(kind, new Entry(kind, null, list));
                } else {
                    withLists.put(kind, new Entry(kind, existing.tier(), list));
                }
            }
        }
        return new DubheEventTierTable(withLists);
    }

    private Entry entry(String kind) {
        return kind == null ? null : entries.get(kind.trim());
    }

    private static DubheEventTierTable loadBuiltin() {
        try (InputStream stream = DubheEventTierTable.class.getResourceAsStream(BUILTIN_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing event tier table resource: " + BUILTIN_RESOURCE);
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement element = JsonParser.parseReader(reader);
                if (!element.isJsonObject()) {
                    throw new IllegalStateException("event tier table resource is not a JSON object");
                }
                JsonObject json = element.getAsJsonObject();
                int schemaVersion = json.has("schemaVersion") && !json.get("schemaVersion").isJsonNull()
                        ? json.get("schemaVersion").getAsInt()
                        : SCHEMA_VERSION;
                if (schemaVersion != SCHEMA_VERSION) {
                    throw new IllegalStateException("unsupported event tier table schema: " + schemaVersion);
                }
                return fromJson(json);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to read event tier table resource", e);
        }
    }
}
