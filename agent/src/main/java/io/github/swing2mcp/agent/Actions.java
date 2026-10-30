package io.github.swing2mcp.agent;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListCellRenderer;
import javax.swing.MenuSelectionManager;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.Component;
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
 * User-like actions. Validation runs synchronously on the EDT; the action itself is
 * queued with invokeLater so that an action which opens a modal dialog does not block
 * the agent. Afterwards we wait for the EDT to drain before reporting back.
 */
final class Actions {

    private static final long SETTLE_TIMEOUT_MS = 5000;
    private static final long CHECK_TIMEOUT_MS = 3000;

    private final RefRegistry refs;

    Actions(RefRegistry refs) {
        this.refs = refs;
    }

    static final class Result {
        final String message;
        final boolean settled;

        Result(String message, boolean settled) {
            this.message = message;
            this.settled = settled;
        }
    }

    Result click(final Map<String, String> p) throws Exception {
        final Component c = resolve(p);
        final int clickCount = intParam(p, "clickCount", 1);
        final boolean right = "right".equalsIgnoreCase(p.get("button"));
        final Integer row = optInt(p, "row");
        final Integer col = optInt(p, "col");
        final Integer x = optInt(p, "x");
        final Integer y = optInt(p, "y");

        final Point point = Edt.call(new Callable<Point>() {
            public Point call() {
                ensureUsable(c, c instanceof JMenuItem);
                if (c instanceof AbstractButton && clickCount == 1 && !right && x == null && row == null) {
                    return null; // use doClick
                }
                if (x != null && y != null) {
                    return new Point(x, y);
                }
                Rectangle cell = null;
                if (row != null && c instanceof JTable) {
                    JTable table = (JTable) c;
                    checkIndex(row, table.getRowCount(), "row");
                    cell = table.getCellRect(row, col == null ? 0 : col, false);
                } else if (row != null && c instanceof JList) {
                    checkIndex(row, ((JList<?>) c).getModel().getSize(), "row");
                    cell = ((JList<?>) c).getCellBounds(row, row);
                } else if (row != null && c instanceof JTree) {
                    checkIndex(row, ((JTree) c).getRowCount(), "row");
                    cell = ((JTree) c).getRowBounds(row);
                }
                if (cell != null) {
                    ((JComponent) c).scrollRectToVisible(cell);
                    return new Point(cell.x + Math.min(cell.width / 2, 20), cell.y + cell.height / 2);
                }
                return new Point(c.getWidth() / 2, c.getHeight() / 2);
            }
        }, CHECK_TIMEOUT_MS);

        final String what = describe(c) + (row != null ? " row " + row + (col != null ? " col " + col : "") : "");
        if (point == null) {
            return perform("Clicked " + what, new Runnable() {
                public void run() {
                    if (c instanceof JMenuItem) {
                        MenuSelectionManager.defaultManager().clearSelectedPath();
                    }
                    ((AbstractButton) c).doClick(0);
                }
            });
        }
        return perform((clickCount == 2 ? "Double-clicked " : right ? "Right-clicked " : "Clicked ") + what,
                new Runnable() {
                    public void run() {
                        mouse(c, point, clickCount, right);
                    }
                });
    }

