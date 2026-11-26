package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.LocateResult;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;

/** Walks a circular, chunk-spaced grid by horizontal distance. Disk reads may finish out of order. */
final class LocateGridSearch implements WorldLocateSource.Search {
    enum Match { HIT, MISS, ABSENT }

    @FunctionalInterface
    interface Probe {
        CompletableFuture<Match> at(int x, int z);
    }

    private record Point(int dx, int dz, int step) {
        int squared() { return dx * dx + dz * dz; }
        Point next() { return new Point(dx + step, dz, step); }
    }
    private record Pending(Point point, CompletableFuture<Match> result) {}

    private static final int STEP = 16;
    private static final int READ_AHEAD = 16;
    private final PriorityQueue<Point> rows = new PriorityQueue<>(Comparator.comparingInt(Point::squared)
            .thenComparingInt(Point::dz).thenComparingInt(Point::dx));
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final int x;
    private final int y;
    private final int z;
    private final int radius;
    private final String id;
    private final String kind;
    private final Probe probe;
    private int sampled;

    LocateGridSearch(int x, int y, int z, int gridX, int gridZ, int radius, String kind, String id, Probe probe) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
        this.kind = kind;
        this.id = id;
        this.probe = probe;
        // Each half-row is already sorted. Merging them needs only O(radius / STEP) cursors.
        int firstX = Math.floorMod(gridX - x, STEP);
        int firstZ = -radius + Math.floorMod(gridZ - z + radius, STEP);
        for (int dz = firstZ; dz <= radius; dz += STEP) {
            enqueue(new Point(firstX, dz, STEP));
            enqueue(new Point(firstX - STEP, dz, -STEP));
        }
    }

    private void enqueue(Point point) {
        if (point.squared() <= radius * radius) {
            rows.add(point);
        }
    }

    @Override
    public LocateResult advance(int budget) {
        while (true) {
            while (!pending.isEmpty() && pending.getFirst().result.isDone()) {
                Pending first = pending.removeFirst();
                Match match = first.result.join(); // isDone also includes failures, which must reach the caller.
                if (match != Match.ABSENT && kind.equals("biome")) {
                    sampled++;
                }
                if (match == Match.HIT) {
                    Point at = first.point;
                    return new LocateResult(kind, id, true, x + at.dx, y, z + at.dz,
                            Math.sqrt(at.squared()), sampled, "found");
                }
            }
            if (rows.isEmpty() && pending.isEmpty()) {
                return LocateResult.notFound(kind, id, sampled, "No match in the search scope within " + radius + " blocks");
            }
            if (budget-- == 0 || rows.isEmpty() || pending.size() == READ_AHEAD) {
                return null;
            }
            Point point = rows.remove();
            pending.addLast(new Pending(point, probe.at(x + point.dx, z + point.dz)));
            enqueue(point.next());
        }
    }
}
