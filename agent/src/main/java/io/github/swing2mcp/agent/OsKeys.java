package io.github.swing2mcp.agent;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.KeyboardFocusManager;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.Callable;

/**
 * Keys through the operating system (java.awt.Robot), for the rare case where in-JVM events are
 * not enough (e.g. native input methods). The application's window must be in front and focused;
 * before every key we check that it still is, so keys never go to another program. Runs on an
 * agent thread, never on the EDT.
 */
final class OsKeys {

    private static final long FOCUS_WAIT_MS = 2000;

    private final Robot robot;
    private final Window window;
    private int sent;
    /** Key presses that really arrived in the application, to detect keys that went nowhere. */
    private volatile int arrived;
    private final AWTEventListener counter = new AWTEventListener() {
        public void eventDispatched(AWTEvent event) {
            if (event.getID() == KeyEvent.KEY_PRESSED) {
                arrived++;
            }
        }
    };

    private OsKeys(Window window) throws Exception {
        this.window = window;
        this.robot = new Robot();
        this.robot.setAutoDelay(5);
        Toolkit.getDefaultToolkit().addAWTEventListener(counter, AWTEvent.KEY_EVENT_MASK);
    }

    /** Brings the window of target (or the key window) to the front and focuses target. */
    static OsKeys open(final Component target) throws Exception {
        final Window window = Edt.call(new Callable<Window>() {
            public Window call() {
                Window w = target != null ? Keys.windowOf(target) : Keys.targetWindow();
                if (w == null) {
                    throw new IllegalArgumentException("No visible window to send keys to");
                }
                Keys.noteWindow(w);
                return w;
            }
        }, 3000);
        front(window, "mode=os");
        if (target != null) {
            Edt.call(new Callable<Object>() {
                public Object call() {
                    target.requestFocusInWindow();
                    return null;
                }
            }, 3000);
            boolean ok = waitFor(new Callable<Boolean>() {
                public Boolean call() {
                    return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == target;
                }
            });
            if (!ok) {
                String why = Edt.call(new Callable<String>() {
                    public String call() {
                        return Keys.focusRefused(target, KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
                    }
                }, 3000);
                throw new IllegalArgumentException(why);
            }
        }
        Component at = target;
        if (at == null) {
            at = Edt.call(new Callable<Component>() {
                public Component call() {
                    Component owner = Keys.focusOwner(window);
                    return owner != null ? owner : window;
                }
            }, 3000);
        }
        java.awt.PointerInfo pointer = java.awt.MouseInfo.getPointerInfo();
        java.awt.Point home = pointer == null ? null : pointer.getLocation();
        try {
            checkOnTop(at, null, "mode=os");
        } finally {
            if (home != null) {
                OsMouse.move(new Robot(), home.x, home.y);
            }
        }
        return new OsKeys(window);
    }

    /**
     * Proves that the application is really in front at point p of c (default: its center) before
     * anything is pressed: moves the pointer there and checks that the move arrives in c's window.
     * Java's own focus state is not enough: Windows may refuse to bring the window forward while
     * Java still believes it is focused (seen after the screen had been locked).
     */
    static void checkOnTop(final Component c, final java.awt.Point p, String what) throws Exception {
        final Window window = Edt.call(new Callable<Window>() {
            public Window call() {
                return Keys.windowOf(c);
            }
        }, 3000);
        final java.awt.Point at = Edt.call(new Callable<java.awt.Point>() {
            public java.awt.Point call() {
                java.awt.Point origin = c.getLocationOnScreen();
                return p != null ? new java.awt.Point(origin.x + p.x, origin.y + p.y)
                        : new java.awt.Point(origin.x + c.getWidth() / 2, origin.y + c.getHeight() / 2);
            }
        }, 3000);
        final int[] seen = {0};
        AWTEventListener listener = new AWTEventListener() {
            public void eventDispatched(AWTEvent event) {
                int id = event.getID();
                if ((id == java.awt.event.MouseEvent.MOUSE_MOVED || id == java.awt.event.MouseEvent.MOUSE_ENTERED)
                        && event.getSource() instanceof Component && Keys.windowOf((Component) event.getSource()) == window) {
                    seen[0]++;
                }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        try {
            Robot robot = new Robot();
            OsMouse.move(robot, at.x + 4, at.y + 4);
            robot.delay(40);
            OsMouse.move(robot, at.x, at.y);
            long deadline = System.currentTimeMillis() + 1000;
            while (seen[0] == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
                Edt.flush(1000);
            }
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
        }
        if (seen[0] == 0) {
            throw new IllegalArgumentException(what + " needs the application window in front, but at " + at.x + ","
                    + at.y + " the pointer is over another program or a locked screen (the operating system did not let "
                    + "the application come forward). Nothing was pressed. Click the application window once and try "
                    + "again, or leave out " + what + ": the default works in the background.");
        }
    }

    /** Brings the window to the front and waits until it has the focus; what names the caller for the error. */
    static void front(final Window window, String what) throws Exception {
        Edt.call(new Callable<Object>() {
            public Object call() {
                Keys.noteWindow(window);
                if (!window.isFocused()) {
                    window.toFront();
                    window.requestFocus();
                }
                return null;
            }
        }, 3000);
        if (!waitFor(new Callable<Boolean>() {
            public Boolean call() {
                return window.isFocused();
            }
        })) {
            throw new IllegalArgumentException(what + " needs the application window in front, and the operating system "
                    + "did not let it come forward (only the program in front may do that). Click the application "
                    + "window once and try again, or leave out " + what + ": the default needs no focus.");
        }
    }

    void type(String text) throws Exception {
        for (int i = 0; i < text.length(); i++) {
            stroke(Keys.forChar(text.charAt(i)));
        }
    }

    void stroke(Keys.Stroke stroke) throws Exception {
        if (stroke.keyCode == KeyEvent.VK_UNDEFINED) {
            throw new IllegalArgumentException("mode=os cannot type " + Text.quote(stroke.label) + " (no key for it)");
        }
        checkFocus();
        int[][] modifierKeys = {
                {InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_CONTROL},
                {InputEvent.ALT_DOWN_MASK, KeyEvent.VK_ALT},
                {InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_SHIFT},
                {InputEvent.META_DOWN_MASK, KeyEvent.VK_META},
                {InputEvent.ALT_GRAPH_DOWN_MASK, KeyEvent.VK_ALT_GRAPH}};
        for (int[] m : modifierKeys) {
            if ((stroke.modifiers & m[0]) != 0) {
                robot.keyPress(m[1]);
            }
        }
        try {
            robot.keyPress(stroke.keyCode);
            robot.keyRelease(stroke.keyCode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode=os cannot type " + Text.quote(stroke.label) + " on this keyboard");
        } finally {
            for (int i = modifierKeys.length - 1; i >= 0; i--) {
                if ((stroke.modifiers & modifierKeys[i][0]) != 0) {
                    robot.keyRelease(modifierKeys[i][1]);
                }
            }
        }
        sent++;
    }

    /**
     * Waits until the operating system has delivered the keys and checks that they arrived (a
     * locked screen or another program's keyboard grab swallows them silently).
     */
    void finish() throws InterruptedException {
        try {
            // not Robot.waitForIdle: on Java 8 it waits for the UI thread without a time limit
            Thread.sleep(200);
            Edt.flush(3000);
        } finally {
            close();
        }
        if (sent > 0 && arrived == 0) {
            throw new IllegalArgumentException("The operating system did not deliver the keys to the application "
                    + "(is the screen locked?). Leave out mode=os: the default sends keys inside the application.");
        }
    }

    /** Stops counting arrived keys; safe to call more than once. */
    void close() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(counter);
    }

    private void checkFocus() throws Exception {
        boolean focused = Edt.call(new Callable<Boolean>() {
            public Boolean call() {
                // a dialog the application opened in between has the focus too: still this program
                return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() != null;
            }
        }, 3000);
        if (!focused) {
            throw new IllegalArgumentException("Stopped after " + sent + " key(s): the application window lost the "
                    + "focus, and further keys would go to another program");
        }
    }

    private static boolean waitFor(Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + FOCUS_WAIT_MS;
        while (true) {
            if (Edt.call(condition, 3000)) {
                return true;
            }
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(50);
        }
    }
}
