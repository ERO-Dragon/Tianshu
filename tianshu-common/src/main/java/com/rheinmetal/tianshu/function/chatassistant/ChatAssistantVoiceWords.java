package com.rheinmetal.tianshu.function.chatassistant;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

public final class ChatAssistantVoiceWords {
    private static final String RESOURCE = "/com/rheinmetal/tianshu/constant/chat_assistant_voice_words.json";
    private static final Gson GSON = new Gson();
    private static final List<String> DEFAULT_HOTWORDS = List.of("发送消息", "聊天", "打开聊天", "语音发送");
    private static final List<String> DEFAULT_EXTRA_WORDS = List.of("发送", "确认", "取消", "重来");

    private ChatAssistantVoiceWords() {
    }

    public static Words load() {
        try (InputStream inputStream = ChatAssistantVoiceWords.class.getResourceAsStream(RESOURCE)) {
            if (inputStream == null) {
                return defaults();
            }
            try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                Config config = GSON.fromJson(reader, Config.class);
                if (config == null) {
                    return defaults();
                }
                List<String> hotwords = safeList(config.hotwords);
                List<String> extraWords = safeList(config.extraWords);
                if (hotwords.isEmpty() && extraWords.isEmpty()) {
                    return defaults();
                }
                return new Words(hotwords.isEmpty() ? DEFAULT_HOTWORDS : hotwords, extraWords.isEmpty() ? DEFAULT_EXTRA_WORDS : extraWords);
            }
        } catch (IOException | RuntimeException e) {
            return defaults();
        }
    }

    private static Words defaults() {
        return new Words(DEFAULT_HOTWORDS, DEFAULT_EXTRA_WORDS);
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private static final class Config {
        private List<String> hotwords;
        private List<String> extraWords;
    }

    public record Words(List<String> hotwords, List<String> extraWords) {
    }
}
