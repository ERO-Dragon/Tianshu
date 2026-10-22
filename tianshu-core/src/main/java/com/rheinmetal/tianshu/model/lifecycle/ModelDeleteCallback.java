package com.rheinmetal.tianshu.model.lifecycle;

/**
 * Result of one model deletion.
 *
 * <p>Shared by every model front and by the settings pages that request deletion, so the callback
 * type no longer has to be re-declared per module.</p>
 */
public interface ModelDeleteCallback {

    void onComplete(boolean deleted);
}
