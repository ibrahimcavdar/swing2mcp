package com.acme.legacy;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Random;

public class ReportPanel extends JPanel {

    private ChartPanel chart;
    private JButton btnRefresh;

    public ReportPanel() {
        setLayout(new BorderLayout());
        chart = new ChartPanel();
        btnRefresh = new JButton("Refresh");
        btnRefresh.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                chart.randomize();
            }
        });
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(btnRefresh);
        add(top, BorderLayout.NORTH);
        add(chart, BorderLayout.CENTER);
    }

    /** Custom-painted component: no child components, text only exists as pixels. */
    static class ChartPanel extends JComponent {
        private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun"};
        private final int[] values = {12, 19, 7, 23, 15, 28};
        private final Random random = new Random();

        ChartPanel() {
            setPreferredSize(new Dimension(400, 300));
        }

        void randomize() {
            for (int i = 0; i < values.length; i++) {
                values[i] = 5 + random.nextInt(30);
            }
            repaint();
        }

        protected void paintComponent(Graphics g) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Color.DARK_GRAY);
            g.drawString("Monthly Sales (units)", 20, 24);
            int barWidth = Math.max(10, (getWidth() - 60) / values.length - 10);
            int baseY = getHeight() - 30;
            for (int i = 0; i < values.length; i++) {
                int h = values[i] * Math.max(1, (baseY - 50) / 35);
                int x = 30 + i * (barWidth + 10);
                g.setColor(new Color(70, 110, 180));
                g.fillRect(x, baseY - h, barWidth, h);
                g.setColor(Color.DARK_GRAY);
                g.drawString(MONTHS[i], x, baseY + 16);
                g.drawString(String.valueOf(values[i]), x, baseY - h - 4);
            }
        }
    }
}
