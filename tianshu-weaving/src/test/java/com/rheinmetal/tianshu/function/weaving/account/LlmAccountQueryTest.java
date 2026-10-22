package com.rheinmetal.tianshu.function.weaving.account;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LlmAccountQueryTest {
    private static final String PROVIDER = "https://api.deepseek.com/v1";

    @Test
    void anUnwiredQueryReportsUnwiredAndNeverDispatches() {
        LlmAccountQuery query = new LlmAccountQuery();
        LlmAccountKey key = LlmAccountKey.of(PROVIDER, "sk-test");

        assertFalse(query.wired());
        assertEquals(LlmAccountQueryState.UNWIRED, query.snapshot(key).state());

        query.refresh(key);

        assertEquals(LlmAccountQueryState.UNWIRED, query.snapshot(key).state());
        assertEquals(LlmAccountSnapshot.UNKNOWN_AMOUNT, query.snapshot(key).balanceMicros());
    }

    @Test
    void aBoundProbeFillsTheSnapshotWithProviderAmounts() {
        LlmAccountQuery query = new LlmAccountQuery((key, sink) ->
                sink.accept(LlmAccountSnapshot.available(key, 1_000_000L, 5_000_000L, 12_000_000L, "CNY", 42L)),
                0L);
        LlmAccountKey key = LlmAccountKey.of(PROVIDER, "sk-test");

        query.refresh(key);
        LlmAccountSnapshot snapshot = query.snapshot(key);

        assertEquals(LlmAccountQueryState.AVAILABLE, snapshot.state());
        assertEquals(1_000_000L, snapshot.todayCostMicros());
        assertEquals(5_000_000L, snapshot.totalCostMicros());
        assertEquals(12_000_000L, snapshot.balanceMicros());
        assertEquals("CNY", snapshot.currencyCode());
        assertTrue(LlmAccountSnapshot.amountKnown(snapshot.balanceMicros()));
    }

    @Test
    void theSnapshotReadsAsQueryingWhileTheRequestIsInFlight() {
        AtomicReference<LlmAccountQuery> holder = new AtomicReference<>();
        AtomicReference<LlmAccountQueryState> stateAtDispatch = new AtomicReference<>();
        LlmAccountQuery query = new LlmAccountQuery((key, sink) -> {
            stateAtDispatch.set(holder.get().snapshot(key).state());
            sink.accept(LlmAccountSnapshot.querying(key));
        }, 0L);
        holder.set(query);

        query.refresh(LlmAccountKey.of(PROVIDER, "sk-test"));

        assertEquals(LlmAccountQueryState.QUERYING, stateAtDispatch.get());
    }

    @Test
    void aFailingProbeLeavesAFailureInsteadOfZeroAmounts() {
        LlmAccountQuery query = new LlmAccountQuery((key, sink) -> {
            throw new IllegalStateException("network down");
        }, 0L);
        LlmAccountKey key = LlmAccountKey.of(PROVIDER, "sk-test");

        query.refresh(key);
        LlmAccountSnapshot snapshot = query.snapshot(key);

        assertEquals(LlmAccountQueryState.FAILED, snapshot.state());
        assertEquals(LlmAccountSnapshot.UNKNOWN_AMOUNT, snapshot.balanceMicros());
        assertEquals(LlmAccountSnapshot.UNKNOWN_AMOUNT, snapshot.todayCostMicros());
        assertTrue(snapshot.fetchedAtMillis() > 0L);
        assertFalse(LlmAccountSnapshot.amountKnown(snapshot.balanceMicros()));
        assertFalse(LlmAccountSnapshot.amountKnown(snapshot.totalCostMicros()));
    }

    @Test
    void aReportedFailureWithoutAmountsStaysFailed() {
        LlmAccountQuery query = new LlmAccountQuery((key, sink) ->
                sink.accept(LlmAccountSnapshot.failed(key, "tianshu.llm.account.unavailable", 7L)), 0L);
        LlmAccountKey key = LlmAccountKey.of(PROVIDER, "sk-test");

        query.refresh(key);

        assertEquals(LlmAccountQueryState.FAILED, query.snapshot(key).state());
        assertEquals("tianshu.llm.account.unavailable", query.snapshot(key).failureKey());
    }

    @Test
    void twoKeysOfOneAccountKeepSeparateAnswers() {
        LlmAccountKey first = LlmAccountKey.of(PROVIDER, "sk-first");
        LlmAccountKey second = LlmAccountKey.of(PROVIDER, "sk-second");
        LlmAccountQuery query = new LlmAccountQuery((key, sink) -> sink.accept(
                LlmAccountSnapshot.available(
                        key,
                        LlmAccountSnapshot.UNKNOWN_AMOUNT,
                        LlmAccountSnapshot.UNKNOWN_AMOUNT,
                        key.equals(second) ? 7L : 3L,
                        "CNY",
                        1L)), 0L);

        query.refresh(first);
        query.refresh(second);

        assertNotEquals(first, second);
        assertEquals(3L, query.snapshot(first).balanceMicros());
        assertEquals(7L, query.snapshot(second).balanceMicros());
        assertEquals(LlmAccountSnapshot.UNKNOWN_AMOUNT, query.snapshot(first).todayCostMicros());
        assertEquals(LlmAccountQueryState.UNWIRED, query.snapshot(LlmAccountKey.of(PROVIDER, "")).state());
    }

    @Test
    void theMinimumIntervalSuppressesRepeatedDispatch() {
        AtomicInteger dispatches = new AtomicInteger();
        LlmAccountQuery query = new LlmAccountQuery((key, sink) -> {
            dispatches.incrementAndGet();
            sink.accept(LlmAccountSnapshot.available(key, 0L, 0L, 0L, "CNY", 1L));
        }, 60_000L);
        LlmAccountKey key = LlmAccountKey.of(PROVIDER, "sk-test");

        query.refresh(key);
        query.refresh(key);

        assertEquals(1, dispatches.get());
    }

    @Test
    void anUnconfiguredKeyIsNeverQueried() {
        AtomicInteger dispatches = new AtomicInteger();
        LlmAccountQuery query = new LlmAccountQuery((key, sink) -> {
            dispatches.incrementAndGet();
            sink.accept(LlmAccountSnapshot.unwired(key));
        }, 0L);

        query.refresh(LlmAccountKey.unspecified());

        assertEquals(0, dispatches.get());
        assertEquals(LlmAccountQueryState.UNWIRED, query.snapshot(LlmAccountKey.unspecified()).state());
    }

    @Test
    void theKeyKeepsTheCredentialOutOfTheSnapshot() {
        LlmAccountKey key = LlmAccountKey.of(PROVIDER, "sk-secret-value");

        assertTrue(key.configured());
        assertEquals(PROVIDER, key.providerId());
        assertNotEquals("sk-secret-value", key.apiKeyId());
        assertEquals(key, LlmAccountKey.of(PROVIDER, "sk-secret-value"));
        assertNotEquals(key, LlmAccountKey.of(PROVIDER, "sk-other-value"));
        assertFalse(LlmAccountKey.of("", "").configured());
    }
}
