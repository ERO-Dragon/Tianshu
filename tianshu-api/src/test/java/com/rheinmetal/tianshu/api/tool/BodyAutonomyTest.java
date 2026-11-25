package com.rheinmetal.tianshu.api.tool;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two autonomy scales, and the comparison that makes them one decision.
 *
 * <p>The case this file exists for: a body write declares survival-level body work, so a server set to "reads only"
 * refuses it. Before the body scale existed, that call was judged only on the player scale, which says nothing about a
 * carrier mining — and a {@code READ_ONLY} server happily let it dig.</p>
 */
class BodyAutonomyTest {

    @Test
    void bodyScaleMirrorsTheServerKnobLevelForLevel() {
        for (ToolAutonomy autonomy : ToolAutonomy.values()) {
            assertEquals(autonomy, BodyAutonomy.from(autonomy).asToolAutonomy(),
                    "the two scales must rank the same knob: " + autonomy);
        }
    }

    @Test
    void permitsIsMonotonic() {
        assertTrue(BodyAutonomy.PRIVILEGED.permits(BodyAutonomy.PRIVILEGED));
        assertTrue(BodyAutonomy.PRIVILEGED.permits(BodyAutonomy.SURVIVAL));
        assertTrue(BodyAutonomy.PRIVILEGED.permits(BodyAutonomy.READ));
        assertTrue(BodyAutonomy.SURVIVAL.permits(BodyAutonomy.READ));
        assertTrue(BodyAutonomy.SURVIVAL.permits(BodyAutonomy.SURVIVAL));
        assertFalse(BodyAutonomy.SURVIVAL.permits(BodyAutonomy.PRIVILEGED));
        assertFalse(BodyAutonomy.READ.permits(BodyAutonomy.SURVIVAL));
        assertFalse(BodyAutonomy.READ.permits(BodyAutonomy.PRIVILEGED));
    }

    @Test
    void aBodyWriteIsJudgedAsSurvivalWorkNotAsARead() {
        FakeTool bodyWrite = new FakeTool(ToolCategory.BODY_WRITE);

        assertEquals(ToolAutonomy.READ_ONLY, bodyWrite.requiredAutonomy(),
                "the player scale says nothing about a carrier acting as itself");
        assertEquals(BodyAutonomy.SURVIVAL, bodyWrite.requiredBodyAutonomy());
        assertEquals(ToolAutonomy.CONSERVATIVE, bodyWrite.requiredLevel(),
                "a body write must need survival level, so READ_ONLY refuses it");
        assertFalse(ToolAutonomy.READ_ONLY.permits(bodyWrite.requiredLevel()));
    }

    @Test
    void aReadStaysAReadOnBothScales() {
        FakeTool read = new FakeTool(ToolCategory.SERVER_READ);

        assertEquals(BodyAutonomy.READ, read.requiredBodyAutonomy());
        assertEquals(ToolAutonomy.READ_ONLY, read.requiredLevel());
        assertTrue(ToolAutonomy.READ_ONLY.permits(read.requiredLevel()));
    }

    @Test
    void aServerWriteIsStillSurvivalWorkForThePlayerScale() {
        FakeTool write = new FakeTool(ToolCategory.SERVER_WRITE);

        assertEquals(ToolAutonomy.CONSERVATIVE, write.requiredLevel());
        assertTrue(ToolAutonomy.CONSERVATIVE.permits(write.requiredLevel()));
        assertFalse(ToolAutonomy.READ_ONLY.permits(write.requiredLevel()));
    }

    @Test
    void theStricterOfTwoDeclarationsDecides() {
        FakeTool privilegedBodyRead = new FakeTool(ToolCategory.BODY_WRITE) {
            @Override
            public BodyAutonomy requiredBodyAutonomy() {
                return BodyAutonomy.PRIVILEGED;
            }
        };

        assertEquals(ToolAutonomy.FULL, privilegedBodyRead.requiredLevel());
        assertFalse(ToolAutonomy.CONSERVATIVE.permits(privilegedBodyRead.requiredLevel()));
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