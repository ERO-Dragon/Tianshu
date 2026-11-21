package com.rheinmetal.tianshu.lab.acceptance;

/**
 * One line of the acceptance bench: an id that matches the issue list, a title a human can read in the report, and
 * a body that either proves its property or says why it could not.
 *
 * <p>Ids are the checklist's ids on purpose. When a report says {@code ACC-EXP-2} passed, the reader can find the
 * same name in the document and check what the case actually asserted; a hand-numbered suite drifts away from the
 * document the first time either side is edited.</p>
 */
public interface AcceptanceCase {

    String id();

    String title();

    /** Runs once. Throwing is a failure, not a crash: the runner isolates it and records it. */
    AcceptanceOutcome run() throws Exception;

    @FunctionalInterface
    interface Body {
        AcceptanceOutcome run() throws Exception;
    }

    static AcceptanceCase of(String id, String title, Body body) {
        return new AcceptanceCase() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String title() {
                return title;
            }

            @Override
            public AcceptanceOutcome run() throws Exception {
                return body.run();
            }
        };
    }
}