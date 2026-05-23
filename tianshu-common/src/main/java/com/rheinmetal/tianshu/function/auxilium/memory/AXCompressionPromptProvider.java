package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptProfile;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptResourceRepository;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptTask;

public final class AXCompressionPromptProvider {
    private final AXPromptResourceRepository resourceRepository;
    private final AXPromptLanguageProvider languageProvider;

    public AXCompressionPromptProvider() {
        this(null, null);
    }

    public AXCompressionPromptProvider(AXPromptResourceRepository resourceRepository) {
        this(resourceRepository, null);
    }

    public AXCompressionPromptProvider(AXPromptResourceRepository resourceRepository, AXPromptLanguageProvider languageProvider) {
        this.resourceRepository = resourceRepository;
        this.languageProvider = languageProvider == null ? AXPromptLanguageProvider.fixed(AXPromptLanguage.EN_US) : languageProvider;
    }

    public String promptFor(AXCompressionTaskType type) {
        AXPromptTask task = type == AXCompressionTaskType.LONG_TERM_MEMORY
                ? AXPromptTask.MEMORY_LONG_TERM_MERGE
                : AXPromptTask.MEMORY_SHORT_TERM_COMPRESSION;
        AXPromptLanguage language = languageProvider.currentLanguage();
        if (resourceRepository != null) {
            AXPromptProfile profile = resourceRepository.loadProfile(task, language, "default");
            if (profile != null && profile.behaviorRules() != null && !profile.behaviorRules().isBlank()) {
                return profile.behaviorRules();
            }
        }
        return fallbackPrompt(type, language);
    }

    private String fallbackPrompt(AXCompressionTaskType type, AXPromptLanguage language) {
        if (language == AXPromptLanguage.ZH_CN) {
            if (type == AXCompressionTaskType.LONG_TERM_MEMORY) {
                return "你是长期记忆合并器。请把输入的多个短期记忆合并成一条长期记忆。只保留长期有效的用户偏好、稳定事实、重要边界和已确认结论。不要保留临时细节。不要添加原文没有的信息。只输出一条长期记忆正文。";
            }
            return "你是对话记忆压缩器。请把输入的连续 user/AX 对话压缩成短期记忆。保留用户明确要求、已确认结论、未解决问题和重要边界。不要添加原文没有的信息。只输出压缩后的记忆正文。";
        }
        if (type == AXCompressionTaskType.LONG_TERM_MEMORY) {
            return "You are a long-term memory merger. Merge multiple short-term memories into one long-term memory. Keep only durable user preferences, stable facts, important boundaries, and confirmed conclusions. Do not keep temporary details. Do not add information that is not present in the input. Output only one long-term memory entry.";
        }
        return "You are a conversation memory compressor. Compress consecutive user/AX turns into short-term memory. Preserve explicit user requirements, confirmed conclusions, unresolved issues, and important boundaries. Do not add information that is not present in the input. Output only the compressed memory text.";
    }
}
