package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stdio transport, held to the two things a shared pipe can get wrong.
 *
 * <p>One pipe carries every exchange, so a request and its answer have to stay together: with two calls in
 * flight, each must receive <em>its own</em> answer rather than whichever line arrived first. And a child process
 * printing to stdout puts lines on that pipe which are not answers at all; treating one as the answer makes
 * {@code tools/list} report zero tools and a tool call return someone else's data.</p>
 *
 * <p>Both cases run against a real child process rather than a mock, because the failure mode lives in the pipe
 * itself.</p>
 */
class McpStdioClientTest {

    @TempDir
    Path root;

    private McpStdioClient client;

    @AfterEach
    void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Test
    void concurrentCallsEachGetTheirOwnAnswer() throws Exception {
        McpStdioClient connection = start();
        connection.initialize();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // The server answers every request on its own thread, so the first answer arrives *after* the second
            // one. Without a lock around write+read the two exchanges read each other's line.
            Future<String> slow = pool.submit(() -> echo(connection, "slow-answer", 200L, false));
            Thread.sleep(50L);
            Future<String> quick = pool.submit(() -> echo(connection, "quick-answer", 0L, false));

            assertEquals("slow-answer", slow.get(15, TimeUnit.SECONDS));
            assertEquals("quick-answer", quick.get(15, TimeUnit.SECONDS),
                    "the second call must not be handed the first call's answer");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aStrayLineAndANotificationAreNotMistakenForTheAnswer() throws Exception {
        McpStdioClient connection = start();
        connection.initialize();

        assertEquals("hello", echo(connection, "hello", 0L, true),
                "a non-JSON log line and a notification precede the answer on the same pipe");
    }

    @Test
    void aDeadProcessIsRejectedInsteadOfQueueingOnAHalfOpenPipe() throws Exception {
        McpStdioClient connection = start("exit-now");

        assertThrows(IOException.class, connection::initialize);

        IOException afterwards = assertThrows(IOException.class, connection::initialize);
        assertTrue(afterwards.getMessage().contains("unusable"),
                "a client whose pipe failed must refuse further work: " + afterwards.getMessage());
    }

    private String echo(McpStdioClient connection, String text, long delayMs, boolean noise) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("text", text);
        arguments.addProperty("delayMs", delayMs);
        arguments.addProperty("noise", noise);
        JsonObject result = connection.callTool("echo", arguments);
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    /** Launches the fixture in its own JVM; the argument file keeps a long classpath off the command line. */
    private McpStdioClient start(String... extraArgs) throws IOException {
        Path jvmArguments = root.resolve("jvm-args-" + System.nanoTime() + ".txt");
        StringBuilder text = new StringBuilder();
        text.append("-cp").append('\n')
                .append(System.getProperty("java.class.path")).append('\n')
                .append(McpStdioTestServer.class.getName()).append('\n');
        for (String extra : extraArgs) {
            text.append(extra).append('\n');
        }
        Files.writeString(jvmArguments, text.toString(), StandardCharsets.UTF_8);

        String executable = Paths.get(System.getProperty("java.home"), "bin", javaExecutable()).toString();
        client = new McpStdioClient(List.of(executable, "@" + jvmArguments.toAbsolutePath()));
        return client;
    }

    private static String javaExecutable() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
    }
}