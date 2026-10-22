package com.rheinmetal.tianshu.function.echo.synthesis;

import java.util.function.BooleanSupplier;

public interface TtsCodecExecution {
    Task submit(Runnable work);

    interface Task {
        boolean accepted();

        void cancel(String reason);

        void await(BooleanSupplier cancellationRequested) throws Exception;
    }
}
