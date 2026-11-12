package com.rheinmetal.tianshu.function.auxilium.core.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPolicy;
import com.rheinmetal.tianshu.api.tool.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXToolProtocolTest {

    private static final ToolId ECHO = ToolId.of("test", "echo");

    @AfterEach
    void clean() {
        TianshuTools.unregister(ECHO);
    }

    @Test
    void parsesAPlainCall() {
        AXToolCall call = AXToolCallParser.parse("{\"tool\":\"test:echo\",\"args\":{\"text\":\"hi\"}}").orElseThrow();
        assertEquals(ECHO, call.toolId());
        assertEquals("hi", call.args().get("text").getAsString());
    }

    @Test
    void toleratesFencedJsonAndMissingArgs() {
        assertTrue(AXToolCallParser.parse("```json\n{\"tool\":\"test:echo\"}\n```").isPresent());
        assertEquals(0, AXToolCallParser.parse("```\n{\"tool\":\"test:echo\"}\n```").orElseThrow().args().size());
    }

    @Test
    void anOrdinaryAnswerIsNotACall() {
        assertTrue(AXToolCallParser.parse("It is 3 blocks north of you.").isEmpty());
        assertTrue(AXToolCallParser.parse("{\"tool\":\"echo\"}").isEmpty());
        assertTrue(AXToolCallParser.parse("{\"tool\":\"test:echo\",\"args\":[1]}").isEmpty());
        assertTrue(AXToolCallParser.parse(null).isEmpty());
    }

    @Test
    void theCatalogueListOnlyEnabledTools() {
        ToolSpec enabled = new ToolSpec(ToolId.of("test", "one"), ToolCategory.LOCAL, "does one", new JsonObject(), true);
        ToolSpec disabled = new ToolSpec(ToolId.of("test", "two"), ToolCategory.LOCAL, "does two", null, false);
        String text = AXToolCatalog.describe(List.of(enabled, disabled));
        assertTrue(text.contains("test:one"));
        assertFalse(text.contains("test:two"));
        assertTrue(text.contains("You can call at most one tool"));
    }

    @Test
    void theExecutorRunsThroughTheRegistry() throws Exception {
        TianshuTools.register(new EchoTool());

        JsonObject ok = new AXToolExecutor().call(new AXToolCall(ECHO,
                        com.google.gson.JsonParser.parseString("{\"text\":\"hi\"}").getAsJsonObject()))
                .get(2, TimeUnit.SECONDS);
        assertTrue(ok.get("ok").getAsBoolean());
        assertEquals("hi", ok.getAsJsonObject("result").get("reply").getAsString());

        JsonObject unknown = new AXToolExecutor().call(new AXToolCall(ToolId.of("test", "missing"), new JsonObject()))
                .get(2, TimeUnit.SECONDS);
        assertFalse(unknown.get("ok").getAsBoolean());
        assertEquals("UNKNOWN_TOOL", unknown.get("code").getAsString());
    }

    public static final class Args {
        public String text;
    }

    public static final class Reply {
        public String reply;
    }

    private static final class EchoTool implements TianshuTool<Args, Reply> {
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
            return "Echoes; a test double.";
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
        public ToolPolicy policy() {
            return ToolPolicy.forCategory(ToolCategory.LOCAL);
        }

        @Override
        public CompletableFuture<Reply> invoke(ToolContext context, Args args) {
            Reply reply = new Reply();
            reply.reply = args.text;
            return CompletableFuture.completedFuture(reply);
        }
    }
}