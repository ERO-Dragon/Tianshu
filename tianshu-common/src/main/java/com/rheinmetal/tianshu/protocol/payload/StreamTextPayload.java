package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record StreamTextPayload(String text, int index, boolean last) implements ITianshuPayload {
    public StreamTextPayload {
        if (text == null) text = "";
    }
}
