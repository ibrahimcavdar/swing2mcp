package io.github.swing2mcp.agent;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFormattedTextField;
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.MenuElement;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.table.JTableHeader;
import javax.swing.JRootPane;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * User-like actions. Before acting we wait (up to timeoutMs) until the component can take
 * input: showing, enabled, editable where relevant, and not blocked by a modal dialog or a busy
 * glass pane. Validation runs synchronously on the EDT; the action itself is queued with
 * invokeLater so that an action which opens a modal dialog does not block the agent.
 * Afterwards we wait for the EDT to drain before reporting back.
 */
final class Actions {

    private static final long SETTLE_TIMEOUT_MS = 5000;
    private static final long CHECK_TIMEOUT_MS = 3000;
    /** How long an action waits for its component to become usable, unless timeoutMs is given. */
    static final long DEFAULT_WAIT_MS = 10000;
    private static final long POLL_MS = 100;
    /** Windows opens context menus on mouse release, other systems on press (MouseEvent.isPopupTrigger). */
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    /** How long an action with dialog=... waits for the dialog to open. */
    private static final long DIALOG_WAIT_MS = 3000;

    private final RefRegistry refs;

    Actions(RefRegistry refs) {
        this.refs = refs;
    }

    static final class Result {
        final String message;
        final boolean settled;
        /** The action could not be done (e.g. the focus was refused); reported as an error. */
        final boolean failed;

        Result(String message, boolean settled) {
            this(message, settled, false);
        }

        Result(String message, boolean settled, boolean failed) {
            this.message = message;
            this.settled = settled;
            this.failed = failed;
        }
    }

    Result click(final Map<String, String> p) throws Exception {
        final Component c = header(resolve(p), p);
        final int clickCount = intParam(p, "clickCount", 1);
        final boolean right = "right".equalsIgnoreCase(p.get("button"));
        final Integer rowParam = optInt(p, "row");
        final Integer col = optInt(p, "col");
        final Integer x = optInt(p, "x");
        final Integer y = optInt(p, "y");
        final String value = Text.isBlank(p.get("value")) ? null : p.get("value");
        final String[] target = {""};

        final StringBuilder waited = new StringBuilder();
        final Point point = whenUsable(p, waited, new Callable<Point>() {
            public Point call() {
                ensureUsable(c, c instanceof JMenuItem);
                Keys.noteWindow(Keys.windowOf(c));
                if (c instanceof JMenuItem && clickCount == 1 && !right && x == null) {
                    return null; // use doClick: works for items of closed menus too
                }
                if (x != null && y != null) {
                    return new Point(x, y);
                }
                Integer row = rowParam;
                Rectangle cell = null;
                if (c instanceof JTableHeader) {
                    JTableHeader header = (JTableHeader) c;
                    int column = col == null ? 0 : col;
                    checkIndex(column, header.getColumnModel().getColumnCount(), "col");
                    cell = header.getHeaderRect(column);
                    target[0] = " column " + column + " " + Text.quote(String.valueOf(
                            header.getColumnModel().getColumn(column).getHeaderValue()), 60);
                } else if (c instanceof JTable && (row != null || value != null)) {
                    JTable table = (JTable) c;
                    int column = col == null ? 0 : col;
                    if (value != null) {
                        int[] found = Items.findCell(table, value, col);
                        row = found[0];
                        column = found[1];
                    }
                    checkIndex(row, table.getRowCount(), "row");
                    checkIndex(column, table.getColumnCount(), "col");
                    cell = table.getCellRect(row, column, false);
                    target[0] = " row " + row + " col " + column + " " + Text.quote(Items.cellText(table, row, column), 60);
                } else if (c instanceof JList && (row != null || value != null)) {
                    JList<?> list = (JList<?>) c;
                    row = value != null ? Items.findListItem(list, value) : row;
                    checkIndex(row, list.getModel().getSize(), "row");
                    cell = list.getCellBounds(row, row);
                    target[0] = " item " + row + " " + Text.quote(Items.listText(list, row), 60);
                } else if (c instanceof JTree && (row != null || value != null)) {
                    JTree tree = (JTree) c;
                    if (value != null) {
                        TreePath path = Items.findPath(tree, value, true);
                        tree.makeVisible(path);
                        row = tree.getRowForPath(path);
                    }
                    checkIndex(row, tree.getRowCount(), "row");
                    cell = tree.getRowBounds(row);
                    target[0] = " node " + Text.quote(Items.nodeText(tree, tree.getPathForRow(row).getLastPathComponent()), 60);
                }
                if (cell != null) {
                    ((JComponent) c).scrollRectToVisible(cell);
                    return new Point(cell.x + Math.min(cell.width / 2, 20), cell.y + cell.height / 2);
                }
                return new Point(c.getWidth() / 2, c.getHeight() / 2);
            }
        });

        final String what = describe(c) + target[0];
        if (point == null) {
            String[] menuPath = Edt.call(new Callable<String[]>() {
                public String[] call() {
                    return c.isShowing() ? null : menuPathOf((JMenuItem) c);
                }
            }, CHECK_TIMEOUT_MS);
            if (menuPath != null) {
                // an item of a closed menu: open the menus first, as a user must
                return selectMenu(menuPath, null, false, waited.toString());
            }
            return perform(waited + "Clicked " + what, new Runnable() {
                public void run() {
                    if (c instanceof JMenuItem) {
                        MenuSelectionManager.defaultManager().clearSelectedPath();
                    }
                    ((AbstractButton) c).doClick(0);
                }
            });
        }
        List<Runnable> steps = mouse(c, point, clickCount, right);
        final String[] refused = new String[1];
        steps.add(new Runnable() {
            public void run() {
                refused[0] = focusRefusedByVerifier(c);
            }
        });
        Result result = perform(waited + (clickCount == 2 ? "Double-clicked " : right ? "Right-clicked " : "Clicked ") + what,
                steps);
        return refused[0] == null ? result
                : new Result(result.message + "\n" + refused[0] + ".", result.settled, result.failed);
    }

