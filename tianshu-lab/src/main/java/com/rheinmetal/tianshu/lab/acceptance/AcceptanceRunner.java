package com.rheinmetal.tianshu.lab.acceptance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs acceptance cases one after another and never lets one of them take the run down with it.
 *
 * <p>Isolation is the whole contract: a case that throws, or that returns nothing, becomes a failed outcome with the
 * throwable's description. A suite that stops at the first exception tells you one thing; this tells you all of them,
 * which is what a person wants when they sit down to work through a list.</p>
 *
 * <p>Cases run on the caller's thread. {@link com.rheinmetal.tianshu.lab.LabAcceptance} calls this from a
 * worker thread because a case may wait on a future; nothing here touches the game thread.</p>
 */
public final class AcceptanceRunner {

    private final List<AcceptanceCase> cases;

    public AcceptanceRunner(List<AcceptanceCase> cases) {
        this.cases = List.copyOf(cases);
    }

    /** The bench's own suite. */
    public static AcceptanceRunner standard() {
        return new AcceptanceRunner(LabAcceptanceCases.all());
    }

    public List<AcceptanceCase> cases() {
        return cases;
    }

    public AcceptanceReport runAll() {
        return runAll(null);
    }

    /**
     * Runs the suite, telling {@code progress} about each case as it finishes.
     *
     * <p>The callback exists because two cases are slow by nature — the budget case deliberately waits out five
     * seconds — and a bench that shows nothing for six seconds reads as a dead button. A person who presses twice then
     * gets two runs, which is worse than slow: the probes would collide.</p>
     */
    public AcceptanceReport runAll(java.util.function.Consumer<AcceptanceOutcome> progress) {
        List<AcceptanceOutcome> outcomes = new ArrayList<>(cases.size());
        boolean selfCheckSeen = false;
        boolean selfCheckHeld = false;
        for (AcceptanceCase acceptanceCase : cases) {
            AcceptanceOutcome outcome = runSafely(acceptanceCase);
            if (AcceptanceReport.SELF_CHECK_ID.equals(acceptanceCase.id())) {
                selfCheckSeen = true;
                selfCheckHeld = outcome.status() == AcceptanceOutcome.Status.FAIL;
            }
            outcomes.add(outcome);
            if (progress != null) {
                try {
                    progress.accept(outcome);
                } catch (RuntimeException ignored) {
                    // A progress listener must never break the run it is watching.
                }
            }
        }
        return new AcceptanceReport(Instant.now().toString(), List.copyOf(outcomes), selfCheckSeen && selfCheckHeld);
    }

    public int size() {
        return cases.size();
    }

    /** Re-runs one case by id; an unknown id is a failed outcome rather than an exception. */
    public AcceptanceOutcome runOne(String id) {
        for (AcceptanceCase acceptanceCase : cases) {
            if (acceptanceCase.id().equals(id)) {
                return runSafely(acceptanceCase);
            }
        }
        return AcceptanceOutcome.fail(id, "unknown acceptance case", "no case with this id is registered");
    }

    private static AcceptanceOutcome runSafely(AcceptanceCase acceptanceCase) {
        try {
            AcceptanceOutcome outcome = acceptanceCase.run();
            if (outcome == null) {
                return AcceptanceOutcome.fail(acceptanceCase.id(), acceptanceCase.title(),
                        "the case returned no outcome; silence is not a pass");
            }
            return outcome;
        } catch (Throwable failure) {
            return AcceptanceOutcome.fail(acceptanceCase.id(), acceptanceCase.title(), describe(failure));
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}