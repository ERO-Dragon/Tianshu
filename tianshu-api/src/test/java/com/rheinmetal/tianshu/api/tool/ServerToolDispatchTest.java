package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one dispatch path: parsing, execution, envelopes, idempotency and error hygiene. */
class ServerToolDispatchTest {

    private static final ToolId ECHO = ToolId.of("test", "dispatch.echo");
    private static final ToolId WRITE = ToolId.of("test", "dispatch.write");
    private static final ToolId BLOCKING = ToolId.of("test", "dispatch.blocking");
    private static final ToolId COOPERATIVE = ToolId.of("test", "dispatch.cooperative");

    @BeforeEach
    void setUp() {
        ServerToolDispatch.clear();
        TianshuTools.clearRateLimits();
        EchoTool.CALLS.set(0);
        TianshuTools.register(new EchoTool());
        TianshuTools.register(new WriteTool());
        TianshuTools.register(new BlockingTool());
        TianshuTools.register(new CooperativeTool());
        TianshuTools.setEnabled(WRITE, true);
    }

    @AfterEach
    void clean() {
        TianshuTools.unregister(ECHO);
        TianshuTools.unregister(WRITE);
        TianshuTools.unregister(BLOCKING);
        TianshuTools.unregister(COOPERATIVE);
        ServerToolDispatch.clear();
    }

    @Test
    void aCallReturnsTheToolResultInTheWireEnvelope() throws Exception {
        ServerToolDispatch.Reply reply = ServerToolDispatch.respond(
                        ToolCaller.player("p1", false), "r1", ECHO.toString(), "{\"text\":\"hi\"}")
                .get(2, TimeUnit.SECONDS);

        assertTrue(reply.ok());
        assertEquals("r1", reply.requestId());
        assertTrue(reply.valueJson().contains("hi"));
    }

    @Test
    void aReplayedRequestIdRunsTheToolOnce() throws Exception {
        String args = "{\"text\":\"hi\"}";
        ServerToolDispatch.Reply first = ServerToolDispatch.respond(
                ToolCaller.player("p1", false), "same", ECHO.toString(), args).get(2, TimeUnit.SECONDS);
        ServerToolDispatch.Reply second = ServerToolDispatch.respond(
                ToolCaller.player("p1", false), "same", ECHO.toString(), args).get(2, TimeUnit.SECONDS);

        assertEquals(first.valueJson(), second.valueJson());
        assertEquals(1, EchoTool.CALLS.get(), "a retried request must not run the world change again");
    }

    @Test
    void malformedIdsAndArgumentsAnswerWithoutThrowing() throws Exception {
        ServerToolDispatch.Reply badId = ServerToolDispatch.respond(
                        ToolCaller.player("p1", false), "r2", "not a tool id", "{}")
                .get(2, TimeUnit.SECONDS);
        assertEquals("UNKNOWN_TOOL", badId.errorCode());

        ServerToolDispatch.Reply badArgs = ServerToolDispatch.respond(
                        ToolCaller.player("p1", false), "r3", ECHO.toString(), "{oops")
                .get(2, TimeUnit.SECONDS);
        assertEquals("INVALID_ARGS", badArgs.errorCode());
    }

    @Test
    void aThrownFailureIsOneLineWithoutAStackTrace() throws Exception {
        ServerToolDispatch.Reply reply = ServerToolDispatch.respond(
                        ToolCaller.player("p1", false), "r4", ECHO.toString(), "{\"explode\":true}")
                .get(2, TimeUnit.SECONDS);

        assertFalse(reply.ok());
        assertEquals("FAILED", reply.errorCode());
        assertFalse(reply.message().contains("\n"), "no stack or multi-line leakage");
        assertTrue(reply.message().contains("boom"));
    }

