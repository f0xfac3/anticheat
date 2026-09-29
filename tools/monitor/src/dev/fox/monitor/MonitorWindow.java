package dev.fox.monitor;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Observe and investigate. Registry workflows live in Workspaces; decisions stay in the server. */
final class MonitorWindow extends JFrame {
    final MonitorApp app;
    final MonitorModel model;
    final Framework framework;
    final JLabel serverStatus = Theme.label("Starting", 12, false),
                 footer = Theme.label("Loading registry", 12, false);
    final JButton stop = Theme.button("Stop server");
    final JTextArea serverLog = Theme.area(), detail = Theme.area();
    final JTextField command = Theme.field();
    final CardLayout cards = new CardLayout();
    final JPanel pages = Theme.panel(cards);
    final Map<String, JButton> navigation = new LinkedHashMap<>();
    final Map<String, JLabel> counts = new LinkedHashMap<>();
    final TrendChart cadence =
        new TrendChart("Movement cadence", "Packets / second · 30-second windows", true);
    final TrendChart risk = new TrendChart(
        "Detection trend", "Timer margin above threshold · not a probability", false);
    final Grid sessions =
        new Grid("PLAYER / SESSION", "LAST WINDOW", "MOVE / S", "ATTACK / S", "LATEST MODEL STATE");
    final Grid incidents = new Grid("TIME", "PLAYER", "DETECTION", "OUTCOME");
    final Workspaces workspaces;
    Registry.Snapshot snapshot = new Registry.Snapshot();
    String view = "Overview";
    private String evidence = "";
    private JDialog console;
    private boolean closing;
    private long lastRecords = -1, lastLog = -1;
    private final List<String> incidentEvidence = new ArrayList<>();

