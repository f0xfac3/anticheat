/**
 * MonitorWindow.java displays alerts, activity, players, and the full server console.
 * The interface reads Finding fields; detection decisions remain in C++.
 */

package dev.fox.monitor;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;

final class MonitorWindow extends JFrame{
    final MonitorApp app;
    final MonitorModel model;
    final JLabel serverStatus = Theme.label("Server  |  starting", 12, false);
    final JLabel engineStatus = Theme.label("Engine  |  waiting", 12, false);
    final JLabel alertCount = Theme.label("0", 32, false);
    final JLabel playerCount = Theme.label("0", 32, false);
    final JLabel banCount = Theme.label("0", 32, false);
    final JLabel recordCount = Theme.label("0", 32, false);
    final JLabel footer = Theme.label("Waiting for observations", 11, false);
    final JLabel title = Theme.label("Alerts", 22, false);
    final JLabel subtitle = Theme.label("Only findings marked suspicious by the C++ engine.", 12, false);
    final JLabel selectionTitle = Theme.label("No finding selected", 15, true);
    final JLabel selectionSub = Theme.label("Select an alert to inspect its evidence.", 11, false);
    final JTextField search = Theme.field();
    final JTextArea detail = Theme.area();
    final JTextArea serverLog = Theme.area();
    final JTextField command = Theme.field();
    final CardLayout cards = new CardLayout();
    final JPanel pages = Theme.panel(cards);
    String view = "Alerts";
    List<String[]> responseRows = new ArrayList<>();
    List<Finding> rows = new ArrayList<>();
    List<MonitorModel.Player> playerRows = new ArrayList<>();
    final RecordTable tableModel = new RecordTable();
    final JTable table = new JTable(tableModel);
    final JButton stop = Theme.button("Stop server");
    final JButton forceStop = Theme.button("Force stop");
    final JButton copy = Theme.button("Copy JSON");
    final List<JButton> navigation = new ArrayList<>();
    final JToggleButton follow = new JToggleButton("Follow log", true);
    Finding selected;
    private long lastRecordVersion = -1;
    private long lastLogVersion = -1;
    private String lastQuery = "";
    private String lastLogQuery = "";
    private boolean closing;
    private long stoppingSince;

