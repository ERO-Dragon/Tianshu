package com.rheinmetal.tianshu.dubhe.external;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheCallerQuotaTest {

    private final DubheCallerQuota quota = new DubheCallerQuota();

    @Test
    void oneRunsAndTheRestWaitInOrder() {
        List<CompletableFuture<JsonObject>> work = new ArrayList<>();
        AtomicInteger started = new AtomicInteger();
        List<CompletableFuture<JsonObject>> answers = new ArrayList<>();

        for (int index = 0; index < 3; index++) {
            answers.add(quota.submit("caller|cap", () -> {
                started.incrementAndGet();
                CompletableFuture<JsonObject> pending = new CompletableFuture<>();
                work.add(pending);
                return pending;
            }));
        }

        assertEquals(1, started.get(), "concurrency is one");
        assertEquals(2, quota.load("caller|cap")[1], "two are queued");

        work.get(0).complete(ok());
        assertEquals(2, started.get(), "the second starts when the first completes");
        work.get(1).complete(ok());
        assertEquals(3, started.get(), "the third starts when the second completes");
        work.get(2).complete(ok());

        assertTrue(answers.stream().allMatch(CompletableFuture::isDone));
        assertEquals(0, quota.load("caller|cap")[0]);
    }

    @Test
    void theSixthCallIsRejectedWithAStructuredQuotaError() {
        CompletableFuture<JsonObject> running = new CompletableFuture<>();
        quota.submit("caller|cap", () -> running);
        for (int index = 0; index < DubheCallerQuota.MAX_QUEUED; index++) {
            quota.submit("caller|cap", CompletableFuture::new);
        }

        JsonObject rejected = quota.submit("caller|cap", CompletableFuture::new).join();

        assertFalse(rejected.get("ok").getAsBoolean());
        assertEquals(DubheCallerQuota.CODE_QUOTA_EXCEEDED, rejected.get("code").getAsString());
        running.complete(ok());
    }

    @Test
    void differentCallersAndCapabilitiesDoNotShareASlot() {
        CompletableFuture<JsonObject> first = new CompletableFuture<>();
        CompletableFuture<JsonObject> second = new CompletableFuture<>();
        quota.submit("callerA|research", () -> first);
        quota.submit("callerB|research", () -> second);

        assertEquals(1, quota.load("callerA|research")[0]);
        assertEquals(1, quota.load("callerB|research")[0], "another caller has its own slot");
        first.complete(ok());
        second.complete(ok());
    }

    @Test
    void aFailedRunStillFreesTheSlot() {
        CompletableFuture<JsonObject> failing = new CompletableFuture<>();
        quota.submit("caller|cap", () -> failing);
        failing.completeExceptionally(new IllegalStateException("boom"));

        assertEquals(0, quota.load("caller|cap")[0]);
        JsonObject next = quota.submit("caller|cap", () -> CompletableFuture.completedFuture(ok())).join();
        assertTrue(next.get("ok").getAsBoolean());
    }

    private static JsonObject ok() {
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", true);
        return answer;
    }
}