package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record LlmTaskStreamChunkPayload(
        String taskId,
        String purpose,
        int index,
        String text,
        boolean finished
) implements ITianshuPayload {
    public LlmTaskStreamChunkPayload {
        taskId = taskId == null || taskId.isBlank() ? "llm.task" : taskId.trim();
        purpose = purpose == null || purpose.isBlank() ? "llm.task" : purpose.trim();
        if (index < 0) {
            index = 0;
        }
        text = text == null ? "" : text;
    }
}
