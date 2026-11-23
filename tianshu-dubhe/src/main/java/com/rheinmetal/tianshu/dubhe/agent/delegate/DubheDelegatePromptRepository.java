package com.rheinmetal.tianshu.dubhe.agent.delegate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * 代理任务的提示词目录（{@code dubhe_agent_tasks.json}）。
 *
 * <p>与记忆维护任务同一个模式：文案全在资源里，代码里不出现规则文本，也不出现语言片段。</p>
 *
 * <p>代理的上下文是<b>独立</b>的（§5.5：不复用主链前缀、不共享缓存），因此这里给的是自包含的任务提示词：
 * 带原生工具调用的 system 规则，以及任务内压缩的规则与模板。</p>
 */
public final class DubheDelegatePromptRepository {

    private static final String BUILTIN_RESOURCE = "/com/rheinmetal/tianshu/dubhe/prompts/dubhe_agent_tasks.json";

    /** 带工具调用的代理任务 system；技能目录走原生 tools[]，不拼进文本。 */
    public static final String AGENT_SYSTEM = "agent.skill.system";
    /** 任务内压缩：system 规则与携带待压缩数据的 user 模板。 */
    public static final String COMPACT_SYSTEM = "agent.compact.system";
    public static final String COMPACT_INSTRUCTION = "agent.compact.user";
    /** 软预算提示：到大部分步数时要求收尾或明确说"需要继续"。 */
    public static final String SOFT_BUDGET = "agent.skill.soft_budget";
    /** 玩家同意续跑的词表（逗号分隔）；它是交互识别，不是模型提示词。 */
    public static final String RESUME_ACCEPT = "agent.resume.accept";
    public static final String CONSENT_ACCEPT = "agent.consent.accept";

    private static final String PLACEHOLDER_PAYLOAD = "{{payload}}";

    private final DubhePromptLanguageProvider languageProvider;
    private final JsonObject catalog;

    public DubheDelegatePromptRepository(DubhePromptLanguageProvider languageProvider) {
        this.languageProvider = languageProvider == null
                ? DubhePromptLanguageProvider.fixed(DubhePromptLanguage.EN_US)
                : languageProvider;
        this.catalog = readBuiltinCatalog();
    }

    public String softBudgetNotice() {
        return load(SOFT_BUDGET, currentLanguage());
    }

    /** 同意续跑的词；空白表示不做续跑识别。 */
    public java.util.List<String> resumeAcceptWords() {
        return wordsFrom(load(RESUME_ACCEPT, currentLanguage()));
    }

    /** 逗号分隔的词表；资源里留空表示不做识别。 */
    private static java.util.List<String> wordsFrom(String csv) {
        if (csv == null || csv.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<String> words = new java.util.ArrayList<>();
        for (String part : csv.split(",")) {
            String word = part.trim();
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return java.util.List.copyOf(words);
    }

    /** 玩家同意某个档位首次使用的词表；与续跑词表分开，避免"继续"被当成授权。 */
    public java.util.List<String> consentAcceptWords() {
        return wordsFrom(load(CONSENT_ACCEPT, currentLanguage()));
    }
    /** DUBHE.SEARCH：只读查证的 system 与一句用户问法（G10）。 */
    public String externalSearchSystem() {
        return load("external.search.system", currentLanguage());
    }

    public String externalSearchUser(String intent) {
        String template = load("external.search.user", currentLanguage());
        return template.replace("{{intent}}", intent == null ? "" : intent.trim());
    }
    public String agentSystemPrompt() {
        return load(AGENT_SYSTEM, currentLanguage());
    }

    public String compactionSystemPrompt() {
        return load(COMPACT_SYSTEM, currentLanguage());
    }

    public String compactionInstruction(String payloadJson) {
        String template = load(COMPACT_INSTRUCTION, currentLanguage());
        if (template.isBlank()) {
            return "";
        }
        return template.replace(PLACEHOLDER_PAYLOAD, payloadJson == null ? "" : payloadJson).trim();
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
