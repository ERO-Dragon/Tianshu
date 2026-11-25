package com.rheinmetal.tianshu.dubhe.shared.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheWebTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200, "hello"));
        server.createContext("/missing", exchange -> respond(exchange, 404, "gone"));
        server.createContext("/moved", exchange -> redirect(exchange, "/ok"));
        server.createContext("/away", exchange -> redirect(exchange, "http://example.invalid/ok"));
        server.createContext("/loop", exchange -> redirect(exchange, "/loop"));
        server.createContext("/huge", exchange -> respond(exchange, 200, "a".repeat(DubheWeb.MAX_BYTES + 4096)));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void returnsTheBodyOfASuccessfulGet() {
        assertEquals(Optional.of("hello"), DubheWeb.get(base + "/ok").join());
    }

    @Test
    void treatsANonOkStatusAsNoAnswer() {
        assertTrue(DubheWeb.get(base + "/missing").join().isEmpty());
    }

    @Test
    void followsARedirectOnTheSameHost() {
        assertEquals(Optional.of("hello"), DubheWeb.get(base + "/moved").join());
    }

    @Test
    void refusesARedirectToAnotherHost() {
        // A source must not be able to walk us somewhere else; no connection is attempted to the other host.
        assertTrue(DubheWeb.get(base + "/away").join().isEmpty());
    }

    @Test
    void stopsAfterTheRedirectBudget() {
        assertTrue(DubheWeb.get(base + "/loop").join().isEmpty());
    }

    @Test
    void truncatesABodyAtTheByteCap() {
        Optional<String> body = DubheWeb.get(base + "/huge").join();

        assertTrue(body.isPresent());
        assertEquals(DubheWeb.MAX_BYTES, body.get().length());
    }

    @Test
    void refusesSchemesThatAreNotWeb() {
        assertTrue(DubheWeb.get("file:///etc/passwd").join().isEmpty());
        assertTrue(DubheWeb.get("not a url").join().isEmpty());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }
}