package com.rheinmetal.tianshu.function.auxilium.event;

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
 * Built-in tier and blacklist table for event kinds.
 *
 * <p>The table is data, not code: it ships as {@code ax_event_tiers.json} next to this package so the
 * built-in kinds can be audited against the design document without reading Java. Lookups stay
 * permissive on purpose - an unknown kind resolves to an empty tier and a {@code false} blacklist, and
 * the dispatcher turns that into the {@code low} fallback.
 *
 * <p>The table says nothing about wording. How a folded window reads is declared by the producer
 * through {@link AXEventAggregateRenderer}, so a kind unknown to this table can still fold if its
 * producer supplies a renderer.
 */
public final class AXEventTierTable {
    private static final String BUILTIN_RESOURCE =
            "/com/rheinmetal/tianshu/function/auxilium/event/ax_event_tiers.json";
    private static final int SCHEMA_VERSION = 1;
    private static final String KINDS_FIELD = "kinds";

    private static final AXEventTierTable BUILTIN = loadBuiltin();

    private final Map<String, Entry> entries;

    private AXEventTierTable(Map<String, Entry> entries) {
        // Insertion order is kept so entries() reads in table order, which mirrors the design doc.
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** One table row: the tier of a kind and whether it is blacklisted. */
    public record Entry(String kind, AXEventTier tier, boolean blacklist) {
    }

    /** Table shipped with the mod. */
    public static AXEventTierTable builtin() {
        return BUILTIN;
    }

    public Optional<AXEventTier> tierOf(String kind) {
        Entry entry = entry(kind);
        return entry == null ? Optional.empty() : Optional.of(entry.tier());
    }

    public boolean blacklisted(String kind) {
        Entry entry = entry(kind);
        return entry != null && entry.blacklist();
    }

    public Collection<Entry> entries() {
        return entries.values();
    }

    public int size() {
        return entries.size();
    }

    public long count(AXEventTier tier) {
        return entries.values().stream().filter(entry -> entry.tier() == tier).count();
    }

    public long countBlacklisted() {
        return entries.values().stream().filter(Entry::blacklist).count();
    }

    /**
     * Parses a table document.
     *
     * @throws IllegalStateException when the document is malformed; a broken built-in table is a
     *                               deployment error that must fail fast instead of degrading
     */
    public static AXEventTierTable fromJson(JsonObject json) {
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
            if (!raw.getValue().isJsonObject()) {
                throw new IllegalStateException("event tier entry is not an object: " + kind);
            }
            JsonObject row = raw.getValue().getAsJsonObject();
            AXEventTier tier = AXEventTier.fromId(readString(row, "tier"));
            if (tier == null) {
                throw new IllegalStateException("event tier entry has no valid tier: " + kind);
            }
            boolean blacklist = readBoolean(row, "blacklist");
            parsed.put(kind.trim(), new Entry(kind.trim(), tier, blacklist));
        }
        if (parsed.isEmpty()) {
            throw new IllegalStateException("event tier table is empty");
        }
        return new AXEventTierTable(parsed);
    }

    private Entry entry(String kind) {
        return kind == null ? null : entries.get(kind.trim());
    }

    private static AXEventTierTable loadBuiltin() {
        try (InputStream stream = AXEventTierTable.class.getResourceAsStream(BUILTIN_RESOURCE)) {
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

    private static String readString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private static boolean readBoolean(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() && json.get(key).getAsBoolean();
    }
}
