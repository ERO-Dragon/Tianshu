package com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.io.InputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;

public final class AXMemoryTaskPromptRepository {
    private static final String BUILTIN_RESOURCE = "/com/rheinmetal/tianshu/function/auxilium/prompts/ax_memory_tasks.json";

    private final AXStorageLayout layout;
    private final AXPromptLanguageProvider languageProvider;

    public AXMemoryTaskPromptRepository(AXStorageLayout layout, AXPromptLanguageProvider languageProvider) {
        this.layout = layout;
        this.languageProvider = languageProvider == null ? AXPromptLanguageProvider.fixed(AXPromptLanguage.EN_US) : languageProvider;
        ensureExternalCatalog();
    }

    public String compressionSystemPrompt() {
        AXPromptLanguage language = currentLanguage();
        return load("memory.compress.system", language);
    }

    public String compressionUserPrompt(String worldId, String turns) {
        AXPromptLanguage language = currentLanguage();
        String template = load("memory.compress.user", language);
        return render(template, Map.of(
                "world", clean(worldId),
                "turns", clean(turns)
        ));
    }

    public String extractionSystemPrompt() {
        AXPromptLanguage language = currentLanguage();
        return load("memory.extract.system", language);
    }

    public String extractionUserPrompt(String stmText) {
        AXPromptLanguage language = currentLanguage();
        String template = load("memory.extract.user", language);
        return render(template, Map.of("stm", clean(stmText)));
    }

    public String rawTurnLine(AXRawTurn turn) {
        if (turn == null || turn.isEmpty()) {
            return "";
        }
        AXPromptLanguage language = currentLanguage();
        if (turn.gameChatRole()) {
            String template = load("memory.turn.game_chat", language);
            return render(template, Map.of(
                    "sender", clean(turn.speakerName()),
                    "message", clean(turn.content())
            ));
        }
        String template = load("memory.turn.dialogue", language);
        return render(template, Map.of(
                "role", clean(turn.role()),
                "content", clean(turn.content())
        ));
    }

    private AXPromptLanguage currentLanguage() {
        AXPromptLanguage language = languageProvider.currentLanguage();
        return language == null ? AXPromptLanguage.EN_US : language;
    }

    private String load(String name, AXPromptLanguage language) {
        if (name == null || name.isBlank()) {
            return "";
        }
        AXPromptLanguage effectiveLanguage = language == null ? AXPromptLanguage.EN_US : language;
        String value = readExternalCatalog(name, effectiveLanguage);
        if (!value.isBlank()) {
            return value;
        }
        if (effectiveLanguage != AXPromptLanguage.EN_US) {
            value = readExternalCatalog(name, AXPromptLanguage.EN_US);
            if (!value.isBlank()) {
                return value;
            }
        }
        value = readBuiltinCatalog(name, effectiveLanguage);
        if (!value.isBlank()) {
            return value;
        }
        if (effectiveLanguage != AXPromptLanguage.EN_US) {
            return readBuiltinCatalog(name, AXPromptLanguage.EN_US);
        }
        return "";
    }

    private String readExternalCatalog(String name, AXPromptLanguage language) {
        if (layout == null) {
            return "";
        }
        return readCatalog(readJson(layout.memoryTaskPromptsFile()), name, language);
    }

    private void ensureExternalCatalog() {
        if (layout == null || Files.isRegularFile(layout.memoryTaskPromptsFile())) {
            return;
        }
        try (InputStream stream = AXMemoryTaskPromptRepository.class.getResourceAsStream(BUILTIN_RESOURCE)) {
            if (stream == null) {
                return;
            }
            Files.createDirectories(layout.promptsRoot());
            Files.copy(stream, layout.memoryTaskPromptsFile());
        } catch (IOException ignored) {
        }
    }

    private String readBuiltinCatalog(String name, AXPromptLanguage language) {
        try (InputStream stream = AXMemoryTaskPromptRepository.class.getResourceAsStream(BUILTIN_RESOURCE)) {
            if (stream == null) {
                return "";
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement element = JsonParser.parseReader(reader);
                return element != null && element.isJsonObject() ? readCatalog(element.getAsJsonObject(), name, language) : "";
            }
        } catch (Exception ignored) {
            return "";
        }
    }

    private JsonObject readJson(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private String readCatalog(JsonObject catalog, String name, AXPromptLanguage language) {
        if (catalog == null || name == null || language == null) {
            return "";
        }
        JsonObject prompts = catalog.has("prompts") && catalog.get("prompts").isJsonObject()
                ? catalog.getAsJsonObject("prompts")
                : catalog;
        if (!prompts.has(name) || !prompts.get(name).isJsonObject()) {
            return "";
        }
        JsonObject entry = prompts.getAsJsonObject(name);
        return readCatalogValue(entry, language.code());
    }

    private String readCatalogValue(JsonObject entry, String languageCode) {
        if (entry == null || languageCode == null || !entry.has(languageCode) || entry.get(languageCode).isJsonNull()) {
            return "";
        }
        String value = entry.get(languageCode).getAsString();
        return value == null ? "" : value.trim();
    }

    private String render(String template, Map<String, String> values) {
        String result = template == null ? "" : template;
        for (Map.Entry<String, String> entry : Objects.requireNonNullElse(values, Map.<String, String>of()).entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result.trim();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
