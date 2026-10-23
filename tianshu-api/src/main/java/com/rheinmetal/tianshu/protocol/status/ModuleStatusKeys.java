package com.rheinmetal.tianshu.protocol.status;

import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The language keys of the module lifecycle dimension, derived from the module id instead of spelled
 * out at each call site.
 *
 * <p>A hand-written key is invisible to the compiler and to every other check: a module can publish
 * "starting" with a key that does not exist in either language file, and nothing fails — the row just
 * renders empty or as a raw key, and only a player notices. Deriving the key from
 * {@link ProtocolModuleIds} and checking the result against both language files
 * ({@code ModuleStatusLanguageTest}) turns a missing key into a failing build.</p>
 *
 * <p>Only the lifecycle dimension is derived here. Operation statuses carry payload-specific keys
 * ({@code reload_failed}, {@code missing_api_key}, …) whose set is defined by what each module can
 * report, so they stay hand-written; the same test sweeps the sources for those literals.</p>
 */
public final class ModuleStatusKeys {

    /** The key family every presence row lives in. */
    private static final String PREFIX = "tianshu.presence.";

    private static final String STARTING = ".starting";
    private static final String READY = ".ready";
    private static final String STOPPED = ".stopped";

    /** Every module whose lifecycle this build publishes, in the order the rows are listed. */
    private static final List<String> LIFECYCLE_MODULES = List.of(
            ProtocolModuleIds.AX,
            ProtocolModuleIds.IA,
            ProtocolModuleIds.PRESENCE,
            ProtocolModuleIds.LLM,
            ProtocolModuleIds.ASR,
            ProtocolModuleIds.TTS
    );

    private ModuleStatusKeys() {
    }

    public static String starting(String moduleId) {
        return lifecycleKey(moduleId, STARTING);
    }

    public static String ready(String moduleId) {
        return lifecycleKey(moduleId, READY);
    }

    public static String stopped(String moduleId) {
        return lifecycleKey(moduleId, STOPPED);
    }

    /**
     * Every lifecycle key this build can emit.
     *
     * <p>The language test walks this set, so adding a module to {@link #LIFECYCLE_MODULES} without its
     * two language entries fails immediately instead of at runtime.</p>
     */
    public static Set<String> lifecycleKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (String moduleId : LIFECYCLE_MODULES) {
            keys.add(starting(moduleId));
            keys.add(ready(moduleId));
            keys.add(stopped(moduleId));
        }
        return keys;
    }

    /** {@code module.llm} → {@code tianshu.presence.module.llm.ready}: no id rewriting, the id is the path. */
    private static String lifecycleKey(String moduleId, String phase) {
        return PREFIX + moduleId + phase;
    }
}
