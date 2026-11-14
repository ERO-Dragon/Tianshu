package com.rheinmetal.tianshu.client.capability.mcp;

import java.io.IOException;

/** The remote server answered, but rejected the method: the connection itself is healthy. */
public final class McpRemoteException extends IOException {

    public McpRemoteException(String message) {
        super(message);
    }
}
