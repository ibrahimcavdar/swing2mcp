package io.github.ibrahimcavdar.swing2mcp.agent;

import javax.swing.JComponent;
import javax.swing.RootPaneContainer;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Real key events inside the JVM. Each event is dispatched to the component that has the keyboard
 * focus at that moment, through the KeyboardFocusManager, so key listeners, key bindings, menu
 * accelerators, the default button, focus traversal (Tab) and input verifiers run as for a user.
 *
 * The application's window usually is not the active window (the developer is typing in another
 * program), and then AWT has no focus owner at all. Keys then go to the window's most recent focus
 * owner, which focus requests still update (and which input verifiers still guard); the focus
 * listeners of the components that lose and gain this "logical" focus are called the way AWT
 * would call them in an active window. The user's own keyboard and the foreground window are
 * never touched. All methods here run on the EDT unless noted.
 */
final class Keys {

    private static WeakReference<Window> lastWindow = new WeakReference<Window>(null);

    private Keys() {
    }

    /** One key press with modifiers, e.g. Ctrl+S. */
    static final class Stroke {
        final int modifiers;
        final int keyCode;
        final char keyChar;
        final String label;

        Stroke(int modifiers, int keyCode, char keyChar, String label) {
            this.modifiers = modifiers;
            this.keyCode = keyCode;
            this.keyChar = keyChar;
            this.label = label;
        }

        boolean is(int mods, int code) {
            return modifiers == mods && keyCode == code;
        }
    }

    // ---------------------------------------------------------------- parsing

    private static final Map<String, String> ALIASES = new HashMap<String, String>();

    static {
        String[] pairs = {
                "ESC", "ESCAPE", "RETURN", "ENTER", "DEL", "DELETE", "INS", "INSERT",
                "BACKSPACE", "BACK_SPACE", "BKSP", "BACK_SPACE", "PGUP", "PAGE_UP", "PAGEUP", "PAGE_UP",
                "PGDN", "PAGE_DOWN", "PAGEDOWN", "PAGE_DOWN", "ARROWUP", "UP", "ARROWDOWN", "DOWN",
                "ARROWLEFT", "LEFT", "ARROWRIGHT", "RIGHT", "PLUS", "PLUS", "MINUS", "MINUS",
                "MENU", "CONTEXT_MENU", "CONTEXTMENU", "CONTEXT_MENU", "APPS", "CONTEXT_MENU",
                "PRINTSCREEN", "PRINTSCREEN", "CAPSLOCK", "CAPS_LOCK", "NUMLOCK", "NUM_LOCK",
                "SCROLLLOCK", "SCROLL_LOCK", "PAUSE", "PAUSE", "SPACEBAR", "SPACE"};
        for (int i = 0; i < pairs.length; i += 2) {
            ALIASES.put(pairs[i], pairs[i + 1]);
        }
    }

    /**
     * Parses "Enter", "Ctrl+S", "Shift+Tab", "Alt+F4", "F5", "a" or a space-separated sequence
     * such as "Tab Tab Enter". Not on the EDT necessarily.
     */
    static List<Stroke> parse(String spec) {
        if (Text.isBlank(spec)) {
            throw new IllegalArgumentException("Missing parameter: key (e.g. Enter, Escape, Tab, Ctrl+S, F5)");
        }
        List<Stroke> strokes = new ArrayList<Stroke>();
        for (String token : spec.trim().split("\\s+")) {
            strokes.add(parseStroke(token));
        }
        return strokes;
    }

