package com.rheinmetal.tianshu.function.llm.rag;

import java.nio.file.Path;

public record LlmRagPathResolution(
        String worldId,
        String moduleId,
        String agentId,
        String profile,
        Path ragRoot,
        Path worldRoot,
        Path profilesFile,
        Path moduleRoot,
        Path staticRagRoot,
        Path agentRoot,
        Path memoryRagRoot,
        Path memoriesFile
) {
}
