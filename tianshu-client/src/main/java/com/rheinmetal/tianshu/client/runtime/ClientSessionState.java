package com.rheinmetal.tianshu.client.runtime;

public enum ClientSessionState {
    NEW,
    /** Client-wide subsystems are being installed; nothing may open a world yet. */
    INITIALIZING,
    CLIENT_READY,
    /** A required client subsystem could not be installed; the session will not open a world. */
    CLIENT_FAILED,
    WORLD_STARTING,
    WORLD_RUNNING,
    WORLD_STOPPING,
    SHUTDOWN
}