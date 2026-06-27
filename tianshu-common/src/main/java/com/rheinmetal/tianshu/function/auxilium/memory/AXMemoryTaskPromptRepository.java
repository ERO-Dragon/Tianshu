package com.rheinmetal.tianshu.function.auxilium.memory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;
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
        String template = load("memory.compress.system", language);
        return template.isBlank() ? defaultCompressionSystemPrompt(language) : template;
    }

    public String compressionUserPrompt(String worldId, String turns) {
        AXPromptLanguage language = currentLanguage();
        String template = load("memory.compress.user", language);
        if (template.isBlank()) {
            template = defaultCompressionUserPrompt(language);
        }
        return render(template, Map.of(
                "world", clean(worldId),
                "turns", clean(turns)
        ));
    }

    public String extractionSystemPrompt() {
        AXPromptLanguage language = currentLanguage();
        String template = load("memory.extract.system", language);
        return template.isBlank() ? defaultExtractionSystemPrompt(language) : template;
    }

    public String extractionUserPrompt(String stmText) {
        AXPromptLanguage language = currentLanguage();
        String template = load("memory.extract.user", language);
        if (template.isBlank()) {
            template = defaultExtractionUserPrompt(language);
        }
        return render(template, Map.of("stm", clean(stmText)));
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
        value = readLegacyTemplate(name, effectiveLanguage);
        if (!value.isBlank()) {
            return value;
        }
        if (effectiveLanguage != AXPromptLanguage.EN_US) {
            value = readLegacyTemplate(name, AXPromptLanguage.EN_US);
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
        if (layout == null || Files.isRegularFile(layout.memoryTaskPromptsFile()) || hasLegacyTemplates()) {
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

    private boolean hasLegacyTemplates() {
        if (layout == null || !Files.isDirectory(layout.promptsRoot())) {
            return false;
        }
        try (java.util.stream.Stream<Path> paths = Files.list(layout.promptsRoot())) {
            return paths.anyMatch(path -> path != null
                    && Files.isRegularFile(path)
                    && path.getFileName() != null
                    && path.getFileName().toString().startsWith("memory.")
                    && path.getFileName().toString().endsWith(".txt"));
        } catch (IOException ignored) {
            return false;
        }
    }

    private String readLegacyTemplate(String name, AXPromptLanguage language) {
        if (layout == null) {
            return "";
        }
        return readText(layout.promptsRoot().resolve(name + "." + language.code() + ".txt"));
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

    private String readText(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return "";
        }
    }

    private String render(String template, Map<String, String> values) {
        String result = template == null ? "" : template;
        for (Map.Entry<String, String> entry : Objects.requireNonNullElse(values, Map.<String, String>of()).entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result.trim();
    }

    private String defaultCompressionSystemPrompt(AXPromptLanguage language) {
        if (language == AXPromptLanguage.ZH_CN) {
            return """
                    将下面的 AX/玩家对话压缩为一个忠实的短期记忆段落。
                    保留具体语境和先后顺序。不要添加主观重要性、偏好结论或行动指令。
                    只输出 STM 正文。
                    """.trim();
        }
        return """
                Compress the following AX/player dialogue into one faithful short-term memory paragraph.
                Preserve concrete context and sequence. Do not add subjective importance, preference conclusions, or instructions.
                Output only the STM text.
                """.trim();
    }

    private String defaultCompressionUserPrompt(AXPromptLanguage language) {
        if (language == AXPromptLanguage.ZH_CN) {
            return "世界：{{world}}\n\n{{turns}}";
        }
        return "World: {{world}}\n\n{{turns}}";
    }

    private String defaultExtractionSystemPrompt(AXPromptLanguage language) {
        if (language == AXPromptLanguage.ZH_CN) {
            return """
                    从这段 AX 短期记忆中抽取客观原子事实。
                    规则：
                    - 每行输出一条事实。
                    - 保持事实具体且符合真实历史。
                    - 不输出重要性、保留建议、偏好、性格结论或动机推断。
                    - 如果没有客观事实，则不输出任何内容。
                    """.trim();
        }
        return """
                Extract objective atomic facts from this AX short-term memory.
                Rules:
                - Output one fact per line.
                - Keep facts concrete and historically true.
                - Do not output importance, retention advice, preferences, personality conclusions, or inferred motives.
                - If there are no objective facts, output nothing.
                """.trim();
    }

    private String defaultExtractionUserPrompt(AXPromptLanguage language) {
        if (language == AXPromptLanguage.ZH_CN) {
            return "STM 正文：\n{{stm}}";
        }
        return "STM text:\n{{stm}}";
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
