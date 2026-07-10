package com.rheinmetal.tianshu.function.llm.service;

import java.time.Duration;

public interface RagPersistenceScheduler {

    void schedule(Runnable task, Duration delay);

    static RagPersistenceScheduler immediate() {
        return new RagPersistenceScheduler() {
            @Override
            public void schedule(Runnable task, Duration delay) {
                task.run();
            }
        };
    }
}
