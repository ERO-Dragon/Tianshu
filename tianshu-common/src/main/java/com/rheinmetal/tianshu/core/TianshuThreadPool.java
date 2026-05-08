package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TianshuThreadPool {

    private final IGameEnvironment env;
    private static final int TOOL_THREADS = 4;

    private final ExecutorService voiceExecutor;
    private final ExecutorService protocolExecutor;
    private final ExecutorService audioExecutor;
    private final ExecutorService toolExecutor;

    public TianshuThreadPool(IGameEnvironment env) {
        this.env = env;

        this.voiceExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Tianshu-Voice-Executor");
            t.setDaemon(true);
            return t;
        });

        this.protocolExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Tianshu-Protocol-Executor");
            t.setDaemon(true);
            return t;
        });

        this.audioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Tianshu-Audio-Executor");
            t.setDaemon(true);
            return t;
        });

        this.toolExecutor = Executors.newFixedThreadPool(TOOL_THREADS, r -> {
            Thread t = new Thread(r, "Tianshu-Tool-Worker");
            t.setDaemon(true);
            return t;
        });

        env.info("天枢线程池初始化完成");
    }

    public ExecutorService getVoiceExecutor() {
        return voiceExecutor;
    }

    public ExecutorService getProtocolExecutor() {
        return protocolExecutor;
    }

    public ExecutorService getAudioExecutor() {
        return audioExecutor;
    }

    public ExecutorService getToolExecutor() {
        return toolExecutor;
    }

    public void shutdown() {
        env.info("关闭天枢线程池");
        voiceExecutor.shutdown();
        protocolExecutor.shutdown();
        audioExecutor.shutdown();
        toolExecutor.shutdown();
    }
}
