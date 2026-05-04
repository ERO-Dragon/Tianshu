package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record LlmPromptPayload(String text, String context) implements ITianshuPayload {
    public LlmPromptPayload {
        if (text == null) text = "";
        if (context == null) context = "";
    }
}
