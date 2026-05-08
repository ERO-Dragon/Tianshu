package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record ChatAssistantClientEventPayload(Action action, String text, long deadlineAtMillis, String reason) implements ITianshuPayload {
    public enum Action {
        OPEN_INPUT,
        UPDATE_TEXT,
        RESET_COUNTDOWN,
        CLOSE_INPUT,
        SHOW_HINT
    }

    public ChatAssistantClientEventPayload {
        if (action == null) action = Action.SHOW_HINT;
        if (text == null) text = "";
        if (reason == null) reason = "";
        deadlineAtMillis = Math.max(0L, deadlineAtMillis);
    }
}
