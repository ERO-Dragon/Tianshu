package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TianshuToolsTest {

    private static final ToolId ECHO = ToolId.of("test", "echo");

    @AfterEach
    void cleanRegistry() {
        TianshuTools.unregister(ECHO);
        TianshuTools.unregister(ToolId.of("test", "write"));
        TianshuTools.unregister(ToolId.of("test", "read"));
        TianshuTools.unregister(ToolId.of("test", "slow"));
        TianshuTools.unregister(ToolId.of("test", "operator"));
        TianshuTools.uninstallServerChannel();
    }


    @Test
    void aPlayerCallingInARapidLoopIsThrottledButTheAgentIsNot() throws Exception {
        TianshuTools.register(new EchoTool());
        TianshuTools.clearRateLimits();
        JsonObject args = JsonParser.parseString("{\"text\":\"hi\",\"count\":1}").getAsJsonObject();

        int limited = 0;
        for (int call = 0; call < 40; call++) {
            JsonObject envelope = TianshuTools.callJson(ECHO, args, ToolCaller.player("p1", false))
                    .get(2, TimeUnit.SECONDS);
            if (!envelope.get("ok").getAsBoolean()) {
                assertEquals("RATE_LIMITED", envelope.get("code").getAsString());
                limited++;
            }
        }
        assertTrue(limited > 0, "a player hammering one tool must eventually be throttled");

        for (int call = 0; call < 40; call++) {
            JsonObject envelope = TianshuTools.callJson(ECHO, args, ToolCaller.agent(false))
                    .get(2, TimeUnit.SECONDS);
            assertTrue(envelope.get("ok").getAsBoolean(), "the agent is a trusted peer, not a network surface");
        }
    }

    @Test
    void aLocalToolWorksForAnyCallerAndBothPaths() throws Exception {
        TianshuTools.register(new EchoTool());

        ToolHandle<Query, Answer> echo = TianshuTools.handle(ECHO, Query.class, Answer.class);
        Answer typed = echo.call(new Query("hello", 2), ToolCaller.mod("anothermod"))
                .get(2, TimeUnit.SECONDS);
        assertEquals("hello", typed.text);

        JsonObject envelope = TianshuTools.callJson(ECHO,
                        JsonParser.parseString("{\"text\":\"hello\",\"count\":2}"),
                        ToolCaller.agent(false))
                .get(2, TimeUnit.SECONDS);
        assertTrue(envelope.get("ok").getAsBoolean());
        assertEquals("hello", envelope.getAsJsonObject("result").get("text").getAsString());
    }

    @Test
    void envelopesCarryTimingAndPayloadSize() throws Exception {
        TianshuTools.register(new EchoTool());
        JsonObject success = TianshuTools.callJson(ECHO,
                        JsonParser.parseString("{\"text\":\"hello\"}"), ToolCaller.agent(false))
                .get(2, TimeUnit.SECONDS);
        assertTrue(success.get("durationMs").getAsLong() >= 0);
        assertTrue(success.get("bytes").getAsInt() > 0);

        JsonObject failure = TianshuTools.callJson(ToolId.of("test", "missing"), new JsonObject(),
                ToolCaller.agent(false)).get(2, TimeUnit.SECONDS);
        assertTrue(failure.get("durationMs").getAsLong() >= 0);
        assertFalse(failure.has("bytes"));
    }

    @Test
    void categoryDefaultsMatchThePolicyTable() {
        assertTrue(ToolPolicy.forCategory(ToolCategory.LOCAL).enabledByDefault());
        assertTrue(ToolPolicy.forCategory(ToolCategory.SERVER_READ).enabledByDefault());
        assertFalse(ToolPolicy.forCategory(ToolCategory.SERVER_WRITE).enabledByDefault());
        assertEquals(ToolPermission.OPERATOR, ToolPolicy.forCategory(ToolCategory.SERVER_WRITE).permission());
    }

    @Test
    void aWriteToolStartsDisabledAndNeedsAnOperator() throws Exception {
        ToolId write = ToolId.of("test", "write");
        TianshuTools.register(new FixedResultTool(write, ToolCategory.SERVER_WRITE, "changed"));
        ToolHandle<Query, Answer> handle = TianshuTools.handle(write, Query.class, Answer.class);
        try {
            ExecutionException disabled = assertThrows(ExecutionException.class,
                    () -> handle.call(new Query("x", 1), ToolCaller.player("p1", true), ToolSide.SERVER)
                            .get(2, TimeUnit.SECONDS));
            assertInstanceOf(ToolException.class, disabled.getCause());
            assertEquals(ToolError.DISABLED, ((ToolException) disabled.getCause()).code());

            TianshuTools.setEnabled(write, true);
            ExecutionException noPermission = assertThrows(ExecutionException.class,
                    () -> handle.call(new Query("x", 1), ToolCaller.player("p1", false), ToolSide.SERVER)
                            .get(2, TimeUnit.SECONDS));
            assertEquals(ToolError.NO_PERMISSION, ((ToolException) noPermission.getCause()).code());

            Answer answer = handle.call(new Query("x", 1), ToolCaller.player("p1", true), ToolSide.SERVER)
                    .get(2, TimeUnit.SECONDS);
            assertEquals("changed", answer.text);
        } finally {
            TianshuTools.unregister(write);
        }
    }

    @Test
    void aServerToolWithoutADeclaredChannelIsUnavailable() throws Exception {
        ToolId read = ToolId.of("test", "read");
        TianshuTools.register(new FixedResultTool(read, ToolCategory.SERVER_READ, "server"));
        ToolHandle<Query, Answer> handle = TianshuTools.handle(read, Query.class, Answer.class);
        try {
            List<ToolSpec> catalogue = TianshuTools.catalog(ToolCaller.agent(false));
            assertTrue(catalogue.stream().noneMatch(spec -> spec.id().equals(read)));

            ExecutionException unsupported = assertThrows(ExecutionException.class,
                    () -> handle.call(new Query("x", 1), ToolCaller.agent(false), ToolSide.CLIENT)
                            .get(2, TimeUnit.SECONDS));
            assertEquals(ToolError.UNSUPPORTED_SIDE, ((ToolException) unsupported.getCause()).code());

            JsonObject envelope = TianshuTools.callJson(read, new JsonObject(), ToolCaller.agent(false),
                    ToolSide.CLIENT).get(2, TimeUnit.SECONDS);
            assertFalse(envelope.get("ok").getAsBoolean());
            assertEquals("UNSUPPORTED_SIDE", envelope.get("code").getAsString());
        } finally {
            TianshuTools.unregister(read);
        }
    }

    @Test
    void aServerToolOnTheClientTravelsOverTheInstalledChannel() throws Exception {
        ToolId read = ToolId.of("test", "read");
        TianshuTools.register(new FixedResultTool(read, ToolCategory.SERVER_READ, "client"));
        ToolHandle<Query, Answer> handle = TianshuTools.handle(read, Query.class, Answer.class);
        try {
            TianshuTools.installServerChannel((id, args, caller, timeoutMillis) -> {
                JsonObject result = new JsonObject();
                result.addProperty("text", "from-server:" + args.getAsJsonObject().get("text").getAsString());
                result.addProperty("count", 7);
                return CompletableFuture.completedFuture(result);
            });

            Answer typed = handle.call(new Query("ping", 1), ToolCaller.mod("anothermod"))
                    .get(2, TimeUnit.SECONDS);
            assertEquals("from-server:ping", typed.text);
            assertEquals(7, typed.count);
        } finally {
            TianshuTools.unregister(read);
        }
    }

    @Test
    void unknownToolsFailWithAStableCode() {
        ToolHandle<Query, Answer> missing = TianshuTools.handle(ToolId.of("test", "missing"), Query.class,
                Answer.class);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> missing.call(new Query("x", 1), ToolCaller.agent(false)).get(2, TimeUnit.SECONDS));
        assertEquals(ToolError.UNKNOWN_TOOL, ((ToolException) failure.getCause()).code());
    }

    @Test
    void malformedArgumentsBecomeInvalidArgsRatherThanAnException() throws Exception {
        TianshuTools.register(new EchoTool());
        JsonObject envelope = TianshuTools.callJson(ECHO, JsonParser.parseString("{\"text\":123,\"count\":\"x\"}"),
                ToolCaller.agent(false)).get(2, TimeUnit.SECONDS);
        assertFalse(envelope.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", envelope.get("code").getAsString());
    }

    @Test
    void aToolThatNeverFinishesTimesOut() {
        ToolId slow = ToolId.of("test", "slow");
        TianshuTools.register(new SlowTool(slow));
        ToolHandle<Query, Answer> handle = TianshuTools.handle(slow, Query.class, Answer.class);
        try {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> handle.call(new Query("x", 1), ToolCaller.agent(false)).get(2, TimeUnit.SECONDS));
            assertEquals(ToolError.TIMEOUT, ((ToolException) failure.getCause()).code());
        } finally {
            TianshuTools.unregister(slow);
        }
    }

    @Test
    void duplicateRegistrationIsRejected() {
        TianshuTools.register(new EchoTool());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TianshuTools.register(new EchoTool()));
        assertTrue(failure.getMessage().contains(ECHO.toString()));
    }

    @Test
    void theCatalogueListsDisabledToolsButHidesOnesTheCallerMayNeverUse() {
        ToolId operatorOnly = ToolId.of("test", "operator");
        TianshuTools.register(new EchoTool());
        TianshuTools.register(new FixedResultTool(operatorOnly, ToolCategory.SERVER_READ, "x",
                ToolPolicy.forCategory(ToolCategory.SERVER_READ)
                        .withPermission(ToolPermission.OPERATOR)
                        .withTimeout(500)));
        TianshuTools.setEnabled(ECHO, false);
        try {
            List<ToolSpec> plain = TianshuTools.catalog(ToolCaller.agent(false), ToolSide.SERVER);
            assertTrue(plain.stream().anyMatch(spec -> spec.id().equals(ECHO) && !spec.enabled()));
            assertTrue(plain.stream().noneMatch(spec -> spec.id().equals(operatorOnly)));

            List<ToolSpec> operator = TianshuTools.catalog(ToolCaller.player("op", true), ToolSide.SERVER);
            assertTrue(operator.stream().anyMatch(spec -> spec.id().equals(operatorOnly)));
        } finally {
            TianshuTools.unregister(operatorOnly);
            TianshuTools.clearEnabledOverride(ECHO);
        }
    }

    public static final class Query {
        public String text;
        public int count;

        public Query(String text, int count) {
            this.text = text;
            this.count = count;
        }
    }

    public static final class Answer {
        public String text;
        public int count;

        public Answer(String text, int count) {
            this.text = text;
            this.count = count;
        }
    }

    private static final class EchoTool implements TianshuTool<Query, Answer> {
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
            return "Echoes its argument; a test double.";
        }

        @Override
        public Type argsType() {
            return Query.class;
        }

        @Override
        public Type resultType() {
            return Answer.class;
        }

        @Override
        public CompletableFuture<Answer> invoke(ToolContext context, Query args) {
            return CompletableFuture.completedFuture(new Answer(args.text, args.count));
        }
    }

    private static final class FixedResultTool implements TianshuTool<Query, Answer> {
        private final ToolId id;
        private final ToolCategory category;
        private final String text;
        private final ToolPolicy policy;

        FixedResultTool(ToolId id, ToolCategory category, String text) {
            this(id, category, text, ToolPolicy.forCategory(category));
        }

        FixedResultTool(ToolId id, ToolCategory category, String text, ToolPolicy policy) {
            this.id = id;
            this.category = category;
            this.text = text;
            this.policy = policy;
        }

        @Override
        public ToolId id() {
            return id;
        }

        @Override
        public ToolCategory category() {
            return category;
        }

        @Override
        public String description() {
            return "Returns a fixed answer; a test double.";
        }

        @Override
        public Type argsType() {
            return Query.class;
        }

        @Override
        public Type resultType() {
            return Answer.class;
        }

        @Override
        public ToolPolicy policy() {
            return policy;
        }

        @Override
        public CompletableFuture<Answer> invoke(ToolContext context, Query args) {
            return CompletableFuture.completedFuture(new Answer(text, 1));
        }
    }

    private static final class SlowTool implements TianshuTool<Query, Answer> {
        private final ToolId id;

        SlowTool(ToolId id) {
            this.id = id;
        }

        @Override
        public ToolId id() {
            return id;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.LOCAL;
        }

        @Override
        public String description() {
            return "Never answers; a test double.";
        }

        @Override
        public Type argsType() {
            return Query.class;
        }

        @Override
        public Type resultType() {
            return Answer.class;
        }

        @Override
        public ToolPolicy policy() {
            return ToolPolicy.forCategory(ToolCategory.LOCAL).withTimeout(100);
        }

        @Override
        public CompletableFuture<Answer> invoke(ToolContext context, Query args) {
            return new CompletableFuture<>();
        }
    }
}