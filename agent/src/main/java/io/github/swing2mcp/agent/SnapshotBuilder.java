package io.github.swing2mcp.agent;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDesktopPane;
import javax.swing.JEditorPane;
import javax.swing.JFormattedTextField;
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JRootPane;
import javax.swing.JScrollBar;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.RootPaneContainer;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.table.JTableHeader;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the live Swing component tree into a compact, indented text outline that an
 * AI model can read, in the spirit of Playwright's accessibility snapshot. Every
 * element gets a {@code [ref=eN]} that the action endpoints accept. Must run on the EDT.
 */
final class SnapshotBuilder {

    static final class Options {
        /** Rows of tables and items of lists shown; trees show twice as many rows. */
        int maxRows = 10;
        boolean expandMenus = false;
    }

    /** Columns and characters per cell shown for a table; read_items gives the rest. */
    private static final int MAX_COLUMNS = 8;
    private static final int MAX_CELL = 40;

    private final RefRegistry refs;
    private final Options options;
    private final StringBuilder out = new StringBuilder();
    private final Naming naming = new Naming();
    /** Where keys go now (also in an inactive window); marked "focused". */
    private Component focusOwner;

    SnapshotBuilder(RefRegistry refs, Options options) {
        this.refs = refs;
        this.options = options;
    }

