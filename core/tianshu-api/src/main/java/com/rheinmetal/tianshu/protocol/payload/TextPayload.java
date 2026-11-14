package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record TextPayload(String text) implements ITianshuPayload {
    public TextPayload {
        if (text == null) text = "";
    }
}
