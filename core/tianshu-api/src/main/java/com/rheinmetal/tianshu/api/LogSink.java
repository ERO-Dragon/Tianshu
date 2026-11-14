package com.rheinmetal.tianshu.api;

/** Platform-neutral sink for Tianshu runtime logs. */
public interface LogSink {
    LogSink NOOP = new LogSink() {
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void error(String message, Throwable failure) { }
    };

    void info(String message);

    void warn(String message);

    void error(String message, Throwable failure);
}
