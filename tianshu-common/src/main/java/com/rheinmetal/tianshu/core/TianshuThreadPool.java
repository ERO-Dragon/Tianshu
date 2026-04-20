package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TianshuThreadPool {

    private final IGameEnvironment env;
    private final ExecutorService asrWorker;
    private final ExecutorService llmWorker;
    private final ExecutorService ttsWorker;
    private final ExecutorService toolWorker;

    public TianshuThreadPool(IGameEnvironment env) {
        this.env = env;

        this.asrWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Tianshu-ASR-Worker");
            t.setDaemon(true);
            return t;
        });

        this.llmWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Tianshu-LLM-Worker");
            t.setDaemon(true);
            return t;
        });

        this.ttsWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Tianshu-TTS-Worker");
            t.setDaemon(true);
            return t;
        });

        this.toolWorker = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Tianshu-Tool-Worker");
            t.setDaemon(true);
            return t;
        });

        env.info("天枢线程池初始化完成");
    }

    public ExecutorService getAsrWorker() {
        return asrWorker;
    }

    public ExecutorService getLlmWorker() {
        return llmWorker;
    }

    public ExecutorService getTtsWorker() {
        return ttsWorker;
    }

    public ExecutorService getToolWorker() {
        return toolWorker;
    }

    public void shutdown() {
        env.info("关闭天枢线程池");
        asrWorker.shutdown();
        llmWorker.shutdown();
        ttsWorker.shutdown();
        toolWorker.shutdown();
    }
}
