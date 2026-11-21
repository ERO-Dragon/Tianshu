package com.rheinmetal.tianshu.dubhe.module.gamecontext;

import com.rheinmetal.tianshu.dubhe.module.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.module.system.DubhePromptLanguageProvider;
import com.rheinmetal.tianshu.dubhe.module.system.DubhePromptResourceRepository;
import com.rheinmetal.tianshu.dubhe.module.system.DubhePromptTexts;
import com.rheinmetal.tianshu.presence.protocol.PresenceContextFactIds;
import com.rheinmetal.tianshu.protocol.payload.FactValue;
import com.rheinmetal.tianshu.presence.payload.PresenceContextSnapshotPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DubheDynamicKnowledgeFormatter {
    private final DubhePromptResourceRepository resourceRepository;
    private final DubhePromptLanguageProvider languageProvider;

    public DubheDynamicKnowledgeFormatter(DubhePromptResourceRepository resourceRepository, DubhePromptLanguageProvider languageProvider) {
        this.resourceRepository = resourceRepository;
        this.languageProvider = languageProvider == null ? DubhePromptLanguageProvider.fixed(DubhePromptLanguage.EN_US) : languageProvider;
    }

    public DubheDynamicFact format(PresenceContextSnapshotPayload.FactPayload fact) {
        if (fact == null) {
            return null;
        }
        DubheFactView view = DubheFactView.of(fact.fields());
        if (view.isEmpty()) {
            return null;
        }
        DubhePromptTexts texts = loadTexts();
        String rendered = switch (fact.factId()) {
            case PresenceContextFactIds.PLAYER_STATUS -> playerStatus(texts, view);
            case PresenceContextFactIds.WORLD_ENVIRONMENT -> worldEnvironment(texts, view);
            case PresenceContextFactIds.PLAYER_INVENTORY -> inventory(texts, view);
            case PresenceContextFactIds.PLAYER_ACTIVE_EFFECTS -> activeEffects(texts, view);
            case PresenceContextFactIds.PLAYER_EQUIPMENT -> equipment(texts, view);
            case PresenceContextFactIds.INTERACTION_CONTEXT -> interaction(texts, view);
            default -> "";
        };
        if (rendered.isBlank()) {
            return null;
        }
        return new DubheDynamicFact(
                fact.factId(),
                rendered,
                fact.priority(),
                fact.source(),
                fact.subject(),
                fact.tags(),
                fact.updatedAtMillis(),
                fact.ttlMillis()
        );
    }

    private DubhePromptTexts loadTexts() {
        DubhePromptLanguage language = languageProvider.currentLanguage();
        if (resourceRepository == null) {
            return DubhePromptTexts.builtin(language);
        }
        return resourceRepository.loadTexts(language);
    }

    private String playerStatus(DubhePromptTexts texts, DubheFactView view) {
        return texts.render(DubhePromptTexts.DYNAMIC_KNOWLEDGE_PLAYER_STATUS, vars(
                "dimension", readableId(texts, view.text("dimensionId")),
                "health", view.text("health"),
                "hunger", view.text("hunger"),
                "experienceLevel", view.text("experienceLevel")
        ));
    }

    private String worldEnvironment(DubhePromptTexts texts, DubheFactView view) {
        return texts.render(DubhePromptTexts.DYNAMIC_KNOWLEDGE_WORLD_ENVIRONMENT, vars(
                "biome", readable(view.text("biomeDisplayName"), readableId(texts, view.text("biomeId"))),
                "weather", weather(texts, view),
                "time", clockTime(view.text("dayTimeTicks"))
        ));
    }

    private String inventory(DubhePromptTexts texts, DubheFactView view) {
        List<Map<String, FactValue>> items = view.records("item");
        if (items.isEmpty()) {
            return "";
        }
        Map<String, InventoryAmount> counts = new LinkedHashMap<>();
        for (Map<String, FactValue> item : items) {
            int count = DubheFactView.memberInt(item, "count", 0);
            if (count <= 0) {
                continue;
            }
            String name = readable(DubheFactView.memberText(item, "displayName"),
                    readableId(texts, DubheFactView.memberText(item, "itemId")));
            int maxStackSize = DubheFactView.memberInt(item, "maxStackSize", 64);
            InventoryAmount amount = counts.computeIfAbsent(name, ignored -> new InventoryAmount(maxStackSize));
            amount.add(count, maxStackSize);
        }
        if (counts.isEmpty()) {
            return "";
        }
        List<String> lines = counts.entrySet().stream()
                .limit(8)
                .map(entry -> texts.render(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_ITEM, vars(
                        "name", entry.getKey(),
                        "amount", inventoryAmountText(texts, entry.getValue())
                )))
                .filter(text -> !text.isBlank())
                .toList();
        if (lines.isEmpty()) {
            return "";
        }
        return texts.render(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_ITEMS, vars(
                "items", String.join(texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_SEPARATOR), lines)
        ));
    }

    private String activeEffects(DubhePromptTexts texts, DubheFactView view) {
        List<Map<String, FactValue>> effects = view.records("effect");
        if (effects.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, FactValue> effect : effects) {
            String name = readable(DubheFactView.memberText(effect, "displayName"),
                    readableId(texts, DubheFactView.memberText(effect, "effectId")));
            if (name.isBlank()) {
                continue;
            }
            lines.add(texts.render(DubhePromptTexts.DYNAMIC_KNOWLEDGE_EFFECTS_ENTRY, vars(
                    "name", name,
                    "level", effectLevel(texts, DubheFactView.memberInt(effect, "amplifier", 0) + 1),
                    "duration", durationText(texts, Math.max(0, DubheFactView.memberInt(effect, "durationTicks", 0) / 20))
            )));
            if (lines.size() >= 6) {
                break;
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        return texts.render(DubhePromptTexts.DYNAMIC_KNOWLEDGE_EFFECTS_ACTIVE, vars(
                "effects", String.join(texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_SEPARATOR), lines)
        ));
    }

    private String interaction(DubhePromptTexts texts, DubheFactView view) {
        List<String> parts = new ArrayList<>();
        if (hasText(view.text("heldItemId"))) {
            parts.add(texts.render(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INTERACTION_HELD_ITEM, vars(
                    "item", readableId(texts, view.text("heldItemId"))
            )));
        }
        if (hasText(view.text("screenKind")) && !Objects.equals(view.text("screenKind"), "NONE")) {
            parts.add(texts.render(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INTERACTION_SCREEN, vars(
                    "screen", view.text("screenKind").toLowerCase(java.util.Locale.ROOT)
            )));
        }
        if (Boolean.parseBoolean(view.text("interactionKeyDown"))) {
            parts.add(texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INTERACTION_USE_KEY));
        }
        if (Boolean.parseBoolean(view.text("attackKeyDown"))) {
            parts.add(texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INTERACTION_ATTACK_KEY));
        }
        if (Boolean.parseBoolean(view.text("sneaking"))) {
            parts.add(texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INTERACTION_SNEAKING));
        }
        if (hasText(view.text("crosshairTargetTypeId"))) {
            parts.add(texts.render(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INTERACTION_CROSSHAIR, vars(
                    "target", readable(view.text("crosshairTargetDisplayName"), readableId(texts, view.text("crosshairTargetTypeId"))),
                    "distance", view.text("crosshairTargetDistance")
            )));
        }
        List<String> cleanParts = parts.stream().filter(text -> text != null && !text.isBlank()).toList();
        if (cleanParts.isEmpty()) {
            return "";
        }
        return texts.render(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INTERACTION, vars(
                "details", String.join(texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_SEPARATOR), cleanParts)
        ));
    }

    /**
     * Wall-clock time of day for a tick count, as {@code HH:mm}.
     *
     * <p>A raw tick count means nothing to a reader, so the prompt says what a player would say: {@code 6000}
     * ticks is noon. Ticks start at 06:00 and a full day is 24000, so the hour is {@code ticks/1000 + 6} modulo
     * 24 — negative or out-of-range values are wrapped instead of blowing up, because a modded dimension may
     * report anything.</p>
     */
    private String clockTime(String ticks) {
        long raw;
        try {
            raw = Long.parseLong(ticks == null ? "" : ticks.trim());
        } catch (NumberFormatException notANumber) {
            return ticks == null ? "" : ticks;
        }
        long dayMillis = ((raw % 24000L) + 24000L) % 24000L;
        long hour = (dayMillis / 1000L + 6L) % 24L;
        long minute = (dayMillis % 1000L) * 60L / 1000L;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute);
    }
    /** Slot order the sentence lists equipment in; also the record field names the producer uses. */
    private static final List<String> EQUIPMENT_SLOTS = List.of(
            "helmet", "chestplate", "leggings", "boots", "mainHand", "offHand");

    /**
     * Worn and held items plus the armour number.
     *
     * <p>Only slots the player actually fills are mentioned, so an empty slot produces no clause at all — that is
     * why the producer omits the field instead of sending an empty stack. When nothing is worn or held the whole
     * sentence is dropped, because "Player equipment:" with an empty list tells the model less than saying
     * nothing.</p>
     */
    private String equipment(DubhePromptTexts texts, DubheFactView view) {
        List<String> parts = new ArrayList<>();
        for (String slot : EQUIPMENT_SLOTS) {
            List<Map<String, FactValue>> records = view.records(slot);
            if (records.isEmpty()) {
                continue;
            }
            Map<String, FactValue> record = records.get(0);
            String name = readable(DubheFactView.memberText(record, "displayName"),
                    readableId(texts, DubheFactView.memberText(record, "itemId")));
            if (name.isBlank()) {
                continue;
            }
            parts.add(texts.render(DubhePromptTexts.DYNAMIC_KNOWLEDGE_PLAYER_EQUIPMENT_SLOT,
                    vars("slot", slotLabel(texts, slot), "name", name)));
        }
        if (parts.isEmpty()) {
            return "";
        }
        return texts.render(DubhePromptTexts.DYNAMIC_KNOWLEDGE_PLAYER_EQUIPMENT, vars(
                "slots", String.join(texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_SEPARATOR), parts),
                "armorPoints", view.text("armorPoints")
        ));
    }
    /**
     * The word used for a slot inside the sentence.
     *
     * <p>The slot keys ({@code helmet}, {@code mainHand}, …) are identifiers, not words a player reads, so the
     * prompt names them through their own language keys and falls back to the raw key only when a template for
     * that slot is missing — an untranslated slot then looks odd, but it never renders as an empty clause.</p>
     */
    private String slotLabel(DubhePromptTexts texts, String slot) {
        String label = texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_PLAYER_EQUIPMENT_SLOT_LABEL_PREFIX + slot);
        return label.isBlank() ? slot : label;
    }
    private String weather(DubhePromptTexts texts, DubheFactView view) {
        if (Boolean.parseBoolean(view.text("thundering"))) {
            return texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_WEATHER_THUNDER);
        }
        if (Boolean.parseBoolean(view.text("raining"))) {
            return texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_WEATHER_RAIN);
        }
        return texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_WEATHER_CLEAR);
    }

    private String inventoryAmountText(DubhePromptTexts texts, InventoryAmount amount) {
        if (amount == null || amount.count <= 0) {
            return texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_FEW);
        }
        int stackSize = Math.max(1, amount.maxStackSize);
        if (stackSize <= 1 || amount.count <= Math.max(1, stackSize / 8)) {
            return texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_FEW);
        }
        int halfStack = Math.max(1, stackSize / 2);
        if (amount.count < halfStack) {
            return texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_LESS_THAN_HALF_STACK);
        }
        if (amount.count == halfStack) {
            return texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_HALF_STACK);
        }
        if (amount.count < stackSize) {
            return texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_MORE_THAN_HALF_STACK);
        }
        if (amount.count == stackSize) {
            return texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_ONE_STACK);
        }
        if (amount.count < stackSize * 2) {
            return texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_ONE_STACK_MORE);
        }
        int fullStacks = amount.count / stackSize;
        String key = amount.count % stackSize == 0
                ? DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_MULTI_STACK
                : DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_MULTI_STACK_MORE;
        return texts.render(key, vars("stacks", stackCountText(texts, fullStacks)));
    }

    private String stackCountText(DubhePromptTexts texts, int stacks) {
        int normalized = Math.max(2, stacks);
        String localized = texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_INVENTORY_STACK_COUNT_PREFIX + normalized);
        return localized.isBlank() ? Integer.toString(normalized) : localized;
    }

    private String effectLevel(DubhePromptTexts texts, int level) {
        int normalized = Math.max(1, level);
        String localized = texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_EFFECT_LEVEL_PREFIX + normalized);
        return localized.isBlank() ? Integer.toString(normalized) : localized;
    }

    private String durationText(DubhePromptTexts texts, int seconds) {
        int normalized = Math.max(0, seconds);
        if (normalized < 60) {
            return texts.render(DubhePromptTexts.DYNAMIC_KNOWLEDGE_DURATION_SECONDS, vars("seconds", Integer.toString(normalized)));
        }
        int minutes = normalized / 60;
        int remainingSeconds = normalized % 60;
        if (remainingSeconds == 0) {
            return texts.render(DubhePromptTexts.DYNAMIC_KNOWLEDGE_DURATION_MINUTES, vars("minutes", Integer.toString(minutes)));
        }
        return texts.render(DubhePromptTexts.DYNAMIC_KNOWLEDGE_DURATION_MINUTES_SECONDS, vars(
                "minutes", Integer.toString(minutes),
                "seconds", Integer.toString(remainingSeconds)
        ));
    }


    private String readable(String value, String fallback) {
        return hasText(value) ? value.trim() : clean(fallback);
    }

    private String readableId(DubhePromptTexts texts, String value) {
        if (!hasText(value)) {
            return texts.text(DubhePromptTexts.DYNAMIC_KNOWLEDGE_VALUE_UNKNOWN);
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
