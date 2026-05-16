package com.rheinmetal.tianshu.function.assistant.memory;

import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptLanguage;
import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptProfile;
import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptResourceRepository;
import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptTask;

public final class AssistantCompressionPromptProvider {
    private final AssistantPromptResourceRepository resourceRepository;

    public AssistantCompressionPromptProvider() {
        this(null);
    }

    public AssistantCompressionPromptProvider(AssistantPromptResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    public String promptFor(AssistantCompressionTaskType type) {
        AssistantPromptTask task = type == AssistantCompressionTaskType.LONG_TERM_MEMORY
                ? AssistantPromptTask.MEMORY_LONG_TERM_MERGE
                : AssistantPromptTask.MEMORY_SHORT_TERM_COMPRESSION;
        if (resourceRepository != null) {
            AssistantPromptProfile profile = resourceRepository.loadProfile(task, AssistantPromptLanguage.ZH_CN, "default");
            if (profile != null && profile.behaviorRules() != null && !profile.behaviorRules().isBlank()) {
                return profile.behaviorRules();
            }
        }
        return fallbackPrompt(type);
    }

    private String fallbackPrompt(AssistantCompressionTaskType type) {
        if (type == AssistantCompressionTaskType.LONG_TERM_MEMORY) {
            return "你是长期记忆合并器。请把输入的多个短期记忆合并成一条长期记忆。只保留长期有效的用户偏好、稳定事实、重要边界和已确认结论。不要保留临时细节。不要添加原文没有的信息。只输出一条长期记忆正文。";
        }
        return "你是对话记忆压缩器。请把输入的连续 user/assistant 对话压缩成短期记忆。保留用户明确要求、已确认结论、未解决问题和重要边界。不要添加原文没有的信息。只输出压缩后的记忆正文。";
    }
}
