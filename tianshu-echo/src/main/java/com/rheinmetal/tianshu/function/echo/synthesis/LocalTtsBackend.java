package com.rheinmetal.tianshu.function.echo.synthesis;

/**
 * A backend that speaks from a model directory on disk.
 *
 * <p>{@code initialize} lives here rather than on {@link TtsBackend} because it takes a model directory, and
 * a backend that has no model to load must not be forced to implement a signature it cannot honour. The
 * engine provider is the only caller, and it builds the candidate and its initializer in the same branch, so
 * the two always agree on which kind of backend was created.</p>
 */
public interface LocalTtsBackend extends TtsBackend {

    boolean initialize(TtsResolvedModel model);
}