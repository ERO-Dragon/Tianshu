package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record ServerActionPayload(String actionId, String serializedIntent) implements ITianshuPayload {
    public ServerActionPayload {
        if (actionId == null) actionId = "";
        if (serializedIntent == null) serializedIntent = "";
    }
}
