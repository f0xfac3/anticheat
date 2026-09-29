package dev.fox.monitor;

import static dev.fox.monitor.MonitorWindow.*;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** The UI is a thin client of analytics/lab.py. All gates also apply to headless users. */
final class Workspaces {
    private final MonitorWindow window;
    final Grid detections = new Grid("DETECTION", "FEATURES", "DEPLOYMENT", "VALIDATION");
    final Grid samples =
        new Grid("SAMPLE", "INPUT", "LABEL / BEHAVIOR", "NETWORK", "PURPOSE", "REVIEW");
    final JTextArea detectionDetail = Theme.area(), sampleDetail = Theme.area();
    final JLabel datasetSummary = Theme.label("No samples", 13, false);
    final FeatureComparison comparison = new FeatureComparison();
    private final List<Map<String, Object>> detectionRows = new ArrayList<>(),
                                            sampleRows = new ArrayList<>();
    private int registryHash;
    Workspaces(MonitorWindow window) {
        this.window = window;
    }
    JPanel detectionPanel() {
        JPanel panel = Theme.panel(new BorderLayout(0, 16));
        panel.add(heading("Detections",
                      "Detection settings, model results and collection plans."),
            BorderLayout.NORTH);
        detectionDetail.setLineWrap(true);
        detectionDetail.setWrapStyleWord(true);
        JPanel left = Theme.panel(new BorderLayout(0, 16));
        left.add(Theme.scroll(detections.table), BorderLayout.CENTER);
        left.add(comparison, BorderLayout.SOUTH);
        panel.add(split(left, Theme.scroll(detectionDetail), .53), BorderLayout.CENTER);
        panel.add(actions(button("Register recipe", this::registerRecipe),
                      button("Next experiment", this::nextExperiment),
                      button("Train / validate", this::train),
                      button("Set deployment", this::deploy), button("Rollback", this::rollback)),
            BorderLayout.SOUTH);
        detections.table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting())
                showDetection();
        });
        return panel;
    }
    JPanel validationPanel() {
        JPanel panel = Theme.panel(new BorderLayout(0, 16));
        JPanel top = Theme.panel(new BorderLayout(0, 12));
        top.add(heading("Validation",
                    "Record declared conditions, inspect packets and review labels."),
            BorderLayout.NORTH);
        datasetSummary.setForeground(Theme.MUTED);
        top.add(datasetSummary, BorderLayout.SOUTH);
        panel.add(top, BorderLayout.NORTH);
        sampleDetail.setLineWrap(true);
        sampleDetail.setWrapStyleWord(true);
        panel.add(split(Theme.scroll(samples.table), Theme.scroll(sampleDetail), .62),
            BorderLayout.CENTER);
        panel.add(actions(button("Record session", this::record),
                      button("Finish capture", this::finishCapture),
                      button("Import captures",
                          ()
                              -> window.run(Arrays.asList("sync", window.app.config.raw.toString()),
                                  "Capture admission complete")),
                      button("Review sample", this::review)),
            BorderLayout.SOUTH);
        samples.table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting())
                showSample();
        });
        return panel;
    }
    void refresh() {
        int hash =
            Objects.hash(window.snapshot.rows("detections"), window.snapshot.rows("experiments"),
                window.snapshot.rows("samples"), window.snapshot.rows("jobs"));
        if (hash == registryHash)
            return;
        registryHash = hash;
        String selectedDetection = selection(detections, detectionRows),
               selectedSample = selection(samples, sampleRows);
        detectionRows.clear();
        detections.clear();
        for (Map<String, Object> row : window.snapshot.rows("detections")) {
            detectionRows.add(row);
            Map<String, Object> spec = Json.object(s(row, "spec_json")),
                                experiment = window.latest(s(row, "id"));
            detections.add(s(row, "name"), String.valueOf(spec.get("features")),
                row.get("mode") == null ? "Not deployed" : "Configured " + s(row, "mode"),
                experiment == null ? "No experiment" : s(experiment, "status"));
        }
        restore(detections, detectionRows, selectedDetection);
        showDetection();
        sampleRows.clear();
        samples.clear();
        for (Map<String, Object> row : window.snapshot.rows("samples")) {
            sampleRows.add(row);
            samples.add(shortId(s(row, "id")), s(row, "input_source"),
                s(row, "label") + " / " + s(row, "behavior"), s(row, "network"), s(row, "purpose"),
                s(row, "review"));
        }
        restore(samples, sampleRows, selectedSample);
        showSample();
        int total = 0, human = 0, reviewed = 0;
        for (Map<String, Object> row : window.snapshot.rows("coverage")) {
            int n = (int) Json.number(row, "count");
            total += n;
            if (s(row, "input_source").equals("human"))
                human += n;
            if (s(row, "review").equals("reviewed"))
                reviewed += n;
        }
        long planned = window.snapshot.rows("jobs")
                           .stream()
                           .filter(r -> s(r, "status").equals("planned"))
                           .count();
        datasetSummary.setText(total + " admitted samples  ·  " + human + " human  ·  " + reviewed
            + " reviewed  ·  " + planned + " planned captures");
    }
    private void showDetection() {
        Map<String, Object> row = selected(detections, detectionRows);
        if (row == null) {
            comparison.data(null);
            detectionDetail.setText("Select a detection to inspect its mechanism, features, and "
                + "promotion requirements.");
            return;
        }
        Map<String, Object> spec = Json.object(s(row, "spec_json")),
                            exp = window.latest(s(row, "id"));
        StringBuilder text = new StringBuilder(s(row, "name"))
                                 .append("\n\nMECHANISM\n")
                                 .append(spec.get("mechanism"))
                                 .append("\n\nSOURCE\n")
                                 .append(spec.get("source"))
                                 .append("\n\nFEATURES\n")
                                 .append(spec.get("features"))
                                 .append("\n\nPOLICY\n")
                                 .append(spec.get("consecutive_windows"))
                                 .append(" consecutive eligible windows\nTail cutoff: ")
                                 .append(spec.get("tail_cutoff"));
        if (exp != null) {
            Map<String, Object> report = Json.object(s(exp, "report_json"));
            comparison.data(report.get("feature_summary"));
            text.append("\n\nLATEST EXPERIMENT\n")
                .append(s(exp, "scope"))
                .append(" / ")
                .append(s(exp, "status"))
                .append("\n")
                .append(s(exp, "id"))
                .append("\n\nHELD-OUT METRICS\n");
            appendMap(text, report.get("metrics"));
            text.append("\nPROMOTION BLOCKERS\n");
            Object blockers = report.get("blockers");
            if (blockers instanceof List)
                for (Object blocker : (List<?>) blockers)
                    text.append("• ").append(blocker).append('\n');
            text.append("\nCOVERAGE\n");
            appendMap(text, report.get("coverage"));
            text.append("\nMEASURED HELD-OUT FEATURES\n");
            appendMap(text, report.get("feature_summary"));
            text.append("\nTRANSPORT STRESS (SYNTHETIC REPLAY)\n");
            appendMap(text, report.get("stress"));
        } else {
            comparison.data(null);
            text.append("\n\nNo fitted model. Collect the required classes and independent "
                + "controls first.");
        }
        text.append("\n\nModel settings load at server startup. Native checks retain their own "
            + "enforcement policy.");
        detectionDetail.setText(text.toString());
        detectionDetail.setCaretPosition(0);
    }
    private void showSample() {
        Map<String, Object> row = selected(samples, sampleRows);
        if (row == null) {
            sampleDetail.setText("Select a sample to inspect its raw source, declaration, and "
                + "review.\n\nReview human and network conditions against an "
                + "independent record before accepting the label.");
            return;
        }
        StringBuilder text = new StringBuilder();
        for (String key : new String[] {"id", "manifest_path", "source_hash", "player_group", "day",
                 "client", "network", "input_source", "script_family", "behavior", "label", "route",
                 "purpose", "review", "reviewer"})
            text.append(key).append("\n").append(s(row, key)).append("\n\n");
        Map<String, Object> meta = Json.object(s(row, "metadata_json"));
        text.append("DECLARATION\n").append(meta.get("declared"));
        sampleDetail.setText(text.toString());
        sampleDetail.setCaretPosition(0);
    }
    private void registerRecipe() {
        JFileChooser chooser =
            new JFileChooser(window.app.config.framework.resolve("analytics/recipes").toFile());
        chooser.setDialogTitle("Register a versioned detection recipe (JSON)");
        if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION)
            window.run(Arrays.asList("register", chooser.getSelectedFile().toString()),
                "Recipe registered");
    }
    private void train() {
        Map<String, Object> row = selected(detections, detectionRows);
        if (row == null) {
            window.message("Select a detection first.");
            return;
        }
        Object mode = JOptionPane.showInputDialog(window,
            "Pilot uses route groups. Independent validation requires reviewed, separately "
                + "reserved people and script families.",
            "Run experiment", JOptionPane.PLAIN_MESSAGE, null,
            new String[] {"independent", "pilot"}, "independent");
        if (mode != null)
            window.run(Arrays.asList("train", s(row, "id"), "--scope", mode.toString()),
                "Experiment saved");
    }
    void nextExperiment() {
        Map<String, Object> row = selected(detections, detectionRows);
        if (row == null) {
            window.message("Select a detection first.");
            return;
        }
        String id = s(row, "id");
        window.footer.setText("Reading collection gaps for " + id + "…");
        window.framework.run(Arrays.asList("plan-next", id), output -> {
            if (selection(detections, detectionRows).equals(id)) {
                detectionDetail.setText(output);
                detectionDetail.setCaretPosition(0);
            }
            window.footer.setText("Collection plan ready for " + id);
        }, window::message);
    }
    private void deploy() {
        Map<String, Object> row = selected(detections, detectionRows);
        if (row == null)
            return;
        Map<String, Object> exp = window.latest(s(row, "id"));
        if (exp == null) {
            window.message("Train an experiment first.");
            return;
        }
        Object mode = JOptionPane.showInputDialog(window,
            "Apply the latest experiment on the next server start. Enforcement is blocked unless "
                + "all validation gates pass.",
            "Deployment mode", JOptionPane.PLAIN_MESSAGE, null,
            new String[] {"shadow", "enforce", "disabled"}, "shadow");
        if (mode != null)
            window.run(Arrays.asList("deploy", s(exp, "id"), mode.toString(), modelDirectory()),
                "Deployment saved. Restart the server to load it.");
    }
    private void rollback() {
        Map<String, Object> row = selected(detections, detectionRows);
        if (row != null)
            window.run(Arrays.asList("rollback", s(row, "id"), modelDirectory()),
                "Previous model restored in shadow mode. Restart the server to load it.");
    }
    private String modelDirectory() {
        return window.app.config.server.resolve("plugins/FoxAntiCheat/models").toString();
    }
    private void record() {
        if (window.app.replay || !window.app.process.alive()) {
            window.message("Start the server before recording.");
            return;
        }
        JPanel form = Theme.panel(new GridLayout(0, 2, 12, 9));
        String[] keys = {
            "player", "client", "configuration", "network", "route", "script_family", "reference"};
        Map<String, JTextField> fields = new LinkedHashMap<>();
        String[] defaults = {"", "vanilla-1.8.9", "all-off", "local-loopback", "manual-01", "", ""};
        for (int i = 0; i < keys.length; i++) {
            JTextField field = Theme.field();
            field.setText(defaults[i]);
            fields.put(keys[i], field);
            form.add(Theme.label(keys[i].replace('_', ' '), 13, false));
            form.add(field);
        }
        JComboBox<String> label = combo("legit", "cheat"), input = combo("human", "scripted"),
                          purpose = combo("development", "validation");
        JTextField behavior = Theme.field();
        behavior.setText("none");
        form.add(Theme.label("label", 13, false));
        form.add(label);
        form.add(Theme.label("behavior (none / timer / attack_macro)", 13, false));
        form.add(behavior);
        form.add(Theme.label("input source", 13, false));
        form.add(input);
        form.add(Theme.label("reserve before collection", 13, false));
        form.add(purpose);
        if (JOptionPane.showConfirmDialog(window, form,
                "Record a declared session · use an external reference for review",
                JOptionPane.OK_CANCEL_OPTION)
            != JOptionPane.OK_OPTION)
            return;
        StringBuilder json = new StringBuilder("{");
        for (String key : keys)
            if (!key.equals("player"))
                json.append(quote(key))
                    .append(':')
                    .append(quote(fields.get(key).getText().trim()))
                    .append(',');
        json.append("\"label\":")
            .append(quote(label.getSelectedItem().toString()))
            .append(",\"input_source\":")
            .append(quote(input.getSelectedItem().toString()))
            .append(",\"purpose\":")
            .append(quote(purpose.getSelectedItem().toString()))
            .append(",\"behavior\":")
            .append(quote(behavior.getText().trim()))
            .append('}');
        window.footer.setText("Planning capture…");
        window.framework.run(
            Arrays.asList("plan", fields.get("player").getText().trim(), json.toString()),
            output
            -> {
                window.app.process.command((String) Json.read(output));
                window.message("Capture requested. Check server controls for acceptance. Keep this "
                    + "connection dedicated to collection; enforcement remains exempt "
                    + "until reconnect.\n\nUse Finish capture when done.");
            },
            window::message);
    }
    private void finishCapture() {
        String player = JOptionPane.showInputDialog(window, "Player whose capture should stop:");
        if (player == null)
            return;
        if (!player.matches("[A-Za-z0-9_]{1,16}")) {
            window.message("Enter an exact Minecraft username.");
            return;
        }
        window.app.process.command("acdata stop " + player);
        javax.swing.Timer delay = new javax.swing.Timer(2500,
            e
            -> window.run(Arrays.asList("sync", window.app.config.raw.toString()),
                "Capture admission complete; review the sample before validation."));
        delay.setRepeats(false);
        delay.start();
    }
    private void review() {
        Map<String, Object> row = selected(samples, sampleRows);
        if (row == null) {
            window.message("Select a sample first.");
            return;
        }
        JTextField reviewer = Theme.field();
        JComboBox<String> outcome = combo("reviewed", "rejected");
        JPanel form = Theme.panel(new GridLayout(0, 1, 0, 8));
        form.add(Theme.label("Reviewer identity (independent of the recorded player)", 13, false));
        form.add(reviewer);
        form.add(outcome);
        form.add(
            Theme.label("Verify settings, input source, and network against the capture reference.",
                12, false));
        if (JOptionPane.showConfirmDialog(
                window, form, "Review sample", JOptionPane.OK_CANCEL_OPTION)
            == JOptionPane.OK_OPTION) {
            List<String> args = new ArrayList<>(Arrays.asList(
                "review", s(row, "id"), reviewer.getText(), outcome.getSelectedItem().toString()));
            if (outcome.getSelectedItem().equals("reviewed")) {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(
                    "Select independent review evidence (hashed into the audit trail)");
                if (chooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION)
                    return;
                args.add("--reference");
                args.add(chooser.getSelectedFile().toString());
            }
            window.run(args, "Review recorded in the audit trail");
        }
    }
    private static void appendMap(StringBuilder text, Object value) {
        if (value instanceof Map)for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())
                text.append(e.getKey().toString().replace('_', ' '))
                    .append(": ")
                    .append(e.getValue())
                    .append('\n');
    }
    private static Map<String, Object> selected(Grid grid, List<Map<String, Object>> rows) {
        int n = grid.table.getSelectedRow();
        return n >= 0 && n < rows.size() ? rows.get(n) : null;
    }
    private static String selection(Grid grid, List<Map<String, Object>> rows) {
        Map<String, Object> row = selected(grid, rows);
        return row == null ? "" : s(row, "id");
    }
    private static void restore(Grid grid, List<Map<String, Object>> rows, String id) {
        for (int i = 0; i < rows.size(); i++)
            if (s(rows.get(i), "id").equals(id)) {
                grid.table.setRowSelectionInterval(i, i);
                break;
            }
    }
    private static JComboBox<String> combo(String... values) {
        JComboBox<String> c = new JComboBox<>(values);
        c.setFont(Theme.NORMAL);
        c.setBackground(Theme.RAISED);
        c.setForeground(Theme.WHITE);
        return c;
    }
    private static String quote(String s) {
        return "\""
            + s.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t")
            + "\"";
    }
}
