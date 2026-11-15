package io.github.swing2mcp.agent;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceAdapter;
import java.awt.dnd.DragSourceDragEvent;
import java.awt.dnd.DragSourceDropEvent;
import java.awt.event.AWTEventListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * A drag with the real mouse pointer through java.awt.Robot, for the cases in-JVM events cannot
 * cover (the operating system's drag and drop, e.g. AWT drag gesture recognizers). The window comes
 * to the front first; before every move we check that the application still has the focus, so the
 * drag never continues in another program. Afterwards the pointer goes back where it was. Runs on
 * an agent thread, never on the EDT.
 */
final class OsMouse {

    private static final long CHECK_TIMEOUT_MS = 3000;

    private OsMouse() {
    }

    static Actions.Result drag(final Component source, final Actions.Spot from, final Component target,
                               final Actions.Spot to, Map<String, String> p, String description) throws Exception {
        final int button = Actions.button(p.get("button"));
        final int modifiers = Keys.modifiers(p.get("modifiers"));
        Integer stepsParam = Actions.optInt(p, "steps");
        Integer durationParam = Actions.optInt(p, "durationMs");
        Window window = Edt.call(new Callable<Window>() {
            public Window call() {
                return Keys.windowOf(source);
            }
        }, CHECK_TIMEOUT_MS);
        OsKeys.front(window, "mode=os");
        final Point[] points = Edt.call(new Callable<Point[]>() {
            public Point[] call() {
                Point a = source.getLocationOnScreen();
                Point b = target.getLocationOnScreen();
                return new Point[]{new Point(a.x + from.point.x, a.y + from.point.y), new Point(b.x + to.point.x, b.y + to.point.y)};
            }
        }, CHECK_TIMEOUT_MS);
        final String before = Edt.call(new Callable<String>() {
            public String call() {
                return Drags.state(source);
            }
        }, CHECK_TIMEOUT_MS);
        double distance = points[0].distance(points[1]);
        int moves = Math.max(2, Math.min(200, stepsParam != null ? stepsParam : (int) Math.max(6, Math.min(30, distance / 12))));
        int duration = Math.max(100, Math.min(10000, durationParam != null ? durationParam : 500));

        final int[] presses = {0};
        AWTEventListener counter = new AWTEventListener() {
            public void eventDispatched(AWTEvent event) {
                if (event.getID() == MouseEvent.MOUSE_PRESSED) {
                    presses[0]++;
                }
            }
        };
        final String[] nativeDrag = {null};
        DragSourceAdapter dnd = new DragSourceAdapter() {
            public void dragEnter(DragSourceDragEvent e) {
                if (nativeDrag[0] == null) {
                    nativeDrag[0] = "started";
                }
            }

            public void dragDropEnd(DragSourceDropEvent e) {
                nativeDrag[0] = e.getDropSuccess() ? "dropped (" + actionName(e.getDropAction()) + ")" : "ended without a drop";
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(counter, AWTEvent.MOUSE_EVENT_MASK);
        DragSource.getDefaultDragSource().addDragSourceListener(dnd);
        PointerInfo pointer = MouseInfo.getPointerInfo();
        Point home = pointer == null ? null : pointer.getLocation();
        Robot robot = new Robot();
        try {
            OsKeys.checkOnTop(source, from.point, "mode=os");
        } catch (Exception e) {
            if (home != null) {
                move(robot, home.x, home.y);
            }
            Toolkit.getDefaultToolkit().removeAWTEventListener(counter);
            DragSource.getDefaultDragSource().removeDragSourceListener(dnd);
            throw e;
        }
        int mask = InputEvent.getMaskForButton(button);
        boolean pressed = false;
        int[][] modifierKeys = {
                {InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_CONTROL},
                {InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_SHIFT},
                {InputEvent.ALT_DOWN_MASK, KeyEvent.VK_ALT}};
        int done = 0;
        try {
            move(robot, points[0].x, points[0].y);
            robot.delay(60);
            checkFocus(0);
            for (int[] m : modifierKeys) {
                if ((modifiers & m[0]) != 0) {
                    robot.keyPress(m[1]);
                }
            }
            robot.mousePress(mask);
            pressed = true;
            robot.delay(60);
            for (int i = 1; i <= moves; i++) {
                checkFocus(i - 1);
                int x = points[0].x + (int) Math.round((points[1].x - points[0].x) * (double) i / moves);
                int y = points[0].y + (int) Math.round((points[1].y - points[0].y) * (double) i / moves);
                if (i == moves) {
                    move(robot, x, y); // the release point must be exact; the moves on the way need not be
                } else {
                    robot.mouseMove(x, y);
                }
                robot.delay(Math.max(5, duration / moves));
                done = i;
            }
            robot.delay(80);
        } finally {
            if (pressed) {
                robot.mouseRelease(mask);
            }
            for (int i = modifierKeys.length - 1; i >= 0; i--) {
                if ((modifiers & modifierKeys[i][0]) != 0) {
                    robot.keyRelease(modifierKeys[i][1]);
                }
            }
            robot.delay(150);
            if (home != null) {
                move(robot, home.x, home.y);
            }
            // not Robot.waitForIdle: on Java 8 it waits for the UI thread without a time limit
            Edt.flush(3000);
            Thread.sleep(150);
            Edt.flush(3000);
            Toolkit.getDefaultToolkit().removeAWTEventListener(counter);
            DragSource.getDefaultDragSource().removeDragSourceListener(dnd);
        }
        if (presses[0] == 0) {
            throw new IllegalArgumentException("The operating system did not deliver the mouse to the application (is the "
                    + "screen locked, or another window on top at " + points[0].x + "," + points[0].y + "?). Leave out "
                    + "mode=os: mode=mouse and mode=transfer work inside the application.");
        }
        String after = Edt.call(new Callable<String>() {
            public String call() {
                return Drags.state(source);
            }
        }, CHECK_TIMEOUT_MS);
        StringBuilder sb = new StringBuilder(description).append(" (").append(done).append(" moves, ")
                .append(Math.round(distance)).append(" px; the pointer was put back afterwards)");
        if (after != null) {
            sb.append('\n').append(Actions.describe(source)).append(": ")
                    .append(after.equals(before) ? "unchanged, " + after : before + " -> " + after).append('.');
        }
        if (nativeDrag[0] != null) {
            sb.append("\nThe operating system's drag and drop ").append(nativeDrag[0]).append('.');
        }
        return Actions.settle(sb, true);
    }

    /**
     * Moves the pointer exactly to x,y. Robot on JDK 8 for Windows moves the pointer relatively
     * (mouse_event), which pointer ballistics and display scaling bend: on a 125% display it landed
     * up to 10% off, and a naive correction overshot. So: wait until the pointer settles, measure how
     * far it went compared to what was asked (per axis), and correct with that ratio. On JDKs whose
     * Robot is exact this costs one settle wait.
     */
    static void move(Robot robot, int x, int y) {
        Point at = settled();
        if (at == null) {
            robot.mouseMove(x, y);
            return;
        }
        double gx = 1;
        double gy = 1;
        int tx = x;
        int ty = y;
        for (int attempt = 0; attempt < 8; attempt++) {
            robot.mouseMove(tx, ty);
            Point now = settled();
            if (now == null || (now.x == x && now.y == y)) {
                return;
            }
            int askedX = tx - at.x;
            int askedY = ty - at.y;
            if (Math.abs(askedX) >= 3) {
                gx = gain((now.x - at.x) / (double) askedX);
            }
            if (Math.abs(askedY) >= 3) {
                gy = gain((now.y - at.y) / (double) askedY);
            }
            at = now;
            tx = at.x + (int) Math.round((x - at.x) / gx);
            ty = at.y + (int) Math.round((y - at.y) / gy);
        }
    }

    private static double gain(double g) {
        return g < 0.5 ? 0.5 : g > 2 ? 2 : g;
    }

    /** The pointer position once it has not changed for 60 ms (at most about 0.6 s); null if unknown. */
    private static Point settled() {
        PointerInfo info = MouseInfo.getPointerInfo();
        if (info == null) {
            return null;
        }
        Point last = info.getLocation();
        int same = 0;
        for (int i = 0; i < 60 && same < 6; i++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
            info = MouseInfo.getPointerInfo();
            Point now = info == null ? last : info.getLocation();
            same = now.equals(last) ? same + 1 : 0;
            last = now;
        }
        return last;
    }

    private static String actionName(int action) {
        return action == DnDConstants.ACTION_MOVE ? "move" : action == DnDConstants.ACTION_COPY ? "copy"
                : action == DnDConstants.ACTION_LINK ? "link" : "none";
    }

    private static void checkFocus(int moves) throws Exception {
        boolean focused = Edt.call(new Callable<Boolean>() {
            public Boolean call() {
                return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() != null;
            }
        }, CHECK_TIMEOUT_MS);
        if (!focused) {
            throw new IllegalArgumentException("Stopped after " + moves + " mouse move(s): the application window lost "
                    + "the focus, and the drag would continue in another program. The button was released.");
        }
    }
}
