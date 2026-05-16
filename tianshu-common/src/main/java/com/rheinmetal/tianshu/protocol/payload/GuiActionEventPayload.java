package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.gui.GuiActionEvent;

public record GuiActionEventPayload(GuiActionEvent event) implements ITianshuPayload {
    public GuiActionEventPayload {
        if (event == null) {
            throw new IllegalArgumentException("event cannot be null");
        }
    }
}
