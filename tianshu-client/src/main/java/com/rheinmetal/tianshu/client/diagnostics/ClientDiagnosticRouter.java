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
    /** Whether any record was accepted: a router that never logged must not create the file just to close. */
    private final AtomicBoolean acceptedRecords = new AtomicBoolean();

    public ClientDiagnosticRouter(Path gameDirectory, Predicate<String> moduleEnabled) {
        this(gameDirectory, moduleEnabled, ClientDiagnosticMessageSink.NOOP);
    }

    /** Production shape: the queue and rotation budget stay owned here instead of being repeated at the call site. */
    public ClientDiagnosticRouter(Path gameDirectory, Predicate<String> moduleEnabled, ClientDiagnosticMessageSink messageSink) {
        this(gameDirectory, moduleEnabled, 2_048, 8L * 1024L * 1024L, 5, messageSink);
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
        } else {
            acceptedRecords.set(true);
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
        } else {
            acceptedRecords.set(true);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            long dropped = droppedEvents.get();
            if (dropped > 0L) {
                writer.offerRuntime("WARN", "diagnostic.events_dropped count=" + dropped, null);
            }
            // The last record this sink ever writes — only if something was written at all: a router for a
            // disabled module must not create the log file just to announce that it is closing.
            if (acceptedRecords.get()) {
                writer.offerRuntime("INFO", "diagnostic.log_closed", null);
            }
            writer.closeAndFlush();
        }
    }

    long droppedEventCount() {
        return droppedEvents.get();
    }

}
