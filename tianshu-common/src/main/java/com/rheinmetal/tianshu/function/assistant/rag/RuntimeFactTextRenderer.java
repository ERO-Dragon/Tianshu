package com.rheinmetal.tianshu.function.assistant.rag;

import com.rheinmetal.tianshu.function.assistant.fact.RuntimeFact;
import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptLanguage;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuntimeFactTextRenderer {
    private static final Pattern INVENTORY_ITEM_PATTERN = Pattern.compile("(.+)x(\\d+)");
    private static final int MAX_RENDERED_ITEM_TYPES = 36;
    private final RuntimeFactLanguageBundle languageBundle;

    public RuntimeFactTextRenderer() {
        this(RuntimeFactLanguageBundle.defaultBundle());
    }

    public RuntimeFactTextRenderer(RuntimeFactLanguageBundle languageBundle) {
        this.languageBundle = languageBundle == null ? RuntimeFactLanguageBundle.defaultBundle() : languageBundle;
    }

    public String render(RuntimeFact fact, AssistantPromptLanguage language) {
        if (fact == null) {
            return "";
        }
        AssistantPromptLanguage effectiveLanguage = language == null ? AssistantPromptLanguage.EN_US : language;
        return switch (fact.type()) {
            case "player_dimension" -> renderDimension(fact.fields(), effectiveLanguage);
            case "player_status" -> renderPlayerStatus(fact.fields(), effectiveLanguage);
            case "world_environment" -> renderWorldEnvironment(fact.fields(), effectiveLanguage);
            case "inventory_items" -> renderInventoryItems(fact.fields(), effectiveLanguage);
            default -> "";
        };
    }

    private String renderDimension(Map<String, String> fields, AssistantPromptLanguage language) {
        return languageBundle.format(language, "tianshu.llm.rag.player.dimension", Map.of(
                "dimension", displayNameOrLocalizedId("dimension", value(fields, "dimensionDisplayName"), value(fields, "dimension"), language)
        ));
    }

    private String renderPlayerStatus(Map<String, String> fields, AssistantPromptLanguage language) {
        return languageBundle.format(language, "tianshu.llm.rag.player.status", Map.of(
                "health", percentFromFraction(value(fields, "health"), language),
                "hunger", percentFromValue(value(fields, "hunger"), 20.0d, language),
                "saturation", percentFromValue(value(fields, "saturation"), 20.0d, language),
                "experienceLevel", fallback(value(fields, "experienceLevel"), languageBundle.text(language, "tianshu.llm.rag.value.unknown"))
        ));
    }

    private String renderWorldEnvironment(Map<String, String> fields, AssistantPromptLanguage language) {
        return languageBundle.format(language, "tianshu.llm.rag.world.environment", Map.of(
                "biome", displayNameOrLocalizedId("biome", value(fields, "biomeDisplayName"), value(fields, "biome"), language),
                "weather", localizedWeather(value(fields, "raining"), value(fields, "thundering"), language),
                "time", readableTime(value(fields, "dayTimeTicks"), language)
        ));
    }

    private String renderInventoryItems(Map<String, String> fields, AssistantPromptLanguage language) {
        String items = inventoryItems(value(fields, "items"), language);
        if (items.isBlank()) {
            return languageBundle.text(language, "tianshu.llm.rag.inventory.empty");
        }
        return languageBundle.format(language, "tianshu.llm.rag.inventory.items", Map.of("items", items));
    }

    private String displayNameOrLocalizedId(String category, String displayName, String rawValue, AssistantPromptLanguage language) {
        String normalizedDisplayName = fallbackDisplayName(displayName);
        if (!normalizedDisplayName.isBlank()) {
            return normalizedDisplayName;
        }
        return localizedId(category, rawValue, language);
    }

    private String localizedId(String category, String rawValue, AssistantPromptLanguage language) {
        String normalized = normalizeId(rawValue);
        if (normalized.isBlank()) {
            return languageBundle.text(language, "tianshu.llm.rag." + category + ".unknown");
        }
        String localized = languageBundle.text(language, category + "." + normalized.replace(':', '.'));
        if (!localized.equals(category + "." + normalized.replace(':', '.'))) {
            return localized;
        }
        if (language == AssistantPromptLanguage.EN_US) {
            return titleizeIdentifier(rawValue);
        }
        return rawValue.trim();
    }

    private String localizedWeather(String raining, String thundering, AssistantPromptLanguage language) {
        if (Boolean.parseBoolean(thundering)) {
            return languageBundle.text(language, "tianshu.llm.rag.weather.thunderstorm");
        }
        if (Boolean.parseBoolean(raining)) {
            return languageBundle.text(language, "tianshu.llm.rag.weather.rain");
        }
        return languageBundle.text(language, "tianshu.llm.rag.weather.clear");
    }

    private String readableTime(String ticks, AssistantPromptLanguage language) {
        long tick = parseLong(ticks, 0L);
        long normalized = Math.floorMod(tick, 24000L);
        long minutes = Math.floorMod(normalized + 6000L, 24000L) * 1440L / 24000L;
        long hour = minutes / 60L;
        String periodKey = timePeriodKey(hour);
        int displayHour = (int) (hour % 12L);
        if (displayHour == 0) {
            displayHour = 12;
        }
        return languageBundle.format(language, "tianshu.llm.rag.time.expression", Map.of(
                "period", languageBundle.text(language, periodKey),
                "hour", Integer.toString(displayHour)
        ));
    }

    private String timePeriodKey(long hour) {
        if (hour >= 0L && hour < 5L) {
            return "tianshu.llm.rag.time.late_night";
        }
        if (hour < 8L) {
            return "tianshu.llm.rag.time.early_morning";
        }
        if (hour < 12L) {
            return "tianshu.llm.rag.time.morning";
        }
        if (hour < 13L) {
            return "tianshu.llm.rag.time.noon";
        }
        if (hour < 18L) {
            return "tianshu.llm.rag.time.afternoon";
        }
        if (hour < 21L) {
            return "tianshu.llm.rag.time.evening";
        }
        return "tianshu.llm.rag.time.night";
    }

    private String percentFromFraction(String fraction, AssistantPromptLanguage language) {
        if (fraction == null || fraction.isBlank()) {
            return languageBundle.text(language, "tianshu.llm.rag.value.unknown");
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

    private String percentFromValue(String value, double maximum, AssistantPromptLanguage language) {
        double current = parseDouble(value, -1.0d);
        if (current < 0.0d || maximum <= 0.0d) {
            return fallback(value, languageBundle.text(language, "tianshu.llm.rag.value.unknown"));
        }
        return Math.round(current * 100.0d / maximum) + "%";
    }

    private String inventoryItems(String rawItems, AssistantPromptLanguage language) {
        if (rawItems == null || rawItems.isBlank()) {
            return "";
        }
        String[] entries = rawItems.split("，");
        StringBuilder builder = new StringBuilder();
        int rendered = 0;
        for (String entry : entries) {
            String trimmed = entry == null ? "" : entry.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String text = renderInventoryItem(trimmed, language);
            if (builder.length() > 0) {
                builder.append(languageBundle.text(language, "tianshu.llm.rag.inventory.separator"));
            }
            builder.append(text);
            rendered++;
            if (rendered >= MAX_RENDERED_ITEM_TYPES) {
                int remaining = entries.length - rendered;
                if (remaining > 0) {
                    builder.append(languageBundle.text(language, "tianshu.llm.rag.inventory.separator"));
                    builder.append(languageBundle.format(language, "tianshu.llm.rag.inventory.more", Map.of("count", Integer.toString(remaining))));
                }
                break;
            }
        }
        return builder.toString();
    }

    private String renderInventoryItem(String entry, AssistantPromptLanguage language) {
        Matcher matcher = INVENTORY_ITEM_PATTERN.matcher(entry);
        if (!matcher.matches()) {
            return entry;
        }
        return languageBundle.format(language, "tianshu.llm.rag.inventory.item", Map.of(
                "name", matcher.group(1).trim(),
                "count", matcher.group(2).trim()
        ));
    }

    private String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private String titleizeIdentifier(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return languageBundle.text(AssistantPromptLanguage.EN_US, "tianshu.llm.rag.biome.unknown");
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
        return builder.isEmpty() ? languageBundle.text(AssistantPromptLanguage.EN_US, "tianshu.llm.rag.biome.unknown") : builder.toString();
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
