package com.rheinmetal.tianshu.function.presence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The query path must shed load instead of growing a queue.
 *
 * <p>Presence answers queries on the client tick, so an unbounded backlog would turn a burst of requests into a
 * memory leak and into longer and longer answers. The bound and the busy reply are the whole policy, and they are
 * cheap to keep honest here.</p>
 */
final class PresenceBackpressureTest {

    @Test
    void contextQueriesUseABoundedQueueAndReportBusyInsteadOfGrowing() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/function/presence/context/PresenceContextQueryCoordinator.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("new ArrayBlockingQueue<>(MAX_PENDING_QUERIES)"),
                "the pending queue must have a fixed capacity");
        assertTrue(source.contains("\"PRESENCE_BUSY\""),
                "a full queue must answer BUSY instead of waiting");
    }
}
