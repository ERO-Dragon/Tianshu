package com.rheinmetal.tianshu.lab.acceptance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcceptanceRunnerTest {

    private static AcceptanceCase passing(String id) {
        return AcceptanceCase.of(id, "passes", () -> AcceptanceOutcome.pass(id, "passes", "held"));
    }

    private static AcceptanceCase failing(String id) {
        return AcceptanceCase.of(id, "fails", () -> AcceptanceOutcome.fail(id, "fails", "did not hold"));
    }

    private static AcceptanceCase selfCheck(boolean broken) {
        return AcceptanceCase.of(AcceptanceReport.SELF_CHECK_ID, "self check",
                () -> broken
                        ? AcceptanceOutcome.fail(AcceptanceReport.SELF_CHECK_ID, "self check", "broke as designed")
                        : AcceptanceOutcome.pass(AcceptanceReport.SELF_CHECK_ID, "self check", "unexpectedly held"));
    }

    @Test
    void aThrowingCaseIsRecordedAndTheRestStillRun() {
        AcceptanceRunner runner = new AcceptanceRunner(List.of(
                AcceptanceCase.of("A-1", "throws", () -> {
                    throw new IllegalStateException("handler exploded");
                }),
                passing("A-2")));

        AcceptanceReport report = runner.runAll();

        assertEquals(2, report.outcomes().size(), "a throw must not abort the run");
        assertEquals(AcceptanceOutcome.Status.FAIL, report.outcomes().get(0).status());
        assertTrue(report.outcomes().get(0).detail().contains("handler exploded"),
                "the failure detail must carry the throwable's message");
        assertEquals(AcceptanceOutcome.Status.PASS, report.outcomes().get(1).status());
    }

    @Test
    void aCaseThatReturnsNoOutcomeFails() {
        AcceptanceRunner runner = new AcceptanceRunner(List.of(AcceptanceCase.of("A-1", "silent", () -> null)));

        AcceptanceReport report = runner.runAll();

        assertEquals(AcceptanceOutcome.Status.FAIL, report.outcomes().get(0).status(),
                "silence is not a pass");
    }

    @Test
    void aRunIsVoidWhenTheDeliberateFailurePasses() {
        AcceptanceReport healthy = new AcceptanceRunner(List.of(passing("A-1"), selfCheck(true))).runAll();
        assertTrue(healthy.accepted(), "self-check broke as designed, nothing else failed");

        AcceptanceReport blind = new AcceptanceRunner(List.of(passing("A-1"), selfCheck(false))).runAll();
        assertFalse(blind.selfCheckHeld(), "the bench failed to detect its own broken case");
        assertFalse(blind.accepted(), "a green self-check voids every other green line");
    }

    @Test
    void aRunWithoutTheSelfCheckIsNeverAccepted() {
        AcceptanceReport report = new AcceptanceRunner(List.of(passing("A-1"))).runAll();

        assertFalse(report.accepted(), "a suite with no known-bad case cannot claim to detect failures");
    }

    @Test
    void skippingNarrowsTheRunWithoutFailingIt() {
        AcceptanceCase skipped = AcceptanceCase.of("A-2", "needs a server",
                () -> AcceptanceOutcome.skip("A-2", "needs a server", "no server channel"));

        AcceptanceReport report = new AcceptanceRunner(List.of(passing("A-1"), skipped, selfCheck(true))).runAll();

        assertEquals(1, report.count(AcceptanceOutcome.Status.SKIP));
        assertTrue(report.accepted(), "a skip is a gap, not a failure");
        assertTrue(report.table().contains("needs a server"), "the skip reason must be visible in the report");
    }

    @Test
    void anUnknownCaseIdIsAFailureRatherThanAnException() {
        AcceptanceOutcome outcome = new AcceptanceRunner(List.of(passing("A-1"))).runOne("NOPE-9");

        assertEquals(AcceptanceOutcome.Status.FAIL, outcome.status());
    }

    @Test
    void theReportPublishesWhatItCannotCover() {
        String json = new AcceptanceRunner(List.of(selfCheck(true))).runAll().toJson().toString();

        assertFalse(AcceptanceReport.uncovered().isEmpty(), "an empty gap list would mean the bench claims coverage");
        assertTrue(json.contains("uncovered"));
        assertTrue(AcceptanceReport.uncovered().stream()
                        .anyMatch(area -> area.area().contains("speech")),
                "speech is judged by ear and is deferred to the module's own acceptance");
    }

    @Test
    void theStandardSuiteStillContainsItsSelfCheck() {
        assertTrue(AcceptanceRunner.standard().cases().stream()
                .anyMatch(one -> AcceptanceReport.SELF_CHECK_ID.equals(one.id())));
    }

    /**
     * The matrix document names these ids, so losing one has to break a build rather than quietly shrink the suite.
     */
    @Test
    void theStandardSuiteKeepsEveryCaseTheMatrixNames() {
        List<String> expected = List.of(
                "ACC-EXP-1", "ACC-EXP-2", "ACC-EXP-3", "ACC-EXP-4",
                "ACC-ARG-1", "ACC-ARG-2", "ACC-ARG-3", "ACC-CAT-1",
                "ACC-AGT-1", "ACC-AGT-2", "ACC-AGT-3",
                "ACC-CAP-1", "ACC-CAP-2", "ACC-CAP-3", "ACC-CAP-4", "ACC-DIAG-1", "ACC-DIAG-2", "ACC-SET-1",
                "ACC-CAT-2", "ACC-CAT-3", "ACC-AGT-4", "ACC-EXP-5", "ACC-EXP-6", "ACC-EXP-7",
                "ACC-EXP-8", "ACC-EXP-9", "ACC-DIAG-3", "ACC-CAP-5",
                "ACC-CAT-4", "ACC-EXP-10", "ACC-DIAG-4", "ACC-BDY-1", "ACC-BDY-2", "ACC-BDY-3", "ACC-BDY-4", "ACC-BDY-7", "ACC-ENT-1", "ACC-PRT-1",
                "ACC-HOST-1", "ACC-PRO-1",
                "ACC-ACT-1", "ACC-ACT-2", "ACC-ACT-3", "ACC-CNR-1", "ACC-GIV-1", "ACC-PLC-1", "ACC-MDL-1", "ACC-MDL-2", "ACC-GAME-1", "ACC-GAME-2",
                AcceptanceReport.SELF_CHECK_ID);
        List<String> actual = AcceptanceRunner.standard().cases().stream().map(AcceptanceCase::id).toList();

        assertEquals(expected.size(), actual.stream().distinct().count(), "every case id must be unique");
        assertTrue(actual.containsAll(expected), "missing cases: "
                + expected.stream().filter(id -> !actual.contains(id)).toList());
        assertTrue(AcceptanceRunner.standard().cases().stream().allMatch(one -> !one.title().isBlank()),
                "every case needs a title the report can show");
    }

    /**
     * The report claims coverage by naming cases; a claim that names a case which does not exist is worse than no
     * claim at all, because it reads as evidence.
     */
    @Test
    void everyCoverageClaimNamesARealCase() {
        List<String> actual = AcceptanceRunner.standard().cases().stream().map(AcceptanceCase::id).toList();
        List<String> phantom = AcceptanceReport.coveredAreas().stream()
                .flatMap(area -> area.cases().stream())
                .filter(id -> !actual.contains(id))
                .toList();

        assertFalse(AcceptanceReport.coveredAreas().isEmpty(), "a bench that covers nothing should say so");
        assertTrue(phantom.isEmpty(), "coverage claims without a case: " + phantom);
    }

    /** Each case should be claimed by exactly one area, or the coverage count inflates itself. */
    @Test
    void everyCaseIsClaimedExactlyOnce() {
        List<String> claimed = AcceptanceReport.coveredAreas().stream()
                .flatMap(area -> area.cases().stream())
                .toList();
        List<String> caseIds = AcceptanceRunner.standard().cases().stream()
                .map(AcceptanceCase::id)
                .filter(id -> !AcceptanceReport.SELF_CHECK_ID.equals(id))
                .toList();

        assertEquals(caseIds.size(), claimed.size(), "claim count should match the number of real cases");
        assertEquals(caseIds.size(), claimed.stream().distinct().count(), "a case is claimed twice");
    }
}