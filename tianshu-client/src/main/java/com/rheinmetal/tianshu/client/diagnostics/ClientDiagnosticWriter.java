package com.rheinmetal.tianshu.client.diagnostics;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ClientDiagnosticWriter implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 2_048;
    private static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_ARCHIVES = 5;
    /** Flush on a batch instead of per line; an idle queue also flushes, so the file never lags by more than one burst. */
    private static final int FLUSH_EVERY_LINES = 32;
    /** How long the writer waits before trying the file again after an IO failure. */
    private static final long RETRY_COOLDOWN_MILLIS = 5_000L;
    /** Failures between two reports, so a long outage does not fill the launcher log with the same sentence. */
    private static final int REPORT_EVERY_FAILURES = 60;

    private final Path logFile;
    private final ArrayBlockingQueue<String> queue;
    private final long maxFileBytes;
    private final int maxArchives;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final CountDownLatch terminated = new CountDownLatch(1);
    private final Thread worker;

    ClientDiagnosticWriter(Path logFile) {
        this(logFile, QUEUE_CAPACITY, MAX_FILE_BYTES, MAX_ARCHIVES);
    }

    ClientDiagnosticWriter(Path logFile, int queueCapacity, long maxFileBytes, int maxArchives) {
        this.logFile = logFile.toAbsolutePath().normalize();
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.maxFileBytes = Math.max(1L, maxFileBytes);
        this.maxArchives = Math.max(1, maxArchives);
        this.worker = new Thread(this::run, "Tianshu-Diagnostics-Writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    boolean offer(DiagnosticEvent event) {
        return accepting.get() && queue.offer(serialize(event));
    }

    boolean offerRuntime(String level, String message, Throwable failure) {
        if (!accepting.get()) {
            return false;
        }
        return queue.offer(serializeRuntime(level, message, failure));
    }

    @Override
    public void close() {
        closeAndFlush();
    }

    void closeAndFlush() {
        if (!accepting.getAndSet(false)) {
            return;
        }
        worker.interrupt();
        try {
            terminated.await(5L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        BufferedWriter writer = null;
        int linesSinceFlush = 0;
        long writtenBytes = 0L;
        int writeFailures = 0;
        long droppedLines = 0L;
        long retryAfterMillis = 0L;
        while (accepting.get() || !queue.isEmpty()) {
            String line;
            try {
                line = queue.poll(250L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                if (!accepting.get() && queue.isEmpty()) {
                    break;
                }
                continue;
            }
            if (line == null) {
                // Nothing queued: make the buffered lines visible instead of waiting for the next burst.
                if (writer != null && linesSinceFlush > 0) {
                    flushQuietly(writer);
                    linesSinceFlush = 0;
                }
                continue;
            }
            if (retryAfterMillis > System.currentTimeMillis()) {
                // Still inside the cooldown: drop this line instead of hammering a file that just failed. The queue
                // keeps draining, so a broken disk cannot grow it without bound.
                droppedLines++;
                continue;
            }
            try {
                if (writer == null) {
                    Files.createDirectories(logFile.getParent());
                    writer = openWriter();
                    writtenBytes = Files.isRegularFile(logFile) ? Files.size(logFile) : 0L;
                }
                if (writtenBytes >= maxFileBytes) {
                    writer.flush();
                    writer.close();
                    rotateIfNeeded();
                    writer = openWriter();
                    writtenBytes = 0L;
                    linesSinceFlush = 0;
                }
                writer.write(line);
                writer.newLine();
                // Counting bytes here keeps the rotation check free of a stat call per line.
                writtenBytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
                linesSinceFlush++;
                if (linesSinceFlush >= FLUSH_EVERY_LINES || queue.isEmpty()) {
                    writer.flush();
                    linesSinceFlush = 0;
                }
            } catch (Exception failure) {
                // A short file lock or a full disk must not end diagnostics for the session. The writer used to switch
                // itself off after four failures and print one line to stderr: after that every WARN and ERROR was
                // dropped silently, including the "events dropped" summary that would have explained the silence.
                // Now it drops this line, waits out a cooldown and tries again on a later line — and says so, with the
                // path, the cause and the running count.
                closeQuietly(writer);
                writer = null;
                linesSinceFlush = 0;
                writeFailures++;
                droppedLines++;
                retryAfterMillis = System.currentTimeMillis() + RETRY_COOLDOWN_MILLIS;
                if (writeFailures == 1 || writeFailures % REPORT_EVERY_FAILURES == 0) {
                    // stderr on purpose: this sink cannot report its own failure through itself, and the diagnostics
                    // package is deliberately not allowed to depend on a host logger.
                    System.err.println("Tianshu: diagnostics log unavailable at " + logFile + " after "
                            + writeFailures + " IO failure(s) of type " + failure.getClass().getSimpleName()
                            + "; " + droppedLines + " line(s) dropped, retrying in "
                            + (RETRY_COOLDOWN_MILLIS / 1000L) + "s");
                }
                sleepQuietly(250L);
            }
        }
        if (droppedLines > 0L) {
            System.err.println("Tianshu: diagnostics log closed at " + logFile + " with " + droppedLines
                    + " line(s) dropped across " + writeFailures + " IO failure(s)");
        }
        closeQuietly(writer);
        terminated.countDown();
    }

    private static void flushQuietly(BufferedWriter writer) {
        try {
            writer.flush();
        } catch (IOException ignored) {
            // The next write reports the failure and goes through the retry path.
        }
    }

    private static void closeQuietly(BufferedWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // Already failing; nothing useful to add.
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private BufferedWriter openWriter() throws IOException {
        return Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    private void rotateIfNeeded() throws IOException {
        if (!Files.exists(logFile) || Files.size(logFile) < maxFileBytes) {
            return;
        }
        for (int index = maxArchives; index >= 1; index--) {
            Path source = index == 1 ? logFile : archive(index - 1);
            Path target = archive(index);
            if (Files.exists(source)) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Path archive(int index) {
        return logFile.resolveSibling(logFile.getFileName() + "." + index);
    }

    private static String serialize(DiagnosticEvent event) {
        StringBuilder json = new StringBuilder(256);
        json.append('{')
                .append("\"module\":\"").append(escape(event.moduleId())).append("\",")
                .append("\"code\":\"").append(escape(event.code())).append("\",")
                .append("\"severity\":\"").append(event.severity()).append("\",")
                .append("\"privacy\":\"").append(event.privacy()).append("\",")
                .append("\"timestamp\":").append(event.occurredAtMillis()).append(',')
                .append("\"attributes\":{");
        boolean first = true;
        for (var entry : event.attributes().entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('\"').append(escape(entry.getKey())).append("\":\"").append(escape(entry.getValue())).append('\"');
        }
        return json.append("}}" ).toString();
    }

    private static String serializeRuntime(String level, String message, Throwable failure) {
        StringBuilder json = new StringBuilder(192)
                .append('{')
                .append("\"type\":\"runtime\",")
                .append("\"severity\":\"").append(escape(level)).append("\",")
                .append("\"timestamp\":").append(System.currentTimeMillis()).append(',')
                .append("\"message\":\"").append(escape(message == null ? "" : message)).append('"');
        if (failure != null) {
            json.append(",\"exception\":\"").append(escape(failure.toString())).append('"');
        }
        return json.append('}').toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