    Result type(final Map<String, String> p) throws Exception {
        final Component c = resolve(p);
        final String text = p.get("text") == null ? "" : p.get("text");
        final boolean submit = "true".equalsIgnoreCase(p.get("submit"));
        final boolean append = "true".equalsIgnoreCase(p.get("append"));
        Edt.call(new Callable<Object>() {
            public Object call() {
                ensureUsable(c, false);
                if (c instanceof JTextComponent) {
                    if (!((JTextComponent) c).isEditable()) {
                        throw new IllegalArgumentException(describe(c) + " is read-only");
                    }
                } else if (!(c instanceof JSpinner) && !(c instanceof JComboBox && ((JComboBox<?>) c).isEditable())) {
                    throw new IllegalArgumentException(describe(c) + " does not accept text; use click or select_option");
                }
                return null;
            }
        }, CHECK_TIMEOUT_MS);

        return perform((append ? "Appended text to " : "Typed into ") + describe(c) + (submit ? " and pressed Enter" : ""),
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
        if (value == null && index == null) {
            throw new IllegalArgumentException("select needs either value or index");
        }
        final AtomicReference<Runnable> action = new AtomicReference<Runnable>();
        final String chosen = Edt.call(new Callable<String>() {
            public String call() {
                ensureUsable(c, false);
                if (c instanceof JComboBox) {
                    final JComboBox<?> combo = (JComboBox<?>) c;
                    List<String> items = new ArrayList<String>();
                    for (int i = 0; i < combo.getItemCount(); i++) {
                        items.add(comboText(combo, combo.getItemAt(i), i));
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
                        items.add(listText(list, i));
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
                        throw new IllegalArgumentException("Tab " + Text.quote(items.get(i)) + " is disabled");
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
                    final int row = index != null ? index : findRow(table, value);
                    checkIndex(row, table.getRowCount(), "row");
                    action.set(new Runnable() {
                        public void run() {
                            table.setRowSelectionInterval(row, row);
                            table.scrollRectToVisible(table.getCellRect(row, 0, true));
                        }
                    });
                    return "row " + row;
                }
                if (c instanceof JTree) {
                    final JTree tree = (JTree) c;
                    final TreePath path = index != null ? tree.getPathForRow(index) : findPath(tree, value);
                    if (path == null) {
                        throw new IllegalArgumentException("No tree node " + (index != null ? "at row " + index : Text.quote(value)));
                    }
                    action.set(new Runnable() {
                        public void run() {
                            tree.expandPath(path.getParentPath());
                            tree.setSelectionPath(path);
                            tree.scrollPathToVisible(path);
                        }
                    });
                    return String.valueOf(path.getLastPathComponent());
                }
                throw new IllegalArgumentException(describe(c) + " does not support select; use click");
            }
        }, CHECK_TIMEOUT_MS);
        return perform("Selected " + Text.quote(chosen) + " in " + describe(c), action.get());
    }

    // ---------------------------------------------------------------------------------

    /** Runs an action on the EDT without blocking on modal dialogs, then waits for the UI to settle. */
    static Result perform(String description, final Runnable action) {
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        EventQueue.invokeLater(new Runnable() {
            public void run() {
                try {
                    action.run();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }
        });
        boolean settled = Edt.flush(SETTLE_TIMEOUT_MS);
        if (settled) {
            // let follow-up invokeLater calls and repaints run
            sleep(150);
            settled = Edt.flush(2000);
        }
        StringBuilder sb = new StringBuilder(description);
        if (failure.get() != null) {
            sb.append("\nAction failed: ").append(failure.get());
        }
        if (!settled) {
            sb.append("\nThe UI thread is still busy after ").append(SETTLE_TIMEOUT_MS)
                    .append(" ms (long-running action?). Use wait_for or snapshot to check later.");
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

    /** Must be called on the EDT. */
    private static void ensureUsable(Component c, boolean allowHidden) {
        if (!allowHidden && !c.isShowing()) {
            throw new IllegalArgumentException(describe(c) + " is not visible; take a new snapshot");
        }
        if (!c.isEnabled()) {
            throw new IllegalArgumentException(describe(c) + " is disabled");
        }
        Dialog modal = SnapshotBuilder.activeModalDialog();
        Window window = windowOf(c);
        if (modal != null && window != null && !isSameOrOwnedBy(window, modal)) {
            throw new IllegalArgumentException("Blocked by modal dialog " + Text.quote(modal.getTitle())
                    + "; close it first");
        }
    }

    /** Window of a component; for items in a closed menu, the window of the menu bar. */
    private static Window windowOf(Component c) {
        Component invoker = c;
        while (invoker != null) {
            if (invoker instanceof Window) {
                return (Window) invoker;
            }
            Component parent = invoker.getParent();
            if (parent == null && invoker instanceof javax.swing.JPopupMenu) {
                parent = ((javax.swing.JPopupMenu) invoker).getInvoker();
            }
            invoker = parent;
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

    private static void mouse(Component c, Point p, int count, boolean right) {
        int button = right ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1;
        int downMask = right ? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;
        long now = System.currentTimeMillis();
        for (int i = 1; i <= count; i++) {
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, now, downMask, p.x, p.y, i, false, button));
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, now, 0, p.x, p.y, i, right, button));
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, now, 0, p.x, p.y, i, false, button));
        }
    }

    static String describe(Component c) {
        String text = null;
        if (c instanceof AbstractButton) {
            text = ((AbstractButton) c).getText();
        } else if (c instanceof JLabel) {
            text = ((JLabel) c).getText();
        }
        String type = c.getClass().getSimpleName().length() > 0 ? c.getClass().getSimpleName() : c.getClass().getName();
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

    private static int findRow(JTable table, String value) {
        for (int row = 0; row < table.getRowCount(); row++) {
            for (int col = 0; col < table.getColumnCount(); col++) {
                Object cell = table.getValueAt(row, col);
                if (cell != null && String.valueOf(cell).trim().equalsIgnoreCase(value.trim())) {
                    return row;
                }
            }
        }
        throw new IllegalArgumentException("No table row has a cell equal to " + Text.quote(value));
    }

    /** value is a "/"-separated path of node texts, e.g. "Categories/Electronics/Phones". */
    private static TreePath findPath(JTree tree, String value) {
        TreeModel model = tree.getModel();
        Object node = model.getRoot();
        String[] parts = value.split("/");
        int start = 0;
        if (parts.length > 0 && text(tree, node).equalsIgnoreCase(parts[0].trim())) {
            start = 1;
        }
        TreePath path = new TreePath(node);
        for (int p = start; p < parts.length; p++) {
            Object match = null;
            for (int i = 0; i < model.getChildCount(node); i++) {
                Object child = model.getChild(node, i);
                if (text(tree, child).equalsIgnoreCase(parts[p].trim())) {
                    match = child;
                    break;
                }
            }
            if (match == null) {
                return null;
            }
            node = match;
            path = path.pathByAddingChild(node);
        }
        return path;
    }

    private static String text(JTree tree, Object node) {
        return tree.convertValueToText(node, false, false, tree.getModel().isLeaf(node), -1, false).trim();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String listText(JList list, int i) {
        Object value = list.getModel().getElementAt(i);
        try {
            Component rc = list.getCellRenderer().getListCellRendererComponent(list, value, i, false, false);
            if (rc instanceof JLabel) {
                return Text.plain(((JLabel) rc).getText());
            }
        } catch (RuntimeException ignored) {
            // fall back to toString
        }
        return String.valueOf(value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String comboText(JComboBox combo, Object value, int index) {
        try {
            Component rc = ((ListCellRenderer) combo.getRenderer())
                    .getListCellRendererComponent(new JList(), value, index, false, false);
            if (rc instanceof JLabel && !Text.isBlank(((JLabel) rc).getText())) {
                return Text.plain(((JLabel) rc).getText());
            }
        } catch (RuntimeException ignored) {
            // fall back to toString
        }
        return String.valueOf(value);
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
