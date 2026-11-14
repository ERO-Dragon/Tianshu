package com.rheinmetal.tianshu.function.echo.synthesis;

/**
 * What the synthesis lane is currently asked to speak with.
 *
 * <p>A backend is not always a local model: a cloud provider answers with audio without any model files on
 * disk. Naming that choice as its own value is what keeps the model directory out of the backend contract —
 * every caller that only cares about "what is running now" compares selections instead of paths.</p>
 *
 * <p>The type is sealed on purpose: the engine provider dispatches over its variants to build a candidate and
 * its initializer, and a new kind of backend must make that dispatch fail to compile rather than fall through
 * to a default branch.</p>
 */
public sealed interface TtsBackendSelection permits LocalTtsBackendSelection, CloudTtsBackendSelection {

    /** Which backend implementation serves this selection. */
    TtsBackendType backendType();

    /**
     * A player-facing identity for the "running" row.
     *
     * <p>It is a fact about the selection, not a translated phrase: the settings page decides how to show it,
     * and a cloud provider's label comes from the provider catalog rather than from here.</p>
     */
    String label();
}