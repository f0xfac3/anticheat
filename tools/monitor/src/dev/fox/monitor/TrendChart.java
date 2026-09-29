package dev.fox.monitor;

import java.awt.*;
import java.awt.geom.Path2D;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import javax.swing.JPanel;

/** Draws measured series only. Missing windows break the line. */
final class TrendChart extends JPanel {
    static final class Point {
        final long time;
        final double value;
        Point(long time, double value) {
            this.time = time;
            this.value = value;
        }
    }
    private final String title, unit;
    private final boolean zero;
    private Map<String, List<Point>> series = Collections.emptyMap();
    TrendChart(String title, String unit, boolean zero) {
        this.title = title;
        this.unit = unit;
        this.zero = zero;
        setBackground(Theme.PANEL);
        setPreferredSize(new Dimension(460, 245));
        setBorder(javax.swing.BorderFactory.createLineBorder(Theme.LINE));
    }
    void data(Map<String, List<Point>> values) {
        series = values;
        repaint();
    }
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(Theme.NORMAL.deriveFont(Font.BOLD));
        g.setColor(Theme.WHITE);
        g.drawString(title, 20, 29);
        g.setFont(Theme.SMALL);
        g.setColor(Theme.MUTED);
        g.drawString(unit, 20, 49);
        long start = Long.MAX_VALUE, end = Long.MIN_VALUE;
        double low = zero ? 0 : Double.POSITIVE_INFINITY, high = 0;
        for (List<Point> points : series.values())
            for (Point p : points) {
                start = Math.min(start, p.time);
                end = Math.max(end, p.time);
                low = Math.min(low, p.value);
                high = Math.max(high, p.value);
            }
        if (start == Long.MAX_VALUE) {
            g.drawString("Waiting for complete 30-second windows", 20, getHeight() / 2 + 12);
            g.dispose();
            return;
        }
        low = Math.min(low, 0);
        high = Math.max(high, zero ? 22 : 1);
        if (high - low < 1)
            high = low + 1;
        high += (high - low) * .12;
        start = Math.min(start, end - 120000);
        int left = 53, top = 76, right = getWidth() - 22, bottom = getHeight() - 47;
        for (int i = 0; i <= 3; i++) {
            int y = top + (bottom - top) * i / 3;
            g.setColor(Theme.LINE);
            g.drawLine(left, y, right, y);
            g.setColor(Theme.MUTED);
            g.drawString(
                String.format(Locale.ROOT, "%.1f", high - (high - low) * i / 3), 12, y + 4);
        }
        SimpleDateFormat time = new SimpleDateFormat("HH:mm");
        if (!zero) {
            int baseline = bottom - (int) ((bottom - top) * (0 - low) / (high - low));
            g.setColor(Theme.MUTED);
            g.setStroke(new BasicStroke(
                1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] {4, 4}, 0));
            g.drawLine(left, baseline, right, baseline);
            g.drawString("threshold", right - 57, baseline - 6);
        }
        g.drawString(time.format(new Date(start)), left, bottom + 21);
        g.drawString(time.format(new Date(end)), right - 34, bottom + 21);
        Color[] colors = {new Color(113, 184, 245), new Color(240, 119, 130),
            new Color(112, 207, 177), new Color(209, 177, 238)};
        int n = 0;
        for (Map.Entry<String, List<Point>> entry : series.entrySet()) {
            Color color = colors[n % colors.length];
            g.setColor(color);
            g.setStroke(new BasicStroke(2f));
            Path2D path = new Path2D.Double();
            long previous = 0;
            List<Point> sorted = new ArrayList<>(entry.getValue());
            sorted.sort(Comparator.comparingLong(p -> p.time));
            for (Point p : sorted) {
                double x = left + (right - left) * (p.time - start) / (double) (end - start),
                       y = bottom - (bottom - top) * (p.value - low) / (high - low);
                if (previous == 0 || p.time - previous > 45000)
                    path.moveTo(x, y);
                else
                    path.lineTo(x, y);
                g.fillOval((int) x - 2, (int) y - 2, 4, 4);
                previous = p.time;
            }
            g.draw(path);
            if (n < 3) {
                String name = entry.getKey();
                if (name.length() > 18)
                    name = name.substring(0, 18);
                g.setFont(Theme.SMALL);
                g.drawString(name, 20 + n * 155, getHeight() - 7);
            }
            n++;
        }
        g.dispose();
    }
}
