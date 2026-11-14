package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only a write that actually ran is audited; a refused call is not. */
class ToolAuditTest {

    private static final ToolId WRITE = ToolId.of("test", "audit.write");
    private final AtomicReference<ToolAudit.Event> seen = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        TianshuTools.register(new WriteTool());
        TianshuTools.setEnabled(WRITE, true);
        TianshuTools.clearRateLimits();
        ToolAudits.install(seen::set);
    }

    @AfterEach
    void clean() {
        ToolAudits.uninstall();
        TianshuTools.unregister(WRITE);
    }

    @Test
    void aWriteThatRunsIsAudited() throws Exception {
        JsonObject result = TianshuTools.callJson(WRITE, new JsonObject(), ToolCaller.player("p1", true),
                        ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
        assertTrue(result.get("ok").getAsBoolean());

        ToolAudit.Event event = seen.get();
        assertNotNull(event);
        assertEquals(WRITE, event.toolId());
        assertEquals(ToolCategory.SERVER_WRITE, event.category());
        assertEquals("p1", event.callerId());
        assertTrue(event.ok());
    }

    @Test
    void aRefusedWriteIsNotAudited() throws Exception {
        TianshuTools.setEnabled(WRITE, false);

        JsonObject result = TianshuTools.callJson(WRITE, new JsonObject(), ToolCaller.player("p1", true),
                        ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
        assertEquals("DISABLED", result.get("code").getAsString());
        assertNull(seen.get());
    }

    public static final class Args {
    }

    public static final class Reply {
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
            return "Write; an audit test double.";
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
}
