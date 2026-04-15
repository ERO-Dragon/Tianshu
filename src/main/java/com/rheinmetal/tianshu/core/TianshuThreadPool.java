package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.Tianshu;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TianshuThreadPool {
    // 单例模式
    private static TianshuThreadPool instance;

    // 线程池定义
    private final ExecutorService asrWorker;
    private final ExecutorService llmWorker;
    private final ExecutorService ttsWorker;
    private final ExecutorService toolWorker;

    private TianshuThreadPool() {
        // ASR Worker：单线程常驻
        this.asrWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Tianshu-ASR-Worker");
            t.setDaemon(true);
            return t;
        });

        // LLM Worker：单线程常驻
        this.llmWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Tianshu-LLM-Worker");
            t.setDaemon(true);
            return t;
        });

        // TTS Worker：单线程常驻
        this.ttsWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Tianshu-TTS-Worker");
            t.setDaemon(true);
            return t;
        });

        // TOOL Worker：动态线程池
        this.toolWorker = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Tianshu-Tool-Worker");
            t.setDaemon(true);
            return t;
        });

        Tianshu.LOGGER.info("天枢线程池初始化完成");
    }

    // 获取单例
    public static synchronized TianshuThreadPool getInstance() {
        if (instance == null) {
            instance = new TianshuThreadPool();
        }
        return instance;
    }

    // 获取ASR Worker线程池
    public ExecutorService getAsrWorker() {
        return asrWorker;
    }

    // 获取LLM Worker线程池
    public ExecutorService getLlmWorker() {
        return llmWorker;
    }

    // 获取TTS Worker线程池
    public ExecutorService getTtsWorker() {
        return ttsWorker;
    }

    // 获取TOOL Worker线程池
    public ExecutorService getToolWorker() {
        return toolWorker;
    }

    // 关闭所有线程池
    public void shutdown() {
        Tianshu.LOGGER.info("关闭天枢线程池");
        asrWorker.shutdown();
        llmWorker.shutdown();
        ttsWorker.shutdown();
        toolWorker.shutdown();
    }
}