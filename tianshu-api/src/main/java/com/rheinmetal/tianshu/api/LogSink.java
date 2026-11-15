package com.rheinmetal.tianshu.api;

/** Platform-neutral sink for Tianshu runtime logs. */
public interface LogSink {
    LogSink NOOP = new LogSink() {
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void error(String message, Throwable failure) { }
    };

    /**
     * A sink that writes through {@link System.Logger}.
     *
     * <p>The default for platform-neutral code, so a component without a host wiring still leaves a trace. Until this
     * existed, the whole protocol runtime reported failures nowhere: an envelope could fail, a dead letter could be
     * dropped, and nothing said so.</p>
     */
    LogSink SYSTEM = new LogSink() {
        private final System.Logger logger = System.getLogger("tianshu");

        @Override public void info(String message) { logger.log(System.Logger.Level.INFO, message); }

        @Override public void warn(String message) { logger.log(System.Logger.Level.WARNING, message); }

        @Override public void error(String message, Throwable failure) {
            logger.log(System.Logger.Level.ERROR, message, failure);
        }
    };

    void info(String message);

    void warn(String message);

    void error(String message, Throwable failure);
}
