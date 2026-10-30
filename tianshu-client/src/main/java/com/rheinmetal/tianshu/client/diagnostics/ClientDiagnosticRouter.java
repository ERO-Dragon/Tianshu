package com.rheinmetal.tianshu.client.diagnostics;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.client.host.DiagnosticChatCatalogue;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One sink for everything the session reports: the structured log file, and the optional progress lines in chat.
 *
 * <p>The two outputs are decided separately on purpose. The file is the support surface — a warning or an error
 * belongs there in every build. Chat is the debugging surface, and only exists while the debug switch is on, which
 * a release build can never turn on. A module does not choose the destination: it picks a severity and, if the code
 * deserves a player-facing line at all, registers that code in the {@link DiagnosticChatCatalogue}.</p>
 */
public final class ClientDiagnosticRouter implements DiagnosticSink, LogSink, AutoCloseable {
    private final Predicate<String> moduleEnabled;
    private final BooleanSupplier inGameMessages;
    private final ClientDiagnosticWriter writer;
    private final ClientDiagnosticMessageSink messageSink;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong droppedEvents = new AtomicLong();
    /** Whether any record was accepted: a router that never logged must not create the file just to close. */
    private final AtomicBoolean acceptedRecords = new AtomicBoolean();
    private final DiagnosticChatCatalogue chatCatalogue;

    public ClientDiagnosticRouter(Path gameDirectory, Predicate<String> moduleEnabled) {
        this(gameDirectory, moduleEnabled, ClientDiagnosticMessageSink.NOOP);
    }

    /** Production shape: the queue and rotation budget stay owned here instead of being repeated at the call site. */
    public ClientDiagnosticRouter(Path gameDirectory, Predicate<String> moduleEnabled, ClientDiagnosticMessageSink messageSink) {
        this(gameDirectory, moduleEnabled, messageSink, new ClientDiagnosticChatCatalogue());
    }

    /**
     * @param chatCatalogue the session's code → chat-line table, filled by the modules as they assemble; an empty
     *                      catalogue keeps the generic fallback, which is what a test or a headless run wants
     */
    public ClientDiagnosticRouter(Path gameDirectory, Predicate<String> moduleEnabled,
                                  ClientDiagnosticMessageSink messageSink,
                                  DiagnosticChatCatalogue chatCatalogue) {
        this(gameDirectory, moduleEnabled, () -> false, 2_048, 8L * 1024L * 1024L, 5, messageSink, chatCatalogue);
    }

    /**
     * @param inGameMessages whether progress lines may reach the player; the platform passes the debug switch here,
     *                       so a release build logs without ever printing in game
     */
    public ClientDiagnosticRouter(Path gameDirectory, Predicate<String> moduleEnabled, BooleanSupplier inGameMessages,
                                  ClientDiagnosticMessageSink messageSink,
                                  DiagnosticChatCatalogue chatCatalogue) {
        this(gameDirectory, moduleEnabled, inGameMessages, 2_048, 8L * 1024L * 1024L, 5, messageSink, chatCatalogue);
    }

    ClientDiagnosticRouter(Path gameDirectory, Predicate<String> moduleEnabled, int queueCapacity, long maxFileBytes, int maxArchives) {
        this(gameDirectory, moduleEnabled, queueCapacity, maxFileBytes, maxArchives, ClientDiagnosticMessageSink.NOOP);
    }

    public ClientDiagnosticRouter(Path gameDirectory, Predicate<String> moduleEnabled, int queueCapacity, long maxFileBytes, int maxArchives, ClientDiagnosticMessageSink messageSink) {
        this(gameDirectory, moduleEnabled, () -> false, queueCapacity, maxFileBytes, maxArchives, messageSink, new ClientDiagnosticChatCatalogue());
    }

    public ClientDiagnosticRouter(Path gameDirectory, Predicate<String> moduleEnabled, int queueCapacity, long maxFileBytes, int maxArchives, ClientDiagnosticMessageSink messageSink, DiagnosticChatCatalogue chatCatalogue) {
        this(gameDirectory, moduleEnabled, () -> false, queueCapacity, maxFileBytes, maxArchives, messageSink, chatCatalogue);
    }

    public ClientDiagnosticRouter(Path gameDirectory, Predicate<String> moduleEnabled, BooleanSupplier inGameMessages,
                                  int queueCapacity, long maxFileBytes, int maxArchives,
                                  ClientDiagnosticMessageSink messageSink, DiagnosticChatCatalogue chatCatalogue) {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        this.moduleEnabled = Objects.requireNonNull(moduleEnabled, "moduleEnabled");
        this.inGameMessages = inGameMessages == null ? () -> false : inGameMessages;
        this.messageSink = messageSink == null ? ClientDiagnosticMessageSink.NOOP : messageSink;
        this.chatCatalogue = chatCatalogue == null ? new ClientDiagnosticChatCatalogue() : chatCatalogue;
        this.writer = new ClientDiagnosticWriter(gameDirectory.resolve("logs").resolve("tianshu-diagnostics.log"), queueCapacity, maxFileBytes, maxArchives);
    }

    @Override
    public void publish(DiagnosticEvent event) {
        if (event == null || closed.get() || !moduleEnabled.test(event.moduleId())) {
            return;
        }
        boolean inGame = inGameMessages.getAsBoolean();
        // Trace, debug and progress output are debugging aids and stay out of a shipping log; a warning or an error
        // is news the log keeps whether or not anyone turned the switch on.
        if (inGame || event.severity() == DiagnosticSeverity.WARN || event.severity() == DiagnosticSeverity.ERROR) {
            if (!writer.offer(event)) {
                droppedEvents.incrementAndGet();
            } else {
                acceptedRecords.set(true);
            }
        }
        if (!inGame) {
            return;
        }
        ClientDiagnosticMessage message = ClientDiagnosticProgress.messageFor(event, chatCatalogue);
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