    /**
     * After a click: if c wanted the focus but the focused field's input verifier kept it, say so
     * (a click on a button still fires its action in that case, as in Swing).
     */
    private static String focusRefusedByVerifier(Component c) {
        if (!(c instanceof JComponent) || !c.isFocusable() || !((JComponent) c).getVerifyInputWhenFocusTarget()
                || (c instanceof AbstractButton && !((AbstractButton) c).isRequestFocusEnabled())) {
            return null;
        }
        Component owner = Keys.focusOwner(Keys.windowOf(c));
        if (owner == c || !(owner instanceof JComponent) || ((JComponent) owner).getInputVerifier() == null
                || SwingUtilities.isDescendingFrom(owner, c)) {
            return null;
        }
        return Keys.focusRefused(c, owner);
    }

    Result type(final Map<String, String> p) throws Exception {
        final Component c = resolve(p);
        final String text = p.get("text") == null ? "" : p.get("text");
        final boolean submit = "true".equalsIgnoreCase(p.get("submit"));
        final boolean append = "true".equalsIgnoreCase(p.get("append"));
        final StringBuilder waited = new StringBuilder();
        whenUsable(p, waited, new Callable<Object>() {
            public Object call() {
                ensureUsable(c, false);
                if (c instanceof JTextComponent) {
                    if (!((JTextComponent) c).isEditable()) {
                        throw new NotReady(describe(c) + " is read-only");
                    }
                } else if (!(c instanceof JSpinner) && !(c instanceof JComboBox && ((JComboBox<?>) c).isEditable())) {
                    throw new IllegalArgumentException(describe(c) + " does not accept text; use click or select_option");
                }
                return null;
            }
        });

        String mode = p.get("mode") == null ? "keys" : p.get("mode").trim().toLowerCase(java.util.Locale.ROOT);
        String done = (append ? "Appended text to " : "Typed into ") + describe(c) + (submit ? " and pressed Enter" : "");
        if (mode.equals("keys")) {
            return typeKeys(c, text, append, submit, waited + done);
        }
        if (mode.equals("os")) {
            return typeOs(c, text, append, submit, waited + done + " (keys through the operating system)");
        }
        if (!mode.equals("set")) {
            throw new IllegalArgumentException("mode must be keys (default), set or os");
        }
        return perform(waited + done + " (text set directly, no key events)",
                new Runnable() {
                    public void run() {
                        c.requestFocusInWindow();
                        if (c instanceof JTextComponent) {
                            JTextComponent t = (JTextComponent) c;
                            if (append) {
                                t.setCaretPosition(t.getDocument().getLength());
                                t.replaceSelection(text);
                            } else {
                                t.setText(text);
                            }
                            if (c instanceof JFormattedTextField) {
                                try {
                                    ((JFormattedTextField) c).commitEdit();
                                } catch (java.text.ParseException ignored) {
                                    // shown as "invalid" in the next snapshot
                                }
                            }
                            if (submit && c instanceof JTextField) {
                                ((JTextField) c).postActionEvent();
                            }
                        } else if (c instanceof JSpinner) {
                            JFormattedTextField field = ((JSpinner.DefaultEditor) ((JSpinner) c).getEditor()).getTextField();
                            field.setText(text);
                            try {
                                field.commitEdit();
                            } catch (java.text.ParseException e) {
                                throw new IllegalArgumentException("Spinner rejected value " + text);
                            }
                        } else {
                            ((JComboBox<?>) c).setSelectedItem(text);
                        }
                    }
                });
    }

    Result select(final Map<String, String> p) throws Exception {
        final Component c = resolve(p);
        final String value = p.get("value");
        final Integer index = optInt(p, "index");
        final Integer col = optInt(p, "col");
        if (value == null && index == null) {
            throw new IllegalArgumentException("select needs either value or index");
        }
        final AtomicReference<Runnable> action = new AtomicReference<Runnable>();
        final StringBuilder waited = new StringBuilder();
        final String chosen = whenUsable(p, waited, new Callable<String>() {
            public String call() {
                ensureUsable(c, false);
                if (c instanceof JComboBox) {
                    final JComboBox<?> combo = (JComboBox<?>) c;
                    List<String> items = new ArrayList<String>();
                    for (int i = 0; i < combo.getItemCount(); i++) {
                        items.add(Items.comboText(combo, combo.getItemAt(i), i));
                    }
                    final int i = pick(items, value, index);
                    action.set(new Runnable() {
                        public void run() {
                            combo.setSelectedIndex(i);
                        }
                    });
                    return items.get(i);
                }
                if (c instanceof JList) {
                    final JList<?> list = (JList<?>) c;
                    List<String> items = new ArrayList<String>();
                    for (int i = 0; i < list.getModel().getSize(); i++) {
                        items.add(Items.listText(list, i));
                    }
                    final int i = pick(items, value, index);
                    action.set(new Runnable() {
                        public void run() {
                            list.setSelectedIndex(i);
                            list.ensureIndexIsVisible(i);
                        }
                    });
                    return items.get(i);
                }
                if (c instanceof JTabbedPane) {
                    final JTabbedPane tabs = (JTabbedPane) c;
                    List<String> items = new ArrayList<String>();
                    for (int i = 0; i < tabs.getTabCount(); i++) {
                        items.add(Text.plain(tabs.getTitleAt(i)));
                    }
                    final int i = pick(items, value, index);
                    if (!tabs.isEnabledAt(i)) {
                        throw new NotReady("Tab " + Text.quote(items.get(i)) + " is disabled");
                    }
                    action.set(new Runnable() {
                        public void run() {
                            tabs.setSelectedIndex(i);
                        }
                    });
                    return items.get(i);
                }
                if (c instanceof JTable) {
                    final JTable table = (JTable) c;
                    final int row;
                    final int column;
                    if (index != null) {
                        row = index;
                        column = col != null ? col : 0;
                    } else {
                        int[] found = Items.findCell(table, value, col);
                        row = found[0];
                        column = found[1];
                    }
                    checkIndex(row, table.getRowCount(), "row");
                    checkIndex(column, table.getColumnCount(), "col");
                    action.set(new Runnable() {
                        public void run() {
                            // what a click on the cell does: selection model, anchor, autoscroll
                            table.changeSelection(row, column, false, false);
                        }
                    });
                    return "row " + row + (col != null || index == null ? " col " + column : "");
                }
                if (c instanceof JTree) {
                    final JTree tree = (JTree) c;
                    final TreePath path;
                    if (index != null) {
                        checkIndex(index, tree.getRowCount(), "index");
                        path = tree.getPathForRow(index);
                    } else {
                        path = Items.findPath(tree, value, true);
                    }
                    action.set(new Runnable() {
                        public void run() {
                            tree.setSelectionPath(path);
                            tree.scrollPathToVisible(path);
                        }
                    });
                    return Items.nodeText(tree, path.getLastPathComponent());
                }
                throw new IllegalArgumentException(describe(c) + " does not support select; use click");
            }
        });
        return perform(waited + "Selected " + Text.quote(chosen) + " in " + describe(c), action.get());
    }

