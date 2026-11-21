package com.rheinmetal.tianshu.lab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.lab.acceptance.AcceptanceOutcome;
import com.rheinmetal.tianshu.lab.acceptance.AcceptanceReport;
import com.rheinmetal.tianshu.lab.acceptance.AcceptanceRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The bench's acceptance run: executes the suite, keeps the last report for the settings page, publishes one
 * diagnostics event, and drops the machine-readable result next to the run.
 *
 * <p>Blocking. The caller starts it on a worker thread, for the same reason the capability self-check does: a case
 * waits on provider futures, and none of that belongs on the game thread.</p>
 *
 * <p>The JSON file is best effort. A run whose report cannot be written is still a run — its table stays on screen
 * and in the diagnostics log — but the line saying where it went tells the truth about whether it did.</p>
 */
public final class LabAcceptance {

    private static final System.Logger LOGGER = System.getLogger("tianshu.lab");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final String REPORT_DIRECTORY = "tianshu-lab";

    private final DiagnosticSink diagnostics;
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile String report = "";

    public LabAcceptance(DiagnosticSink diagnostics) {
        this.diagnostics = diagnostics == null ? DiagnosticSink.NOOP : diagnostics;
    }

    public String report() {
        return report;
    }

    /**
     * Blocking; run it off the UI thread.
     *
     * <p>Single-flight on purpose. Two presses used to start two runs, and the cases that switch a tool off or
     * register a probe would then fight each other — a second press would look like the bench "working", when what it
     * had done was corrupt the first run. A second press now does nothing.</p>
     *
     * <p>The page gets a line the moment the button is pressed and one after each case, because the suite takes about
     * six seconds: the budget case waits out its full five-second deadline, and that wait is the point of the case.</p>
     */
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            report = "running… (0/" + AcceptanceRunner.standard().size() + ")";
            AcceptanceRunner runner = AcceptanceRunner.standard();
            java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
            AcceptanceReport result = runner.runAll(outcome -> report =
                    "running… (" + done.incrementAndGet() + "/" + runner.size() + ") "
                            + outcome.id() + " " + outcome.status().name());
            Path written = write(result);
            report = result.table() + (written == null
                    ? "-- report not written: see the log\n"
                    : "-- report: " + written.toAbsolutePath() + "\n");
            publish(result, written);
        } finally {
            running.set(false);
        }
    }

    private void publish(AcceptanceReport result, Path written) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("cases", Integer.toString(result.outcomes().size()));
        attributes.put("pass", Long.toString(result.count(AcceptanceOutcome.Status.PASS)));
        attributes.put("fail", Long.toString(result.count(AcceptanceOutcome.Status.FAIL)));
        attributes.put("skip", Long.toString(result.count(AcceptanceOutcome.Status.SKIP)));
        attributes.put("selfCheckHeld", Boolean.toString(result.selfCheckHeld()));
        attributes.put("accepted", Boolean.toString(result.accepted()));
        attributes.put("uncovered", Integer.toString(AcceptanceReport.uncovered().size()));
        attributes.put("report", written == null ? "" : written.getFileName().toString());
        try {
            diagnostics.publish(DiagnosticEvent.now(LabModule.MODULE_ID, "acceptance.run",
                    result.accepted() ? DiagnosticSeverity.INFO : DiagnosticSeverity.WARN,
                    DiagnosticPrivacy.PUBLIC, attributes));
        } catch (RuntimeException ignored) {
            // Diagnostics is observability; a broken sink must not hide the UI result.
        }
        LOGGER.log(System.Logger.Level.INFO, "acceptance run:\n{0}", report);
    }

    private static Path write(AcceptanceReport result) {
        Path file = Path.of(REPORT_DIRECTORY, "acceptance-" + System.currentTimeMillis() + ".json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(result.toJson()), StandardCharsets.UTF_8);
            return file;
        } catch (IOException | RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "acceptance report not written", failure);
            return null;
        }
    }
}