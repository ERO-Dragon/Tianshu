package com.rheinmetal.tianshu.dubhe.module.memory.preference;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.dubhe.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.storage.DubheStorageLayout;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubhePlayerPreferenceStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void preferencesLiveUnderThePlayerDirectoryAndAreNotDeduplicated() {
        DubheStorageLayout layout = layout();
        DubhePlayerPreferenceStore store = new DubhePlayerPreferenceStore(layout, new DubheJsonStore(new FakeGameEnvironment()));
        DubhePlayerPreference first = preference("player-a", "玩家不喜欢下矿", List.of("t1"), 1_000L);
        DubhePlayerPreference second = preference("player-a", "玩家不喜欢下矿", List.of("t1"), 1_000L);

        assertTrue(store.appendAll("player-a", List.of(first, second)).success());

        assertTrue(Files.isRegularFile(layout.playerPreferencesFile("player-a")));
        assertEquals(
                layout.playersRoot().resolve("player-a").resolve("preferences.jsonl"),
                layout.playerPreferencesFile("player-a"),
                "偏好路径键里不能出现 worldId"
        );
        assertEquals(2, store.loadAll("player-a").size(), "连完全相同的两条都保留：写入侧不去重");
        assertEquals("玩家不喜欢下矿", store.loadAll("player-a").get(0).fact());
    }

    @Test
    void differentPlayersAreIsolated() {
        DubheStorageLayout layout = layout();
        DubhePlayerPreferenceStore store = new DubhePlayerPreferenceStore(layout, new DubheJsonStore(new FakeGameEnvironment()));

        assertTrue(store.appendAll("player-a", List.of(preference("player-a", "玩家喜欢钓鱼", List.of("t1"), 1_000L))).success());
        assertTrue(store.appendAll("player-b", List.of(preference("player-b", "玩家讨厌下雨", List.of("t1"), 1_000L))).success());

        assertEquals(1, store.loadAll("player-a").size());
        assertEquals(1, store.loadAll("player-b").size());
        assertEquals("玩家喜欢钓鱼", store.loadAll("player-a").get(0).fact());
        assertEquals("玩家讨厌下雨", store.loadAll("player-b").get(0).fact());
    }

    @Test
    void preferenceSurvivesAWorldChange() {
        DubheStorageLayout layout = layout();
        DubhePlayerPreferenceStore store = new DubhePlayerPreferenceStore(layout, new DubheJsonStore(new FakeGameEnvironment()));

        // 存储键只有 playerId：换存档后再读，同一玩家的偏好仍在。
        assertTrue(store.appendAll("player-a", List.of(preference("player-a", "玩家喜欢在村庄定居", List.of("t7"), 1_000L))).success());

        DubhePlayerPreferenceStore reloaded = new DubhePlayerPreferenceStore(layout, new DubheJsonStore(new FakeGameEnvironment()));
        assertEquals(1, reloaded.loadAll("player-a").size());
    }

    private DubheStorageLayout layout() {
        return new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
    }

    private static DubhePlayerPreference preference(String playerId, String fact, List<String> turnRefs, long createdAtMillis) {
        return new DubhePlayerPreference("", playerId, fact, 0.8D, turnRefs, "raw_batch_test", createdAtMillis);
    }
}
