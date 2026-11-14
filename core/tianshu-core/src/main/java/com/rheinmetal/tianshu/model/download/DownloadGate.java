package com.rheinmetal.tianshu.model.download;

import java.io.IOException;
import java.util.Objects;

/**
 * Pause and cancellation gate for a single model download session.
 *
 * <p>Every model front runs the same waiting-transport state machine: the download thread parks
 * while the session is paused and must be released by resume, by cancel, or by thread interruption.
 * Keeping one implementation here means a concurrency fix reaches every front at once instead of
 * drifting across per-front copies.</p>
 *
 * <p>The gate owns no downloader. The session supplies a cancellation hook so each front can abort
 * its own transfers (HuggingFace files, a GitHub archive, or both) without the gate knowing which
 * transport is behind it.</p>
 */
public final class DownloadGate {

    /** Raised from {@link #awaitReady()} when the session was cancelled while waiting. */
    public static final class CancelledException extends IOException {
        public CancelledException() {
            super("Download was cancelled");
        }
    }

    private final Runnable cancelTransfers;
    private final Object monitor = new Object();

    private boolean paused;
    private boolean cancelled;
    private boolean keepPartial;
    private boolean cancelNotified;

    public DownloadGate(Runnable cancelTransfers) {
        this.cancelTransfers = Objects.requireNonNull(cancelTransfers, "cancelTransfers");
    }

    public boolean isPaused() {
        synchronized (monitor) {
            return paused;
        }
    }

    public boolean isCancelled() {
        synchronized (monitor) {
            return cancelled;
        }
    }

    public boolean shouldKeepPartialDownload() {
        synchronized (monitor) {
            return keepPartial;
        }
    }

    public void pause() {
        synchronized (monitor) {
            if (!cancelled) {
                paused = true;
            }
        }
    }

    public void resume() {
        synchronized (monitor) {
            if (!cancelled) {
                paused = false;
            }
            monitor.notifyAll();
        }
    }

    public void cancel() {
        requestCancellation(false);
    }

    public void cancelKeepingPartial() {
        requestCancellation(true);
    }

    /**
     * Aborts active transfers once per session. Later calls are no-ops: the session is already
     * cancelled, so a second abort request has nothing left to interrupt.
     */
    public void cancelActiveTransfers() {
        boolean notifyTransfers;
        synchronized (monitor) {
            notifyTransfers = !cancelNotified;
            cancelNotified = true;
        }
        if (notifyTransfers) {
            cancelTransfers.run();
        }
    }

    /**
     * Blocks while the session is paused and fails once it is cancelled.
     *
     * @throws CancelledException when the session was cancelled before or during the wait
     * @throws IOException when the waiting thread itself was interrupted
     */
    public void awaitReady() throws IOException {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
        synchronized (monitor) {
            while (paused && !cancelled) {
                try {
                    monitor.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download thread was interrupted", interrupted);
                }
            }
        }
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    private void requestCancellation(boolean keepPartialDownload) {
        boolean notifyTransfers;
        synchronized (monitor) {
            cancelled = true;
            keepPartial = keepPartialDownload;
            paused = false;
            notifyTransfers = !cancelNotified;
            cancelNotified = true;
            monitor.notifyAll();
        }
        if (notifyTransfers) {
            cancelTransfers.run();
        }
    }
}