    private static Stroke parseStroke(String token) {
        List<String> parts = new ArrayList<String>();
        // "Ctrl++" means Ctrl and the plus key
        String rest = token;
        while (rest.length() > 1 && rest.indexOf('+', 1) > 0) {
            int plus = rest.indexOf('+', 1);
            parts.add(rest.substring(0, plus));
            rest = rest.substring(plus + 1);
        }
        parts.add(rest);
        int modifiers = 0;
        for (int i = 0; i < parts.size() - 1; i++) {
            modifiers |= modifier(parts.get(i), token);
        }
        String key = parts.get(parts.size() - 1);
        if (key.length() == 1) {
            char ch = key.charAt(0);
            int code = KeyEvent.getExtendedKeyCodeForChar(ch);
            if (code == KeyEvent.VK_UNDEFINED) {
                throw new IllegalArgumentException("No key for " + Text.quote(key) + " in " + Text.quote(token));
            }
            if (Character.isLetter(ch)) {
                ch = (modifiers & InputEvent.SHIFT_DOWN_MASK) != 0 ? Character.toUpperCase(ch) : Character.toLowerCase(ch);
            }
            return new Stroke(modifiers, code, typedChar(modifiers, code, ch), token);
        }
        int code = namedKey(key, token);
        return new Stroke(modifiers, code, typedChar(modifiers, code, namedChar(code)), token);
    }

    /** Modifier keys held during a mouse action, e.g. "Ctrl", "Shift", "Ctrl+Shift"; 0 when blank. */
    static int modifiers(String spec) {
        if (Text.isBlank(spec)) {
            return 0;
        }
        int mask = 0;
        for (String part : spec.trim().split("\\s*[+,\\s]\\s*")) {
            if (part.length() > 0) {
                mask |= modifier(part, spec);
            }
        }
        return mask;
    }

    private static int modifier(String name, String token) {
        String n = name.toUpperCase(Locale.ROOT);
        if (n.equals("CTRL") || n.equals("CONTROL")) {
            return InputEvent.CTRL_DOWN_MASK;
        }
        if (n.equals("ALT") || n.equals("OPTION")) {
            return InputEvent.ALT_DOWN_MASK;
        }
        if (n.equals("SHIFT")) {
            return InputEvent.SHIFT_DOWN_MASK;
        }
        if (n.equals("META") || n.equals("CMD") || n.equals("COMMAND")) {
            return InputEvent.META_DOWN_MASK;
        }
        if (n.equals("ALTGR") || n.equals("ALT_GRAPH")) {
            return InputEvent.ALT_GRAPH_DOWN_MASK;
        }
        throw new IllegalArgumentException("Unknown modifier " + Text.quote(name) + " in " + Text.quote(token)
                + "; use Ctrl, Alt, Shift, Meta or AltGr");
    }

