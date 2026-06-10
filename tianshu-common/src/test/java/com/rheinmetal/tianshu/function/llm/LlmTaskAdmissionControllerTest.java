package com.rheinmetal.tianshu.function.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmTaskAdmissionControllerTest {

    @Test
    void firstTaskStartsImmediatelyAndSecondWaitsUntilCompletion() {
        LlmTaskAdmissionController controller = new LlmTaskAdmissionController(1);
        Harness harness = new Harness();

        assertEquals(LlmTaskAdmissionController.AdmissionState.STARTED, harness.submit(controller, "first", 0, false));
        assertEquals(LlmTaskAdmissionController.AdmissionState.QUEUED, harness.submit(controller, "second", 0, false));

        assertEquals(List.of("first"), harness.started);
        harness.complete("first");
        assertEquals(List.of("first", "second"), harness.started);
    }

    @Test
    void priorityIsNormalizedAtAdmissionBoundary() {
        LlmTaskAdmissionController controller = new LlmTaskAdmissionController(1);
        Harness harness = new Harness();

        harness.submit(controller, "active", 999, true);
        assertEquals(LlmTaskAdmissionController.AdmissionState.QUEUED, harness.submit(controller, "negative", -1, false));
        assertEquals(LlmTaskAdmissionController.AdmissionState.STARTED, harness.submit(controller, "too-high", 1001, false));

        assertEquals(List.of("active", "too-high"), harness.started);
    }

    @Test
    void queueFullRejectsLowestValueTask() {
        LlmTaskAdmissionController controller = new LlmTaskAdmissionController(1);
        Harness harness = new Harness();

        harness.submit(controller, "active", 0, false);
        harness.submit(controller, "waiting", 1, false);
        assertEquals(LlmTaskAdmissionController.AdmissionState.REJECTED, harness.submit(controller, "rejected", 0, false));

        assertEquals(List.of("rejected:LLM_TASK_QUEUE_FULL"), harness.rejected);
        harness.complete("active");
        assertEquals(List.of("active", "waiting"), harness.started);
    }

    @Test
    void higherPriorityTaskReplacesWaitingTaskWhenQueueIsFull() {
        LlmTaskAdmissionController controller = new LlmTaskAdmissionController(1);
        Harness harness = new Harness();

        harness.submit(controller, "active", 0, false);
        harness.submit(controller, "low", 0, false);
        assertEquals(LlmTaskAdmissionController.AdmissionState.QUEUED, harness.submit(controller, "high", 10, false));

        assertEquals(List.of("low:LLM_TASK_QUEUE_FULL"), harness.rejected);
        harness.complete("active");
        assertEquals(List.of("active", "high"), harness.started);
    }

    @Test
    void preemptibleActiveLetsHigherPriorityTaskEnterLibsImmediately() {
        LlmTaskAdmissionController controller = new LlmTaskAdmissionController(1);
        Harness harness = new Harness();

        harness.submit(controller, "active", 0, true);
        assertEquals(LlmTaskAdmissionController.AdmissionState.STARTED, harness.submit(controller, "preempting", 10, false));

        assertEquals(List.of("active", "preempting"), harness.started);
    }

    @Test
    void nonPreemptibleActiveKeepsHigherPriorityTaskWaiting() {
        LlmTaskAdmissionController controller = new LlmTaskAdmissionController(1);
        Harness harness = new Harness();

        harness.submit(controller, "active", 0, false);
        assertEquals(LlmTaskAdmissionController.AdmissionState.QUEUED, harness.submit(controller, "waiting", 10, false));

        assertEquals(List.of("active"), harness.started);
        harness.complete("active");
        assertEquals(List.of("active", "waiting"), harness.started);
    }

    @Test
    void agingCanMakeOlderWaitingTaskWin() {
        LlmTaskAdmissionController controller = new LlmTaskAdmissionController(2, 10);
        Harness harness = new Harness();

        harness.submit(controller, "active", 5, true);
        harness.submit(controller, "old", 0, false);
        harness.submit(controller, "newer", 4, false);

        assertEquals(List.of("active", "old"), harness.started);
    }

    @Test
    void inFlightPreemptedTaskPreventsHiddenQueueDrain() {
        LlmTaskAdmissionController controller = new LlmTaskAdmissionController(2);
        Harness harness = new Harness();

        harness.submit(controller, "active", 0, true);
        harness.submit(controller, "preempting", 10, false);
        harness.submit(controller, "waiting", 1, false);

        harness.complete("preempting");
        assertEquals(List.of("active", "preempting"), harness.started);

        harness.complete("active");
        assertEquals(List.of("active", "preempting", "waiting"), harness.started);
    }

    @Test
    void clearWaitingTasksRejectsOnlyWaitingTasks() {
        LlmTaskAdmissionController controller = new LlmTaskAdmissionController(2);
        Harness harness = new Harness();

        harness.submit(controller, "active", 0, false);
        harness.submit(controller, "waiting-a", 1, false);
        harness.submit(controller, "waiting-b", 2, false);

        controller.clearWaitingTasks("STOPPING", "stopping");

        assertEquals(List.of("waiting-a:STOPPING", "waiting-b:STOPPING"), harness.rejected);
        harness.complete("active");
        assertEquals(List.of("active"), harness.started);
    }

    private static final class Harness {
        private final List<String> started = new ArrayList<>();
        private final List<String> rejected = new ArrayList<>();
        private final List<String> labels = new ArrayList<>();
        private final List<CompletableFuture<Void>> futures = new ArrayList<>();

        private LlmTaskAdmissionController.AdmissionState submit(
                LlmTaskAdmissionController controller,
                String label,
                int priority,
                boolean preemptible
        ) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            labels.add(label);
            futures.add(future);
            return controller.submit(
                    priority,
                    preemptible,
                    () -> {
                        started.add(label);
                        return future;
                    },
                    (code, message) -> rejected.add(label + ":" + code)
            ).state();
        }

        private void complete(String label) {
            int index = labels.indexOf(label);
            if (index >= 0) {
                futures.get(index).complete(null);
            }
        }
    }
}
