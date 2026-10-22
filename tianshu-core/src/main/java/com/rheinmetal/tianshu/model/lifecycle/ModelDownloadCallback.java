package com.rheinmetal.tianshu.model.lifecycle;

import com.rheinmetal.tianshu.model.ModelDownloadProgress;

/**
 * Outcome callbacks for one model download.
 *
 * <p>Every model front and every transport-level downloader reports the same four events. Keeping one
 * contract means a settings page, a download coordinator and a downloader all speak the same type
 * instead of each declaring an identical nested interface.</p>
 *
 * <p>{@link #onCancelled()} is a default no-op: a transport that only knows how to report progress,
 * completion and failure does not have to implement it, while callers that care about
 * cancellation can still rely on being told.</p>
 */
public interface ModelDownloadCallback {

    void onProgress(ModelDownloadProgress progress);

    void onComplete();

    void onError(String message);

    default void onCancelled() {
    }
}
