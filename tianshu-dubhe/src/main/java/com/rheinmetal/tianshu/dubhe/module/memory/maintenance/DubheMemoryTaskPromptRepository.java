package com.rheinmetal.tianshu.dubhe.module.memory.maintenance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheStorageLayout;

import java.io.InputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRawTurn;

/**
 * 记忆维护任务的提示词目录（{@code dubhe_memory_tasks.json}）。
 *
 * <p>所有文案都走资源：代码里不出现规则文本，也不出现语言片段。键名：</p>
 * <ul>
 *   <li>{@code memory.compact.system} — 一段式压缩的规则表 + JSON schema + importance 锚例</li>
 *   <li>{@code memory.compact.user} — 指令后缀（请求里唯一新增的真后缀）</li>
 *   <li>{@code memory.compact.turn_dialogue} — 带序号的一行轮次（所有轮次共用一行，事件的主体写在角色位）</li>
 * </ul>
 */
public final class DubheMemoryTaskPromptRepository {
    private static final String BUILTIN_RESOURCE = "/com/rheinmetal/tianshu/dubhe/prompts/dubhe_memory_tasks.json";

    private final DubheStorageLayout layout;
    private final DubhePromptLanguageProvider languageProvider;

    public DubheMemoryTaskPromptRepository(DubheStorageLayout layout, DubhePromptLanguageProvider languageProvider) {
        this.layout = layout;
        this.languageProvider = languageProvider == null ? DubhePromptLanguageProvider.fixed(DubhePromptLanguage.EN_US) : languageProvider;
        ensureExternalCatalog();
    }

    /** 一段式压缩的 system：规则表、JSON schema 与打分锚例必须在同一份里（设计 §10 G1）。 */
    public String compactSystemPrompt() {
        return load("memory.compact.system", currentLanguage());
    }

    /** 请求的指令后缀：只描述本单元范围与"只输出 JSON"，不含规则文本。 */
    public String compactInstruction(String worldId, int turnCount) {
        String template = load("memory.compact.user", currentLanguage());
        return render(template, Map.of(
                "world", clean(worldId),
                "turnCount", Integer.toString(Math.max(0, turnCount))
        ));
    }

    /**
     * The one previous-unit summary that connects this unit to its predecessor (G34).
     *
     * <p>Exactly one: carrying every summary of the generation would grow with the generation and accumulate
     * paraphrase drift along the chain. Blank means "no predecessor", and then the request has no such message.</p>
     */
    public String compactPreviousSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        String template = load("memory.compact.previous_summary", currentLanguage());
        return render(template, Map.of("summary", clean(summary)));
    }
    /**
     * 一行带序号的轮次。
     *
     * <p>序号是 1-based，与提示词里 {@code turnRefs} 的写法一致——模型只要抄行首方括号里的数字就能
     * 给出轮级溯源。</p>
     *
     * <p>只有一种行：事件与对话共用 {@code memory.compact.turn_dialogue}。带主体的事件把主体写进角色位
     * （{@code [7] 铁匠: 今天矿石不够了。}），因此压缩输入仍然认得出"谁说的"，而不再有第二套模板。</p>
     */
    public String compactTurnLine(DubheRawTurn turn, int oneBasedIndex) {
        if (turn == null || turn.isEmpty()) {
            return "";
        }
        DubhePromptLanguage language = currentLanguage();
        String speaker = turn.speakerName() == null ? "" : turn.speakerName().trim();
        String template = load("memory.compact.turn_dialogue", language);
        return render(template, Map.of(
                "index", Integer.toString(oneBasedIndex),
                "role", speaker.isBlank() ? clean(turn.role()) : speaker,
                "content", clean(turn.content())
        ));
    }

    private DubhePromptLanguage currentLanguage() {
        DubhePromptLanguage language = languageProvider.currentLanguage();
        return language == null ? DubhePromptLanguage.EN_US : language;
    }

    private String load(String name, DubhePromptLanguage language) {
        if (name == null || name.isBlank()) {
            return "";
        }
        DubhePromptLanguage effectiveLanguage = language == null ? DubhePromptLanguage.EN_US : language;
        String value = readExternalCatalog(name, effectiveLanguage);
        if (!value.isBlank()) {
            return value;
        }
        if (effectiveLanguage != DubhePromptLanguage.EN_US) {
            value = readExternalCatalog(name, DubhePromptLanguage.EN_US);
            if (!value.isBlank()) {
                return value;
            }
        }
        value = readBuiltinCatalog(name, effectiveLanguage);
        if (!value.isBlank()) {
            return value;
        }
        if (effectiveLanguage != DubhePromptLanguage.EN_US) {
            return readBuiltinCatalog(name, DubhePromptLanguage.EN_US);
        }
        return "";
    }

    private String readExternalCatalog(String name, DubhePromptLanguage language) {
        if (layout == null) {
            return "";
        }
        return readCatalog(readJson(layout.memoryTaskPromptsFile()), name, language);
    }

    private void ensureExternalCatalog() {
        if (layout == null || Files.isRegularFile(layout.memoryTaskPromptsFile())) {
            return;
        }
        try (InputStream stream = DubheMemoryTaskPromptRepository.class.getResourceAsStream(BUILTIN_RESOURCE)) {
            if (stream == null) {
                return;
            }
            Files.createDirectories(layout.promptsRoot());
            Files.copy(stream, layout.memoryTaskPromptsFile());
        } catch (IOException ignored) {
        }
    }

    private String readBuiltinCatalog(String name, DubhePromptLanguage language) {
        try (InputStream stream = DubheMemoryTaskPromptRepository.class.getResourceAsStream(BUILTIN_RESOURCE)) {
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

    private String readCatalog(JsonObject catalog, String name, DubhePromptLanguage language) {
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