    private static int namedKey(String name, String token) {
        String n = name.toUpperCase(Locale.ROOT);
        String alias = ALIASES.get(n.replace("_", ""));
        List<String> candidates = new ArrayList<String>();
        if (alias != null) {
            candidates.add(alias);
        }
        candidates.add(n);
        // PageUp -> PAGE_UP, NumPad5 -> NUM_PAD5
        candidates.add(name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT));
        for (String candidate : candidates) {
            try {
                return KeyEvent.class.getField("VK_" + candidate).getInt(null);
            } catch (Exception ignored) {
                // try the next spelling
            }
        }
        throw new IllegalArgumentException("Unknown key " + Text.quote(name) + " in " + Text.quote(token)
                + "; use names such as Enter, Escape, Tab, Space, Backspace, Delete, Up, Down, Left, Right, "
                + "Home, End, PageUp, PageDown, F1-F24, or a single character");
    }

    private static char namedChar(int code) {
        switch (code) {
            case KeyEvent.VK_ENTER:
                return '\n';
            case KeyEvent.VK_TAB:
                return '\t';
            case KeyEvent.VK_BACK_SPACE:
                return '\b';
            case KeyEvent.VK_ESCAPE:
                return 0x1b;
            case KeyEvent.VK_DELETE:
                return 0x7f;
            case KeyEvent.VK_SPACE:
                return ' ';
            default:
                return KeyEvent.CHAR_UNDEFINED;
        }
    }

    /** The character a key produces with these modifiers (Windows gives control characters for Ctrl+letter). */
    private static char typedChar(int modifiers, int code, char ch) {
        if (ch == KeyEvent.CHAR_UNDEFINED) {
            return ch;
        }
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0 && code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) {
            return (char) (code - KeyEvent.VK_A + 1);
        }
        return ch;
    }

    /** The stroke that types one character, e.g. 'A' is Shift+A, '\n' is Enter. */
    static Stroke forChar(char ch) {
        switch (ch) {
            case '\n':
                return new Stroke(0, KeyEvent.VK_ENTER, '\n', "Enter");
            case '\t':
                return new Stroke(0, KeyEvent.VK_TAB, '\t', "Tab");
            case '\b':
                return new Stroke(0, KeyEvent.VK_BACK_SPACE, '\b', "Backspace");
            default:
                int code = KeyEvent.getExtendedKeyCodeForChar(ch);
                int modifiers = Character.isUpperCase(ch) ? InputEvent.SHIFT_DOWN_MASK : 0;
                return new Stroke(modifiers, code, ch, String.valueOf(ch));
        }
    }

    // ---------------------------------------------------------------- dispatching

    /**
     * The events of a stroke in the order a keyboard produces them: modifier presses, the key's
     * press, typed and release, modifier releases. Each runs as its own EDT task (see Actions), so
     * that what one event triggers (a focus change, a dialog) happens before the next event, as
     * with a real keyboard.
     */
    static List<Runnable> events(final Stroke stroke) {
        List<Runnable> events = new ArrayList<Runnable>();
        int[][] modifierKeys = {
                {InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_CONTROL},
                {InputEvent.ALT_DOWN_MASK, KeyEvent.VK_ALT},
                {InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_SHIFT},
                {InputEvent.META_DOWN_MASK, KeyEvent.VK_META},
                {InputEvent.ALT_GRAPH_DOWN_MASK, KeyEvent.VK_ALT_GRAPH}};
        // A typed letter only needs Shift as a state, not as a separate key press.
        boolean pressModifiers = !(stroke.modifiers == InputEvent.SHIFT_DOWN_MASK && stroke.keyChar != KeyEvent.CHAR_UNDEFINED
                && Character.isLetter(stroke.keyChar) && stroke.label.length() == 1);
        int held = 0;
        List<int[]> pressed = new ArrayList<int[]>();
        if (pressModifiers) {
            for (int[] m : modifierKeys) {
                if ((stroke.modifiers & m[0]) != 0) {
                    held |= m[0];
                    events.add(keyEvent(KeyEvent.KEY_PRESSED, held, m[1], KeyEvent.CHAR_UNDEFINED));
                    pressed.add(m);
                }
            }
        }
        events.add(keyEvent(KeyEvent.KEY_PRESSED, stroke.modifiers, stroke.keyCode, stroke.keyChar));
        if (stroke.keyChar != KeyEvent.CHAR_UNDEFINED) {
            events.add(keyEvent(KeyEvent.KEY_TYPED, stroke.modifiers, KeyEvent.VK_UNDEFINED, stroke.keyChar));
        }
        events.add(keyEvent(KeyEvent.KEY_RELEASED, stroke.modifiers, stroke.keyCode, stroke.keyChar));
        for (int i = pressed.size() - 1; i >= 0; i--) {
            held &= ~pressed.get(i)[0];
            events.add(keyEvent(KeyEvent.KEY_RELEASED, held, pressed.get(i)[1], KeyEvent.CHAR_UNDEFINED));
        }
        return events;
    }

    private static Runnable keyEvent(final int id, final int modifiers, final int keyCode, final char keyChar) {
        return new Runnable() {
            public void run() {
                Window window = targetWindow();
                if (window == null) {
                    throw new IllegalStateException("No visible window to send keys to");
                }
                Component target = focusOwner(window);
                if (target == null) {
                    target = window instanceof RootPaneContainer ? ((RootPaneContainer) window).getRootPane() : window;
                }
                Component before = target;
                int location = id == KeyEvent.KEY_TYPED ? KeyEvent.KEY_LOCATION_UNKNOWN : KeyEvent.KEY_LOCATION_STANDARD;
                target.dispatchEvent(new KeyEvent(target, id, System.currentTimeMillis(), modifiers, keyCode, keyChar,
                        location));
                syncFocus(window, before);
            }
        };
    }

    /**
     * Alt+F4 is handled by the operating system, which asks the window to close; Swing never sees
     * the key. Returns the equivalent, or null for other strokes.
     */
    static Runnable systemAction(Stroke stroke) {
        if (stroke.is(InputEvent.ALT_DOWN_MASK, KeyEvent.VK_F4)) {
            return new Runnable() {
                public void run() {
                    Window window = targetWindow();
                    if (window != null) {
                        window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
                    }
                }
            };
        }
        return null;
    }

    // ---------------------------------------------------------------- focus

    /** Remembers the window the last action was aimed at; keys without a ref go there. */
    static void noteWindow(Window window) {
        if (window != null) {
            lastWindow = new WeakReference<Window>(window);
        }
    }

    /**
     * The window that receives keys: the open modal dialog, else the window of the last action,
     * else the active window, else the most recently created visible frame or dialog.
     */
    static Window targetWindow() {
        Dialog modal = SnapshotBuilder.activeModalDialog();
        if (modal != null) {
            return modal;
        }
        Window last = lastWindow.get();
        if (last != null && last.isShowing()) {
            return last;
        }
        Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        if (focused != null && focused.isShowing()) {
            return focused;
        }
        Window found = null;
        for (Window window : SnapshotBuilder.showingWindows()) {
            if (window instanceof Frame || window instanceof Dialog) {
                found = window;
            }
        }
        return found;
    }

    /** The component that has (or, in an inactive window, would have) the keyboard focus. */
    static Component focusOwner(Window window) {
        if (window == null) {
            return null;
        }
        if (window.isFocused()) {
            Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (owner != null) {
                return owner;
            }
        }
        Component owner = window.getMostRecentFocusOwner();
        return owner != null && owner.isShowing() ? owner : null;
    }

    /** The focus owner of the window that receives keys, for the snapshot's "focused" mark. */
    static Component focusOwner() {
        return focusOwner(targetWindow());
    }

    /**
     * Moves the focus to c as a click or Tab would, running the current focus owner's input
     * verifier. In the active window AWT completes the change asynchronously (check afterwards in
     * a later EDT task); in an inactive one it is done on return.
     */
    static void requestFocus(Component c) {
        Window window = windowOf(c);
        noteWindow(window);
        Component before = focusOwner(window);
        if (before != c) {
            c.requestFocusInWindow();
            syncFocus(window, before);
        }
    }

    /**
     * In an inactive window AWT moves the most recent focus owner but sends no focus events, so
     * deliver them the way the KeyboardFocusManager does in the active window (redispatchEvent:
     * processFocusEvent overrides such as JFormattedTextField's commit run, then the listeners).
     * In the active window AWT does it itself.
     */
    static void syncFocus(Window window, Component before) {
        if (window == null || window.isFocused()) {
            return;
        }
        Component after = focusOwner(window);
        if (after == before) {
            return;
        }
        KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        if (before != null) {
            manager.redispatchEvent(before, new FocusEvent(before, FocusEvent.FOCUS_LOST, false, after));
        }
        if (after != null) {
            manager.redispatchEvent(after, new FocusEvent(after, FocusEvent.FOCUS_GAINED, false, before));
        }
    }

    /** Why the focus could not move to target, for the result. */
    static String focusRefused(Component target, Component owner) {
        StringBuilder sb = new StringBuilder("The focus stayed on ")
                .append(owner == null ? "nothing" : Actions.describe(owner) + Actions.valueNote(owner));
        if (owner instanceof JComponent && ((JComponent) owner).getInputVerifier() != null) {
            sb.append(": its input verifier rejected the value");
        } else if (!target.isFocusable()) {
            sb.append(": ").append(Actions.describe(target)).append(" is not focusable");
        }
        return sb.toString();
    }

    static Window windowOf(Component c) {
        Component at = c;
        while (at != null) {
            if (at instanceof Window) {
                return (Window) at;
            }
            Component parent = at.getParent();
            if (parent == null && at instanceof javax.swing.JPopupMenu) {
                parent = ((javax.swing.JPopupMenu) at).getInvoker();
            }
            at = parent;
        }
        return null;
    }
}