    /**
     * Answers a dialog like a user: optionally types the answer of an input dialog or picks a file
     * in a file chooser, then presses a button (by text or role: ok, yes, no, cancel, open, save;
     * "close" uses the window's close box).
     */
    Result handleDialog(final Map<String, String> p) throws Exception {
        final String button = Text.isBlank(p.get("button")) ? null : p.get("button");
        final String text = p.get("text");
        final String path = Text.isBlank(p.get("path")) ? null : p.get("path");
        if (button == null && text == null && path == null) {
            throw new IllegalArgumentException("Say what to do: button (e.g. OK, Yes, Cancel, close), text for an "
                    + "input dialog, or path for a file chooser");
        }
        final String ref = p.get("ref");
        final StringBuilder waited = new StringBuilder();
        final Window dialog = whenUsable(p, waited, new Callable<Window>() {
            public Window call() {
                Window w;
                if (!Text.isBlank(ref)) {
                    Component c = refs.resolve(ref.trim());
                    w = c == null ? null : Keys.windowOf(c);
                    if (w == null) {
                        throw new IllegalArgumentException("Unknown or disposed ref " + ref + "; take a new snapshot");
                    }
                    if (!(w instanceof Dialog)) {
                        throw new IllegalArgumentException(ref + " is not in a dialog; use click for other windows");
                    }
                    Dialog modal = SnapshotBuilder.activeModalDialog();
                    if (modal != null && modal != w && !isSameOrOwnedBy(w, modal)) {
                        throw new NotReady("blocked by modal dialog " + Text.quote(modal.getTitle()), "; answer it first");
                    }
                } else {
                    w = Dialogs.current();
                    if (w == null) {
                        throw new NotReady("No dialog is open");
                    }
                }
                if (!w.isShowing()) {
                    throw new NotReady(Dialogs.summary(w) + " is not visible");
                }
                return w;
            }
        });
        String summary = Edt.call(new Callable<String>() {
            public String call() {
                return Dialogs.summary(dialog);
            }
        }, CHECK_TIMEOUT_MS);
        StringBuilder done = new StringBuilder(waited).append("Answered ").append(summary);
        if (path != null) {
            final JFileChooser chooser = Edt.call(new Callable<JFileChooser>() {
                public JFileChooser call() {
                    return Dialogs.find(dialog, JFileChooser.class);
                }
            }, CHECK_TIMEOUT_MS);
            if (chooser == null) {
                throw new IllegalArgumentException(summary + " has no file chooser; use text for input fields");
            }
            Result chosen = perform("", new Runnable() {
                public void run() {
                    if (!Dialogs.setFileName(chooser, path)) {
                        chooser.setSelectedFile(new java.io.File(path));
                    }
                }
            });
            if (chosen.failed || !chosen.settled) {
                return chosen;
            }
            done.append(" with file ").append(Text.quote(path));
        }
        if (text != null) {
            final Component field = Edt.call(new Callable<Component>() {
                public Component call() {
                    return Dialogs.inputField(dialog);
                }
            }, CHECK_TIMEOUT_MS);
            if (field == null) {
                throw new IllegalArgumentException(summary + " has no input field");
            }
            Result typed;
            if (field instanceof JComboBox && !((JComboBox<?>) field).isEditable()) {
                Map<String, String> select = new java.util.HashMap<String, String>();
                select.put("ref", refs.refFor(field));
                select.put("value", text);
                typed = select(select);
            } else {
                typed = typeKeys(field, text, false, false, "");
            }
            if (typed.failed || !typed.settled) {
                return typed;
            }
            done.append(" with text ").append(Text.quote(text));
        }
        String press = button;
        if (press == null) {
            press = path != null ? "approve" : "ok";
        }
        final String pressName = press;
        final AbstractButton target = Edt.call(new Callable<AbstractButton>() {
            public AbstractButton call() {
                return Dialogs.button(dialog, pressName);
            }
        }, CHECK_TIMEOUT_MS);
        if (target == null) {
            done.append(" by closing it");
            return perform(done.toString(), new Runnable() {
                public void run() {
                    dialog.dispatchEvent(new java.awt.event.WindowEvent(dialog, java.awt.event.WindowEvent.WINDOW_CLOSING));
                }
            });
        }
        done.append(" by pressing ").append(describe(target));
        final Point center = Edt.call(new Callable<Point>() {
            public Point call() {
                return new Point(target.getWidth() / 2, target.getHeight() / 2);
            }
        }, CHECK_TIMEOUT_MS);
        List<Runnable> steps = mouse(target, center, 1, false);
        final String[] still = {""};
        steps.add(new Runnable() {
            public void run() {
                if (dialog.isShowing()) {
                    JFileChooser chooser = Dialogs.find(dialog, JFileChooser.class);
                    still[0] = "\nThe dialog is still open" + (chooser == null ? "."
                            : ", now in " + Text.quote(String.valueOf(chooser.getCurrentDirectory())) + ".");
                }
            }
        });
        Result result = perform(done.toString(), steps);
        return new Result(result.message + still[0], result.settled, result.failed);
    }

