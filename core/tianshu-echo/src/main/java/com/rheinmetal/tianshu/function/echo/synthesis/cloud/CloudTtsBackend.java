package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import com.rheinmetal.tianshu.function.echo.synthesis.TtsBackend;

/**
 * A backend that speaks through a cloud provider instead of a model directory.
 *
 * <p>{@code initialize} lives here rather than on {@link TtsBackend} for the same reason it lives on the local
 * variant: the argument is what the backend needs, and a backend with no model files must not be asked to
 * implement a signature it cannot honour.</p>
 */
public interface CloudTtsBackend extends TtsBackend {

    boolean initialize(TtsCloudProfile profile);
}