package com.rheinmetal.tianshu.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class ModelDownloadTestServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, Queue<Response>> responses = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();

    ModelDownloadTestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    URI uri(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + normalized);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void enqueue(String path, Response... scriptedResponses) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        responses.computeIfAbsent(normalized, ignored -> new ArrayDeque<>())
                .addAll(Arrays.asList(scriptedResponses));
    }

    int requests(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        return requestCounts.getOrDefault(normalized, new AtomicInteger()).get();
    }

    static Response text(int status, String body) {
        return bytes(status, body.getBytes(StandardCharsets.UTF_8));
    }

    static Response bytes(int status, byte[] body) {
        return new Response(status, body, body.length);
    }

    static Response declaredLength(int status, byte[] body, long declaredLength) {
        return new Response(status, body, declaredLength);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        requestCounts.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        Queue<Response> queue = responses.get(path);
        Response response = queue == null ? null : queue.poll();
        if (response == null) {
            response = text(404, "not found");
        }
        exchange.sendResponseHeaders(response.status(), response.declaredLength());
        try {
            exchange.getResponseBody().write(response.body());
        } catch (IOException ignored) {
            // A deliberately short response can make HttpServer reject close/write.
        } finally {
            try {
                exchange.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    record Response(int status, byte[] body, long declaredLength) {
    }
}
