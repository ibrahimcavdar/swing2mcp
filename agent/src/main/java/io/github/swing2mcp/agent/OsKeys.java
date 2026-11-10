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
                if (!w.isFocused()) {
                    w.toFront();
                    w.requestFocus();
                }
                return w;
            }
        }, 3000);
        if (!waitFor(new Callable<Boolean>() {
            public Boolean call() {
                return window.isFocused();
            }
        })) {
            throw new IllegalArgumentException("mode=os needs the application window in front, and the operating system "
                    + "did not let it come forward (only the program in front may do that). Click the application "
                    + "window once and try again, or leave out mode=os: the default needs no focus.");
        }
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
        return new OsKeys(window);
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
