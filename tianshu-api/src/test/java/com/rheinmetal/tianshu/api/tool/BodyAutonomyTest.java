package com.rheinmetal.tianshu.api.tool;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The body scale: two levels, and the policy that says what a server's ceiling means for bodies.
 *
 * <p>The case this file exists for: a body write declares survival-level body work, so a server set to "reads only"
 * refuses it. Before the body scale existed, that call was judged only on the player scale, which has no opinion about a
 * carrier mining — and a {@code READ_ONLY} server happily let it dig.</p>
 */
class BodyAutonomyTest {

    @Test
    void theServerCeilingDecidesWhatBodiesMayDo() {
        assertNull(BodyAutonomy.allowedBy(ToolAutonomy.READ_ONLY), "read-only means no body work at all");
        assertEquals(BodyAutonomy.SURVIVAL, BodyAutonomy.allowedBy(ToolAutonomy.CONSERVATIVE));
        assertEquals(BodyAutonomy.PRIVILEGED, BodyAutonomy.allowedBy(ToolAutonomy.FULL));
    }

    @Test
    void permitsIsMonotonic() {
        assertTrue(BodyAutonomy.SURVIVAL.permits(BodyAutonomy.SURVIVAL));
        assertFalse(BodyAutonomy.SURVIVAL.permits(BodyAutonomy.PRIVILEGED));
        assertTrue(BodyAutonomy.PRIVILEGED.permits(BodyAutonomy.SURVIVAL));
        assertTrue(BodyAutonomy.PRIVILEGED.permits(BodyAutonomy.PRIVILEGED));
    }

    @Test
    void aBodyWriteDeclaresSurvivalWork() {
        FakeTool bodyWrite = new FakeTool(ToolCategory.BODY_WRITE);

        assertEquals(BodyAutonomy.SURVIVAL, bodyWrite.requiredBodyAutonomy());
        assertEquals(ToolAutonomy.READ_ONLY, bodyWrite.requiredAutonomy(),
                "the player scale says nothing about a carrier acting as itself");
    }

    @Test
    void aReadDeclaresNothingOnTheBodyScale() {
        assertNull(new FakeTool(ToolCategory.SERVER_READ).requiredBodyAutonomy(),
                "a read is not something the body does to the world");
        assertNull(new FakeTool(ToolCategory.SERVER_WRITE).requiredBodyAutonomy(),
                "a player-only write says nothing about bodies either");
    }

    @Test
    void aReadOnlyServerRefusesABodyWrite() {
        FakeTool bodyWrite = new FakeTool(ToolCategory.BODY_WRITE);
        BodyAutonomy allowed = BodyAutonomy.allowedBy(ToolAutonomy.READ_ONLY);

        // This is the defect the scale exists to close: the player scale alone would have let it through.
        assertTrue(ToolAutonomy.READ_ONLY.permits(bodyWrite.requiredAutonomy()));
        assertFalse(allowed != null && allowed.permits(bodyWrite.requiredBodyAutonomy()));
    }

    @Test
    void aConservativeServerAllowsSurvivalWorkButNotPrivilegedWork() {
        BodyAutonomy allowed = BodyAutonomy.allowedBy(ToolAutonomy.CONSERVATIVE);

        assertTrue(allowed.permits(BodyAutonomy.SURVIVAL));
        assertFalse(allowed.permits(BodyAutonomy.PRIVILEGED));

        FakeTool privileged = new FakeTool(ToolCategory.BODY_WRITE) {
            @Override
            public BodyAutonomy requiredBodyAutonomy() {
                return BodyAutonomy.PRIVILEGED;
            }
        };
        assertFalse(allowed.permits(privileged.requiredBodyAutonomy()));
    }

    @Test
    void theContextReportsTheSameLevelTheRegistryEnforces() {
        ToolCaller full = ToolCaller.agent("test-mod", false, ToolAutonomy.FULL);
        ToolCaller conservative = ToolCaller.agent("test-mod", false, ToolAutonomy.CONSERVATIVE);

        // Client side, because that is where the caller's own tier is what decides. On the server side the configured
        // ceiling port decides and the caller's claim is ignored, which is the documented rule: a caller may narrow what
        // it offers, never widen what the server allows.
        Optional<BodyAutonomy> onFull = new ToolContext(full, ToolSide.CLIENT).allowedBodyAutonomy();
        Optional<BodyAutonomy> onConservative =
                new ToolContext(conservative, ToolSide.CLIENT).allowedBodyAutonomy();

        assertEquals(Optional.of(BodyAutonomy.PRIVILEGED), onFull);
        assertEquals(Optional.of(BodyAutonomy.SURVIVAL), onConservative);
    }

    /** The smallest tool that only answers the two scale questions. */
    private static class FakeTool implements TianshuTool<Object, Object> {

        private final ToolCategory category;

        FakeTool(ToolCategory category) {
            this.category = category;
        }

        @Override
        public ToolId id() {
            return ToolId.of("tianshu", "test.scale");
        }

        @Override
        public ToolCategory category() {
            return category;
        }

        @Override
        public String description() {
            return "test";
        }

        @Override
        public Type argsType() {
            return Object.class;
        }

        @Override
        public Type resultType() {
            return Object.class;
        }

        @Override
        public CompletableFuture<Object> invoke(ToolContext context, Object args) {
            return CompletableFuture.completedFuture(null);
        }
    }
}