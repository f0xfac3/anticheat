package dev.fox.monitor;

import java.awt.*;
import java.util.*;
import javax.swing.JPanel;

/** Observed held-out ranges, not inferred confidence intervals. */
final class FeatureComparison extends JPanel {
    private String feature = "";
    private Map<String, Object> legitimate, positive;
    FeatureComparison() {
        setBackground(Theme.PANEL);
        setPreferredSize(new Dimension(500, 265));
        setBorder(javax.swing.BorderFactory.createLineBorder(Theme.LINE));
    }
    @SuppressWarnings("unchecked")
    void data(Object summary) {
        legitimate = positive = null;
        feature = "";
        if(summary instanceof Map&&!((Map<?,?>)summary).isEmpty()){
            Map.Entry<?,?> first=((Map<?,?>)summary).entrySet().iterator().next();
            feature = first.getKey().toString();
            Map<String, Object> values = (Map<String, Object>) first.getValue();
            legitimate = (Map<String, Object>) values.get("legitimate");
            positive = (Map<String, Object>) values.get("cheat_or_automation");
        }
        repaint();
    }
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(Theme.NORMAL.deriveFont(Font.BOLD));
        g.setColor(Theme.WHITE);
        g.drawString("Held-out comparison", 20, 30);
        g.setFont(Theme.SMALL);
        g.setColor(Theme.MUTED);
        if (legitimate == null || positive == null) {
            g.drawString("Select a completed experiment", 20, 65);
            g.dispose();
            return;
        }
        g.drawString(feature + " · raw 30-second measurements", 20, 54);
        double low = Math.min(Json.number(legitimate, "minimum"), Json.number(positive, "minimum"));
        double high =
            Math.max(Json.number(legitimate, "maximum"), Json.number(positive, "maximum"));
        double pad = Math.max((high - low) * .15, .1);
        low -= pad;
        high += pad;
        int left = 35, right = getWidth() - 35;
        Map<?, ?>[] rows = {legitimate, positive};
        Color[] colors = {new Color(113, 184, 245), new Color(240, 119, 130)};
        String[] labels = {"Declared legitimate", "Declared cheat / automation"};
        for (int i = 0; i < 2; i++) {
            @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) rows[i];
            int y = 105 + i * 77;
            g.setColor(Theme.WHITE);
            g.drawString(
                labels[i] + " · " + (int) Json.number(row, "windows") + " windows", left, y - 15);
            double min = Json.number(row, "minimum"), max = Json.number(row, "maximum"),
                   median = Json.number(row, "median");
            int x1 = left + (int) ((right - left) * (min - low) / (high - low)),
                x2 = left + (int) ((right - left) * (max - low) / (high - low)),
                xm = left + (int) ((right - left) * (median - low) / (high - low));
            g.setColor(Theme.LINE);
            g.drawLine(left, y, right, y);
            g.setColor(colors[i]);
            g.setStroke(new BasicStroke(5));
            g.drawLine(x1, y, x2, y);
            g.fillOval(xm - 5, y - 5, 10, 10);
            g.setFont(Theme.MONO);
            g.drawString(String.format(Locale.ROOT, "%.3f  /  %.3f  /  %.3f", min, median, max),
                left, y + 26);
            g.setFont(Theme.SMALL);
        }
        g.setColor(Theme.MUTED);
        g.drawString("Minimum / median / maximum · not confidence bounds", 20, getHeight() - 17);
        g.dispose();
    }
}
