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
    private static final System.Logger LOGGER = System.getLogger(ClientDiagnosticWriter.class.getName());
    private static final int QUEUE_CAPACITY = 2_048;
    private static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_ARCHIVES = 3;

    private final Path logFile;
    private final ArrayBlockingQueue<DiagnosticEvent> queue;
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
        return accepting.get() && queue.offer(event);
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
            if (!terminated.await(5L, TimeUnit.SECONDS)) {
                LOGGER.log(System.Logger.Level.WARNING, "Tianshu diagnostic writer did not flush before timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        BufferedWriter writer = null;
        try {
            while (accepting.get() || !queue.isEmpty()) {
                DiagnosticEvent event;
                try {
                    event = queue.poll(250L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    if (!accepting.get() && queue.isEmpty()) {
                        break;
                    }
                    continue;
                }
                if (event == null) {
                    continue;
                }
                if (writer == null) {
                    Files.createDirectories(logFile.getParent());
                    writer = openWriter();
                }
                if (Files.size(logFile) >= maxFileBytes) {
                    writer.flush();
                    writer.close();
                    rotateIfNeeded();
                    writer = openWriter();
                }
                writer.write(serialize(event));
                writer.newLine();
                writer.flush();
            }
        } catch (Exception exception) {
            LOGGER.log(System.Logger.Level.ERROR, "Tianshu diagnostic writer failed", exception);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // The writer is already shutting down; the primary failure is logged above.
                }
            }
            terminated.countDown();
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
