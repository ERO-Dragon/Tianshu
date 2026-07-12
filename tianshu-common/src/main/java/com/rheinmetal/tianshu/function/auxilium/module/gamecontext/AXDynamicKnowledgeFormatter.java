package com.rheinmetal.tianshu.function.auxilium.module.gamecontext;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptResourceRepository;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptTexts;
import com.rheinmetal.tianshu.protocol.PresenceContextFactIds;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AXDynamicKnowledgeFormatter {
    private final AXPromptResourceRepository resourceRepository;
    private final AXPromptLanguageProvider languageProvider;

    public AXDynamicKnowledgeFormatter(AXPromptResourceRepository resourceRepository, AXPromptLanguageProvider languageProvider) {
        this.resourceRepository = resourceRepository;
        this.languageProvider = languageProvider == null ? AXPromptLanguageProvider.fixed(AXPromptLanguage.EN_US) : languageProvider;
    }

    public AXDynamicFact format(PresenceContextSnapshotPayload.FactPayload fact) {
        if (fact == null || fact.nativeValues().isEmpty()) {
            return null;
        }
        AXPromptTexts texts = loadTexts();
        String rendered = switch (fact.factId()) {
            case PresenceContextFactIds.PLAYER_STATUS -> playerStatus(texts, fact.nativeValues());
            case PresenceContextFactIds.WORLD_ENVIRONMENT -> worldEnvironment(texts, fact.nativeValues());
            case PresenceContextFactIds.PLAYER_INVENTORY -> inventory(texts, fact.nativeValues());
            case PresenceContextFactIds.PLAYER_ACTIVE_EFFECTS -> activeEffects(texts, fact.nativeValues());
            case PresenceContextFactIds.INTERACTION_CONTEXT -> interaction(texts, fact.nativeValues());
            default -> "";
        };
        if (rendered.isBlank()) {
            return null;
        }
        return new AXDynamicFact(
                fact.factId(),
                rendered,
                fact.priority(),
                fact.source(),
                fact.subject(),
                fact.tags(),
                fact.updatedAtMillis(),
                fact.ttlMillis(),
                fact.nativeValues()
        );
    }

    private AXPromptTexts loadTexts() {
        AXPromptLanguage language = languageProvider.currentLanguage();
        if (resourceRepository == null) {
            return AXPromptTexts.builtin(language);
        }
        return resourceRepository.loadTexts(language);
    }

    private String playerStatus(AXPromptTexts texts, Map<String, String> values) {
        return texts.render(AXPromptTexts.DYNAMIC_KNOWLEDGE_PLAYER_STATUS, vars(
                "dimension", readableId(texts, values.get("dimensionId")),
                "health", values.get("health"),
                "hunger", values.get("hunger"),
                "experienceLevel", values.get("experienceLevel")
        ));
    }

    private String worldEnvironment(AXPromptTexts texts, Map<String, String> values) {
        return texts.render(AXPromptTexts.DYNAMIC_KNOWLEDGE_WORLD_ENVIRONMENT, vars(
                "biome", readable(values.get("biomeDisplayName"), readableId(texts, values.get("biomeId"))),
                "weather", weather(texts, values),
                "time", values.get("dayTimeTicks")
        ));
    }

    private String inventory(AXPromptTexts texts, Map<String, String> values) {
        JsonArray items = readArray(values.get("inventoryItemsJson"));
        if (items.isEmpty()) {
            return "";
        }
        Map<String, InventoryAmount> counts = new LinkedHashMap<>();
        for (JsonElement element : items) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            int count = intValue(item, "count", 0);
            if (count <= 0) {
                continue;
            }
            String name = readable(stringValue(item, "displayName"), readableId(texts, stringValue(item, "itemId")));
            InventoryAmount amount = counts.computeIfAbsent(name, ignored -> new InventoryAmount(intValue(item, "maxStackSize", 64)));
            amount.add(count, intValue(item, "maxStackSize", 64));
        }
        if (counts.isEmpty()) {
            return "";
        }
        List<String> lines = counts.entrySet().stream()
                .limit(8)
                .map(entry -> texts.render(AXPromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_ITEM, vars(
                        "name", entry.getKey(),
                        "amount", inventoryAmountText(texts, entry.getValue())
                )))
                .filter(text -> !text.isBlank())
                .toList();
        if (lines.isEmpty()) {
            return "";
        }
        return texts.render(AXPromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_ITEMS, vars(
                "items", String.join(texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_SEPARATOR), lines)
        ));
    }

    private String activeEffects(AXPromptTexts texts, Map<String, String> values) {
        JsonArray effects = readArray(values.get("activeEffectsJson"));
        if (effects.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (JsonElement element : effects) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject effect = element.getAsJsonObject();
            String name = readable(stringValue(effect, "displayName"), readableId(texts, stringValue(effect, "effectId")));
            if (name.isBlank()) {
                continue;
            }
            lines.add(texts.render(AXPromptTexts.DYNAMIC_KNOWLEDGE_EFFECTS_ENTRY, vars(
                    "name", name,
                    "level", effectLevel(texts, intValue(effect, "amplifier", 0) + 1),
                    "duration", durationText(texts, Math.max(0, intValue(effect, "durationTicks", 0) / 20))
            )));
            if (lines.size() >= 6) {
                break;
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        return texts.render(AXPromptTexts.DYNAMIC_KNOWLEDGE_EFFECTS_ACTIVE, vars(
                "effects", String.join(texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_SEPARATOR), lines)
        ));
    }

    private String interaction(AXPromptTexts texts, Map<String, String> values) {
        List<String> parts = new ArrayList<>();
        if (hasText(values.get("heldItemId"))) {
            parts.add(texts.render(AXPromptTexts.DYNAMIC_KNOWLEDGE_INTERACTION_HELD_ITEM, vars(
                    "item", readableId(texts, values.get("heldItemId"))
            )));
        }
        if (hasText(values.get("screenKind")) && !Objects.equals(values.get("screenKind"), "NONE")) {
            parts.add(texts.render(AXPromptTexts.DYNAMIC_KNOWLEDGE_INTERACTION_SCREEN, vars(
                    "screen", values.get("screenKind").toLowerCase(java.util.Locale.ROOT)
            )));
        }
        if (Boolean.parseBoolean(values.get("interactionKeyDown"))) {
            parts.add(texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_INTERACTION_USE_KEY));
        }
        if (Boolean.parseBoolean(values.get("attackKeyDown"))) {
            parts.add(texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_INTERACTION_ATTACK_KEY));
        }
        if (Boolean.parseBoolean(values.get("sneaking"))) {
            parts.add(texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_INTERACTION_SNEAKING));
        }
        if (hasText(values.get("crosshairTargetTypeId"))) {
            parts.add(texts.render(AXPromptTexts.DYNAMIC_KNOWLEDGE_INTERACTION_CROSSHAIR, vars(
                    "target", readable(values.get("crosshairTargetDisplayName"), readableId(texts, values.get("crosshairTargetTypeId"))),
                    "distance", values.get("crosshairTargetDistance")
            )));
        }
        List<String> cleanParts = parts.stream().filter(text -> text != null && !text.isBlank()).toList();
        if (cleanParts.isEmpty()) {
            return "";
        }
        return texts.render(AXPromptTexts.DYNAMIC_KNOWLEDGE_INTERACTION, vars(
                "details", String.join(texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_SEPARATOR), cleanParts)
        ));
    }

    private String weather(AXPromptTexts texts, Map<String, String> values) {
        if (Boolean.parseBoolean(values.get("thundering"))) {
            return texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_WEATHER_THUNDER);
        }
        if (Boolean.parseBoolean(values.get("raining"))) {
            return texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_WEATHER_RAIN);
        }
        return texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_WEATHER_CLEAR);
    }

    private String inventoryAmountText(AXPromptTexts texts, InventoryAmount amount) {
        if (amount == null || amount.count <= 0) {
            return texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_FEW);
        }
        int stackSize = Math.max(1, amount.maxStackSize);
        if (stackSize <= 1 || amount.count <= Math.max(1, stackSize / 8)) {
            return texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_FEW);
        }
        int halfStack = Math.max(1, stackSize / 2);
        if (amount.count < halfStack) {
            return texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_LESS_THAN_HALF_STACK);
        }
        if (amount.count == halfStack) {
            return texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_HALF_STACK);
        }
        if (amount.count < stackSize) {
            return texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_MORE_THAN_HALF_STACK);
        }
        if (amount.count == stackSize) {
            return texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_ONE_STACK);
        }
        if (amount.count < stackSize * 2) {
            return texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_ONE_STACK_MORE);
        }
        int fullStacks = amount.count / stackSize;
        String key = amount.count % stackSize == 0
                ? AXPromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_MULTI_STACK
                : AXPromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_MULTI_STACK_MORE;
        return texts.render(key, vars("stacks", stackCountText(texts, fullStacks)));
    }

    private String stackCountText(AXPromptTexts texts, int stacks) {
        int normalized = Math.max(2, stacks);
        String localized = texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_STACK_COUNT_PREFIX + normalized);
        return localized.isBlank() ? Integer.toString(normalized) : localized;
    }

    private String effectLevel(AXPromptTexts texts, int level) {
        int normalized = Math.max(1, level);
        String localized = texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_EFFECT_LEVEL_PREFIX + normalized);
        return localized.isBlank() ? Integer.toString(normalized) : localized;
    }

    private String durationText(AXPromptTexts texts, int seconds) {
        int normalized = Math.max(0, seconds);
        if (normalized < 60) {
            return texts.render(AXPromptTexts.DYNAMIC_KNOWLEDGE_DURATION_SECONDS, vars("seconds", Integer.toString(normalized)));
        }
        int minutes = normalized / 60;
        int remainingSeconds = normalized % 60;
        if (remainingSeconds == 0) {
            return texts.render(AXPromptTexts.DYNAMIC_KNOWLEDGE_DURATION_MINUTES, vars("minutes", Integer.toString(minutes)));
        }
        return texts.render(AXPromptTexts.DYNAMIC_KNOWLEDGE_DURATION_MINUTES_SECONDS, vars(
                "minutes", Integer.toString(minutes),
                "seconds", Integer.toString(remainingSeconds)
        ));
    }

    private JsonArray readArray(String value) {
        if (!hasText(value)) {
            return new JsonArray();
        }
        try {
            JsonElement element = JsonParser.parseString(value);
            return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
        } catch (RuntimeException ignored) {
            return new JsonArray();
        }
    }

    private String stringValue(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        return json.get(key).getAsString().trim();
    }

    private int intValue(JsonObject json, String key, int fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private String readable(String value, String fallback) {
        return hasText(value) ? value.trim() : clean(fallback);
    }

    private String readableId(AXPromptTexts texts, String value) {
        if (!hasText(value)) {
            return texts.text(AXPromptTexts.DYNAMIC_KNOWLEDGE_VALUE_UNKNOWN);
        }
        String normalized = value.trim();
        int separator = normalized.indexOf(':');
        if (separator >= 0 && separator < normalized.length() - 1) {
            normalized = normalized.substring(separator + 1);
        }
        return normalized.replace('_', ' ').replace('-', ' ');
    }

    private Map<String, String> vars(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(clean(values[index]), clean(values[index + 1]));
        }
        return result;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class InventoryAmount {
        private int count;
        private int maxStackSize;

        private InventoryAmount(int maxStackSize) {
            this.maxStackSize = maxStackSize <= 0 ? 64 : maxStackSize;
        }

        private void add(int value, int candidateMaxStackSize) {
            count += Math.max(0, value);
            maxStackSize = Math.max(maxStackSize, candidateMaxStackSize);
        }
    }
}
