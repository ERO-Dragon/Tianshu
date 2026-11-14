package com.rheinmetal.tianshu.function.auxilium.failure;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXRetryLedgerTest {
    @TempDir
    Path tempDir;

    /** 崩溃之后玩家还能看见没办成的那件事：账本是盘上的，不是内存里的。 */
    @Test
    void aRecordedFailureSurvivesTheProcess() {
        AXStorageLayout layout = layout();
        AXScope scope = scope();
        ledger(layout).record(scope, entry(scope, AXFailureCause.RATE_LIMITED));

        AXRetryLedger restarted = ledger(layout);

        List<AXRetryEntry> entries = restarted.entries(scope);
        assertEquals(1, entries.size());
        assertEquals(AXFailureCause.RATE_LIMITED, entries.get(0).cause());
        assertTrue(entries.get(0).retryable());
    }

    @Test
    void theSameWorldKeepsOneRecord() {
        AXStorageLayout layout = layout();
        AXScope scope = scope();
        AXRetryLedger ledger = ledger(layout);

        ledger.record(scope, entry(scope, AXFailureCause.TIMEOUT));
        ledger.record(scope, entry(scope, AXFailureCause.PROVIDER_ERROR));

        List<AXRetryEntry> entries = ledger.entries(scope);
        assertEquals(1, entries.size(), "一个世界同时只有一件没办成的维护");
        assertEquals(AXFailureCause.PROVIDER_ERROR, entries.get(0).cause(), "留下的是最近一次的结论");
    }

    @Test
    void claimingCountsAnAttemptAndKeepsTheRecord() {
        AXStorageLayout layout = layout();
        AXScope scope = scope();
        AXRetryLedger ledger = ledger(layout);
        ledger.record(scope, entry(scope, AXFailureCause.NETWORK));

        AXRetryEntry claimed = ledger.claim(scope).orElseThrow();

        assertEquals(1, claimed.attempts());
        assertEquals(1, ledger.entries(scope).size(), "账本不猜结果：撤账要等成功路径来叫");
        assertEquals(1, ledger.entries(scope).get(0).attempts());
    }

    @Test
    void aFailedRetryKeepsTheCumulativeCount() {
        AXStorageLayout layout = layout();
        AXScope scope = scope();
        AXRetryLedger ledger = ledger(layout);
        ledger.record(scope, entry(scope, AXFailureCause.TIMEOUT));
        ledger.claim(scope);

        // 重试又失败了：维护会记一条新的，但"试过几次"要继续累加，而不是从零开始。
        ledger.record(scope, entry(scope, AXFailureCause.TIMEOUT));

        assertEquals(1, ledger.entries(scope).get(0).attempts());
    }

    @Test
    void resolvingClearsThePendingWork() {
        AXStorageLayout layout = layout();
        AXScope scope = scope();
        AXRetryLedger ledger = ledger(layout);
        ledger.record(scope, entry(scope, AXFailureCause.TIMEOUT));

        assertTrue(ledger.resolve(scope));

        assertTrue(ledger.entries(scope).isEmpty());
        assertTrue(ledger(layout).entries(scope).isEmpty(), "撤账也要落到盘上");
        assertFalse(ledger.resolve(scope), "撤过的账不该再撤一次");
    }

    @Test
    void dismissingClearsThePendingWork() {
        AXStorageLayout layout = layout();
        AXScope scope = scope();
        AXRetryLedger ledger = ledger(layout);
        ledger.record(scope, entry(scope, AXFailureCause.TIMEOUT));

        assertTrue(ledger.dismiss(scope));

        assertTrue(ledger.entries(scope).isEmpty());
    }

    @Test
    void aFailureThePlayerCannotRetryIsStillRecorded() {
        AXStorageLayout layout = layout();
        AXScope scope = scope();
        AXRetryLedger ledger = ledger(layout);
        ledger.record(scope, entry(scope, AXFailureCause.PARAMETER));

        AXRetryEntry recorded = ledger.entries(scope).get(0);
        assertEquals(AXFailureCause.PARAMETER, recorded.cause());
        assertFalse(recorded.retryable(), "参数错误要改设置，点重试没用");
    }

    @Test
    void aWorldWithoutFailuresHasAnEmptyBook() {
        AXRetryLedger ledger = ledger(layout());

        assertTrue(ledger.entries(scope()).isEmpty());
        assertTrue(ledger.claim(scope()).isEmpty());
    }

    private AXRetryEntry entry(AXScope scope, AXFailureCause cause) {
        return AXRetryEntry.memoryMaintenance(scope, cause, cause.code(), 1_000L);
    }

    private AXRetryLedger ledger(AXStorageLayout layout) {
        return new AXRetryLedger(new AXRetryLedgerStore(layout, new AXJsonStore(new FakeGameEnvironment())));
    }

    private AXStorageLayout layout() {
        return new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
    }

    private AXScope scope() {
        return new AXScope("player", "save:Retry World", "Retry World", AXScopeKind.LOCAL_WORLD, true);
    }
}