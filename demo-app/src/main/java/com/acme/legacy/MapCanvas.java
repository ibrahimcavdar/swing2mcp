package com.acme.legacy;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

/**
 * A custom-painted map, like the GIS views of many legacy applications: it has no child
 * components and no text a snapshot could read. Dragging with the mouse pans it, the wheel zooms.
 */
public class MapCanvas extends JComponent {

    private static final Object[][] PLACES = {
            {"Old Town", 0, 0}, {"Harbor", -320, 140}, {"Airport", 420, -220}, {"Stadium", 180, 160},
            {"University", -150, -230}, {"Station", 60, -90}, {"Lighthouse", -560, 260}, {"Factory", 640, 120}};

    private double centerX;
    private double centerY;
    private double zoom = 1.0;
    private Point dragStart;
    private double startX;
    private double startY;
    private Runnable onChange;

    public MapCanvas() {
        setPreferredSize(new Dimension(420, 360));
        setOpaque(true);
        MouseAdapter mouse = new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                dragStart = e.getPoint();
                startX = centerX;
                startY = centerY;
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }

            public void mouseDragged(MouseEvent e) {
                if (dragStart == null) {
                    return;
                }
                centerX = startX - (e.getX() - dragStart.x) / zoom;
                centerY = startY - (e.getY() - dragStart.y) / zoom;
                changed();
            }

            public void mouseReleased(MouseEvent e) {
                dragStart = null;
                setCursor(Cursor.getDefaultCursor());
            }

            public void mouseWheelMoved(MouseWheelEvent e) {
                setZoom(zoom * (e.getWheelRotation() < 0 ? 1.25 : 0.8));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    public void setZoom(double zoom) {
        this.zoom = Math.max(0.25, Math.min(4.0, zoom));
        changed();
    }

    public double getZoom() {
        return zoom;
    }

    public String describeView() {
        return "View center: " + Math.round(centerX) + ", " + Math.round(centerY) + " | zoom " + Math.round(zoom * 100) + "%";
    }

    private void changed() {
        repaint();
        if (onChange != null) {
            onChange.run();
        }
    }

    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        g2.setColor(new Color(0xDCEBD5));
        g2.fillRect(0, 0, w, h);
        // grid every 100 map units
        g2.setColor(new Color(0xB5C9AC));
        g2.setFont(getFont().deriveFont(Font.PLAIN, 10f));
        double left = centerX - w / 2.0 / zoom;
        double top = centerY - h / 2.0 / zoom;
        for (long gx = (long) Math.floor(left / 100) * 100; screenX(gx) < w; gx += 100) {
            int x = screenX(gx);
            g2.drawLine(x, 0, x, h);
            g2.drawString(String.valueOf(gx), x + 2, 10);
        }
        for (long gy = (long) Math.floor(top / 100) * 100; screenY(gy) < h; gy += 100) {
            int y = screenY(gy);
            g2.drawLine(0, y, w, y);
            g2.drawString(String.valueOf(gy), 2, y - 2);
        }
        // a river
        g2.setColor(new Color(0x7FB2E5));
        g2.setStroke(new BasicStroke((float) Math.max(2, 14 * zoom)));
        g2.drawLine(screenX(-700), screenY(320), screenX(700), screenY(-260));
        g2.setStroke(new BasicStroke(1));
        g2.setFont(getFont().deriveFont(Font.BOLD, 12f));
        for (Object[] place : PLACES) {
            int x = screenX(((Integer) place[1]).doubleValue());
            int y = screenY(((Integer) place[2]).doubleValue());
            g2.setColor(new Color(0xC0392B));
            g2.fillOval(x - 5, y - 5, 10, 10);
            g2.setColor(Color.DARK_GRAY);
            g2.drawString((String) place[0], x + 8, y + 4);
        }
        // crosshair at the view center
        g2.setColor(Color.BLACK);
        g2.drawLine(w / 2 - 8, h / 2, w / 2 + 8, h / 2);
        g2.drawLine(w / 2, h / 2 - 8, w / 2, h / 2 + 8);
        g2.dispose();
    }

    private int screenX(double mapX) {
        return (int) Math.round((mapX - centerX) * zoom + getWidth() / 2.0);
    }

    private int screenY(double mapY) {
        return (int) Math.round((mapY - centerY) * zoom + getHeight() / 2.0);
    }
}
