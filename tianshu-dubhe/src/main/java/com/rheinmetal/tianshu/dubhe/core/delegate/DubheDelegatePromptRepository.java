package com.rheinmetal.tianshu.dubhe.core.delegate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.dubhe.module.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.module.system.DubhePromptLanguageProvider;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * 委派任务的提示词目录（{@code dubhe_agent_tasks.json}）。
 *
 * <p>与记忆维护任务同一个模式：文案全在资源里，代码里不出现规则文本，也不出现语言片段。</p>
 *
 * <p>Complex run 的上下文是<b>独立</b>的（§5.5：不复用主链前缀、不共享缓存），因此这里给的是自包含的
 * 任务提示词：system 规则 + 一句待检索的 query。</p>
 */
public final class DubheDelegatePromptRepository {

    private static final String BUILTIN_RESOURCE = "/com/rheinmetal/tianshu/dubhe/prompts/dubhe_agent_tasks.json";

    /** Complex run 的 system：检索任务规则 + 结论 JSON schema（§5.6 的三条约束写在提示词里）。 */
    public static final String DELEGATE_SYSTEM = "agent.delegate.system";

    /** Complex run 的指令后缀：只描述本次 query，并重申只输出 JSON。 */
    public static final String DELEGATE_INSTRUCTION = "agent.delegate.user";

    private static final String PLACEHOLDER_QUERY = "{{query}}";

    private final DubhePromptLanguageProvider languageProvider;
    private final JsonObject catalog;

    public DubheDelegatePromptRepository(DubhePromptLanguageProvider languageProvider) {
        this.languageProvider = languageProvider == null
                ? DubhePromptLanguageProvider.fixed(DubhePromptLanguage.EN_US)
                : languageProvider;
        this.catalog = readBuiltinCatalog();
    }

    public String delegateSystemPrompt() {
        return load(DELEGATE_SYSTEM, currentLanguage());
    }

    public String delegateInstruction(String query) {
        String template = load(DELEGATE_INSTRUCTION, currentLanguage());
        if (template.isBlank()) {
            return "";
        }
        return template.replace(PLACEHOLDER_QUERY, query == null ? "" : query.trim()).trim();
    }

    private DubhePromptLanguage currentLanguage() {
        DubhePromptLanguage language = languageProvider.currentLanguage();
        return language == null ? DubhePromptLanguage.EN_US : language;
    }

    private String load(String name, DubhePromptLanguage language) {
        if (name == null || name.isBlank()) {
            return "";
        }
        DubhePromptLanguage effective = language == null ? DubhePromptLanguage.EN_US : language;
        String value = readCatalog(catalog, name, effective);
        if (!value.isBlank()) {
            return value;
        }
        return effective == DubhePromptLanguage.EN_US ? "" : readCatalog(catalog, name, DubhePromptLanguage.EN_US);
    }

    private static String readCatalog(JsonObject catalog, String name, DubhePromptLanguage language) {
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
        String code = language.code();
        if (!entry.has(code) || entry.get(code).isJsonNull()) {
            return "";
        }
        String value = entry.get(code).getAsString();
        return value == null ? "" : value.trim();
    }

    private static JsonObject readBuiltinCatalog() {
        try (InputStream stream = DubheDelegatePromptRepository.class.getResourceAsStream(BUILTIN_RESOURCE)) {
            if (stream == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement element = JsonParser.parseReader(reader);
                return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}
