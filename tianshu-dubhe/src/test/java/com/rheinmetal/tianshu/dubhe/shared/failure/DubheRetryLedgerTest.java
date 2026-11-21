package com.rheinmetal.tianshu.dubhe.shared.failure;

import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeKind;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheStorageLayout;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheRetryLedgerTest {
    @TempDir
    Path tempDir;

    /** 崩溃之后玩家还能看见没办成的那件事：账本是盘上的，不是内存里的。 */
    @Test
    void aRecordedFailureSurvivesTheProcess() {
        DubheStorageLayout layout = layout();
        DubheScope scope = scope();
        ledger(layout).record(scope, entry(scope, DubheFailureCause.RATE_LIMITED));

        DubheRetryLedger restarted = ledger(layout);

        List<DubheRetryEntry> entries = restarted.entries(scope);
        assertEquals(1, entries.size());
        assertEquals(DubheFailureCause.RATE_LIMITED, entries.get(0).cause());
        assertTrue(entries.get(0).retryable());
    }

    @Test
    void theSameWorldKeepsOneRecord() {
        DubheStorageLayout layout = layout();
        DubheScope scope = scope();
        DubheRetryLedger ledger = ledger(layout);

        ledger.record(scope, entry(scope, DubheFailureCause.TIMEOUT));
        ledger.record(scope, entry(scope, DubheFailureCause.PROVIDER_ERROR));

        List<DubheRetryEntry> entries = ledger.entries(scope);
        assertEquals(1, entries.size(), "一个世界同时只有一件没办成的维护");
        assertEquals(DubheFailureCause.PROVIDER_ERROR, entries.get(0).cause(), "留下的是最近一次的结论");
    }

    @Test
    void claimingCountsAnAttemptAndKeepsTheRecord() {
        DubheStorageLayout layout = layout();
        DubheScope scope = scope();
        DubheRetryLedger ledger = ledger(layout);
        ledger.record(scope, entry(scope, DubheFailureCause.NETWORK));

        DubheRetryEntry claimed = ledger.claim(scope).orElseThrow();

        assertEquals(1, claimed.attempts());
        assertEquals(1, ledger.entries(scope).size(), "账本不猜结果：撤账要等成功路径来叫");
        assertEquals(1, ledger.entries(scope).get(0).attempts());
    }

    @Test
    void aFailedRetryKeepsTheCumulativeCount() {
        DubheStorageLayout layout = layout();
        DubheScope scope = scope();
        DubheRetryLedger ledger = ledger(layout);
        ledger.record(scope, entry(scope, DubheFailureCause.TIMEOUT));
        ledger.claim(scope);

        // 重试又失败了：维护会记一条新的，但"试过几次"要继续累加，而不是从零开始。
        ledger.record(scope, entry(scope, DubheFailureCause.TIMEOUT));

        assertEquals(1, ledger.entries(scope).get(0).attempts());
    }

    @Test
    void resolvingClearsThePendingWork() {
        DubheStorageLayout layout = layout();
        DubheScope scope = scope();
        DubheRetryLedger ledger = ledger(layout);
        ledger.record(scope, entry(scope, DubheFailureCause.TIMEOUT));

        assertTrue(ledger.resolve(scope));

        assertTrue(ledger.entries(scope).isEmpty());
        assertTrue(ledger(layout).entries(scope).isEmpty(), "撤账也要落到盘上");
        assertFalse(ledger.resolve(scope), "撤过的账不该再撤一次");
    }

    @Test
    void dismissingClearsThePendingWork() {
        DubheStorageLayout layout = layout();
        DubheScope scope = scope();
        DubheRetryLedger ledger = ledger(layout);
        ledger.record(scope, entry(scope, DubheFailureCause.TIMEOUT));

        assertTrue(ledger.dismiss(scope));

        assertTrue(ledger.entries(scope).isEmpty());
    }

    @Test
    void aFailureThePlayerCannotRetryIsStillRecorded() {
        DubheStorageLayout layout = layout();
        DubheScope scope = scope();
        DubheRetryLedger ledger = ledger(layout);
        ledger.record(scope, entry(scope, DubheFailureCause.PARAMETER));

        DubheRetryEntry recorded = ledger.entries(scope).get(0);
        assertEquals(DubheFailureCause.PARAMETER, recorded.cause());
        assertFalse(recorded.retryable(), "参数错误要改设置，点重试没用");
    }

    @Test
    void aWorldWithoutFailuresHasAnEmptyBook() {
        DubheRetryLedger ledger = ledger(layout());

        assertTrue(ledger.entries(scope()).isEmpty());
        assertTrue(ledger.claim(scope()).isEmpty());
    }

    private DubheRetryEntry entry(DubheScope scope, DubheFailureCause cause) {
        return DubheRetryEntry.memoryMaintenance(scope, cause, cause.code(), 1_000L);
    }

    private DubheRetryLedger ledger(DubheStorageLayout layout) {
        return new DubheRetryLedger(new DubheRetryLedgerStore(layout, new DubheJsonStore(new FakeGameEnvironment())));
    }

    private DubheStorageLayout layout() {
        return new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
    }

    private DubheScope scope() {
        return new DubheScope("player", "save:Retry World", "Retry World", DubheScopeKind.LOCAL_WORLD, true);
    }
}