package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.testkit.LangShards;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Presence owns its lifecycle language rows.
 *
 * <p>The kernel derives a lifecycle key from the module id but no longer enumerates presence's id, so presence checks
 * its own three keys: a missing row would render as an empty/raw key in every status view and nothing else would fail.
 * The shard is also part of the global sweep in the kernel's language test, which is why this one only needs to pin
 * the keys the kernel cannot know about.</p>
 */
final class PresenceLanguageTest {

    private static final List<String> LIFECYCLE_KEYS = List.of(
            "tianshu.runtime.module.presence.starting",
            "tianshu.runtime.module.presence.ready",
            "tianshu.runtime.module.presence.stopped"
    );

    @Test
    void everyLifecycleRowHasTextInBothLanguages() {
        Set<String> zh = LangShards.keys(LangShards.ZH_CN);
        Set<String> en = LangShards.keys(LangShards.EN_US);

        for (String key : LIFECYCLE_KEYS) {
            assertTrue(zh.contains(key), "missing in zh_cn: " + key);
            assertTrue(en.contains(key), "missing in en_us: " + key);
        }
    }
}