    @Test
    void onePlayerCannotFillTheWholeInFlightWindow() throws Exception {
        ToolCaller player = ToolCaller.player("p2", false);
        for (int index = 0; index < 8; index++) {
            ServerToolDispatch.respond(player, "block-" + index, BLOCKING.toString(), "{}");
        }
        ServerToolDispatch.Reply ninth = ServerToolDispatch.respond(player, "block-9", BLOCKING.toString(), "{}")
                .get(2, TimeUnit.SECONDS);

        assertTrue(!ninth.ok());
        assertEquals("BUSY", ninth.errorCode());
    }

    @Test
    void theReservedCatalogRequestReturnsTheServerTools() throws Exception {
        ServerToolDispatch.Reply reply = ServerToolDispatch.respond(ToolCaller.player("p1", true), "catalog",
                        ServerToolDispatch.CATALOG_TOOL_ID, "{}")
                .get(2, TimeUnit.SECONDS);

        assertTrue(reply.ok());
        com.google.gson.JsonArray tools = com.google.gson.JsonParser.parseString(reply.valueJson())
                .getAsJsonObject().getAsJsonArray("tools");
        assertTrue(tools.toString().contains(WRITE.toString()));
    }

    @Test
    void aCooperativeToolSeesTheCancellationSignal() throws Exception {
        ToolCaller player = ToolCaller.player("p3", false);
        ServerToolDispatch.respond(player, "coop", COOPERATIVE.toString(), "{}");

        assertTrue(ServerToolDispatch.cancel(player.id(), "coop"));
        for (int attempt = 0; attempt < 100 && !CooperativeTool.observed.get(); attempt++) {
            Thread.sleep(10);
        }
        assertTrue(CooperativeTool.observed.get(), "the tool must see the token flip");
    }

    @Test
    void aCancelRequestCancelsTheInFlightCall() throws Exception {
        ToolCaller player = ToolCaller.player("p1", false);
        ServerToolDispatch.respond(player, "target", BLOCKING.toString(), "{}");

        JsonObject args = new JsonObject();
        args.addProperty("requestId", "target");
        ServerToolDispatch.Reply reply = ServerToolDispatch.respond(player, "cancel", ServerToolDispatch.CANCEL_TOOL_ID,
                        args.toString())
                .get(2, TimeUnit.SECONDS);

        assertTrue(reply.ok());
        assertTrue(com.google.gson.JsonParser.parseString(reply.valueJson()).getAsJsonObject()
                .get("cancelled").getAsBoolean());
    }

    @Test
    void writeToolsStillNeedAnOperator() throws Exception {
        ServerToolDispatch.Reply denied = ServerToolDispatch.respond(
                        ToolCaller.player("p1", false), "r5", WRITE.toString(), "{}")
                .get(2, TimeUnit.SECONDS);
        assertEquals("NO_PERMISSION", denied.errorCode());

        ServerToolDispatch.Reply allowed = ServerToolDispatch.respond(
                        ToolCaller.player("p1", true), "r6", WRITE.toString(), "{}")
                .get(2, TimeUnit.SECONDS);
        assertTrue(allowed.ok());
        assertNotEquals("", allowed.valueJson());
    }

    public static final class Args {
        public String text;
        public boolean explode;
    }

    public static final class Reply {
        public String text;
    }

    private static final class EchoTool implements TianshuTool<Args, Reply> {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Override
        public ToolId id() {
            return ECHO;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.LOCAL;
        }

        @Override
        public String description() {
            return "Echo; a dispatch test double.";
        }

        @Override
        public Type argsType() {
            return Args.class;
        }

        @Override
        public Type resultType() {
            return Reply.class;
        }

        @Override
        public CompletableFuture<Reply> invoke(ToolContext context, Args args) {
            CALLS.incrementAndGet();
            if (args != null && args.explode) {
                return CompletableFuture.failedFuture(new IllegalStateException("boom\nwith a stack"));
            }
            Reply reply = new Reply();
            reply.text = args == null ? "" : args.text;
            return CompletableFuture.completedFuture(reply);
        }
    }

