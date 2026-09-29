/**
 * Theme.java supplies the monitor's shared black/gray Swing styling.
 */

package dev.fox.monitor;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.basic.BasicScrollBarUI;

final class Theme{
    static final Color BACKGROUND = new Color(16, 17, 19);
    static final Color PANEL = new Color(23, 24, 27);
    static final Color RAISED = new Color(30, 32, 35);
    static final Color LINE = new Color(48, 50, 54);
    static final Color WHITE = new Color(237, 238, 240);
    static final Color MUTED = new Color(154, 159, 165);
    static final Color SELECTED = new Color(48, 51, 56);
    static final Font NORMAL = new Font("SansSerif", Font.PLAIN, 13);
    static final Font SMALL = new Font("SansSerif", Font.PLAIN, 11);
    static final Font MONO = new Font("Monospaced", Font.PLAIN, 12);

    static void install(){
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        try{
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        }catch(Exception ignored){}

        for(String key : new String[]{"Panel.background", "OptionPane.background", "Viewport.background"})
            UIManager.put(key, new ColorUIResource(PANEL));

        UIManager.put("OptionPane.messageForeground", new ColorUIResource(WHITE));
        UIManager.put("Label.foreground", new ColorUIResource(WHITE));
        UIManager.put("Button.background", new ColorUIResource(RAISED));
        UIManager.put("Button.foreground", new ColorUIResource(WHITE));
        UIManager.put("TextField.background", new ColorUIResource(RAISED));
        UIManager.put("TextField.foreground", new ColorUIResource(WHITE));
        UIManager.put("TextField.caretForeground", new ColorUIResource(WHITE));
        UIManager.put("SplitPane.background", new ColorUIResource(BACKGROUND));
        UIManager.put("SplitPaneDivider.draggingColor", new ColorUIResource(LINE));
        UIManager.put("ToolTip.background", new ColorUIResource(RAISED));
        UIManager.put("ToolTip.foreground", new ColorUIResource(WHITE));
    }

    static JPanel panel(java.awt.LayoutManager layout){
        JPanel panel = new JPanel(layout);
        panel.setBackground(BACKGROUND);
        return panel;
    }

    static JLabel label(String text, int size, boolean bold){
        JLabel label = new JLabel(text);
        label.putClientProperty("html.disable", Boolean.TRUE);
        label.setFont(NORMAL.deriveFont(bold ? Font.BOLD : Font.PLAIN, (float)size));
        label.setForeground(WHITE);
        return label;
    }

    static JButton button(String text){
        JButton button = new JButton(text);
        button.setFont(NORMAL);
        button.setForeground(WHITE);
        button.setBackground(RAISED);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(LINE),
            BorderFactory.createEmptyBorder(8, 13, 8, 13)
        ));
        return button;
    }

    static JTextField field(){
        JTextField field = new JTextField();
        field.setFont(NORMAL);
        field.setBackground(PANEL);
        field.setForeground(WHITE);
        field.setCaretColor(WHITE);
        field.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(LINE),
            BorderFactory.createEmptyBorder(7, 9, 7, 9)
        ));
        return field;
    }

    static JTextArea area(){
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(MONO);
        area.setBackground(PANEL);
        area.setForeground(WHITE);
        area.setCaretColor(WHITE);
        area.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        area.setTabSize(4);
        return area;
    }

    static JScrollPane scroll(JComponent child){
        JScrollPane pane = new JScrollPane(child);
        pane.setBorder(BorderFactory.createLineBorder(LINE));
        pane.getViewport().setBackground(PANEL);

        for(javax.swing.JScrollBar bar : new javax.swing.JScrollBar[]{
            pane.getVerticalScrollBar(), pane.getHorizontalScrollBar()
        }){
            bar.setUnitIncrement(24);
            bar.setPreferredSize(new Dimension(9, 9));
            bar.setUI(new BasicScrollBarUI(){
                @Override
                protected void configureScrollBarColors(){
                    thumbColor = SELECTED;
                    trackColor = PANEL;
                }

                @Override
                protected JButton createDecreaseButton(int orientation){
                    return emptyButton();
                }

                @Override
                protected JButton createIncreaseButton(int orientation){
                    return emptyButton();
                }

                private JButton emptyButton(){
                    JButton button = new JButton();
                    button.setPreferredSize(new Dimension(0, 0));
                    return button;
                }
            });
        }

        return pane;
    }

    static void table(JTable table){
        table.setFont(NORMAL);
        table.setRowHeight(39);
        table.setBackground(PANEL);
        table.setForeground(WHITE);
        table.setSelectionBackground(SELECTED);
        table.setSelectionForeground(WHITE);
        table.setGridColor(LINE);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setBackground(RAISED);
        table.getTableHeader().setForeground(MUTED);
        table.getTableHeader().setFont(SMALL);
        table.getTableHeader().setPreferredSize(new Dimension(0, 34));
        table.getTableHeader().setDefaultRenderer(new javax.swing.table.DefaultTableCellRenderer(){
            @Override
            public java.awt.Component getTableCellRendererComponent(
                JTable owner, Object value, boolean selected, boolean focus, int row, int column
            ){
                super.getTableCellRendererComponent(owner, value, selected, focus, row, column);
                putClientProperty("html.disable", Boolean.TRUE);
                setBackground(RAISED);
                setForeground(MUTED);
                setFont(SMALL);
                setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));
                return this;
            }
        });
        table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer(){
            @Override
            public java.awt.Component getTableCellRendererComponent(
                JTable owner, Object value, boolean selected, boolean focus, int row, int column
            ){
                putClientProperty("html.disable", Boolean.TRUE);
                super.getTableCellRendererComponent(owner, value, selected, focus, row, column);
                setBackground(selected ? SELECTED : PANEL);
                setForeground(column == 0 ? MUTED : WHITE);
                setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));
                return this;
            }
        });
    }
}
