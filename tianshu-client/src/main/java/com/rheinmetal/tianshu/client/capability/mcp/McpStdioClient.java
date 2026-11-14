package com.rheinmetal.tianshu.client.capability.mcp;

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

    public McpStdioClient(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        this.process = builder.start();
        this.out = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    protected JsonObject exchange(JsonObject request) throws IOException, InterruptedException {
        write(request);
        Future<String> line = reads.submit(in::readLine);
        String response;
        try {
            response = line.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            throw new IOException("MCP stdio read failed", failure.getCause());
        } catch (TimeoutException timeout) {
            line.cancel(true);
            throw new IOException("MCP stdio did not answer in " + READ_TIMEOUT_SECONDS + "s");
        }
        if (response == null || response.isBlank()) {
            throw new IOException("MCP stdio process ended");
        }
        return JsonParser.parseString(response).getAsJsonObject();
    }

    @Override
    protected void sendNotification(JsonObject notification) throws IOException {
        write(notification);
    }

    @Override
    public void close() {
        reads.shutdownNow();
        process.destroy();
    }

    private void write(JsonObject message) throws IOException {
        if (!process.isAlive()) {
            throw new IOException("MCP stdio process is not running");
        }
        synchronized (out) {
            out.write(message.toString());
            out.write("\n");
            out.flush();
        }
    }
}