    private static final class CooperativeTool implements TianshuTool<Args, Reply> {
        static final java.util.concurrent.atomic.AtomicBoolean observed =
                new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public ToolId id() {
            return COOPERATIVE;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.LOCAL;
        }

        @Override
        public String description() {
            return "Polls the cancellation signal.";
        }

        @Override
        public Type argsType() {
            return Args.class;
        }

        @Override
        public Type resultType() {
            return Reply.class;
        }

        @Override
        public CompletableFuture<Reply> invoke(ToolContext context, Args args) {
            return CompletableFuture.supplyAsync(() -> {
                while (!context.cancellation().isCancelled()) {
                    Thread.onSpinWait();
                }
                observed.set(true);
                return new Reply();
            });
        }
    }

    private static final class BlockingTool implements TianshuTool<Args, Reply> {
        @Override
        public ToolId id() {
            return BLOCKING;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.LOCAL;
        }

        @Override
        public String description() {
            return "Blocking; a cancellation test double.";
        }

        @Override
        public Type argsType() {
            return Args.class;
        }

        @Override
        public Type resultType() {
            return Reply.class;
        }

        @Override
        public CompletableFuture<Reply> invoke(ToolContext context, Args args) {
            return new CompletableFuture<>();
        }
    }

    private static final class WriteTool implements TianshuTool<Args, Reply> {
        @Override
        public ToolId id() {
            return WRITE;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.SERVER_WRITE;
        }

        @Override
        public String description() {
            return "Write; a dispatch test double.";
        }

        @Override
        public Type argsType() {
            return Args.class;
        }

        @Override
        public Type resultType() {
            return Reply.class;
        }

        @Override
        public CompletableFuture<Reply> invoke(ToolContext context, Args args) {
            return CompletableFuture.completedFuture(new Reply());
        }
    }
    @Test
    void aRetryThatRacesTheCompletionStillRunsTheToolOnce() throws Exception {
        GatedTool.CALLS.set(0);
        TianshuTools.register(new GatedTool());
        try {
            // Start the work, let it reach its gate, then complete it while a retry is about to look the key up.
            java.util.concurrent.CompletableFuture<ServerToolDispatch.Reply> first = ServerToolDispatch.respond(
                    ToolCaller.player("p1", false), "racing", GATED.toString(), "{}");
            assertTrue(GatedTool.STARTED.await(2, TimeUnit.SECONDS), "the tool should be running");

            Thread retry = new Thread(() -> ServerToolDispatch.respond(
                    ToolCaller.player("p1", false), "racing", GATED.toString(), "{}"));
            retry.start();
            GatedTool.RELEASE.countDown();
            retry.join(2_000L);

            assertTrue(first.get(2, TimeUnit.SECONDS).ok());
            assertEquals(1, GatedTool.CALLS.get(),
                    "a retry may see the work in flight or see its answer, but must never start it again");
        } finally {
            TianshuTools.unregister(GATED);
        }
    }

    private static final ToolId GATED = ToolId.of("test", "dispatch.gated");

    /** Runs once, and only finishes when the test opens its gate, so a retry can be aimed at the completion window. */
    private static final class GatedTool implements TianshuTool<Args, Reply> {
        static final AtomicInteger CALLS = new AtomicInteger();
        static final java.util.concurrent.CountDownLatch STARTED = new java.util.concurrent.CountDownLatch(1);
        static final java.util.concurrent.CountDownLatch RELEASE = new java.util.concurrent.CountDownLatch(1);

        @Override
        public ToolId id() {
            return GATED;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.LOCAL;
        }

        @Override
        public String description() {
            return "Waits for the test before answering.";
        }

        @Override
        public Type argsType() {
            return Args.class;
        }

        @Override
        public Type resultType() {
            return Reply.class;
        }

        @Override
        public CompletableFuture<Reply> invoke(ToolContext context, Args args) {
            CALLS.incrementAndGet();
            STARTED.countDown();
            return CompletableFuture.supplyAsync(() -> {
                try {
                    RELEASE.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new Reply();
            });
        }
    }
}