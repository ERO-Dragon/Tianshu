package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP over stdio: one process, newline-delimited JSON-RPC on stdin/stdout, stderr discarded. The process is started
 * by the constructor and killed on {@link #close()}.
 *
 * <p>One pipe carries every exchange, so a request and its answer are made atomic here: two concurrent calls must
 * never be able to read each other's response. Answers are matched by JSON-RPC {@code id}; anything else on the
 * pipe — a server-initiated notification, a log line a child process wrote to stdout — is skipped instead of being
 * mistaken for the answer.</p>
 */
public final class McpStdioClient extends AbstractMcpClient {

    private static final long READ_TIMEOUT_SECONDS = 15;

    private final Process process;
    private final BufferedWriter out;
    private final BufferedReader in;
    private final ExecutorService reads = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tianshu-mcp-stdio");
        thread.setDaemon(true);
        return thread;
    });

    /** Serialises {@code write + read} so a request and its answer cannot interleave on the shared pipe. */
    private final Object exchangeLock = new Object();

    /**
     * Set once a read times out or the process dies: {@code BufferedReader.readLine()} blocks on a pipe and does
     * not respond to interruption, so the worker thread stays parked and this client cannot be trusted again.
     */
    private volatile boolean unusable;

    public McpStdioClient(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        this.process = builder.start();
        this.out = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    protected JsonObject exchange(JsonObject request) throws IOException, InterruptedException {
        synchronized (exchangeLock) {
            if (unusable) {
                throw new IOException("MCP stdio client is unusable after a failed read");
            }
            write(request);
            return awaitResponse(idOf(request));
        }
    }

    @Override
    protected void sendNotification(JsonObject notification) throws IOException {
        synchronized (exchangeLock) {
            if (unusable) {
                throw new IOException("MCP stdio client is unusable after a failed read");
            }
            write(notification);
        }
    }

    @Override
    public void close() {
        unusable = true;
        reads.shutdownNow();
        process.destroy();
    }

    /** Reads until the answer carrying {@code expectedId}; every other line on the pipe is skipped. */
    private JsonObject awaitResponse(String expectedId) throws IOException, InterruptedException {
        if (expectedId.isEmpty()) {
            throw new IOException("an MCP stdio exchange needs a request id to match its answer");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READ_TIMEOUT_SECONDS);
        while (true) {
            JsonObject message = parse(readLine(deadline));
            if (message == null || !expectedId.equals(idOf(message))) {
                continue;
            }
            return message;
        }
    }

    private String readLine(long deadline) throws IOException, InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
            unusable = true;
            throw new IOException("MCP stdio did not answer in " + READ_TIMEOUT_SECONDS + "s");
        }
        Future<String> line = reads.submit(in::readLine);
        String response;
        try {
            response = line.get(remaining, TimeUnit.NANOSECONDS);
        } catch (ExecutionException failure) {
            unusable = true;
            throw new IOException("MCP stdio read failed", failure.getCause());
        } catch (TimeoutException timeout) {
            // Cancel is best effort (the read ignores interrupts); the flag is what actually protects the caller.
            line.cancel(true);
            unusable = true;
            throw new IOException("MCP stdio did not answer in " + READ_TIMEOUT_SECONDS + "s");
        }
        if (response == null) {
            unusable = true;
            throw new IOException("MCP stdio process ended");
        }
        return response;
    }

    /** A line that is not a JSON object is noise on the pipe, not an answer. */
    private static JsonObject parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(line);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** The JSON-RPC id as text; empty for a notification, which never matches an answer. */
    private static String idOf(JsonObject message) {
        JsonElement id = message.get("id");
        if (id == null || !id.isJsonPrimitive()) {
            return "";
        }
        return id.getAsString();
    }

    private void write(JsonObject message) throws IOException {
        if (!process.isAlive()) {
            unusable = true;
            throw new IOException("MCP stdio process is not running");
        }
        try {
            out.write(message.toString());
            out.write("\n");
            out.flush();
        } catch (IOException broken) {
            // A closed pipe is not recoverable either: marking it here keeps the next call from queueing on it.
            unusable = true;
            throw broken;
        }
    }
}