    MonitorWindow(MonitorApp app){
        super("anticheat / " + (app.replay ? "replay" : "security console"));
        this.app = app;
        model = app.model;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1060, 640));
        setSize(1320, 820);
        setLocationRelativeTo(null);
        getContentPane().setBackground(Theme.BACKGROUND);
        setLayout(new BorderLayout());
        add(sidebar(), BorderLayout.WEST);
        add(content(), BorderLayout.CENTER);

        addWindowListener(new WindowAdapter(){
            @Override
            public void windowClosing(WindowEvent event){
                requestClose();
            }
        });

        search.getDocument().addDocumentListener(new DocumentListener(){
            public void insertUpdate(DocumentEvent event){
                refreshRows(true);
                updateLog(true);
            }

            public void removeUpdate(DocumentEvent event){
                refreshRows(true);
                updateLog(true);
            }

            public void changedUpdate(DocumentEvent event){
                refreshRows(true);
                updateLog(true);
            }
        });

        stop.addActionListener(event->{
            if(app.process.alive() || app.process.starting()){
                app.process.stop();
            }else if(!app.replay){
                message("Close this monitor and reopen start.bat for a fresh server run.");
            }
        });
        forceStop.addActionListener(event->{
            int result = JOptionPane.showConfirmDialog(
                this,
                "Force stop skips world saving and can lose data. Continue?",
                "Force stop",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            );

            if(result == JOptionPane.YES_OPTION)
                app.process.forceStop();
        });
        forceStop.setVisible(false);
        copy.addActionListener(event->{
            if(selected != null){
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new StringSelection(selected.json), null
                );
            }
        });
        copy.setEnabled(false);
    }

    private JPanel sidebar(){
        JPanel bar = Theme.panel(new BorderLayout());
        bar.setPreferredSize(new Dimension(182, 0));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.LINE));
        JPanel stack = Theme.panel(null);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setBorder(BorderFactory.createEmptyBorder(28, 20, 20, 18));
        JLabel name = Theme.label("anticheat", 21, true);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        stack.add(name);
        stack.add(Box.createVerticalStrut(8));
        JLabel tag = Theme.label(app.replay ? "REPLAY" : "LIVE SECURITY", 12, false);
        tag.setForeground(Theme.MUTED);
        stack.add(tag);
        stack.add(Box.createVerticalStrut(42));

        for(String nameText : new String[]{"Alerts", "Responses", "Activity", "Players", "Server log"}){
            JButton button = Theme.button(nameText);
            button.setHorizontalAlignment(JButton.LEFT);
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.setMaximumSize(new Dimension(150, 40));
            button.setBackground(nameText.equals(view) ? Theme.SELECTED : Theme.BACKGROUND);
            button.addActionListener(event->selectView(nameText));
            navigation.add(button);
            stack.add(button);
            stack.add(Box.createVerticalStrut(8));
        }

        bar.add(stack, BorderLayout.NORTH);
        JPanel bottom = Theme.panel(null);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(20, 20, 26, 12));
        JLabel checks = Theme.label("DETECTION SCOPE", 10, true);
        checks.setForeground(Theme.MUTED);
        bottom.add(checks);
        bottom.add(Box.createVerticalStrut(16));
        String[] names = {"Timer - budget", "Reach", "Movement"};
        String[] keys = {"timer", "reach", "movement"};

        for(int i = 0; i < names.length; ++i){
            bottom.add(Theme.label(names[i], 12, true));
            JLabel status = Theme.label(
                app.replay ? "See recorded findings" : app.config.checkSetting(keys[i]),
                10,
                false
            );
            status.setForeground(Theme.MUTED);
            bottom.add(status);
            bottom.add(Box.createVerticalStrut(12));
        }

        bottom.add(Box.createVerticalStrut(27));
        JLabel policy = Theme.label("EVIDENCE / RESPONSE", 10, true);
        policy.setForeground(Theme.MUTED);
        bottom.add(policy);
        bar.add(bottom, BorderLayout.SOUTH);
        return bar;
    }

    private JPanel content(){
        JPanel main = Theme.panel(new BorderLayout(0, 21));
        main.setBorder(BorderFactory.createEmptyBorder(22, 26, 16, 26));
        JPanel top = Theme.panel(new BorderLayout(0, 20));
        JPanel status = Theme.panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        serverStatus.setForeground(Theme.MUTED);
        engineStatus.setForeground(Theme.MUTED);
        status.add(serverStatus);
        status.add(Box.createHorizontalStrut(28));
        status.add(engineStatus);

        JPanel toolbar = Theme.panel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton open = Theme.button("Open saved log");
        open.addActionListener(event->openReplay());
        JButton logs = Theme.button("Log files");
        logs.addActionListener(event->app.openLogs());
        toolbar.add(open);
        toolbar.add(logs);
        toolbar.add(forceStop);
        toolbar.add(stop);
        stop.setEnabled(!app.replay);

        JPanel header = Theme.panel(new BorderLayout());
        header.add(status, BorderLayout.CENTER);
        header.add(toolbar, BorderLayout.EAST);
        top.add(header, BorderLayout.NORTH);

        JPanel stats = Theme.panel(new GridLayout(1, 4, 14, 0));
        stats.add(stat("SUSPICIOUS FINDINGS", alertCount, "Reported by the engine"));
        stats.add(stat("PLAYERS WITH ALERTS", playerCount, "Not a cheating probability"));
        stats.add(stat("FINDINGS RECEIVED", recordCount, "Native evidence records"));
        stats.add(stat("CONFIRMED BANS", banCount, "Server-reported responses"));
        top.add(stats, BorderLayout.CENTER);
        main.add(top, BorderLayout.NORTH);

        JPanel middle = Theme.panel(new BorderLayout(0, 16));
        JPanel heading = Theme.panel(new BorderLayout());
        JPanel text = Theme.panel(new GridLayout(2, 1, 0, 7));
        subtitle.setForeground(Theme.MUTED);
        text.add(title);
        text.add(subtitle);
        heading.add(text, BorderLayout.CENTER);
        search.setPreferredSize(new Dimension(225, 34));
        search.setToolTipText("Filter player, check, message, or evidence");
        JPanel filter = Theme.panel(new BorderLayout(0, 4));
        JLabel filterLabel = Theme.label("FILTER", 10, false);
        filterLabel.setForeground(Theme.MUTED);
        filter.add(filterLabel, BorderLayout.NORTH);
        filter.add(search, BorderLayout.CENTER);
        heading.add(filter, BorderLayout.EAST);
        middle.add(heading, BorderLayout.NORTH);
        Theme.table(table);
        table.getSelectionModel().addListSelectionListener(event->{
            if(!event.getValueIsAdjusting())
                showSelection();
        });

        JPanel evidence = Theme.panel(new BorderLayout(0, 10));
        evidence.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0));
        JPanel evidenceHeading = Theme.panel(new GridLayout(2, 1, 0, 7));
        selectionSub.setForeground(Theme.MUTED);
        evidenceHeading.add(selectionTitle);
        evidenceHeading.add(selectionSub);
        evidence.add(evidenceHeading, BorderLayout.NORTH);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setFont(Theme.NORMAL);
        evidence.add(Theme.scroll(detail), BorderLayout.CENTER);
        evidence.add(copy, BorderLayout.SOUTH);
        evidence.setMinimumSize(new Dimension(290, 160));

        JSplitPane split = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            Theme.scroll(table),
            evidence
        );
        split.setBorder(null);
        split.setDividerSize(3);
        split.setResizeWeight(0.72);
        split.setDividerLocation(690);
        pages.add(split, "Records");
        pages.add(logPanel(), "Logs");
        middle.add(pages, BorderLayout.CENTER);
        main.add(middle, BorderLayout.CENTER);
        footer.setForeground(Theme.MUTED);
        main.add(footer, BorderLayout.SOUTH);
        resizeColumns();
        return main;
    }

    private JPanel stat(String name, JLabel value, String note){
        JPanel card = Theme.panel(new BorderLayout(0, 9));
        card.setBackground(Theme.PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.LINE),
            BorderFactory.createEmptyBorder(16, 19, 16, 19)
        ));
        JLabel heading = Theme.label(name, 10, true);
        heading.setForeground(Theme.MUTED);
        JLabel foot = Theme.label(note, 11, false);
        foot.setForeground(Theme.MUTED);
        card.add(heading, BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        card.add(foot, BorderLayout.SOUTH);
        return card;
    }

    private JPanel logPanel(){
        JPanel panel = Theme.panel(new BorderLayout(0, 12));
        panel.add(Theme.scroll(serverLog), BorderLayout.CENTER);
        JPanel input = Theme.panel(new BorderLayout(8, 0));
        command.setToolTipText("Spigot server command, for example list or stop");
        JButton send = Theme.button("Send command");
        Runnable submit = ()->{
            app.process.command(command.getText());
            command.setText("");
        };
        send.addActionListener(event->submit.run());
        command.addActionListener(event->submit.run());
        send.setEnabled(!app.replay);
        command.setEnabled(!app.replay);
        follow.setBackground(Theme.RAISED);
        follow.setForeground(Theme.WHITE);
        follow.setFont(Theme.SMALL);
        follow.setFocusPainted(false);
        input.add(follow, BorderLayout.WEST);
        input.add(command, BorderLayout.CENTER);
        input.add(send, BorderLayout.EAST);
        panel.add(input, BorderLayout.SOUTH);
        return panel;
    }

    void selectView(String name){
        view = name;
        selected = null;
        title.setText(name);

        if(name.equals("Alerts"))
            subtitle.setText("Only findings marked suspicious by the C++ engine.");
        else if(name.equals("Responses"))
            subtitle.setText("Observed detections and confirmed server bans. Select a response for its evidence.");
        else if(name.equals("Activity"))
            subtitle.setText("Starts, finishes, skips, resets, and alerts. These are Findings, not raw packets.");
        else if(name.equals("Players"))
            subtitle.setText("Players seen in this run. No alert does not establish legitimate play.");
        else
            subtitle.setText("Server output, errors, and startup messages. Full console capture is saved to disk.");

        for(JButton button : navigation)
            button.setBackground(button.getText().equals(name) ? Theme.SELECTED : Theme.BACKGROUND);

        cards.show(pages, name.equals("Server log") ? "Logs" : "Records");
        tableModel.fireTableStructureChanged();
        resizeColumns();
        refreshRows(true);
        updateLog(true);
    }

    private void resizeColumns(){
        int[] widths = view.equals("Players")
            ? new int[]{170, 100, 80, 80, 110}
            : new int[]{80, 160, 110, 285};

        for(int i = 0; i < widths.length; ++i)
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    }

    void refresh(){
        serverStatus.setText(app.replay ? "REPLAY  |  saved log" :
            "Server  |  " + (app.process.alive() && model.ready && !app.process.state.equals("Stopping")
                ? "Ready" : app.process.state));
        engineStatus.setText(app.replay ? "Offline analysis" : "Engine  |  " + model.engineState);
        alertCount.setText(Long.toString(model.suspicious));
        playerCount.setText(Integer.toString(model.flaggedPlayers()));
        recordCount.setText(Long.toString(model.received));
        banCount.setText(Long.toString(model.bans));
        boolean stopping = app.process.state.equals("Stopping");
        stop.setEnabled(!app.replay && !stopping && (app.process.alive() || app.process.starting()));
        stop.setText(stopping ? "Stopping..." : "Stop server");

        if(stopping && stoppingSince == 0)
            stoppingSince = System.nanoTime();

        forceStop.setVisible(app.process.alive() && stopping &&
            System.nanoTime() - stoppingSince > 15_000_000_000L);
        String tail = "View limits: 2,000 alerts / 5,000 activity records";

        if(model.uiDropped > 0 || model.malformed > 0)
            tail = "UI records skipped: " + model.uiDropped + " | Parse failures: " + model.malformed;

        footer.setText((app.replay ? "Saved log" : model.policy) + "   .   " + tail
            + (model.warnings > 0 ? "   .   " + model.warnings + " server warnings/errors" : ""));
        refreshRows(false);
        updateLog(false);

        if(closing && !app.process.alive() && !app.process.starting())
            app.close();
    }

    private void refreshRows(boolean force){
        String query = search.getText();

        if(!force && lastRecordVersion == model.recordVersion && query.equals(lastQuery)
            && !view.equals("Players")){
            return;
        }

        Finding previous = selected;
        rows = model.matching(view.equals("Alerts"), query);
        responseRows = new ArrayList<>();
        for(String[] response : model.responses)
            if(String.join(" ", response).toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT)))
                responseRows.add(response);
        playerRows = new ArrayList<>();

        for(MonitorModel.Player player : model.players.values()){
            String text = (player.uuid + " " + player.name).toLowerCase(java.util.Locale.ROOT);

            if(text.contains(query.toLowerCase(java.util.Locale.ROOT)))
                playerRows.add(player);
        }

        tableModel.fireTableDataChanged();
        lastRecordVersion = model.recordVersion;
        lastQuery = query;
        int index = previous == null ? -1 : rows.indexOf(previous);

        if(view.equals("Responses")){
            if(!responseRows.isEmpty()) table.setRowSelectionInterval(0, 0);
            else showSelection();
        }else if(!view.equals("Players") && !rows.isEmpty()){
            table.setRowSelectionInterval(index < 0 ? 0 : index, index < 0 ? 0 : index);
        }else if(view.equals("Players") && !playerRows.isEmpty()){
            table.setRowSelectionInterval(0, 0);
        }else{
            showSelection();
        }
    }

    private void showSelection(){
        int row = table.getSelectedRow();

        if(view.equals("Responses")) {
            selected = null;
            copy.setEnabled(false);
            if(row < 0 || row >= responseRows.size()) { clearSelection(); return; }
            String[] response = responseRows.get(row);
            selectionTitle.setText(response[2] + " / " + response[1]);
            selectionSub.setText(response[0] + " / server event");
            StringBuilder evidence = new StringBuilder("SERVER RECORD\n" + response[4] + "\n\nEVIDENCE REFERENCE\n" + response[3]);
            if(response[2].equals("BANNED")) {
                String[] id = response[3].split(":");
                for(Finding finding : model.activity) {
                    if(id.length >= 3 && finding.field("session").equals(id[id.length-2]) &&
                       finding.field("event").equals(id[id.length-1]) && model.playerName(finding).equals(response[1])) {
                        evidence.append("\n\nNATIVE EVIDENCE\n").append(finding.json);
                        selected = finding;
                        copy.setEnabled(true);
                        break;
                    }
                }
                evidence.append("\n\nThe plugin reports a completed ban after its durable evidence gate. Full decision history remains in SQLite.");
            }
            detail.setText(evidence.toString());
            detail.setCaretPosition(0);
            return;
        }
        if(view.equals("Players")){
            selected = null;
            copy.setEnabled(false);

            if(row < 0 || row >= playerRows.size()){
                clearSelection();
                return;
            }

            MonitorModel.Player player = playerRows.get(row);
            selectionTitle.setText(player.name == null ? "Player" : player.name);
            selectionSub.setText(player.alerts > 0 ? "Suspicious findings observed" : "No alerts observed");
            detail.setText(
                "PLAYER UUID\n" + player.uuid + "\n\n"
                + "STATUS\n" + player.status + "\n\n"
                + "SUSPICIOUS FINDINGS\n" + player.alerts + "\n\n"
                + "FINDINGS RECEIVED\n" + player.records + "\n\n"
                + "LAST FINDING\n" + player.last + "\n\n"
                + "These counts describe the captured session, not a probability of cheating."
            );
            detail.setCaretPosition(0);
            return;
        }

        if(row < 0 || row >= rows.size()){
            clearSelection();
            return;
        }

        Finding finding = rows.get(row);

        if(finding == selected)
            return;

        selected = finding;
        copy.setEnabled(true);
        selectionTitle.setText(finding.detector() + "  /  " + finding.field("level").toUpperCase(java.util.Locale.ROOT));
        selectionSub.setText(finding.time() + "   .   " + model.playerName(finding));
        StringBuilder text = new StringBuilder();
        java.util.Set<String> shown = new java.util.HashSet<>();

        // Put the measurements first. Packet references remain available below.
        for(String key : new String[]{
            "lead_ms", "counted_packets", "elapsed_ms", "score_pps", "tail_p", "eligible", "observed_ms", "expected_ms", "sus_threshold_ms", "threshold_ms",
            "samples", "block", "tool", "position", "server_break_outcome"
        }){
            String value = finding.evidence.get(key);

            if(value != null){
                appendEvidence(text, key, value);
                shown.add(key);
            }
        }

        text.append("FINDING\n").append(finding.field("message")).append("\n\n");

        for(java.util.Map.Entry<String, String> field : finding.evidence.entrySet()){
            if(!shown.contains(field.getKey()))
                appendEvidence(text, field.getKey(), field.getValue());
        }

        text.append("SOURCE\nCheck: ").append(finding.field("check"))
            .append("\nSession: ").append(finding.field("session"))
            .append("\nEvent: ").append(finding.field("event"))
            .append("\nPlayer: ").append(finding.field("player"))
            .append("\n\nEnforcement outcomes appear in Responses. This view does not recompute the detector's verdict.");
        detail.setText(text.toString());
        detail.setCaretPosition(0);
    }

    private void appendEvidence(StringBuilder text, String key, String value){
        String label = key.toUpperCase(java.util.Locale.ROOT).replace('_', ' ');

        if(key.equals("sus_threshold_ms"))
            label = "SUSPICIOUS THRESHOLD (MS)";

        text.append(label).append('\n').append(value).append("\n\n");
    }

    private void clearSelection(){
        selected = null;
        copy.setEnabled(false);
        selectionTitle.setText("No finding selected");
        selectionSub.setText("Select a row to inspect its evidence.");
        detail.setText(view.equals("Alerts")
            ? "No suspicious findings in this view.\n\nOther native findings remain in Activity. Server messages and errors remain in Server log."
            : "No records in this view yet.");
    }

    private void updateLog(boolean force){
        if(!view.equals("Server log"))
            return;

        if(!force && lastLogVersion == model.logVersion && search.getText().equals(lastLogQuery))
            return;

        StringBuilder text = new StringBuilder();
        String query = search.getText().toLowerCase(java.util.Locale.ROOT);

        for(String line : model.logs){
            if(line.toLowerCase(java.util.Locale.ROOT).contains(query))
                text.append(line).append('\n');
        }

        int caret = serverLog.getCaretPosition();
        serverLog.setText(text.toString());
        serverLog.setCaretPosition(follow.isSelected() ? serverLog.getDocument().getLength()
            : Math.min(caret, serverLog.getDocument().getLength()));
        lastLogVersion = model.logVersion;
        lastLogQuery = search.getText();
    }

    private void openReplay(){
        JFileChooser chooser = new JFileChooser(app.config.home.resolve("logs").toFile());
        chooser.setDialogTitle("Open a saved server log or Finding JSONL file");

        if(chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            app.replay(chooser.getSelectedFile().toPath());
    }

    private void requestClose(){
        if(!app.process.alive() && !app.process.starting()){
            app.close();
            return;
        }

        int answer = JOptionPane.showConfirmDialog(
            this,
            "Stop the server normally, save the world, and close the monitor?",
            "Stop server",
            JOptionPane.YES_NO_OPTION
        );

        if(answer == JOptionPane.YES_OPTION){
            closing = true;
            app.process.stop();
            footer.setText("Waiting for Spigot to save and stop...");
        }
    }

    void message(String text){
        JOptionPane.showMessageDialog(this, text, "anticheat monitor", JOptionPane.INFORMATION_MESSAGE);
    }

    final class RecordTable extends AbstractTableModel{
        public int getRowCount(){
            return view.equals("Responses") ? responseRows.size() : view.equals("Players") ? playerRows.size() : rows.size();
        }

        public int getColumnCount(){
            return view.equals("Players") ? 5 : 4;
        }

        public String getColumnName(int column){
            if(view.equals("Responses")) return new String[]{"TIME", "PLAYER", "ACTION", "EVIDENCE"}[column];
            return view.equals("Players")
                ? new String[]{"PLAYER", "STATE", "ALERTS", "RECORDS", "LAST SEEN"}[column]
                : new String[]{"TIME", "PLAYER", "CHECK", "SUMMARY"}[column];
        }

        public Object getValueAt(int row, int column){
            if(view.equals("Responses")) return responseRows.get(row)[column];
            if(view.equals("Players")){
                MonitorModel.Player player = playerRows.get(row);
                return new Object[]{
                    player.name == null ? player.uuid : player.name,
                    player.status, player.alerts, player.records, player.last
                }[column];
            }

            Finding finding = rows.get(row);
            return new String[]{
                finding.time(), model.playerName(finding), finding.detector(), finding.summary()
            }[column];
        }
    }
}
