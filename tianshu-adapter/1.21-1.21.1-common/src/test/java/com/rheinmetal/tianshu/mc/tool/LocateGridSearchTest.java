package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.LocateRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.rheinmetal.tianshu.mc.tool.LocateGridSearch.Match.*;

import static org.junit.jupiter.api.Assertions.*;

class LocateGridSearchTest {
    private record Point(int x, int z) {}
    private static final Point ORIGIN = new Point(-7, 21);

    @Test
    void startsAtActorAndResumesInDistanceOrderWithinTheCircle() {
        List<Point> samples = new ArrayList<>();
        var search = new LocateGridSearch(ORIGIN.x(), 62, ORIGIN.z(), ORIGIN.x(), ORIGIN.z(), 17, "biome", "test:missing", (x, z) -> {
            samples.add(new Point(x, z));
            return CompletableFuture.completedFuture(MISS);
        });
        assertNull(search.advance(2));
        assertEquals(2, samples.size());
        assertEquals(ORIGIN, samples.getFirst());
        var result = search.advance(10);
        assertFalse(result.found());
        assertEquals(5, result.sampled());
        assertEquals(5, samples.stream().distinct().count());
        assertTrue(samples.stream().skip(1).allMatch(at -> Math.hypot(at.x() - ORIGIN.x(), at.z() - ORIGIN.z()) == 16));
    }

    @Test
    void matchingOriginEndsTheSearchEvenAtMaximumRadius() {
        var search = new LocateGridSearch(0, 64, 0, 0, 0, LocateRequest.MAX_RADIUS_BLOCKS, "biome", "test:here",
                (x, z) -> CompletableFuture.completedFuture(HIT));
        var result = search.advance(1);
        assertTrue(result.found());
        assertEquals(0.0, result.distanceBlocks());
        assertEquals(1, result.sampled());
    }
    @Test
    void missingChunksAreSkippedAndLaterDiskReadsCannotOvertakeTheClosestCandidate() {
        var nearest = new CompletableFuture<LocateGridSearch.Match>();
        var search = new LocateGridSearch(-1, 64, -1, 0, 0, 17, "biome", "test:saved",
                (x, z) -> x == 0 && z == 0 ? nearest : CompletableFuture.completedFuture(HIT));
        assertNull(search.advance(16));
        nearest.complete(ABSENT);
        var result = search.advance(16);
        assertTrue(result.found());
        assertEquals(1, result.sampled());
        assertEquals(Math.sqrt(226), result.distanceBlocks());
    }
}
