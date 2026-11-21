package com.rheinmetal.tianshu.dubhe.core.output;

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
 * 玩家能听到的兜底文案（{@code ax_reply_texts.json}）。
 *
 * <p>为什么需要独立的一层：AX 的输出层必须把<b>字面句子</b>交给 TTS，而 common 层拿不到 Minecraft 的语言表。
 * 因此与 {@code ax_agent_tasks.json} 同一个模式——文案留在资源里、按 {@link DubhePromptLanguage} 取，Java 里
 * 不出现任何自然语言句子。</p>
 *
 * <p>这里只收录"模型没给出可用答句时该说什么"这一类句子；正常答句的正文永远来自模型，不经过本类。</p>
 */
public final class DubheReplyTextRepository {

    private static final String BUILTIN_RESOURCE = "/com/rheinmetal/tianshu/dubhe/prompts/ax_reply_texts.json";

    /**
     * 空答句（模型这一轮没有任何可念的正文）时的唯一兜底句。
     *
     * <p>标签契约没有"违约"形态，因此这是降级路径上仅存的一句玩家可见文案。</p>
     */
    public static final String KEY_EMPTY_ANSWER = "reply.empty_answer";

    private final DubhePromptLanguageProvider languageProvider;
    private final JsonObject catalog;

    public DubheReplyTextRepository(DubhePromptLanguageProvider languageProvider) {
        this.languageProvider = languageProvider == null
                ? DubhePromptLanguageProvider.fixed(DubhePromptLanguage.EN_US)
                : languageProvider;
        this.catalog = readBuiltinCatalog();
    }

    /**
     * 按当前语言取一句兜底文案。
     *
     * @param key 资源键，见 {@link #KEY_EMPTY_ANSWER}
     * @return 文案；键或语言缺失时回落 {@code en_us}，两者都没有时返回空串（调用方按"无可播报内容"处理）
     */
    public String text(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        DubhePromptLanguage language = languageProvider.currentLanguage();
        DubhePromptLanguage effective = language == null ? DubhePromptLanguage.EN_US : language;
        String value = readCatalog(catalog, key, effective);
        return !value.isBlank() || effective == DubhePromptLanguage.EN_US
                ? value
                : readCatalog(catalog, key, DubhePromptLanguage.EN_US);
    }

    private static String readCatalog(JsonObject catalog, String key, DubhePromptLanguage language) {
        if (catalog == null || key == null || language == null) {
            return "";
        }
        JsonObject texts = catalog.has("texts") && catalog.get("texts").isJsonObject()
                ? catalog.getAsJsonObject("texts")
                : catalog;
        if (!texts.has(key) || !texts.get(key).isJsonObject()) {
            return "";
        }
        JsonObject entry = texts.getAsJsonObject(key);
        String code = language.code();
        if (!entry.has(code) || entry.get(code).isJsonNull()) {
            return "";
        }
        String value = entry.get(code).getAsString();
        return value == null ? "" : value.trim();
    }

    private static JsonObject readBuiltinCatalog() {
        try (InputStream stream = DubheReplyTextRepository.class.getResourceAsStream(BUILTIN_RESOURCE)) {
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