    /**
     * Runs an action; with the parameter dialog=<button>, a modal dialog that the action opens
     * (within a few seconds) is answered with that button, and its message is reported.
     */
    Result answering(Map<String, String> p, Callable<Result> action) throws Exception {
        final String answer = p.get("dialog");
        if (Text.isBlank(answer)) {
            return action.call();
        }
        Callable<Dialog> modal = new Callable<Dialog>() {
            public Dialog call() {
                return SnapshotBuilder.activeModalDialog();
            }
        };
        final Dialog before = Edt.call(modal, CHECK_TIMEOUT_MS);
        Result result = action.call();
        if (result.failed || !result.settled) {
            return result;
        }
        try {
            return answer(result, answer, before, modal);
        } catch (Exception e) {
            // the action itself happened: say so, so that it is not repeated
            return new Result(result.message + "\nThe dialog could not be answered: " + e.getMessage(), true, true);
        }
    }

    private Result answer(Result result, String answer, Dialog before, Callable<Dialog> modal) throws Exception {
        long deadline = System.currentTimeMillis() + DIALOG_WAIT_MS;
        Dialog opened;
        while (true) {
            opened = Edt.call(modal, CHECK_TIMEOUT_MS);
            if (opened != null && opened != before) {
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                return new Result(result.message + "\nNo dialog opened within " + DIALOG_WAIT_MS
                        + " ms, so nothing was answered with " + Text.quote(answer) + ".", true, true);
            }
            sleep(POLL_MS);
        }
        Map<String, String> reply = new java.util.HashMap<String, String>();
        reply.put("ref", refs.refFor(opened));
        reply.put("button", answer);
        Result answered = handleDialog(reply);
        return new Result(result.message + "\n" + answered.message, answered.settled, answered.failed);
    }

    /**
     * Opens a menu path such as "File > Export > PDF" in the menu bar (or, with context=true, in
     * the context menu of ref, opened by a right-click) level by level and clicks the last item.
     */
    Result selectMenu(Map<String, String> p) throws Exception {
        String[] parts = Menus.parts(p.get("path") == null ? "" : p.get("path"));
        Component c = Text.isBlank(p.get("ref")) ? null : resolve(p);
        boolean context = "true".equalsIgnoreCase(p.get("context"));
        StringBuilder waited = new StringBuilder();
        if (c != null) {
            final Component at = c;
            whenUsable(p, waited, new Callable<Object>() {
                public Object call() {
                    ensureUsable(at, false);
                    return null;
                }
            });
        }
        return selectMenu(parts, c, context, waited.toString());
    }

    private Result selectMenu(final String[] parts, final Component c, final boolean context, String waited)
            throws Exception {
        final String path = join(parts);
        final MenuElement[] root = new MenuElement[1];
        List<Runnable> steps = new ArrayList<Runnable>();
        if (context && c != null) {
            steps.addAll(mouse(c, Edt.call(new Callable<Point>() {
                public Point call() {
                    return new Point(c.getWidth() / 2, c.getHeight() / 2);
                }
            }, CHECK_TIMEOUT_MS), 1, true));
        }
        steps.add(new Runnable() {
            public void run() {
                if (context) {
                    root[0] = Menus.openPopup();
                    if (root[0] == null) {
                        throw new Stopped(c != null ? "Right-clicking " + describe(c) + " did not open a context menu."
                                : "No context menu is open; give the ref of the component to right-click.");
                    }
                } else {
                    JMenuBar bar = Menus.menuBar(c);
                    if (bar == null) {
                        throw new Stopped("No menu bar in this window" + (c == null ? "" : " of " + describe(c))
                                + "; for a context menu pass context=true and the ref to right-click.");
                    }
                    Keys.noteWindow(Keys.windowOf(bar));
                    root[0] = bar;
                }
            }
        });
        final List<JMenu> opened = new ArrayList<JMenu>();
        final JMenuItem[] last = new JMenuItem[1];
        for (int i = 0; i < parts.length; i++) {
            final int level = i;
            steps.add(new Runnable() {
                public void run() {
                    MenuElement parent = opened.isEmpty() ? root[0] : opened.get(opened.size() - 1);
                    String where = level == 0 ? (context ? "the context menu" : "the menu bar") : join(parts, level);
                    JMenuItem item;
                    try {
                        item = Menus.child(parent, parts[level], where);
                    } catch (IllegalArgumentException e) {
                        Menus.closeAll();
                        throw new Stopped(e.getMessage() + ".");
                    }
                    if (!item.isEnabled()) {
                        Menus.closeAll();
                        throw new Stopped("Menu item " + Text.quote(join(parts, level + 1)) + " is disabled.");
                    }
                    if (item instanceof JMenu && level < parts.length - 1) {
                        opened.add((JMenu) item);
                        Menus.open(root[0], opened); // listeners that fill the menu run now
                    } else if (item instanceof JMenu) {
                        opened.add((JMenu) item);
                        Menus.open(root[0], opened); // the path ends at a menu: leave it open
                    } else if (level < parts.length - 1) {
                        Menus.closeAll();
                        throw new Stopped(Text.quote(join(parts, level + 1)) + " is not a menu; it has no "
                                + Text.quote(parts[level + 1]) + ".");
                    } else {
                        last[0] = item;
                    }
                }
            });
        }
        steps.add(new Runnable() {
            public void run() {
                if (last[0] != null) {
                    // what releasing the mouse on the item does
                    MenuSelectionManager.defaultManager().clearSelectedPath();
                    last[0].doClick(0);
                }
            }
        });
        return perform(waited + "Selected menu " + Text.quote(path), steps);
    }

