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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Built-in tier table and per-row attention states for event kinds.
 *
 * <p>The table is data, not code: it ships as {@code dubhe_event_tiers.json} next to this package so the built-in
 * kinds can be audited against the design document without reading Java. Each kind declares:</p>
 *
 * <ul>
 *   <li>{@code tier} — how often it happens ({@link DubheEventTier});</li>
 *   <li>{@code states} — one {@link DubheEventState} per reachable row. Only the rows its tier can be corrected to
 *       are stored: a rare kind has three, a normal kind two, a frequent kind one.</li>
 * </ul>
 *
 * <p>Lookups stay permissive: an unknown kind has no tier and is {@link DubheEventState#NORMAL}, the dispatcher then
 * falls back to the most conservative row.</p>
 */
public final class DubheEventTierTable {
    private static final String BUILTIN_RESOURCE =
            "/com/rheinmetal/tianshu/dubhe/shared/event/dubhe_event_tiers.json";
    private static final int SCHEMA_VERSION = 5;
    private static final String KINDS_FIELD = "kinds";
    private static final String TIER_FIELD = "tier";
    private static final String STATES_FIELD = "states";

    private static final DubheEventTierTable BUILTIN = loadBuiltin();

    private final Map<String, Entry> entries;

    private DubheEventTierTable(Map<String, Entry> entries) {
        // Insertion order is kept so entries() reads in table order, which mirrors the design doc.
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** One table row: the declared tier of a kind and its state on each reachable frequency row. */
    public record Entry(String kind, DubheEventTier tier, Map<DubheEventTier, DubheEventState> states) {

        public Entry {
            states = states == null ? Map.of() : Map.copyOf(states);
        }

        /** The state on {@code row}; rows the kind cannot reach fall back to the declared row's state. */
        public DubheEventState stateOn(DubheEventTier row) {
            DubheEventState declared = states.get(tier);
            return states.getOrDefault(row, declared == null ? DubheEventState.NORMAL : declared);
        }
    }

    /** Table shipped with the mod. */
    public static DubheEventTierTable builtin() {
        return BUILTIN;
    }

    public Optional<DubheEventTier> tierOf(String kind) {
        Entry entry = entry(kind);
        return entry == null || entry.tier() == null ? Optional.empty() : Optional.of(entry.tier());
    }

    /** The state of a kind on one row; unknown kinds and unknown rows are {@link DubheEventState#NORMAL}. */
    public DubheEventState stateFor(String kind, DubheEventTier row) {
        Entry entry = entry(kind);
        return entry == null ? DubheEventState.NORMAL : entry.stateOn(row);
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

    /** Kinds whose declared row carries {@code state}. */
    public long count(DubheEventState state) {
        return entries.values().stream()
                .filter(entry -> entry.stateOn(entry.tier()) == state)
                .count();
    }

    /**
     * Parses a v5 table document.
     *
     * @throws IllegalStateException when the document is malformed; a broken built-in table is a deployment error
     *                               that must fail fast instead of degrading
     */
    public static DubheEventTierTable fromJson(JsonObject json) {
        if (json == null || !json.has(KINDS_FIELD) || !json.get(KINDS_FIELD).isJsonObject()) {
            throw new IllegalStateException("event tier table has no " + KINDS_FIELD);
        }
        int version = json.has("schemaVersion") ? json.get("schemaVersion").getAsInt() : -1;
        if (version != SCHEMA_VERSION) {
            throw new IllegalStateException("event tier table schema is " + version + ", expected " + SCHEMA_VERSION);
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
            JsonObject body = raw.getValue().getAsJsonObject();
            DubheEventTier tier = DubheEventTier.fromId(text(body, TIER_FIELD));
            if (tier == null) {
                throw new IllegalStateException("event tier entry has no valid tier: " + kind);
            }
            Map<DubheEventTier, DubheEventState> states = new EnumMap<>(DubheEventTier.class);
            if (body.has(STATES_FIELD) && body.get(STATES_FIELD).isJsonObject()) {
                for (Map.Entry<String, JsonElement> stateRaw : body.getAsJsonObject(STATES_FIELD).entrySet()) {
                    DubheEventTier row = DubheEventTier.fromId(stateRaw.getKey());
                    if (row == null) {
                        throw new IllegalStateException("event state has no valid row: " + kind);
                    }
                    DubheEventState state = DubheEventState.fromId(stateRaw.getValue().getAsString());
                    states.put(row, state);
                }
            }
            if (!states.containsKey(tier)) {
                throw new IllegalStateException("event " + kind + " declares no state for its own row " + tier.id());
            }
            parsed.put(kind.trim(), new Entry(kind.trim(), tier, states));
        }
        return new DubheEventTierTable(parsed);
    }

    private Entry entry(String kind) {
        return kind == null ? null : entries.get(kind);
    }

    private static String text(JsonObject body, String field) {
        JsonElement value = body.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static DubheEventTierTable loadBuiltin() {
        try (InputStream stream = DubheEventTierTable.class.getResourceAsStream(BUILTIN_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing built-in event tier table: " + BUILTIN_RESOURCE);
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return fromJson(JsonParser.parseReader(reader).getAsJsonObject());
            }
        } catch (IllegalStateException broken) {
            throw broken;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot read the built-in event tier table", failure);
        }
    }
}