package com.rheinmetal.tianshu.diagnostics;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientDiagnosticRouter implements DiagnosticSink, AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(ClientDiagnosticRouter.class.getName());
    private final Predicate<String> moduleEnabled;
    private final ClientDiagnosticWriter writer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong droppedEvents = new AtomicLong();

    public ClientDiagnosticRouter(Path gameDirectory, Predicate<String> moduleEnabled) {
        this(gameDirectory, moduleEnabled, 2_048, 8L * 1024L * 1024L, 3);
    }

    ClientDiagnosticRouter(Path gameDirectory, Predicate<String> moduleEnabled, int queueCapacity, long maxFileBytes, int maxArchives) {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        this.moduleEnabled = Objects.requireNonNull(moduleEnabled, "moduleEnabled");
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
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            writer.closeAndFlush();
            long dropped = droppedEvents.get();
            if (dropped > 0L) {
                LOGGER.log(System.Logger.Level.WARNING, "Dropped diagnostic events because the bounded queue was full: " + dropped);
            }
        }
    }

    long droppedEventCount() {
        return droppedEvents.get();
    }
}
