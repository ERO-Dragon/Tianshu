package com.rheinmetal.tianshu.function.weaving.blackbox;

import com.rheinmetal.tianshu.function.weaving.contract.LLMCacheManagePayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMCacheManageResultPayload;

import java.nio.file.Path;

/**
 * A separate JVM's entry point for the process-level restart test.
 *
 * <p>The parent test starts this class in a child process; the child boots the real module on the
 * given home, then writes one cache entry ({@code write}) or verifies it is still readable
 * ({@code read}). Exit codes are the assertion: 0 means the phase passed.</p>
 */
public final class BlackBoxCacheProbe {

    private BlackBoxCacheProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.exit(64);
        }
        Path home = Path.of(args[0]);
        String mode = args[1];
        BlackBoxConfig config = new BlackBoxConfig(home, "http://127.0.0.1:1/v1");

        try (BlackBoxDriver driver = BlackBoxDriver.start(config)) {
            if ("write".equals(mode)) {
                LLMCacheManageResultPayload registered = driver.cache(
                        LLMCacheManagePayload.registerLibrary("kb", "modid", "SHARED", java.util.List.of()));
                if (!registered.success()) {
                    System.exit(2);
                }
                LLMCacheManageResultPayload upserted = driver.cache(
                        LLMCacheManagePayload.upsertEntry("kb", "entry-1", "process persisted text",
                                new float[]{1f, 0f, 0f}));
                if (!upserted.success()) {
                    System.exit(3);
                }
                return;
            }
            if ("read".equals(mode)) {
                if (!driver.cache(LLMCacheManagePayload.queryUid("kb")).exists()) {
                    System.exit(4);
                }
                var searched = driver.cache(LLMCacheManagePayload.searchUid("kb", "process", 4, 0.01f));
                if (searched.hits().isEmpty()) {
                    System.exit(5);
                }
                return;
            }
            System.exit(64);
        }
    }
}