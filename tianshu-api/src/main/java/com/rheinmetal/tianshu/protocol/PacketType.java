package com.rheinmetal.tianshu.protocol;

public enum PacketType {
    EVENT,
    COMMAND,
    REQUEST,
    RESPONSE,
    STREAM_START,
    STREAM_CHUNK,
    STREAM_END,
    CANCEL,
    STATUS,
    ERROR,
    HEARTBEAT,
    PROGRESS
}

