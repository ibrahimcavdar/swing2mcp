package io.github.ibrahimcavdar.swing2mcp.agent;

import javax.imageio.ImageIO;
import javax.swing.RootPaneContainer;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Renders components into images by asking Swing to paint them, rather than grabbing
 * screen pixels. Works when the window is behind other windows and needs no OS
 * screen-recording permission. Must run on the EDT.
 */
final class Screenshots {

    private Screenshots() {
    }

    /** The window a user would be looking at: modal dialog, then active window, then first frame. */
    static Window defaultTarget() {
        Dialog modal = SnapshotBuilder.activeModalDialog();
        if (modal != null && !SnapshotBuilder.isSystemFileDialog(modal)) {
            return modal;
        }
        // the operating system's file dialog cannot be painted from here: show the window behind it
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (active != null && active.isShowing() && !SnapshotBuilder.isSystemFileDialog(active)) {
            return active;
        }
        List<Window> windows = SnapshotBuilder.showingWindows();
        for (Window window : windows) {
            if (window instanceof Frame) {
                return window;
            }
        }
        return windows.isEmpty() ? null : windows.get(0);
    }

    static BufferedImage render(Component target) {
        Component painted = target instanceof RootPaneContainer ? ((RootPaneContainer) target).getRootPane() : target;
        int width = Math.max(1, painted.getWidth());
        int height = Math.max(1, painted.getHeight());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(painted.getBackground() != null ? painted.getBackground() : java.awt.Color.WHITE);
            g.fillRect(0, 0, width, height);
            painted.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