    static List<Window> showingWindows() {
        List<Window> result = new ArrayList<Window>();
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && window.getWidth() > 0 && window.getHeight() > 0 && !isFacade(window)) {
                result.add(window);
            }
        }
        return result;
    }

    /**
     * A Window object that only forwards to another container, e.g. a framework's "window" facade
     * for an internal frame: its children belong to that container, which the snapshot already
     * shows where it really is.
     */
    private static boolean isFacade(Window window) {
        Component[] children = window.getComponents();
        return children.length > 0 && children[0].getParent() != window;
    }

    /** The modal dialog that currently blocks input, if any (the most recently opened one). */
    static Dialog activeModalDialog() {
        Window active = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (active instanceof Dialog && ((Dialog) active).isModal() && active.isShowing()) {
            return (Dialog) active;
        }
        List<Dialog> modals = new ArrayList<Dialog>();
        for (Window window : showingWindows()) {
            if (window instanceof Dialog && ((Dialog) window).isModal()) {
                modals.add((Dialog) window);
            }
        }
        // the top one: a modal dialog that owns no other open modal dialog (newest if several)
        Dialog found = null;
        for (Dialog candidate : modals) {
            boolean ownsModal = false;
            for (Dialog other : modals) {
                for (Window w = other.getOwner(); other != candidate && w != null && !ownsModal; w = w.getOwner()) {
                    ownsModal = w == candidate;
                }
            }
            if (!ownsModal) {
                found = candidate;
            }
        }
        return found;
    }

    String build(Window only) {
        List<Window> windows = showingWindows();
        if (windows.isEmpty()) {
            return "(no visible windows)\n";
        }
        focusOwner = Keys.focusOwner();
        Dialog modal = activeModalDialog();
        if (modal != null && only == null) {
            out.append("NOTE: modal dialog ").append(Text.quote(modal.getTitle())).append(" [ref=")
                    .append(refs.refFor(modal)).append("] is open and blocks the other windows.\n");
        }
        for (Window window : windows) {
            if (only != null && window != only) {
                continue;
            }
            naming.indexWindow(window);
            int before = out.length();
            line(0, windowHeader(window));
            int afterHeader = out.length();
            if (window instanceof RootPaneContainer) {
                JRootPane rootPane = ((RootPaneContainer) window).getRootPane();
                if (rootPane.getJMenuBar() != null && rootPane.getJMenuBar().isVisible()) {
                    menuBar(rootPane.getJMenuBar(), 1);
                }
            }
            naming.scanOwner(window);
            children(window, 1);
            // Borderless helper windows (e.g. heavyweight menu popups already shown under their menu).
            if (out.length() == afterHeader && !(window instanceof Frame) && !(window instanceof Dialog)) {
                out.setLength(before);
            }
        }
        return out.toString();
    }

    private String windowHeader(Window window) {
        StringBuilder sb = new StringBuilder();
        if (window instanceof Frame) {
            sb.append("frame ").append(Text.quote(((Frame) window).getTitle()));
        } else if (window instanceof Dialog) {
            sb.append("dialog ").append(Text.quote(((Dialog) window).getTitle()));
        } else {
            sb.append("window");
        }
        sb.append(ref(window)).append(' ').append(window.getWidth()).append('x').append(window.getHeight());
        if (window instanceof Dialog && ((Dialog) window).isModal()) {
            sb.append(" modal");
        }
        javax.swing.JOptionPane pane = Dialogs.find(window, javax.swing.JOptionPane.class);
        if (pane != null) {
            sb.append(" messagebox=").append(Dialogs.messageType(pane.getMessageType()));
        }
        if (window.isActive()) {
            sb.append(" active");
        }
        String field = className(window);
        if (field != null) {
            sb.append(" class=").append(field);
        }
        return sb.toString();
    }

    private void children(Container container, int depth) {
        for (Component child : container.getComponents()) {
            visit(child, depth);
        }
    }

    private void visit(Component c, int depth) {
        if (!c.isShowing() || skip(c)) {
            return;
        }
        if (c instanceof JMenuBar) {
            return; // emitted first by build()
        }
        if (c instanceof JPopupMenu) {
            JPopupMenu popup = (JPopupMenu) c;
            if (popup.getInvoker() instanceof JMenu) {
                return; // shown under its menu
            }
            line(depth, "popupmenu" + ref(c));
            for (Component item : popup.getComponents()) {
                if (item instanceof JMenuItem && item.isVisible()) {
                    menuItem((JMenuItem) item, depth + 1);
                }
            }
            return;
        }
        String widget = widget(c);
        if (widget != null) {
            line(depth, widget);
            widgetDetails(c, depth + 1);
            return;
        }
        if (!(c instanceof Container) || c instanceof JLabel) {
            return; // e.g. a label (also a subclass) already used as a field's name, or without text
        }
        Container container = (Container) c;
        boolean custom = !Naming.isJdkClass(c.getClass());
        if (custom) {
            naming.scanOwner(c);
        }
        String header = containerHeader(container, custom);
        if (header != null) {
            line(depth, header);
            if (container.getComponentCount() == 0 && custom) {
                return;
            }
            children(container, depth + 1);
        } else {
            children(container, depth);
        }
    }

    private static boolean skip(Component c) {
        return c instanceof JScrollBar || c instanceof JTableHeader || c instanceof BasicSplitPaneDivider;
    }

    /** Header line for containers worth showing; null for purely structural containers. */
    private String containerHeader(Container c, boolean custom) {
        String title = titledBorder(c);
        if (c instanceof javax.swing.JFileChooser) {
            javax.swing.JFileChooser chooser = (javax.swing.JFileChooser) c;
            String mode = chooser.getDialogType() == javax.swing.JFileChooser.SAVE_DIALOG ? "save"
                    : chooser.getDialogType() == javax.swing.JFileChooser.OPEN_DIALOG ? "open" : "custom";
            String what = chooser.getFileSelectionMode() == javax.swing.JFileChooser.DIRECTORIES_ONLY ? " directories"
                    : chooser.getFileSelectionMode() == javax.swing.JFileChooser.FILES_AND_DIRECTORIES ? " files+directories" : "";
            return "filechooser " + mode + what + ref(c) + " dir=" + Text.quote(String.valueOf(chooser.getCurrentDirectory()))
                    + " (handle_dialog path=... picks a file)";
        }
        if (c instanceof JInternalFrame) {
            return "internalframe " + Text.quote(((JInternalFrame) c).getTitle()) + ref(c) + classSuffix(c);
        }
        if (c instanceof JToolBar) {
            return "toolbar" + (title != null ? " " + Text.quote(title) : "") + ref(c);
        }
        if (title != null) {
            return "group " + Text.quote(title) + ref(c) + classSuffix(c) + fieldSuffix(c);
        }
        if (custom) {
            if (c.getComponentCount() == 0 && c instanceof JComponent) {
                String text = Text.plain(customText(c));
                if (!Text.isBlank(text)) {
                    return "custom " + className(c) + " " + Text.quote(text, 200) + ref(c) + states(c) + fieldSuffix(c);
                }
                return "custom " + className(c) + ref(c) + " " + c.getWidth() + "x" + c.getHeight()
                        + " (custom-painted, use screenshot to see it)" + fieldSuffix(c);
            }
            return "panel " + className(c) + ref(c) + fieldSuffix(c);
        }
        if (c instanceof JDesktopPane) {
            return "desktop" + ref(c);
        }
        return null;
    }

    /** One-line description for leaf widgets, or null if c is not a widget. */
    private String widget(Component c) {
        if (c instanceof JLabel) {
            JLabel label = (JLabel) c;
            String text = Text.plain(label.getText());
            if (naming.isConsumed(label) || Text.isBlank(text)) {
                return Text.isBlank(text) && !Text.isBlank(label.getToolTipText()) && label.getIcon() != null
                        ? "image " + Text.quote(Text.plain(label.getToolTipText())) + ref(c) : null;
            }
            return "label " + Text.quote(text, 200) + ref(c) + states(c) + fieldSuffix(c);
        }
        if (c instanceof AbstractButton) {
            AbstractButton b = (AbstractButton) c;
            StringBuilder sb = new StringBuilder(buttonRole(b));
            String name = naming.buttonName(b);
            if (name != null) {
                sb.append(' ').append(Text.quote(name));
            } else if (b.getIcon() != null) {
                sb.append(" (icon only)");
            }
            sb.append(ref(c)).append(states(c));
            if (b instanceof JButton && ((JButton) b).isDefaultButton()) {
                sb.append(" default");
            }
            return sb.append(fieldSuffix(c)).toString();
        }
        if (c instanceof JTextComponent) {
            return textWidget((JTextComponent) c);
        }
        if (c instanceof JComboBox) {
            JComboBox<?> combo = (JComboBox<?>) c;
            StringBuilder sb = new StringBuilder("combobox").append(named(c)).append(ref(c));
            sb.append(" value=").append(Text.quote(Items.comboText(combo, combo.getSelectedItem(), -1)));
            sb.append(" options=[");
            int count = combo.getItemCount();
            for (int i = 0; i < Math.min(count, options.maxRows); i++) {
                sb.append(i > 0 ? ", " : "").append(Text.quote(Items.comboText(combo, combo.getItemAt(i), i), 60));
            }
            if (count > options.maxRows) {
                sb.append(", … ").append(count - options.maxRows).append(" more");
            }
            sb.append(']');
            if (combo.isEditable()) {
                sb.append(" editable");
            }
            return sb.append(states(c)).append(fieldSuffix(c)).toString();
        }
        if (c instanceof JList) {
            JList<?> list = (JList<?>) c;
            return "list" + named(c) + ref(c) + " items=" + list.getModel().getSize()
                    + selection(list.getSelectedIndices()) + states(c) + fieldSuffix(c);
        }
        if (c instanceof JTable) {
            JTable table = (JTable) c;
            return "table" + named(c) + ref(c) + " rows=" + table.getRowCount() + " cols=" + table.getColumnCount()
                    + selection(table.getSelectedRows()) + states(c) + fieldSuffix(c);
        }
        if (c instanceof JTree) {
            JTree tree = (JTree) c;
            return "tree" + named(c) + ref(c) + " visibleRows=" + tree.getRowCount()
                    + selectedNode(tree) + states(c) + fieldSuffix(c);
        }
        if (c instanceof JTabbedPane) {
            JTabbedPane tabs = (JTabbedPane) c;
            StringBuilder sb = new StringBuilder("tabs").append(ref(c)).append(" tabs=[");
            for (int i = 0; i < tabs.getTabCount(); i++) {
                sb.append(i > 0 ? ", " : "").append(Text.quote(Text.plain(tabs.getTitleAt(i)), 60));
                if (i == tabs.getSelectedIndex()) {
                    sb.append("(selected)");
                } else if (!tabs.isEnabledAt(i)) {
                    sb.append("(disabled)");
                }
            }
            return sb.append(']').append(fieldSuffix(c)).toString();
        }
        if (c instanceof JSpinner) {
            return "spinner" + named(c) + ref(c) + " value=" + Text.quote(String.valueOf(((JSpinner) c).getValue()))
                    + states(c) + fieldSuffix(c);
        }
        if (c instanceof JSlider) {
            JSlider s = (JSlider) c;
            return "slider" + named(c) + ref(c) + " value=" + s.getValue() + " min=" + s.getMinimum()
                    + " max=" + s.getMaximum() + states(c) + fieldSuffix(c);
        }
        if (c instanceof JProgressBar) {
            JProgressBar p = (JProgressBar) c;
            String value = p.isIndeterminate() ? "indeterminate"
                    : (p.isStringPainted() ? Text.quote(p.getString()) : p.getValue() + "/" + p.getMaximum());
            return "progressbar" + ref(c) + " value=" + value;
        }
        return null;
    }

    /** Extra indented lines for widgets with inner content (table rows, list items...). */
    private void widgetDetails(Component c, int depth) {
        if (c instanceof JTabbedPane) {
            children((Container) c, depth);
        } else if (c instanceof JTable) {
            tableRows((JTable) c, depth);
        } else if (c instanceof JList) {
            listItems((JList<?>) c, depth);
        } else if (c instanceof JTree) {
            treeRows((JTree) c, depth);
        }
    }

    private String textWidget(JTextComponent t) {
        String role;
        if (t instanceof JPasswordField) {
            role = "passwordfield";
        } else if (t instanceof JTextArea) {
            role = "textarea";
        } else if (t instanceof JEditorPane) {
            role = "textpane";
        } else {
            role = "textfield";
        }
        StringBuilder sb = new StringBuilder(role).append(named(t)).append(ref(t));
        if (t instanceof JPasswordField) {
            sb.append(" value=(").append(t.getDocument().getLength()).append(" chars, hidden)");
        } else {
            String value;
            try {
                value = t.getDocument().getText(0, t.getDocument().getLength());
            } catch (Exception e) {
                value = t.getText();
            }
            sb.append(" value=").append(Text.quote(value, 300));
        }
        if (t instanceof JFormattedTextField && !((JFormattedTextField) t).isEditValid()) {
            sb.append(" invalid");
        }
        if (!t.isEditable()) {
            sb.append(" readonly");
        }
        return sb.append(states(t)).append(fieldSuffix(t)).toString();
    }

    private void tableRows(JTable table, int depth) {
        int cols = Math.min(table.getColumnCount(), MAX_COLUMNS);
        StringBuilder header = new StringBuilder("columns: ");
        for (int col = 0; col < cols; col++) {
            header.append(col > 0 ? " | " : "").append(Text.plain(table.getColumnName(col)));
        }
        if (cols < table.getColumnCount()) {
            header.append(" | … ").append(table.getColumnCount() - cols).append(" more columns");
        }
        line(depth, header.toString());
        int rows = table.getRowCount();
        int first = 0;
        Rectangle visible = table.getVisibleRect();
        int topRow = table.rowAtPoint(visible.getLocation());
        if (topRow > 0) {
            first = topRow;
        }
        int last = Math.min(rows, first + options.maxRows);
        if (first > 0) {
            line(depth, "… " + first + " rows above");
        }
        for (int row = first; row < last; row++) {
            StringBuilder sb = new StringBuilder("row ").append(row).append(": ");
            for (int col = 0; col < cols; col++) {
                String text = Items.cellText(table, row, col);
                sb.append(col > 0 ? " | " : "").append(text.length() > MAX_CELL ? text.substring(0, MAX_CELL) + "…" : text);
            }
            if (table.isRowSelected(row)) {
                sb.append("  (selected)");
            }
            line(depth, sb.toString());
        }
        if (last < rows || cols < table.getColumnCount()) {
            line(depth, (last < rows ? "… " + (rows - last) + " more rows; " : "") + "read_items ref="
                    + refs.refFor(table) + " reads all rows and columns");
        }
    }

    private void listItems(JList<?> list, int depth) {
        int size = list.getModel().getSize();
        int first = Math.max(0, list.getFirstVisibleIndex());
        int last = Math.min(size, first + options.maxRows);
        if (first > 0) {
            line(depth, "… " + first + " items above");
        }
        for (int i = first; i < last; i++) {
            line(depth, "item " + i + ": " + Text.quote(Items.listText(list, i)) + (list.isSelectedIndex(i) ? " (selected)" : ""));
        }
        if (last < size) {
            line(depth, "… " + (size - last) + " more items; read_items ref=" + refs.refFor(list) + " reads them");
        }
    }

    /** The visible rows (around the first selected one), as indented nodes. */
    private void treeRows(JTree tree, int depth) {
        int rows = tree.getRowCount();
        int limit = options.maxRows * 2;
        int first = 0;
        int lead = tree.getLeadSelectionRow();
        if (lead >= limit) {
            first = Math.min(lead - limit / 2, Math.max(0, rows - limit));
        }
        int last = Math.min(rows, first + limit);
        if (first > 0) {
            line(depth, "… " + first + " rows above");
        }
        for (int row = first; row < last; row++) {
            TreePath path = tree.getPathForRow(row);
            Object node = path.getLastPathComponent();
            boolean leaf = tree.getModel().isLeaf(node);
            StringBuilder sb = new StringBuilder("node ").append(row).append(": ")
                    .append(Text.quote(Items.nodeText(tree, node)));
            if (!leaf) {
                sb.append(tree.isExpanded(row) ? " expanded" : " collapsed");
            }
            if (tree.isRowSelected(row)) {
                sb.append(" (selected)");
            }
            line(depth + path.getPathCount() - 1, sb.toString());
        }
        if (last < rows) {
            line(depth, "… " + (rows - last) + " more rows; read_items ref=" + refs.refFor(tree) + " reads them");
        }
    }

    /** selected="Root/Child" for the lead selection of a tree (the path is what expand and select take). */
    private static String selectedNode(JTree tree) {
        TreePath path = tree.getLeadSelectionPath();
        if (path == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = tree.isRootVisible() ? 0 : 1; i < path.getPathCount(); i++) {
            sb.append(sb.length() > 0 ? "/" : "").append(Items.nodeText(tree, path.getPathComponent(i)));
        }
        int count = tree.getSelectionCount();
        return " selected=" + Text.quote(sb.toString()) + (count > 1 ? " (+" + (count - 1) + " more)" : "");
    }

    private void menuBar(JMenuBar bar, int depth) {
        line(depth, "menubar" + ref(bar));
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu menu = bar.getMenu(i);
            if (menu != null && menu.isVisible()) {
                menuItem(menu, depth + 1);
            }
        }
    }

    private void menuItem(JMenuItem item, int depth) {
        StringBuilder sb = new StringBuilder(buttonRole(item));
        sb.append(' ').append(Text.quote(Text.plain(item.getText()))).append(ref(item));
        if (!item.isEnabled()) {
            sb.append(" disabled");
        }
        if (item instanceof JCheckBoxMenuItem || item instanceof JRadioButtonMenuItem) {
            sb.append(item.isSelected() ? " checked" : " unchecked");
        }
        KeyStroke accelerator = item.getAccelerator();
        if (accelerator != null && !(item instanceof JMenu)) {
            sb.append(" shortcut=").append(Text.quote(accelerator.toString().replace("pressed ", "")));
        }
        if (item instanceof JMenu) {
            JMenu menu = (JMenu) item;
            boolean open = menu.isPopupMenuVisible();
            if (!open && !options.expandMenus) {
                line(depth, sb.append(" items=").append(menu.getItemCount()).append(" (closed)").toString());
                return;
            }
            line(depth, sb.append(open ? " (open)" : "").toString());
            for (Component sub : menu.getMenuComponents()) {
                if (sub instanceof JMenuItem && sub.isVisible()) {
                    menuItem((JMenuItem) sub, depth + 1);
                }
            }
            return;
        }
        line(depth, sb.toString());
    }

    private static String buttonRole(AbstractButton b) {
        if (b instanceof JMenu) {
            return "menu";
        }
        if (b instanceof JCheckBoxMenuItem) {
            return "checkmenuitem";
        }
        if (b instanceof JRadioButtonMenuItem) {
            return "radiomenuitem";
        }
        if (b instanceof JMenuItem) {
            return "menuitem";
        }
        if (b instanceof JCheckBox) {
            return "checkbox";
        }
        if (b instanceof JRadioButton) {
            return "radio";
        }
        if (b instanceof JToggleButton) {
            return "togglebutton";
        }
        return "button";
    }

    private String states(Component c) {
        StringBuilder sb = new StringBuilder();
        if (!c.isEnabled()) {
            sb.append(" disabled");
        }
        if (c instanceof JToggleButton) {
            sb.append(((JToggleButton) c).isSelected() ? " checked" : " unchecked");
        }
        if (c == focusOwner || (focusOwner != null && Actions.inputOf(c) == focusOwner)) {
            sb.append(" focused");
        }
        return sb.toString();
    }

    private static String selection(int[] indices) {
        if (indices == null || indices.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" selected=[");
        for (int i = 0; i < Math.min(indices.length, 20); i++) {
            sb.append(i > 0 ? "," : "").append(indices[i]);
        }
        if (indices.length > 20) {
            sb.append(",…");
        }
        return sb.append(']').toString();
    }

    private String named(Component c) {
        String name = naming.nameFor(c);
        return name == null ? "" : " " + Text.quote(name);
    }

    private String ref(Component c) {
        return " [ref=" + refs.refFor(c) + "]";
    }

    private String fieldSuffix(Component c) {
        String field = naming.fieldName(c);
        return field == null ? "" : " field=" + field;
    }

    private static String classSuffix(Component c) {
        String name = className(c);
        return name == null ? "" : " class=" + name;
    }

    /** Simple name of an application (non-JDK) class, null for plain Swing classes. */
    private static String className(Component c) {
        Class<?> type = c.getClass();
        if (Naming.isJdkClass(type)) {
            return null;
        }
        String name = type.getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /**
     * Text of a custom-painted component that offers it the usual way: a public no-argument
     * getText() (or getLabel()/getTitle()) returning a String, found by reflection.
     */
    private static String customText(Component c) {
        for (String name : new String[]{"getText", "getLabel", "getTitle"}) {
            try {
                java.lang.reflect.Method method = c.getClass().getMethod(name);
                if (method.getReturnType() == String.class) {
                    if (!java.lang.reflect.Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                        method.setAccessible(true); // public method of a non-public class
                    }
                    return (String) method.invoke(c);
                }
            } catch (NoSuchMethodException e) {
                // try the next name
            } catch (Throwable e) {
                return null; // the component's own code failed; show it as custom-painted
            }
        }
        return null;
    }

    private static String titledBorder(Container c) {
        if (!(c instanceof JComponent)) {
            return null;
        }
        Border border = ((JComponent) c).getBorder();
        while (border instanceof CompoundBorder) {
            Border outside = ((CompoundBorder) border).getOutsideBorder();
            border = outside instanceof TitledBorder ? outside : ((CompoundBorder) border).getInsideBorder();
        }
        if (border instanceof TitledBorder && !Text.isBlank(((TitledBorder) border).getTitle())) {
            return Text.plain(((TitledBorder) border).getTitle());
        }
        return null;
    }

    private void line(int depth, String text) {
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
        out.append("- ").append(text).append('\n');
    }
}