    MonitorWindow(MonitorApp app) {
        super("anticheat / security operations");
        this.app = app;
        model = app.model;
        framework = new Framework(app.config);
        workspaces = new Workspaces(this);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1160, 760));
        setSize(1450, 900);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        getContentPane().setBackground(Theme.BACKGROUND);
        add(sidebar(), BorderLayout.WEST);
        JPanel main = Theme.panel(new BorderLayout(0, 22));
        main.setBorder(BorderFactory.createEmptyBorder(24, 28, 18, 28));
        JPanel header = Theme.panel(new BorderLayout());
        header.add(serverStatus, BorderLayout.WEST);
        header.add(actions(button("Server controls", this::openConsole)), BorderLayout.EAST);
        main.add(header, BorderLayout.NORTH);
        pages.add(overview(), "Overview");
        pages.add(incidentPanel(), "Incidents");
        pages.add(workspaces.detectionPanel(), "Detections");
        pages.add(workspaces.validationPanel(), "Validation");
        main.add(pages, BorderLayout.CENTER);
        footer.setForeground(Theme.MUTED);
        main.add(footer, BorderLayout.SOUTH);
        add(main, BorderLayout.CENTER);
        stop.addActionListener(e -> app.process.stop());
        stop.setEnabled(!app.replay);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                requestClose();
            }
        });
        selectView("Overview");
    }
    private JPanel sidebar() {
        JPanel panel = Theme.panel(new BorderLayout());
        panel.setPreferredSize(new Dimension(205, 0));
        panel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.LINE));
        JPanel list = Theme.panel(null);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(30, 22, 24, 22));
        list.add(Theme.label("anticheat", 25, true));
        list.add(Box.createVerticalStrut(6));
        JLabel sub = Theme.label("SECURITY OPERATIONS", 11, false);
        sub.setForeground(Theme.MUTED);
        list.add(sub);
        list.add(Box.createVerticalStrut(42));
        for (String name : new String[] {"Overview", "Incidents", "Detections", "Validation"}) {
            JButton button = button(name, () -> selectView(name));
            button.setMaximumSize(new Dimension(175, 44));
            button.setHorizontalAlignment(SwingConstants.LEFT);
            button.setAlignmentX(LEFT_ALIGNMENT);
            navigation.put(name, button);
            list.add(button);
            list.add(Box.createVerticalStrut(10));
        }
        panel.add(list, BorderLayout.NORTH);
        JPanel bottom = Theme.panel(new GridLayout(3, 1, 0, 8));
        bottom.setBorder(BorderFactory.createEmptyBorder(20, 22, 28, 12));
        bottom.add(Theme.label(app.replay ? "OFFLINE REPLAY" : "LOCAL CONTROL PLANE", 11, true));
        bottom.add(Theme.label("Evidence → validation", 12, false));
        bottom.add(Theme.label("Shadow → policy → response", 12, false));
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }
    private JPanel overview() {
        JPanel page = Theme.panel(new BorderLayout(0, 20));
        JPanel top = Theme.panel(new BorderLayout(0, 20));
        top.add(heading("Overview",
                    "Live behavior, detection candidates, and server-confirmed responses."),
            BorderLayout.NORTH);
        JPanel stats = Theme.panel(new GridLayout(1, 4, 12, 0));
        stats.add(stat("PLAYERS ONLINE", "online", "Current server run"));
        stats.add(stat("NATIVE ALERTS", "alerts", "Current server run"));
        stats.add(stat("MODEL CANDIDATES", "candidates", "Last hour · shadow included"));
        stats.add(stat("CONFIRMED BANS", "bans", "Persisted server responses"));
        top.add(stats, BorderLayout.CENTER);
        page.add(top, BorderLayout.NORTH);
        JPanel middle = Theme.panel(new BorderLayout(0, 20));
        JPanel charts = Theme.panel(new GridLayout(1, 2, 16, 0));
        charts.add(cadence);
        charts.add(risk);
        middle.add(charts, BorderLayout.NORTH);
        JPanel lower = Theme.panel(new BorderLayout(0, 12));
        lower.add(Theme.label("Observed sessions", 17, true), BorderLayout.NORTH);
        lower.add(Theme.scroll(sessions.table), BorderLayout.CENTER);
        middle.add(lower, BorderLayout.CENTER);
        page.add(middle, BorderLayout.CENTER);
        return page;
    }
    private JPanel stat(String name, String key, String note) {
        JPanel card = Theme.panel(new BorderLayout(0, 8));
        card.setBackground(Theme.PANEL);
        card.setBorder(
            BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.LINE),
                BorderFactory.createEmptyBorder(17, 18, 17, 18)));
        JLabel label = Theme.label(name, 11, true);
        label.setForeground(Theme.MUTED);
        JLabel value = Theme.label("—", 31, false);
        counts.put(key, value);
        card.add(label, BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        JLabel small = Theme.label(note, 12, false);
        small.setForeground(Theme.MUTED);
        card.add(small, BorderLayout.SOUTH);
        return card;
    }
    private JPanel incidentPanel() {
        JPanel panel = Theme.panel(new BorderLayout(0, 16));
        panel.add(
            heading("Incidents",
                "Native evidence and model candidates. A shadow candidate never authorizes a ban."),
            BorderLayout.NORTH);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        JPanel right = Theme.panel(new BorderLayout(0, 10));
        right.add(Theme.label("Evidence", 16, true), BorderLayout.NORTH);
        right.add(Theme.scroll(detail), BorderLayout.CENTER);
        right.add(button("Copy evidence",
                      ()
                          -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                              new StringSelection(evidence), null)),
            BorderLayout.SOUTH);
        panel.add(split(Theme.scroll(incidents.table), right, .64), BorderLayout.CENTER);
        incidents.table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int n = incidents.table.getSelectedRow();
                if (n >= 0 && n < incidentEvidence.size()) {
                    evidence = incidentEvidence.get(n);
                    detail.setText(evidence);
                    detail.setCaretPosition(0);
                }
            }
        });
        return panel;
    }
    void selectView(String name) {
        if (name.equals("Server log")) {
            openConsole();
            return;
        }
        view = name;
        cards.show(pages, name);
        for (Map.Entry<String, JButton> b : navigation.entrySet()) {
            boolean active = b.getKey().equals(name);
            b.getValue().setBackground(active ? Theme.SELECTED : Theme.BACKGROUND);
            b.getValue().setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                    0, active ? 3 : 1, 0, 0, active ? new Color(224, 88, 108) : Theme.LINE),
                BorderFactory.createEmptyBorder(10, 13, 10, 8)));
        }
    }
    void registry(Registry.Snapshot value) {
        snapshot = value;
        try {
            workspaces.refresh();
            refreshBehavior();
            refreshIncidents();
        } catch (RuntimeException error) {
            snapshot.error = "Invalid registry record: " + error.getMessage();
        }
    }
    private void refreshBehavior() {
        Map<String, List<TrendChart.Point>> movement = new LinkedHashMap<>(),
                                            margins = new LinkedHashMap<>();
        Map<String, Map<String, Object>> newest = new LinkedHashMap<>(),
                                         decisions = new LinkedHashMap<>();
        for (Map<String, Object> row : snapshot.rows("decisions")) {
            String key = s(row, "player_uuid") + ":" + s(row, "session_id");
            decisions.putIfAbsent(key, row);
            if (s(row, "detection_id").equals("timer.cadence")) {
                Map<String, Object> exp = experiment(s(row, "model_id"));
                double threshold = exp == null
                    ? Double.NaN
                    : Json.number(Json.object(s(exp, "model_json")), "threshold");
                if (Double.isFinite(threshold))
                    point(margins, player(s(row, "player_uuid")), row,
                        Json.number(row, "margin") - threshold);
            }
        }
        for (Map<String, Object> row : snapshot.rows("windows")) {
            String key = s(row, "player_uuid") + ":" + s(row, "session_id");
            newest.putIfAbsent(key, row);
            point(movement, player(s(row, "player_uuid")), row,
                Json.number(Json.object(s(row, "features_json")), "movement_pps"));
        }
        cadence.data(movement);
        risk.data(margins);
        sessions.clear();
        for (Map.Entry<String, Map<String, Object>> entry : newest.entrySet()) {
            Map<String, Object> row = entry.getValue(),
                                features = Json.object(s(row, "features_json")),
                                decision = decisions.get(entry.getKey());
            sessions.add(player(s(row, "player_uuid")) + " / " + s(row, "session_id"), time(row),
                format(Json.number(features, "movement_pps")),
                format(Json.number(features, "attack_pps")),
                decision == null ? "Telemetry only" : s(decision, "action"));
        }
        counts.get("candidates").setText(Long.toString(total("totals", "candidates")));
    }
    private void point(Map<String, List<TrendChart.Point>> values, String player,
        Map<String, Object> row, double y) {
        if (!Double.isFinite(y) || (!values.containsKey(player) && values.size() >= 8))
            return;
        values.computeIfAbsent(player, k -> new ArrayList<>())
            .add(new TrendChart.Point((long) Json.number(row, "created_ms"), y));
    }
    private void refreshIncidents() {
        int selected = incidents.table.getSelectedRow();
        incidents.clear();
        incidentEvidence.clear();
        for (String[] response : model.responses) {
            if (!response[2].equals("BANNED"))
                continue;
            incidents.add(response[0], response[1], "Server response", response[2]);
            incidentEvidence.add(response[4]);
        }
        for (Map<String, Object> row : snapshot.rows("native_incidents")) {
            incidents.add(
                time(row), player(s(row, "player_uuid")), s(row, "detection_id"), s(row, "action"));
            incidentEvidence.add("NATIVE DECISION\n" + s(row, "id")
                + "\nOutcome: " + s(row, "action") + "\n\n" + s(row, "evidence_json"));
        }
        for (Map<String, Object> row : snapshot.rows("incidents")) {
            incidents.add(
                time(row), player(s(row, "player_uuid")), s(row, "detection_id"), s(row, "action"));
            incidentEvidence.add("MODEL DECISION\n" + s(row, "detection_id")
                + "\nModel: " + s(row, "model_id") + "\nDecision: " + s(row, "id")
                + "\nOutcome: " + s(row, "action") + "\nSustained margin: " + s(row, "margin")
                + "\nLegitimate reference tail rank: " + s(row, "tail_p")
                + "\nThis rank is not the probability of cheating.\n\n"
                + s(row, "evidence_json"));
        }
        for (Finding finding : model.alerts) {
            incidents.add(
                finding.time(), model.playerName(finding), finding.detector(), "Native alert");
            incidentEvidence.add(finding.json);
        }
        if (selected >= 0 && selected < incidents.table.getRowCount())
            incidents.table.setRowSelectionInterval(selected, selected);
    }
    void refresh() {
        serverStatus.setText(app.replay
                ? "OFFLINE REPLAY   /   Saved server evidence"
                : "SERVER  /  " + (app.process.alive() && model.ready ? "Ready" : app.process.state)
                    + "     ENGINE  /  " + model.engineState);
        serverStatus.setForeground(
            app.process.alive() && model.ready ? new Color(112, 207, 177) : Theme.MUTED);
        counts.get("online").setText(Long.toString(model.players.values()
                .stream()
                .filter(p -> p.status.equals("Session open") || p.status.equals("Online"))
                .count()));
        counts.get("alerts").setText(Long.toString(model.suspicious));
        counts.get("bans").setText(Long.toString(
            app.replay ? model.bans : total("totals", "bans") + total("native_totals", "bans")));
        if (lastRecords != model.recordVersion) {
            lastRecords = model.recordVersion;
            refreshIncidents();
        }
        if (console != null && console.isVisible() && lastLog != model.logVersion) {
            lastLog = model.logVersion;
            serverLog.setText(String.join("\n", model.logs));
            serverLog.setCaretPosition(serverLog.getDocument().getLength());
        }
        stop.setEnabled(
            !app.replay && app.process.alive() && !app.process.state.equals("Stopping"));
        footer.setText(!snapshot.error.isEmpty()
                ? "Registry unavailable: " + snapshot.error
                : (app.replay ? "Recorded evidence" : model.behaviorState)
                    + "  ·  Last hour, up to 2,000 windows  ·  "
                    + (model.uiDropped > 0 ? model.uiDropped + " display records dropped"
                                           : "Raw evidence retained on disk"));
        if (closing && !app.process.alive() && !app.process.starting())
            app.close();
    }
    void openConsole() {
        if (console == null) {
            console = new JDialog(this, "Server controls", false);
            console.setSize(1030, 540);
            console.setLocationRelativeTo(this);
            JPanel body = Theme.panel(new BorderLayout(0, 12));
            body.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
            body.add(Theme.scroll(serverLog), BorderLayout.CENTER);
            JPanel bottom = Theme.panel(new BorderLayout(8, 0));
            bottom.add(command, BorderLayout.CENTER);
            JButton send = button("Send command", () -> {
                app.process.command(command.getText());
                command.setText("");
            });
            command.addActionListener(e -> send.doClick());
            send.setEnabled(!app.replay);
            command.setEnabled(!app.replay);
            bottom.add(actions(send, stop), BorderLayout.EAST);
            body.add(bottom, BorderLayout.SOUTH);
            console.setContentPane(body);
        }
        lastLog = -1;
        console.setVisible(true);
        refresh();
    }
    private void requestClose() {
        if (!app.process.alive() && !app.process.starting()) {
            app.close();
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Save the world, stop the server, and close?",
                "Close", JOptionPane.YES_NO_OPTION)
            == JOptionPane.YES_OPTION) {
            closing = true;
            app.process.stop();
        }
    }
    void run(List<String> args, String success) {
        footer.setText("Running " + args.get(0) + "…");
        framework.run(args, result -> message(success + "\n\n" + result), this::message);
    }
    void message(String text) {
        JTextArea area = Theme.area();
        area.setText(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        JScrollPane pane = Theme.scroll(area);
        pane.setPreferredSize(new Dimension(720, 310));
        JOptionPane.showMessageDialog(this, pane, "anticheat", JOptionPane.PLAIN_MESSAGE);
    }
    Map<String, Object> latest(String detection) {
        for (Map<String, Object> row : snapshot.rows("experiments"))
            if (s(row, "detection_id").equals(detection))
                return row;
        return null;
    }
    private long total(String table, String key) {
        return snapshot.rows(table).isEmpty()
            ? 0
            : (long) Json.number(snapshot.rows(table).get(0), key);
    }
    Map<String, Object> experiment(String id) {
        for (Map<String, Object> row : snapshot.rows("experiments"))
            if (s(row, "id").equals(id))
                return row;
        return null;
    }
    private String player(String id) {
        return model.names.getOrDefault(id, shortId(id));
    }
    static String s(Map<String, Object> row, String key) {
        return Json.string(row, key);
    }
    static String shortId(String id) {
        return id.length() > 16 ? id.substring(0, 16) : id;
    }
    static String time(Map<String, Object> row) {
        return new SimpleDateFormat("HH:mm:ss")
            .format(new Date((long) Json.number(row, "created_ms")));
    }
    static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
    static JButton button(String name, Runnable action) {
        JButton b = Theme.button(name);
        b.addActionListener(e -> action.run());
        return b;
    }
    static JPanel actions(JButton... buttons) {
        JPanel panel = Theme.panel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        for (JButton b : buttons)
            panel.add(b);
        return panel;
    }
    static JPanel heading(String title, String subtitle) {
        JPanel panel = Theme.panel(new GridLayout(2, 1, 0, 7));
        panel.add(Theme.label(title, 25, true));
        JLabel sub = Theme.label(subtitle, 13, false);
        sub.setForeground(Theme.MUTED);
        panel.add(sub);
        return panel;
    }
    static JSplitPane split(Component a, Component b, double ratio) {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, a, b);
        split.setBorder(null);
        split.setDividerSize(12);
        split.setResizeWeight(ratio);
        a.setMinimumSize(new Dimension(350, 100));
        b.setMinimumSize(new Dimension(330, 100));
        split.addComponentListener(new ComponentAdapter() {
            boolean first = true;
            public void componentResized(ComponentEvent e) {
                if (first && split.getWidth() > 700) {
                    split.setDividerLocation(ratio);
                    first = false;
                }
            }
        });
        return split;
    }
    static final class Grid {
        final DefaultTableModel data;
        final JTable table;
        Grid(String... headings) {
            data = new DefaultTableModel(headings, 0) {
                public boolean isCellEditable(int r, int c) {
                    return false;
                }
            };
            table = new JTable(data);
            Theme.table(table);
        }
        void clear() {
            data.setRowCount(0);
        }
        void add(Object... row) {
            data.addRow(row);
        }
    }
}
