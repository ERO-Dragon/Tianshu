package com.rheinmetal.tianshu.function.lab.acceptance;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/**
 * Fixtures for the model-download cases: a loopback HTTP server that answers a scripted sequence, the environment
 * stub the transport needs, and a digest helper.
 *
 * <p>Written here rather than taken from {@code tianshu-testkit} on purpose — the testkit is a test-only artifact and
 * never enters a development run, so a case that used it would fail at runtime with a missing class instead of
 * checking the transport.</p>
 */
final class DownloadFixtures {

    private DownloadFixtures() {
    }

    /** A loopback server that serves one body per request, in order, and counts what it answered. */
    static final class ScriptedServer implements AutoCloseable {

        private final HttpServer server;
        private final Deque<String> bodies = new ArrayDeque<>();
        private final List<String> served = new CopyOnWriteArrayList<>();

        ScriptedServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/model.onnx", exchange -> {
                String body;
                synchronized (bodies) {
                    body = bodies.isEmpty() ? "" : bodies.removeFirst();
                    served.add(body);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Length", Integer.toString(bytes.length));
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        void enqueue(String body) {
            synchronized (bodies) {
                bodies.addLast(body);
            }
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model.onnx");
        }

        int servedCount() {
            return served.size();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** The transport reads the environment for logs and the game directory; nothing else here is used. */
    static final class StubEnvironment implements IGameEnvironment {

        private final Path directory;

        StubEnvironment(Path directory) {
            this.directory = directory;
        }

        @Override
        public void executeOnMainThread(Runnable task) {
            task.run();
        }

        @Override
        public Path getGameDirectory() {
            return directory;
        }

        @Override
        public boolean isClientSide() {
            return true;
        }

        @Override
        public void openFolder(Path dir) {
        }

        @Override
        public void info(String msg) {
        }

        @Override
        public void warn(String msg) {
        }

        @Override
        public void error(String msg, Throwable t) {
        }

        @Override
        public DiagnosticSink diagnostics() {
            return DiagnosticSink.NOOP;
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 is unavailable", unavailable);
        }
    }

    static String sha256OfText(String text) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 is unavailable", unavailable);
        }
    }
}