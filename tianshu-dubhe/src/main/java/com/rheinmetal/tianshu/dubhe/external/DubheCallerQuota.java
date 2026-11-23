package com.rheinmetal.tianshu.dubhe.external;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 下游能力的配额（G23）：每个调用方、每个能力**并发 1 + 排队 4**。
 *
 * <p>防的是第三方代码里的循环调用，不是玩家的追问——玩家侧入口已经由 IA 仲裁与按键控制。因此超限不是
 * 排队到天荒地老，而是立刻回一个结构化拒绝：{@code ok=false} + {@code code=QUOTA_EXCEEDED}。</p>
 *
 * <p>键是"调用方 + 能力"：同一个第三方模组调用 RESEARCH 与 RELAY 互不占额度；不同调用方之间也互不影响。</p>
 *
 * <p>队列只保存等待者，不保存工作本身之外的状态；等待者在出队时才真正开始，并且如果它在等待期间已经
 * 被外层（能力超时）取消，出队时会跳过它。</p>
 */
public final class DubheCallerQuota {

    public static final int MAX_CONCURRENT = 1;
    public static final int MAX_QUEUED = 4;
    public static final String CODE_QUOTA_EXCEEDED = "QUOTA_EXCEEDED";

    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

    /**
     * Runs {@code work} under this key's quota, or completes immediately with a quota error.
     */
    public CompletableFuture<JsonObject> submit(String key, Supplier<CompletableFuture<JsonObject>> work) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(work, "work");
        Slot slot = slots.computeIfAbsent(key, ignored -> new Slot());
        Runnable start;
        synchronized (slot) {
            if (slot.active == 0) {
                slot.active = 1;
                start = null; // run below, outside the lock
            } else if (slot.waiting.size() < MAX_QUEUED) {
                CompletableFuture<JsonObject> queued = new CompletableFuture<>();
                slot.waiting.add(new Waiter(queued, work));
                return queued;
            } else {
                return CompletableFuture.completedFuture(rejected(key));
            }
        }
        return begin(slot, work);
    }

    /** Diagnostics only: active + waiting for one key. */
    public int[] load(String key) {
        Slot slot = slots.get(key);
        if (slot == null) {
            return new int[]{0, 0};
        }
        synchronized (slot) {
            return new int[]{slot.active, slot.waiting.size()};
        }
    }

    private CompletableFuture<JsonObject> begin(Slot slot, Supplier<CompletableFuture<JsonObject>> work) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        CompletableFuture<JsonObject> running;
        try {
            running = work.get();
        } catch (RuntimeException failure) {
            running = CompletableFuture.failedFuture(failure);
        }
        running.whenComplete((value, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(value);
            }
            release(slot);
        });
        return result;
    }

    private void release(Slot slot) {
        Waiter next;
        synchronized (slot) {
            next = slot.waiting.poll();
            if (next == null) {
                slot.active = 0;
                return;
            }
        }
        final Waiter picked = next;
        if (picked.queue.isDone()) {
            // The waiter timed out while queued; give the slot to the next one instead of wasting it.
            release(slot);
            return;
        }
        CompletableFuture<JsonObject> started = begin(slot, picked.work);
        started.whenComplete((value, error) -> {
            if (error != null) {
                picked.queue.completeExceptionally(error);
            } else {
                picked.queue.complete(value);
            }
        });
    }

    private static JsonObject rejected(String key) {
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", false);
        answer.addProperty("code", CODE_QUOTA_EXCEEDED);
        answer.addProperty("message", "quota full for " + key + ": " + MAX_CONCURRENT + " running, "
                + MAX_QUEUED + " queued");
        return answer;
    }

    private static final class Slot {
        private int active;
        private final Deque<Waiter> waiting = new ArrayDeque<>();
    }

    private record Waiter(CompletableFuture<JsonObject> queue, Supplier<CompletableFuture<JsonObject>> work) {
    }
}
