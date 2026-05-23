package com.rheinmetal.tianshu.function.auxilium.rag;

import com.rheinmetal.tianshu.function.auxilium.fact.RuntimeFact;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RuntimeFactTextRenderer {
    private final RuntimeFactTextResolver textResolver;

    public RuntimeFactTextRenderer() {
        this(DefaultRuntimeFactTextResolver.instance());
    }

    public RuntimeFactTextRenderer(RuntimeFactTextResolver textResolver) {
        this.textResolver = textResolver == null ? DefaultRuntimeFactTextResolver.instance() : textResolver;
    }

    public String render(RuntimeFact fact, AXPromptLanguage language) {
        if (fact == null) {
            return "";
        }
        AXPromptLanguage effectiveLanguage = language == null ? AXPromptLanguage.EN_US : language;
        return switch (fact.type()) {
            case "player_dimension" -> renderDimension(fact.fields(), effectiveLanguage);
            case "player_status" -> renderPlayerStatus(fact.fields(), effectiveLanguage);
            case "world_environment" -> renderWorldEnvironment(fact.fields(), effectiveLanguage);
            case "active_effects" -> renderActiveEffects(fact.fields(), effectiveLanguage);
            case "inventory_items" -> renderInventoryItems(fact.fields(), effectiveLanguage);
            case "recent_chat" -> renderRecentChat(fact.fields(), effectiveLanguage);
            default -> "";
        };
    }

    private String renderDimension(Map<String, String> fields, AXPromptLanguage language) {
        return textResolver.format(language, "tianshu.llm.rag.player.dimension", Map.of(
                "dimension", displayNameOrLocalizedId("dimension", value(fields, "dimensionDisplayName"), value(fields, "dimension"), language)
        ));
    }

    private String renderPlayerStatus(Map<String, String> fields, AXPromptLanguage language) {
        return textResolver.format(language, "tianshu.llm.rag.player.status", Map.of(
                "dimension", displayNameOrLocalizedId("dimension", value(fields, "dimensionDisplayName"), value(fields, "dimension"), language),
                "health", percentFromFraction(value(fields, "health"), language),
                "hunger", percentFromValue(value(fields, "hunger"), 20.0d, language),
                "experienceLevel", fallback(value(fields, "experienceLevel"), textResolver.text(language, "tianshu.llm.rag.value.unknown"))
        ));
    }

    private String renderWorldEnvironment(Map<String, String> fields, AXPromptLanguage language) {
        return textResolver.format(language, "tianshu.llm.rag.world.environment", Map.of(
                "biome", displayNameOrLocalizedId("biome", value(fields, "biomeDisplayName"), value(fields, "biome"), language),
                "weather", localizedWeather(value(fields, "raining"), value(fields, "thundering"), language),
                "time", readableTime(value(fields, "dayTimeTicks"), language)
        ));
    }

    private String renderInventoryItems(Map<String, String> fields, AXPromptLanguage language) {
        String items = inventoryItems(value(fields, "items"), language);
        if (items.isBlank()) {
            return textResolver.text(language, "tianshu.llm.rag.inventory.empty");
        }
        return textResolver.format(language, "tianshu.llm.rag.inventory.items", Map.of("items", items));
    }

    private String renderActiveEffects(Map<String, String> fields, AXPromptLanguage language) {
        String effects = activeEffects(value(fields, "effects"), language);
        if (effects.isBlank()) {
            return "";
        }
        return textResolver.format(language, "tianshu.llm.rag.effects.active", Map.of("effects", effects));
    }

    private String renderRecentChat(Map<String, String> fields, AXPromptLanguage language) {
        String messages = recentChat(value(fields, "messages"), language);
        if (messages.isBlank()) {
            return "";
        }
        return textResolver.format(language, "tianshu.llm.rag.chat.recent", Map.of("messages", messages));
    }

    private String displayNameOrLocalizedId(String category, String displayName, String rawValue, AXPromptLanguage language) {
        String normalizedDisplayName = fallbackDisplayName(displayName);
        if (!normalizedDisplayName.isBlank()) {
            return normalizedDisplayName;
        }
        return localizedId(category, rawValue, language);
    }

    private String localizedId(String category, String rawValue, AXPromptLanguage language) {
        String normalized = normalizeId(rawValue);
        if (normalized.isBlank()) {
            return textResolver.text(language, "tianshu.llm.rag." + category + ".unknown");
        }
        String lookupKey = category + "." + normalized.replace(':', '.');
        String localized = textResolver.text(language, lookupKey);
        if (!localized.equals(lookupKey)) {
            return localized;
        }
        if (language == AXPromptLanguage.EN_US) {
            return titleizeIdentifier(rawValue);
        }
        return rawValue.trim();
    }

    private String localizedWeather(String raining, String thundering, AXPromptLanguage language) {
        if (Boolean.parseBoolean(thundering)) {
            return textResolver.text(language, "tianshu.llm.rag.weather.thunderstorm");
        }
        if (Boolean.parseBoolean(raining)) {
            return textResolver.text(language, "tianshu.llm.rag.weather.rain");
        }
        return textResolver.text(language, "tianshu.llm.rag.weather.clear");
    }

    private String readableTime(String ticks, AXPromptLanguage language) {
        long tick = parseLong(ticks, 0L);
        long normalized = Math.floorMod(tick, 24000L);
        long totalMinutes = Math.floorMod(normalized + 6000L, 24000L) * 1440L / 24000L;
        long roundedMinutes = Math.round(totalMinutes / 10.0d) * 10L;
        roundedMinutes = Math.floorMod(roundedMinutes, 1440L);
        long hour = roundedMinutes / 60L;
        long minute = roundedMinutes % 60L;
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    private String percentFromFraction(String fraction, AXPromptLanguage language) {
        if (fraction == null || fraction.isBlank()) {
            return textResolver.text(language, "tianshu.llm.rag.value.unknown");
        }
        String[] parts = fraction.split("/");
        if (parts.length != 2) {
            return fraction.trim();
        }
        double current = parseDouble(parts[0], -1.0d);
        double maximum = parseDouble(parts[1], -1.0d);
        if (current < 0.0d || maximum <= 0.0d) {
            return fraction.trim();
        }
        return Math.round(current * 100.0d / maximum) + "%";
    }

    private String percentFromValue(String value, double maximum, AXPromptLanguage language) {
        double current = parseDouble(value, -1.0d);
        if (current < 0.0d || maximum <= 0.0d) {
            return fallback(value, textResolver.text(language, "tianshu.llm.rag.value.unknown"));
        }
        return Math.round(current * 100.0d / maximum) + "%";
    }

    private String inventoryItems(String rawItems, AXPromptLanguage language) {
        if (rawItems == null || rawItems.isBlank()) {
            return "";
        }
        String[] entries = rawItems.split("\\|");
        StringBuilder builder = new StringBuilder();
        for (String entry : entries) {
            String text = renderInventoryItem(entry, language);
            if (text.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(textResolver.text(language, "tianshu.llm.rag.inventory.separator"));
            }
            builder.append(text);
        }
        return builder.toString();
    }

    private String renderInventoryItem(String entry, AXPromptLanguage language) {
        if (entry == null || entry.isBlank()) {
            return "";
        }
        String[] parts = entry.split(";", -1);
        String name = parts.length > 0 ? parts[0].trim() : "";
        if (name.isBlank()) {
            return "";
        }
        int count = parts.length > 1 ? (int) parseLong(parts[1], 1L) : 1;
        int maxStackSize = parts.length > 2 ? (int) parseLong(parts[2], 64L) : 64;
        String amount = inventoryAmount(count, maxStackSize, language);
        if (amount.isBlank()) {
            return name;
        }
        return textResolver.format(language, "tianshu.llm.rag.inventory.item", Map.of("name", name, "amount", amount));
    }

    private String inventoryAmount(int count, int maxStackSize, AXPromptLanguage language) {
        if (count <= 1) {
            return "";
        }
        int stackSize = Math.max(1, maxStackSize);
        if (count < Math.ceil(stackSize * 0.25d)) {
            return textResolver.text(language, "tianshu.llm.rag.inventory.amount.few");
        }
        if (count < Math.ceil(stackSize * 0.5d)) {
            return textResolver.text(language, "tianshu.llm.rag.inventory.amount.less_than_half_stack");
        }
        if (count == Math.round(stackSize * 0.5d)) {
            return textResolver.text(language, "tianshu.llm.rag.inventory.amount.half_stack");
        }
        if (count < stackSize) {
            return textResolver.text(language, "tianshu.llm.rag.inventory.amount.more_than_half_stack");
        }
        int fullStacks = count / stackSize;
        int remainder = count % stackSize;
        if (fullStacks <= 1) {
            return remainder == 0
                    ? textResolver.text(language, "tianshu.llm.rag.inventory.amount.one_stack")
                    : textResolver.text(language, "tianshu.llm.rag.inventory.amount.one_stack_more");
        }
        String stacks = localizedStackCount(fullStacks, language);
        if (remainder == 0) {
            return textResolver.format(language, "tianshu.llm.rag.inventory.amount.multi_stack", Map.of("stacks", stacks));
        }
        return textResolver.format(language, "tianshu.llm.rag.inventory.amount.multi_stack_more", Map.of("stacks", stacks));
    }

    private String localizedStackCount(int count, AXPromptLanguage language) {
        String key = "tianshu.llm.rag.inventory.stack_count." + count;
        String value = textResolver.text(language, key);
        return value.equals(key) ? Integer.toString(count) : value;
    }

    private String activeEffects(String rawEffects, AXPromptLanguage language) {
        if (rawEffects == null || rawEffects.isBlank()) {
            return "";
        }
        String[] entries = rawEffects.split("\\|");
        List<String> rendered = new ArrayList<>();
        for (String entry : entries) {
            String[] parts = entry.split(";", -1);
            if (parts.length == 0 || parts[0].isBlank()) {
                continue;
            }
            String name = parts[0].trim();
            int amplifier = parts.length > 1 ? (int) parseLong(parts[1], 0L) : 0;
            long durationTicks = parts.length > 2 ? parseLong(parts[2], 0L) : 0L;
            rendered.add(textResolver.format(language, "tianshu.llm.rag.effects.entry", Map.of(
                    "name", name,
                    "level", effectLevel(amplifier, language),
                    "duration", duration(durationTicks, language)
            )));
        }
        return String.join(textResolver.text(language, "tianshu.llm.rag.inventory.separator"), rendered);
    }

    private String recentChat(String rawMessages, AXPromptLanguage language) {
        if (rawMessages == null || rawMessages.isBlank()) {
            return "";
        }
        String[] entries = rawMessages.split("\\|");
        List<String> rendered = new ArrayList<>();
        for (String entry : entries) {
            String[] parts = entry.split(";", 2);
            String sender = parts.length > 0 ? parts[0].trim() : "";
            String message = parts.length > 1 ? parts[1].trim() : "";
            if (message.isBlank()) {
                continue;
            }
            if (sender.isBlank()) {
                rendered.add(message);
            } else {
                rendered.add(textResolver.format(language, "tianshu.llm.rag.chat.entry", Map.of("sender", sender, "message", message)));
            }
        }
        return String.join(textResolver.text(language, "tianshu.llm.rag.chat.separator"), rendered);
    }

    private String effectLevel(int amplifier, AXPromptLanguage language) {
        int level = Math.max(1, amplifier + 1);
        String key = "tianshu.llm.rag.effect.level." + level;
        String value = textResolver.text(language, key);
        return value.equals(key) ? Integer.toString(level) : value;
    }

    private String duration(long durationTicks, AXPromptLanguage language) {
        long seconds = Math.max(0L, Math.round(durationTicks / 20.0d));
        if (seconds < 60L) {
            return textResolver.format(language, "tianshu.llm.rag.duration.seconds", Map.of("seconds", Long.toString(seconds)));
        }
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        if (remainingSeconds == 0L) {
            return textResolver.format(language, "tianshu.llm.rag.duration.minutes", Map.of("minutes", Long.toString(minutes)));
        }
        return textResolver.format(language, "tianshu.llm.rag.duration.minutes_seconds", Map.of("minutes", Long.toString(minutes), "seconds", Long.toString(remainingSeconds)));
    }

    private String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private String titleizeIdentifier(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return textResolver.text(AXPromptLanguage.EN_US, "tianshu.llm.rag.biome.unknown");
        }
        int separator = normalized.indexOf(':');
        if (separator >= 0 && separator < normalized.length() - 1) {
            normalized = normalized.substring(separator + 1);
        }
        String[] parts = normalized.replace('-', '_').split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return builder.isEmpty() ? textResolver.text(AXPromptLanguage.EN_US, "tianshu.llm.rag.biome.unknown") : builder.toString();
    }

    private String value(Map<String, String> fields, String key) {
        if (fields == null || key == null) {
            return "";
        }
        String value = fields.get(key);
        return value == null ? "" : value.trim();
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String fallbackDisplayName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (!trimmed.contains(".")) {
            return trimmed;
        }
        if (trimmed.startsWith("biome.") || trimmed.startsWith("dimension.")) {
            return "";
        }
        return trimmed;
    }

    private double parseDouble(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
