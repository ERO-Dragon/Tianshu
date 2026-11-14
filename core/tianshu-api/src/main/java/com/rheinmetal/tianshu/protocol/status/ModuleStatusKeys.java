package com.rheinmetal.tianshu.protocol.status;

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

    /** The neutral key family every module's lifecycle row lives in. */
    private static final String PREFIX = "tianshu.runtime.";

    private static final String STARTING = ".starting";
    private static final String READY = ".ready";
    private static final String STOPPED = ".stopped";

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

    /** {@code module.llm} → {@code tianshu.runtime.module.llm.ready}: no id rewriting, the id is the path. */
    private static String lifecycleKey(String moduleId, String phase) {
        return PREFIX + moduleId + phase;
    }
}