    /** "File > Export > PDF" for an item of a menu bar or popup, from its menus; null if not in one. */
    private static String[] menuPathOf(JMenuItem item) {
        List<String> names = new ArrayList<String>();
        Component at = item;
        while (at != null) {
            if (at instanceof JMenuItem) {
                names.add(0, Menus.text((JMenuItem) at));
            }
            Component parent = at.getParent();
            if (parent instanceof JPopupMenu) {
                Component invoker = ((JPopupMenu) parent).getInvoker();
                if (!(invoker instanceof JMenu)) {
                    return null; // a context menu: it cannot be opened without its invoker
                }
                at = invoker;
            } else if (parent instanceof JMenuBar) {
                return names.toArray(new String[0]);
            } else {
                at = parent;
            }
        }
        return null;
    }

    private static String join(String[] parts) {
        return join(parts, parts.length);
    }

    private static String join(String[] parts, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(i > 0 ? " > " : "").append(parts[i]);
        }
        return sb.toString();
    }

    /** header=true on a table clicks its column header (e.g. to sort) instead of a cell. */
    private static Component header(Component c, Map<String, String> p) {
        if (!"true".equalsIgnoreCase(p.get("header"))) {
            return c;
        }
        if (!(c instanceof JTable) || ((JTable) c).getTableHeader() == null) {
            throw new IllegalArgumentException("header=true needs a table with a column header");
        }
        return ((JTable) c).getTableHeader();
    }

    /** Expands (or collapses) a tree node given by its path; ancestors are expanded on the way. */
    Result expand(final Map<String, String> p) throws Exception {
        final Component c = resolve(p);
        final String value = p.get("path");
        if (Text.isBlank(value)) {
            throw new IllegalArgumentException("Missing parameter: path (e.g. \"Root/Child\")");
        }
        final boolean collapse = "true".equalsIgnoreCase(p.get("collapse"));
        final StringBuilder waited = new StringBuilder();
        final TreePath path = whenUsable(p, waited, new Callable<TreePath>() {
            public TreePath call() {
                ensureUsable(c, false);
                if (!(c instanceof JTree)) {
                    throw new IllegalArgumentException(describe(c) + " is not a tree");
                }
                return Items.findPath((JTree) c, value, true);
            }
        });
        final JTree tree = (JTree) c;
        final String[] outcome = {""};
        List<Runnable> steps = new ArrayList<Runnable>();
        steps.add(new Runnable() {
            public void run() {
                if (collapse) {
                    tree.collapsePath(path);
                } else {
                    tree.expandPath(path);
                }
                tree.scrollPathToVisible(path);
            }
        });
        steps.add(new Runnable() {
            public void run() {
                Object node = path.getLastPathComponent();
                if (tree.getModel().isLeaf(node)) {
                    outcome[0] = "\nThe node is a leaf; it has nothing to expand.";
                } else if (tree.isExpanded(path) == collapse) {
                    outcome[0] = "\nThe application did not let the node " + (collapse ? "collapse" : "expand")
                            + " (a tree-will-expand listener vetoed it).";
                } else if (!collapse) {
                    outcome[0] = "\nIt has " + tree.getModel().getChildCount(node) + " children.";
                }
            }
        });
        Result result = perform(waited + (collapse ? "Collapsed " : "Expanded ") + Text.quote(value) + " in " + describe(c),
                steps);
        return new Result(result.message + outcome[0], result.settled, result.failed);
    }

    /**
     * Types with real key events: focuses the field like a click would (the previous field's input
     * verifier runs), selects the old text unless appending, then sends each character as key
     * pressed/typed/released to whichever component has the focus at that moment.
     */
    private Result typeKeys(final Component c, final String text, final boolean append, final boolean submit,
                            String description) {
        final Component input = inputOf(c);
        final Window window = Keys.windowOf(input);
        final int[] typed = new int[1];
        List<Runnable> steps = new ArrayList<Runnable>();
        steps.addAll(focusSteps(input, "Did not type. "));
        steps.add(new Runnable() {
            public void run() {
                if (input instanceof JTextComponent) {
                    JTextComponent t = (JTextComponent) input;
                    if (append) {
                        t.setCaretPosition(t.getDocument().getLength());
                    } else {
                        t.selectAll();
                    }
                }
            }
        });
        if (!append && text.length() == 0) {
            steps.addAll(Keys.events(Keys.forChar('\b')));
        }
        for (int i = 0; i < text.length(); i++) {
            final int index = i;
            List<Runnable> events = Keys.events(Keys.forChar(text.charAt(i)));
            final Runnable first = events.get(0);
            events.set(0, new Runnable() {
                public void run() {
                    Dialog modal = SnapshotBuilder.activeModalDialog();
                    if (modal != null && modal != window && !isSameOrOwnedBy(window, modal)) {
                        throw new Stopped("Stopped after " + index + " of " + text.length()
                                + " characters: modal dialog " + Text.quote(modal.getTitle()) + " opened.");
                    }
                    typed[0] = index + 1;
                    first.run();
                }
            });
            steps.addAll(events);
        }
        if (submit) {
            steps.addAll(Keys.events(Keys.forChar('\n')));
        }
        Result result = perform(description, steps);
        if (result.failed || !result.settled) {
            return result;
        }
        String note = null;
        try {
            note = Edt.call(new Callable<String>() {
                public String call() {
                    if (!(input instanceof JTextComponent) || submit || typed[0] < text.length()) {
                        return null;
                    }
                    String now = ((JTextComponent) input).getText();
                    if (input instanceof JPasswordField) {
                        return append || now.length() == text.length() ? null
                                : "\nThe field has " + now.length() + " characters, not " + text.length()
                                + ": the application changed or rejected some keys.";
                    }
                    boolean expected = append ? now.endsWith(text) : now.equals(text);
                    return expected ? null : "\nThe field now contains " + Text.quote(now, 200)
                            + ": the application changed or rejected some keys.";
                }
            }, CHECK_TIMEOUT_MS);
        } catch (Exception ignored) {
            // only a hint
        }
        return note == null ? result : new Result(result.message + note, result.settled, result.failed);
    }

    private Result typeOs(Component c, final String text, final boolean append, boolean submit, String description)
            throws Exception {
        final Component input = inputOf(c);
        OsKeys keys = OsKeys.open(input);
        try {
            typeOs(keys, input, text, append, submit);
        } finally {
            keys.close();
        }
        Result result = settle(new StringBuilder(description), true);
        if (submit || !(input instanceof JTextComponent) || input instanceof JPasswordField) {
            return result;
        }
        String now = Edt.call(new Callable<String>() {
            public String call() {
                return ((JTextComponent) input).getText();
            }
        }, CHECK_TIMEOUT_MS);
        boolean expected = append ? now.endsWith(text) : now.equals(text);
        return expected ? result : new Result(result.message + "\nThe field now contains " + Text.quote(now, 200)
                + ": the keyboard layout or the application changed some keys.", result.settled, result.failed);
    }

    private static void typeOs(OsKeys keys, Component input, final String text, final boolean append, boolean submit)
            throws Exception {
        if (input instanceof JTextComponent) {
            final JTextComponent t = (JTextComponent) input;
            Edt.call(new Callable<Object>() {
                public Object call() {
                    if (append) {
                        t.setCaretPosition(t.getDocument().getLength());
                    } else {
                        t.selectAll();
                    }
                    return null;
                }
            }, CHECK_TIMEOUT_MS);
        }
        if (!append && text.length() == 0) {
            keys.stroke(Keys.forChar('\b'));
        }
        keys.type(text);
        if (submit) {
            keys.stroke(Keys.forChar('\n'));
        }
        keys.finish();
    }

    /** Presses keys (e.g. "Enter", "Ctrl+S", "Tab Tab") in the focused component, or in ref after focusing it. */
    Result pressKey(final Map<String, String> p) throws Exception {
        final List<Keys.Stroke> strokes = Keys.parse(p.get("key"));
        final Component c = Text.isBlank(p.get("ref")) ? null : resolve(p);
        final StringBuilder waited = new StringBuilder();
        String target = whenUsable(p, waited, new Callable<String>() {
            public String call() {
                if (c != null) {
                    ensureUsable(c, false);
                    Keys.noteWindow(Keys.windowOf(c));
                    return describe(inputOf(c));
                }
                Window window = Keys.targetWindow();
                if (window == null) {
                    throw new NotReady("No visible window to send keys to");
                }
                Component owner = Keys.focusOwner(window);
                return owner != null ? describe(owner) : "window " + Text.quote(title(window));
            }
        });
        StringBuilder labels = new StringBuilder();
        for (Keys.Stroke s : strokes) {
            labels.append(labels.length() > 0 ? " " : "").append(s.label);
        }
        String description = waited + "Pressed " + labels + " in " + target;
        if ("os".equalsIgnoreCase(p.get("mode"))) {
            OsKeys keys = OsKeys.open(c == null ? null : inputOf(c));
            try {
                for (Keys.Stroke s : strokes) {
                    keys.stroke(s);
                }
                keys.finish();
            } finally {
                keys.close();
            }
            return withFocus(settle(new StringBuilder(description + " (keys through the operating system)"), true));
        }
        List<Runnable> steps = new ArrayList<Runnable>();
        if (c != null) {
            steps.addAll(focusSteps(inputOf(c), "Did not press keys. "));
        }
        for (Keys.Stroke s : strokes) {
            Runnable system = Keys.systemAction(s);
            if (system != null) {
                steps.add(system);
                description += " (" + s.label + " closes the window: sent a window-closing event, as the operating system does)";
            } else {
                steps.addAll(Keys.events(s));
            }
        }
        return withFocus(perform(description, steps));
    }

    /**
     * Moves the focus to input like a click would, then (after AWT has delivered the focus events
     * of an active window) checks that it got there; if not, the action stops with the reason.
     */
    private static List<Runnable> focusSteps(final Component input, final String stopPrefix) {
        List<Runnable> steps = new ArrayList<Runnable>();
        steps.add(new Runnable() {
            public void run() {
                Keys.requestFocus(input);
            }
        });
        steps.add(new Runnable() {
            public void run() {
                Component owner = Keys.focusOwner(Keys.windowOf(input));
                if (owner != input) {
                    throw new Stopped(stopPrefix + Keys.focusRefused(input, owner) + ".");
                }
            }
        });
        return steps;
    }

    /** Adds where the keyboard focus is now, which matters after Tab and Enter. */
    private static Result withFocus(Result result) {
        if (result.failed || !result.settled) {
            return result;
        }
        try {
            String focus = Edt.call(new Callable<String>() {
                public String call() {
                    Component owner = Keys.focusOwner();
                    return owner == null ? null : describe(owner) + valueNote(owner);
                }
            }, CHECK_TIMEOUT_MS);
            return focus == null ? result : new Result(result.message + "\nFocus is now on " + focus + ".", true, false);
        } catch (Exception e) {
            return result;
        }
    }

    /** The component that takes the keys: the text field inside a spinner or editable combo box. */
    static Component inputOf(Component c) {
        if (c instanceof JSpinner && ((JSpinner) c).getEditor() instanceof JSpinner.DefaultEditor) {
            return ((JSpinner.DefaultEditor) ((JSpinner) c).getEditor()).getTextField();
        }
        if (c instanceof JComboBox && ((JComboBox<?>) c).isEditable() && ((JComboBox<?>) c).getEditor() != null) {
            return ((JComboBox<?>) c).getEditor().getEditorComponent();
        }
        return c;
    }

    /** " with value ..." for text components, for messages about the focus. */
    static String valueNote(Component c) {
        if (c instanceof JPasswordField) {
            return "";
        }
        if (c instanceof JTextComponent) {
            return " with value " + Text.quote(((JTextComponent) c).getText(), 80);
        }
        return "";
    }

    private static String title(Window w) {
        return w instanceof java.awt.Frame ? ((java.awt.Frame) w).getTitle()
                : w instanceof Dialog ? ((Dialog) w).getTitle() : "";
    }

    // ---------------------------------------------------------------------------------

    /** A step decided that the action cannot go on; its message ends the result. */
    static final class Stopped extends RuntimeException {
        Stopped(String message) {
            super(message);
        }
    }

    /** Runs an action on the EDT without blocking on modal dialogs, then waits for the UI to settle. */
    static Result perform(String description, Runnable action) {
        List<Runnable> steps = new ArrayList<Runnable>();
        steps.add(action);
        return perform(description, steps);
    }

    /**
     * Runs the steps one after the other, each as its own EDT task, waiting in between until the
     * EDT has handled what the step caused (like the gaps between real input events). A step that
     * opens a modal dialog does not block the next ones: the dialog's event loop runs them.
     */
    static Result perform(String description, List<Runnable> steps) {
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        boolean settled = true;
        int done = 0;
        for (final Runnable step : steps) {
            done++;
            EventQueue.invokeLater(new Runnable() {
                public void run() {
                    if (failure.get() != null) {
                        return;
                    }
                    try {
                        step.run();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }
            });
            settled = Edt.flush(SETTLE_TIMEOUT_MS);
            if (!settled || failure.get() != null) {
                break;
            }
        }
        StringBuilder sb = new StringBuilder(description);
        Throwable t = failure.get();
        if (t instanceof Stopped) {
            sb.setLength(0);
            sb.append(t.getMessage());
        } else if (t != null) {
            sb.append("\nAction failed, the application threw an exception:\n")
                    .append(ErrorMonitor.describe("on the UI thread", t).trim());
        }
        if (!settled) {
            sb.append("\nThe UI thread is still busy after ").append(SETTLE_TIMEOUT_MS)
                    .append(" ms (long-running action?). Use wait_for or snapshot to check later.");
            if (done < steps.size()) {
                sb.append(" The action stopped after step ").append(done).append(" of ").append(steps.size())
                        .append(" (e.g. a key or mouse release was not sent); repeat it if needed.");
            }
            return new Result(sb.toString(), false, t instanceof Stopped);
        }
        Result result = settle(sb, true);
        return t instanceof Stopped ? new Result(result.message, result.settled, true) : result;
    }

    /** Lets follow-up invokeLater calls and repaints run before the result is reported. */
    private static Result settle(StringBuilder sb, boolean settledSoFar) {
        boolean settled = settledSoFar;
        if (settled) {
            sleep(150);
            settled = Edt.flush(2000);
        }
        if (!settled) {
            sb.append("\nThe UI thread is still busy (long-running action?). Use wait_for or snapshot to check later.");
        }
        return new Result(sb.toString(), settled);
    }

    private Component resolve(Map<String, String> p) {
        String ref = p.get("ref");
        if (Text.isBlank(ref)) {
            throw new IllegalArgumentException("Missing parameter: ref");
        }
        Component c = refs.resolve(ref.trim());
        if (c == null) {
            throw new IllegalArgumentException("Unknown or disposed ref " + ref + "; take a new snapshot");
        }
        return c;
    }

    /** The component cannot take input yet; the action waits and checks again. */
    static final class NotReady extends IllegalArgumentException {
        /** What to do if it stays this way, added to the error after the wait. */
        final String hint;

        NotReady(String message) {
            this(message, "");
        }

        NotReady(String message, String hint) {
            super(message);
            this.hint = hint;
        }
    }

    /**
     * Runs the check on the EDT until it no longer throws {@link NotReady} or the wait times out.
     * Other exceptions (wrong component type, bad index) fail at once. When the action had to wait,
     * {@code waited} receives a note for the result.
     */
    static <T> T whenUsable(Map<String, String> p, StringBuilder waited, Callable<T> check) throws Exception {
        long timeout = Math.max(0, intParam(p, "timeoutMs", (int) DEFAULT_WAIT_MS));
        long start = System.currentTimeMillis();
        String firstReason = null;
        while (true) {
            try {
                T result = Edt.call(check, CHECK_TIMEOUT_MS);
                if (firstReason != null) {
                    waited.append("Waited ").append(System.currentTimeMillis() - start).append(" ms (")
                            .append(firstReason).append("). ");
                }
                return result;
            } catch (Exception e) {
                if (!(e instanceof NotReady) && !(e instanceof Edt.BusyException)) {
                    throw e;
                }
                if (firstReason == null) {
                    firstReason = e instanceof NotReady ? e.getMessage() : "the UI thread was busy";
                }
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed >= timeout) {
                    if (e instanceof Edt.BusyException) {
                        throw e;
                    }
                    String reason = e.getMessage();
                    throw new IllegalArgumentException(Character.toUpperCase(reason.charAt(0)) + reason.substring(1)
                            + " (still after waiting " + elapsed + " ms)"
                            + ((NotReady) e).hint);
                }
                sleep(Math.min(POLL_MS, timeout - elapsed));
            }
        }
    }

    /** Must be called on the EDT. */
    private static void ensureUsable(Component c, boolean allowHidden) {
        if (!allowHidden && !c.isShowing()) {
            throw new NotReady(describe(c) + " is not visible", "; if it should be, take a new snapshot");
        }
        if (!c.isEnabled() && !(allowHidden && !c.isShowing())) { // a closed menu may enable it when opened
            throw new NotReady(describe(c) + " is disabled");
        }
        Dialog modal = SnapshotBuilder.activeModalDialog();
        Window window = Keys.windowOf(c);
        if (modal != null && window != null && !isSameOrOwnedBy(window, modal)) {
            throw new NotReady("blocked by modal dialog " + Text.quote(modal.getTitle()), "; close it first");
        }
        if (!allowHidden) {
            Component glass = blockingGlassPane(c);
            if (glass != null) {
                throw new NotReady("blocked by a busy overlay (glass pane " + glass.getClass().getName() + ")");
            }
        }
    }

    /**
     * A visible glass pane over the component that takes mouse input, as busy overlays do (wait
     * cursor, or mouse listeners that swallow clicks). Glass panes of internal frames are only
     * counted with a wait cursor: Swing gives them a mouse listener to activate the frame on click.
     */
    private static Component blockingGlassPane(Component c) {
        // From the innermost root pane (e.g. of an internal frame) out to the window's.
        List<JRootPane> roots = new ArrayList<JRootPane>();
        for (Component at = c; at != null && roots.size() < 10; at = at.getParent()) {
            if (at instanceof JRootPane) {
                roots.add((JRootPane) at);
            }
        }
        for (JRootPane root : roots) {
            Component glass = root.getGlassPane();
            if (glass == null || !glass.isVisible() || SwingUtilities.isDescendingFrom(c, glass)) {
                continue;
            }
            boolean busy = glass.isCursorSet() && glass.getCursor().getType() == Cursor.WAIT_CURSOR;
            boolean swallows = glass.getMouseListeners().length > 0 && !(root.getParent() instanceof JInternalFrame);
            if (!busy && !swallows) {
                continue;
            }
            Point center = SwingUtilities.convertPoint(c, c.getWidth() / 2, c.getHeight() / 2, glass);
            if (glass.contains(center)) {
                return glass;
            }
        }
        return null;
    }

    private static boolean isSameOrOwnedBy(Window window, Window owner) {
        for (Window w = window; w != null; w = w.getOwner()) {
            if (w == owner) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mouse press, release and click as separate steps: what the press causes (a focus change
     * that runs an input verifier or commits a formatted field) happens before the release fires
     * the button's action, as with a real mouse.
     */
    private static List<Runnable> mouse(final Component c, final Point p, int count, final boolean right) {
        final int button = right ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1;
        final int downMask = right ? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;
        List<Runnable> steps = new ArrayList<Runnable>();
        for (int i = 1; i <= count; i++) {
            final int n = i;
            steps.add(new Runnable() {
                public void run() {
                    Window window = Keys.windowOf(c);
                    Component before = Keys.focusOwner(window);
                    c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), downMask,
                            p.x, p.y, n, right && !WINDOWS, button));
                    Keys.syncFocus(window, before);
                }
            });
            steps.add(new Runnable() {
                public void run() {
                    long now = System.currentTimeMillis();
                    c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, now, 0, p.x, p.y, n, right && WINDOWS, button));
                    c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, now, 0, p.x, p.y, n, false, button));
                }
            });
        }
        return steps;
    }

    /** Type and visible name of a component for messages, e.g. JTextField "Email". */
    static String describe(final Component c) {
        Component composite = SwingUtilities.getAncestorOfClass(JSpinner.class, c);
        if (composite == null) {
            composite = SwingUtilities.getAncestorOfClass(JComboBox.class, c);
        }
        if (composite != null) {
            return describe(composite); // the text field inside a spinner or editable combo box
        }
        if (!EventQueue.isDispatchThread() && Naming.isField(c)) {
            try {
                return Edt.call(new Callable<String>() {
                    public String call() {
                        return describe(c);
                    }
                }, CHECK_TIMEOUT_MS);
            } catch (Exception e) {
                // the UI thread is busy: describe it without its label
            }
        }
        String text = null;
        if (c instanceof AbstractButton) {
            text = ((AbstractButton) c).getText();
        } else if (c instanceof JLabel) {
            text = ((JLabel) c).getText();
        } else if (Naming.isField(c) && EventQueue.isDispatchThread()) {
            text = Naming.nameOf(c);
        }
        Class<?> cls = c.getClass();
        // JDK-internal subclasses (e.g. the text field of an input dialog) by their public type
        // and anonymous classes by the class they extend
        while (cls.getSuperclass() != null && (cls.getSimpleName().length() == 0
                || (Naming.isJdkClass(cls) && !java.lang.reflect.Modifier.isPublic(cls.getModifiers())))) {
            cls = cls.getSuperclass();
        }
        if (Text.isBlank(text) && c instanceof AbstractButton) {
            text = ((AbstractButton) c).getToolTipText();
        }
        String type = cls.getSimpleName().length() > 0 ? cls.getSimpleName() : cls.getName();
        return type + (Text.isBlank(text) ? "" : " " + Text.quote(Text.plain(text), 60));
    }

    private static int pick(List<String> items, String value, Integer index) {
        if (index != null) {
            checkIndex(index, items.size(), "index");
            return index;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).equals(value)) {
                return i;
            }
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).trim().equalsIgnoreCase(value.trim())) {
                return i;
            }
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).toLowerCase().contains(value.trim().toLowerCase())) {
                return i;
            }
        }
        throw new IllegalArgumentException("No option matching " + Text.quote(value) + "; options: " + items);
    }

    private static void checkIndex(int i, int size, String what) {
        if (i < 0 || i >= size) {
            throw new IllegalArgumentException(what + " " + i + " out of range (0.." + (size - 1) + ")");
        }
    }

    private static int intParam(Map<String, String> p, String key, int def) {
        Integer v = optInt(p, key);
        return v == null ? def : v;
    }

    private static Integer optInt(Map<String, String> p, String key) {
        String v = p.get(key);
        if (Text.isBlank(v)) {
            return null;
        }
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter " + key + " must be a number: " + v);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
