package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads the possible item ids out of loot-table JSON.
 *
 * <p>The game exposes a loot table as an opaque object, not as its entries, so the server hands us the datapack JSON
 * and this reader flattens it. It follows nested loot tables a bounded number of times and ignores everything that is
 * not an item entry: functions, conditions and counts are not part of "what can it drop".</p>
 *
 * <p>Every read is shape-tolerant. A datapack — or a mod's generated table — may legally write a field as an object or
 * an array where a string is expected, and one such field used to throw out of here and cost the whole table. A value
 * of the wrong shape now reads as "absent": the entry is skipped, the rest of the table is still answered.</p>
 */
public final class LootTableJsonReader {

    private static final int DEFAULT_MAX_DEPTH = 4;
    private static final String ITEM_TYPE = "minecraft:item";
    private static final String TABLE_TYPE = "minecraft:loot_table";

    private LootTableJsonReader() {
    }

    /**
     * @param table     the parsed loot-table JSON
     * @param tableById resolves a referenced loot-table id to its JSON, or {@code null}
     */
    public static Set<String> itemIds(JsonElement table, Function<String, JsonElement> tableById) {
        return itemIds(table, tableById, DEFAULT_MAX_DEPTH);
    }

    public static Set<String> itemIds(JsonElement table, Function<String, JsonElement> tableById, int maxDepth) {
        Set<String> items = new LinkedHashSet<>();
        collect(table, tableById, items, 0, maxDepth);
        return items;
    }

    private static void collect(JsonElement element, Function<String, JsonElement> tableById, Set<String> items,
                                int depth, int maxDepth) {
        if (element == null || depth > maxDepth) {
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                collect(child, tableById, items, depth, maxDepth);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        String type = text(object, "type");
        if (ITEM_TYPE.equals(type)) {
            String name = text(object, "name");
            if (name != null && !name.isBlank()) {
                items.add(name);
            }
        } else if (TABLE_TYPE.equals(type)) {
            String value = text(object, "value");
            if (value != null && tableById != null) {
                JsonElement referenced;
                try {
                    referenced = tableById.apply(value);
                } catch (RuntimeException unreadable) {
                    // One unresolvable reference must not lose every other entry in the table.
                    referenced = null;
                }
                collect(referenced, tableById, items, depth + 1, maxDepth);
            }
        }
        for (var entry : object.entrySet()) {
            collect(entry.getValue(), tableById, items, depth, maxDepth);
        }
    }

    /** One string field; a value of the wrong JSON shape is treated as absent rather than as a fatal error. */
    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}