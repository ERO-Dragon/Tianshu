package com.rheinmetal.tianshu.lab.acceptance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * The result of one acceptance run: every outcome, plus the areas this bench knowingly does not cover.
 *
 * <p>Two honesty rules live here rather than in the runner.</p>
 *
 * <p>First, a run only counts when the deliberately-broken case actually broke. If {@value #SELF_CHECK_ID} passes,
 * something is wrong with the bench itself and every green line beside it is worthless — so the run is reported as
 * not accepted even though a naive "no failures" scan would call it clean.</p>
 *
 * <p>Second, the uncovered areas are part of the report, not a paragraph in a document. A bench that quietly omits
 * concurrency, cancellation, paging and speech reads exactly like a bench that covers them, and that is the mistake
 * the build gates made before a mutation probe showed four of them were inspecting nothing.</p>
 */
public record AcceptanceReport(String startedAt, List<AcceptanceOutcome> outcomes, boolean selfCheckHeld) {

    public static final String SELF_CHECK_ID = "SELF-CHECK-1";

    /** Something this bench cannot check in-process, why, and where it is checked instead. */
    public record Uncovered(String area, String reason, String coveredBy) {
    }

    /** A part of the platform this bench does check, and the cases that do it. */
    public record Covered(String area, List<String> cases) {
    }

    /**
     * What this bench claims to cover, keyed to the cases that cover it.
     *
     * <p>Printed with every report so "没覆盖满" stays visible instead of being something a reader has to reconstruct
     * from the case list. A unit in neither list is a hole nobody wrote down.</p>
     */
    public static List<Covered> coveredAreas() {
        return COVERED;
    }

    private static final List<Covered> COVERED = List.of(
            new Covered("tool registry and invocation",
                    List.of("ACC-EXP-1", "ACC-EXP-2", "ACC-EXP-3", "ACC-EXP-8", "ACC-EXP-9")),
            new Covered("tool argument contract",
                    List.of("ACC-ARG-1", "ACC-ARG-2", "ACC-ARG-3")),
            new Covered("capability table",
                    List.of("ACC-CAT-1", "ACC-CAT-2", "ACC-CAT-3", "ACC-CAT-4",
                            "ACC-CAP-1", "ACC-CAP-2", "ACC-CAP-3", "ACC-CAP-4", "ACC-CAP-5",
                            "ACC-EXP-4", "ACC-EXP-5", "ACC-EXP-6", "ACC-EXP-7", "ACC-EXP-10")),
            new Covered("agent facade", List.of("ACC-AGT-1", "ACC-AGT-2", "ACC-AGT-3", "ACC-AGT-4")),
            new Covered("settings session", List.of("ACC-SET-1")),
            new Covered("body tools", List.of("ACC-BDY-1", "ACC-BDY-2", "ACC-BDY-3", "ACC-BDY-4", "ACC-BDY-5", "ACC-BDY-6", "ACC-ENT-1")),
            new Covered("module execution lane", List.of("ACC-PRT-1")),
            new Covered("server actions (cheat tier)", List.of("ACC-ACT-1", "ACC-ACT-2", "ACC-ACT-3")),
            new Covered("container reading, hand-overs and placement lines", List.of("ACC-CNR-1", "ACC-GIV-1", "ACC-PLC-1")),
            new Covered("model download verification", List.of("ACC-MDL-1", "ACC-MDL-2")),
            new Covered("game context (needs a world)", List.of("ACC-GAME-1", "ACC-GAME-2")),
            new Covered("diagnostics", List.of("ACC-DIAG-1", "ACC-DIAG-2", "ACC-DIAG-3", "ACC-DIAG-4")));

    public long count(AcceptanceOutcome.Status status) {
        return outcomes.stream().filter(outcome -> outcome.status() == status).count();
    }

    /**
     * Whether the run stands: the self-check broke as designed and no other case failed.
     *
     * <p>A skipping case does not make the run fail — it makes it narrower, and {@link #uncovered()} plus the skip
     * reason is where that narrowing is recorded.</p>
     */
    public boolean accepted() {
        return selfCheckHeld && outcomes.stream()
                .filter(outcome -> !SELF_CHECK_ID.equals(outcome.id()))
                .noneMatch(AcceptanceOutcome::failed);
    }

    /** Fixed set of areas no in-process bench can honestly claim. */
    public static List<Uncovered> uncovered() {
        return UNCOVERED;
    }

    private static final List<Uncovered> UNCOVERED = List.of(
            new Uncovered("concurrency quota (BUSY)",
                    "the per-caller limit lives in the tool channel; a direct call never passes through it",
                    "ConnectionToolChannelTest / ServerToolDispatchTest"),
            new Uncovered("cancellation (CANCELLED)",
                    "cancelling a call is a channel operation; there is no channel between two local calls",
                    "ConnectionToolChannelTest / ServerToolDispatchTest"),
            new Uncovered("envelope expiry (EXPIRED)",
                    "needs a running ProtocolRuntime with an envelope queued past its deadline",
                    "ProtocolDeadlineAndDepthTest"),
            new Uncovered("body slot handover",
                    "needs a live body in a loaded world plus a blocked step",
                    "BodyOperationsTest / BodyFollowToolTest"),
            new Uncovered("speech: TTS playback, ASR, device switching",
                    "an ear judges these; the bench can only see that a call returned",
                    "the intake / echo modules' own acceptance"));

    /** One line per case plus a footer that repeats the unknowns. */
    public String table() {
        StringBuilder text = new StringBuilder();
        for (AcceptanceOutcome outcome : outcomes) {
            text.append('[').append(outcome.status().name()).append("] ")
                    .append(outcome.id()).append("  ").append(outcome.title())
                    .append("  ::  ").append(outcome.detail())
                    .append('\n');
        }
        text.append("-- ").append(outcomes.size()).append(" cases: ")
                .append(count(AcceptanceOutcome.Status.PASS)).append(" pass, ")
                .append(count(AcceptanceOutcome.Status.FAIL)).append(" fail, ")
                .append(count(AcceptanceOutcome.Status.SKIP)).append(" skip; ")
                .append(selfCheckHeld ? "self-check broke as designed"
                        : "SELF-CHECK DID NOT FAIL — the bench cannot detect failures, run is void")
                .append("; accepted=").append(accepted())
                .append('\n');
        text.append("-- covered (").append(coveredAreas().size()).append("): ");
        for (Covered area : coveredAreas()) {
            text.append(area.area()).append(" (").append(area.cases().size()).append("); ");
        }
        text.append('\n');
        text.append("-- not covered here (")
                .append(uncovered().size()).append("): ");
        for (Uncovered area : uncovered()) {
            text.append(area.area()).append(" [").append(area.coveredBy()).append("]; ");
        }
        return text.toString();
    }

    /** Machine-readable form, so two runs can be compared instead of re-read. */
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("startedAt", startedAt);
        root.addProperty("selfCheckHeld", selfCheckHeld);
        root.addProperty("accepted", accepted());
        JsonArray cases = new JsonArray();
        for (AcceptanceOutcome outcome : outcomes) {
            JsonObject row = new JsonObject();
            row.addProperty("id", outcome.id());
            row.addProperty("title", outcome.title());
            row.addProperty("status", outcome.status().name());
            row.addProperty("detail", outcome.detail());
            cases.add(row);
        }
        root.add("cases", cases);
        JsonArray gaps = new JsonArray();
        for (Uncovered area : uncovered()) {
            JsonObject row = new JsonObject();
            row.addProperty("area", area.area());
            row.addProperty("reason", area.reason());
            row.addProperty("coveredBy", area.coveredBy());
            gaps.add(row);
        }
        root.add("uncovered", gaps);
        JsonArray covered = new JsonArray();
        for (Covered area : coveredAreas()) {
            JsonObject row = new JsonObject();
            row.addProperty("area", area.area());
            JsonArray ids = new JsonArray();
            area.cases().forEach(ids::add);
            row.add("cases", ids);
            covered.add(row);
        }
        root.add("covered", covered);
        return root;
    }
}