package com.rheinmetal.tianshu.client.diagnostics;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.api.LogSink;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientDiagnosticRouter implements DiagnosticSink, LogSink, AutoCloseable {
    private final Predicate<String> moduleEnabled;
    private final ClientDiagnosticWriter writer;
    private final ClientDiagnosticMessageSink messageSink;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong droppedEvents = new AtomicLong();

    public ClientDiagnosticRouter(Path gameDirectory, Predicate<String> moduleEnabled) {
        this(gameDirectory, moduleEnabled, 2_048, 8L * 1024L * 1024L, 5, ClientDiagnosticMessageSink.NOOP);
    }

    ClientDiagnosticRouter(Path gameDirectory, Predicate<String> moduleEnabled, int queueCapacity, long maxFileBytes, int maxArchives) {
        this(gameDirectory, moduleEnabled, queueCapacity, maxFileBytes, maxArchives, ClientDiagnosticMessageSink.NOOP);
    }

    public ClientDiagnosticRouter(Path gameDirectory, Predicate<String> moduleEnabled, int queueCapacity, long maxFileBytes, int maxArchives, ClientDiagnosticMessageSink messageSink) {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        this.moduleEnabled = Objects.requireNonNull(moduleEnabled, "moduleEnabled");
        this.messageSink = messageSink == null ? ClientDiagnosticMessageSink.NOOP : messageSink;
        this.writer = new ClientDiagnosticWriter(gameDirectory.resolve("logs").resolve("tianshu-diagnostics.log"), queueCapacity, maxFileBytes, maxArchives);
    }

    @Override
    public void publish(DiagnosticEvent event) {
        if (event == null || closed.get() || !moduleEnabled.test(event.moduleId())) {
            return;
        }
        if (!writer.offer(event)) {
            droppedEvents.incrementAndGet();
        }
        ClientDiagnosticMessage message = ClientDiagnosticProgress.messageFor(event);
        if (message != null) {
            try {
                messageSink.publish(message);
            } catch (RuntimeException failure) {
                writer.offerRuntime("ERROR", "diagnostic.message_sink_failed", failure);
            }
        }
    }

    @Override
    public void info(String message) {
        writeRuntime("INFO", message, null);
    }

    @Override
    public void warn(String message) {
        writeRuntime("WARN", message, null);
    }

    @Override
    public void error(String message, Throwable failure) {
        writeRuntime("ERROR", message, failure);
    }

    private void writeRuntime(String level, String message, Throwable failure) {
        if (closed.get() || !writer.offerRuntime(level, message, failure)) {
            droppedEvents.incrementAndGet();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            long dropped = droppedEvents.get();
            if (dropped > 0L) {
                writer.offerRuntime("WARN", "diagnostic.events_dropped count=" + dropped, null);
            }
            writer.closeAndFlush();
        }
    }

    long droppedEventCount() {
        return droppedEvents.get();
    }

}
