package io.github.swing2mcp.agent;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.ListCellRenderer;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Contents of tables, lists, trees and combo boxes as the user sees them (the text the cell
 * renderers paint), with the model value where it differs, and lookups by that text. Must run
 * on the EDT.
 */
final class Items {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 1000;
    /** Upper bound for scanning big tables in find; keeps the EDT responsive. */
    private static final int MAX_SCAN_CELLS = 500000;

    private Items() {
    }

    // ---------------------------------------------------------------- selection

    /** "0,2-5,9" for sorted indices; at most max numbers or ranges, then "…". */
    static String ranges(int[] indices, int max) {
        int[] sorted = indices.clone();
        java.util.Arrays.sort(sorted);
        StringBuilder sb = new StringBuilder();
        int parts = 0;
        for (int i = 0; i < sorted.length; i++) {
            int start = sorted[i];
            while (i + 1 < sorted.length && sorted[i + 1] == sorted[i] + 1) {
                i++;
            }
            if (parts == max) {
                sb.append(",…");
                break;
            }
            sb.append(parts > 0 ? "," : "").append(start).append(sorted[i] > start ? "-" + sorted[i] : "");
            parts++;
        }
        return sb.toString();
    }

    /** The selection of a list, table or tree for results, e.g. "items [0,2-3]", "2 nodes: ...", "nothing". */
    static String selectionText(Component c) {
        if (c instanceof JList) {
            int[] selected = ((JList<?>) c).getSelectedIndices();
            return selected.length == 0 ? "nothing" : (selected.length == 1 ? "item " : "items ")
                    + "[" + ranges(selected, 20) + "]";
        }
        if (c instanceof JTable) {
            JTable table = (JTable) c;
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) {
                return "nothing";
            }
            String text = (rows.length == 1 ? "row " : "rows ") + "[" + ranges(rows, 20) + "]";
            if (table.getColumnSelectionAllowed()) {
                text += " cols [" + ranges(table.getSelectedColumns(), 20) + "]";
            }
            return text;
        }
        if (c instanceof JTree) {
            JTree tree = (JTree) c;
            TreePath[] paths = tree.getSelectionPaths();
            if (paths == null || paths.length == 0) {
                return "nothing";
            }
            return paths.length + (paths.length == 1 ? " node: " : " nodes: ") + selectedPaths(tree, 10);
        }
        return "";
    }

    /** Selected tree paths in row order as "Root/Child", "Root/Other", at most max, then "(+N more)". */
    static String selectedPaths(JTree tree, int max) {
        int[] rows = tree.getSelectionRows();
        List<TreePath> paths = new ArrayList<TreePath>();
        if (rows != null) {
            rows = rows.clone();
            java.util.Arrays.sort(rows);
            for (int row : rows) {
                TreePath path = tree.getPathForRow(row);
                if (path != null) {
                    paths.add(path);
                }
            }
        }
        TreePath[] all = tree.getSelectionPaths();
        if (all != null) {
            for (TreePath path : all) {
                if (!paths.contains(path)) {
                    paths.add(path); // selected inside a collapsed node
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, paths.size()); i++) {
            sb.append(i > 0 ? ", " : "").append(Text.quote(pathText(tree, paths.get(i))));
        }
        if (paths.size() > max) {
            sb.append(" (+").append(paths.size() - max).append(" more)");
        }
        return sb.toString();
    }

    /** "Root/Child" as select and expand take it (the hidden root left out). */
    static String pathText(JTree tree, TreePath path) {
        StringBuilder sb = new StringBuilder();
        for (int i = tree.isRootVisible() ? 0 : 1; i < path.getPathCount(); i++) {
            sb.append(sb.length() > 0 ? "/" : "").append(nodeText(tree, path.getPathComponent(i)));
        }
        return sb.toString();
    }

    /** "single", "single range" or "multiple": what a user can select. */
    static String selectionMode(Component c) {
        int mode;
        if (c instanceof JList) {
            mode = ((JList<?>) c).getSelectionMode();
        } else if (c instanceof JTable) {
            mode = ((JTable) c).getSelectionModel().getSelectionMode();
        } else if (c instanceof JTree) {
            int treeMode = ((JTree) c).getSelectionModel() == null ? 0 : ((JTree) c).getSelectionModel().getSelectionMode();
            return treeMode == javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION ? "single"
                    : treeMode == javax.swing.tree.TreeSelectionModel.CONTIGUOUS_TREE_SELECTION ? "single range" : "multiple";
        } else {
            return "";
        }
        return mode == javax.swing.ListSelectionModel.SINGLE_SELECTION ? "single"
                : mode == javax.swing.ListSelectionModel.SINGLE_INTERVAL_SELECTION ? "single range" : "multiple";
    }

    /** A note for results when several entries were asked for but the component allows fewer. */
    static String selectionModeNote(Component c, boolean wantedSeveral) {
        String mode = selectionMode(c);
        if (!wantedSeveral || mode.equals("multiple") || mode.length() == 0) {
            return "";
        }
        return "\n" + Actions.describe(c) + " allows only " + (mode.equals("single") ? "one selected entry"
                : "one continuous range") + " (its selection mode), as for a user.";
    }

    // ---------------------------------------------------------------- text of one entry

    static String cellText(JTable table, int row, int col) {
        try {
            Component renderer = table.prepareRenderer(table.getCellRenderer(row, col), row, col);
            if (renderer instanceof JLabel) {
                return oneLine(Text.plain(((JLabel) renderer).getText()));
            }
            if (renderer instanceof AbstractButton) {
                return ((AbstractButton) renderer).isSelected() ? "[x]" : "[ ]";
            }
        } catch (RuntimeException ignored) {
            // fall back to the raw value
        }
        Object value = table.getValueAt(row, col);
        return value == null ? "" : oneLine(String.valueOf(value));
    }

    /** " (value=...)" when the model value reads differently from the rendered text. */
    static String valueNote(Object value, String text) {
        if (value == null || value instanceof Boolean) {
            return "";
        }
        String raw = value instanceof java.util.Date ? iso((java.util.Date) value) : oneLine(String.valueOf(value));
        if (raw.equals(text) || raw.trim().equals(text.trim())) {
            return "";
        }
        return " (value=" + Text.quote(raw, 80) + ")";
    }

    /** 2024-12-12, or 2024-12-12T08:30:00 when the time is not midnight. */
    private static String iso(java.util.Date date) {
        String full = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(date);
        return full.endsWith("T00:00:00") ? full.substring(0, 10) : full;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static String listText(JList list, int i) {
        Object value = list.getModel().getElementAt(i);
        try {
            Component rc = list.getCellRenderer().getListCellRendererComponent(list, value, i, false, false);
            if (rc instanceof JLabel) {
                return oneLine(Text.plain(((JLabel) rc).getText()));
            }
        } catch (RuntimeException ignored) {
            // fall back to toString
        }
        return oneLine(String.valueOf(value));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static String comboText(JComboBox combo, Object value, int index) {
        if (value == null) {
            return "";
        }
        try {
            Component rc = ((ListCellRenderer) combo.getRenderer())
                    .getListCellRendererComponent(new JList(), value, index, false, false);
            if (rc instanceof JLabel && !Text.isBlank(((JLabel) rc).getText())) {
                return oneLine(Text.plain(((JLabel) rc).getText()));
            }
        } catch (RuntimeException ignored) {
            // fall back to toString
        }
        return oneLine(String.valueOf(value));
    }

    /** The text the tree's renderer shows for a node (falls back to the tree's own conversion). */
    static String nodeText(JTree tree, Object node) {
        boolean leaf = tree.getModel().isLeaf(node);
        try {
            Component rc = tree.getCellRenderer().getTreeCellRendererComponent(tree, node, false, false, leaf, -1, false);
            if (rc instanceof JLabel && !Text.isBlank(((JLabel) rc).getText())) {
                return oneLine(Text.plain(((JLabel) rc).getText()));
            }
        } catch (RuntimeException ignored) {
            // fall back to convertValueToText
        }
        return oneLine(tree.convertValueToText(node, false, false, leaf, -1, false)).trim();
    }

    static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
    }

    // ---------------------------------------------------------------- lookups

    private static boolean matches(String text, String wanted) {
        return text.trim().equalsIgnoreCase(wanted.trim());
    }

    /** First view row with a cell whose shown text (or value) equals value; [row, col]. */
    static int[] findCell(JTable table, String value, Integer onlyCol) {
        for (int row = 0; row < table.getRowCount(); row++) {
            for (int col = 0; col < table.getColumnCount(); col++) {
                if (onlyCol != null && col != onlyCol) {
                    continue;
                }
                Object raw = table.getValueAt(row, col);
                if (matches(cellText(table, row, col), value) || (raw != null && matches(String.valueOf(raw), value))) {
                    return new int[]{row, col};
                }
            }
        }
        throw new IllegalArgumentException("No table cell shows " + Text.quote(value)
                + (onlyCol != null ? " in column " + onlyCol : "") + "; read_items lists the rows");
    }

    static int findListItem(JList<?> list, String value) {
        for (int i = 0; i < list.getModel().getSize(); i++) {
            if (matches(listText(list, i), value)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No list item " + Text.quote(value) + "; read_items lists the items");
    }

    /** Splits "Root/Child/Leaf" or "Root > Child > Leaf" into node texts. */
    static String[] pathParts(String path) {
        String[] parts = path.contains(" > ") ? path.split(" > ") : path.split("/");
        List<String> result = new ArrayList<String>();
        for (String part : parts) {
            if (part.trim().length() > 0) {
                result.add(part.trim());
            }
        }
        return result.toArray(new String[0]);
    }

    /**
     * Finds the node at a path of node texts; the root may be left out. With expand, every
     * ancestor is expanded on the way, as a user would do, so trees that load children on
     * expansion work. A missing child is "not ready yet" (children may load in the background),
     * so actions keep waiting for it.
     */
    static TreePath findPath(JTree tree, String value, boolean expand) {
        TreeModel model = tree.getModel();
        Object node = model.getRoot();
        if (node == null) {
            throw new IllegalArgumentException("The tree is empty");
        }
        String[] parts = pathParts(value);
        int start = parts.length > 0 && matches(nodeText(tree, node), parts[0]) ? 1 : 0;
        TreePath path = new TreePath(node);
        for (int p = start; p < parts.length; p++) {
            if (expand && !tree.isExpanded(path)) {
                tree.expandPath(path);
            }
            Object match = null;
            List<String> children = new ArrayList<String>();
            for (int i = 0; i < model.getChildCount(node); i++) {
                Object child = model.getChild(node, i);
                String text = nodeText(tree, child);
                if (matches(text, parts[p])) {
                    match = child;
                    break;
                }
                if (children.size() < 20) {
                    children.add(text);
                }
            }
            if (match == null) {
                String where = p == 0 ? "at the top of the tree" : "under " + Text.quote(join(parts, p));
                throw new Actions.NotReady("No node " + Text.quote(parts[p]) + " " + where + "; there are "
                        + (children.isEmpty() ? "no nodes" : children.toString()));
            }
            node = match;
            path = path.pathByAddingChild(node);
        }
        return path;
    }

    private static String join(String[] parts, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(i > 0 ? "/" : "").append(parts[i]);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- read_items

    static String read(Component c, Map<String, String> p) {
        int start = Math.max(0, intParam(p, "start", 0));
        int limit = Math.min(MAX_LIMIT, Math.max(1, intParam(p, "limit", DEFAULT_LIMIT)));
        String find = Text.isBlank(p.get("find")) ? null : p.get("find").trim().toLowerCase(Locale.ROOT);
        String head = Actions.describe(c);
        if (c instanceof JTable) {
            return head + "\n" + table((JTable) c, start, limit, find, p.get("columns"));
        }
        if (c instanceof JList) {
            return head + "\n" + list((JList<?>) c, start, limit, find);
        }
        if (c instanceof JTree) {
            return head + "\n" + tree((JTree) c, start, limit, find, p.get("path"), intParam(p, "depth", 1));
        }
        if (c instanceof JComboBox) {
            return head + "\n" + combo((JComboBox<?>) c, start, limit, find);
        }
        throw new IllegalArgumentException(Actions.describe(c) + " has no items; read_items reads tables, lists, trees "
                + "and combo boxes");
    }

    private static String table(JTable table, int start, int limit, String find, String columnSpec) {
        StringBuilder sb = new StringBuilder();
        int rows = table.getRowCount();
        int modelRows = table.getModel().getRowCount();
        sb.append("rows=").append(rows).append(" cols=").append(table.getColumnCount());
        if (modelRows != rows) {
            sb.append(" (filtered: ").append(rows).append(" of ").append(modelRows).append(" rows shown)");
        }
        if (table.getRowSorter() != null && table.getRowSorter().getSortKeys() != null
                && !table.getRowSorter().getSortKeys().isEmpty()) {
            sb.append(" sorted by column ").append(table.getRowSorter().getSortKeys().get(0).getColumn())
                    .append(' ').append(table.getRowSorter().getSortKeys().get(0).getSortOrder().toString().toLowerCase(Locale.ROOT));
        }
        sb.append(" selection=").append(selectionMode(table)).append(" selected: ").append(selectionText(table));
        sb.append('\n');
        List<Integer> cols = columns(table, columnSpec);
        sb.append("columns: ");
        for (int i = 0; i < cols.size(); i++) {
            sb.append(i > 0 ? " | " : "").append(cols.get(i)).append(' ').append(oneLine(table.getColumnName(cols.get(i))));
        }
        sb.append('\n');
        int shown = 0;
        int matched = 0;
        int scanned = 0;
        int next = -1;
        for (int row = find == null ? start : 0; row < rows; row++) {
            boolean hit = find == null;
            for (int col = 0; !hit && col < table.getColumnCount(); col++) {
                hit = cellText(table, row, col).toLowerCase(Locale.ROOT).contains(find);
            }
            scanned += table.getColumnCount();
            if (!hit) {
                if (scanned > MAX_SCAN_CELLS) {
                    sb.append("… stopped searching at row ").append(row).append(" (table too big); use start\n");
                    break;
                }
                continue;
            }
            matched++;
            if (find != null && matched <= start) {
                continue;
            }
            if (shown == limit) {
                next = find == null ? row : matched - 1;
                break;
            }
            StringBuilder line = new StringBuilder("row ").append(row);
            int modelRow = table.convertRowIndexToModel(row);
            if (modelRow != row) {
                line.append(" (model ").append(modelRow).append(')');
            }
            line.append(": ");
            for (int i = 0; i < cols.size(); i++) {
                String text = cellText(table, row, cols.get(i));
                line.append(i > 0 ? " | " : "").append(text).append(valueNote(table.getValueAt(row, cols.get(i)), text));
            }
            if (table.isRowSelected(row)) {
                line.append("  (selected)");
            }
            sb.append(line).append('\n');
            shown++;
        }
        footer(sb, shown, next, find, "rows");
        return sb.toString();
    }

    /** Columns by index or name ("0,2" or "Name,City"); all when blank. */
    private static List<Integer> columns(JTable table, String spec) {
        List<Integer> cols = new ArrayList<Integer>();
        if (Text.isBlank(spec)) {
            for (int col = 0; col < table.getColumnCount(); col++) {
                cols.add(col);
            }
            return cols;
        }
        for (String part : spec.split(",")) {
            String name = part.trim();
            int found = -1;
            if (name.matches("\\d+")) {
                found = Integer.parseInt(name);
                if (found >= table.getColumnCount()) {
                    found = -1;
                }
            } else {
                for (int col = 0; col < table.getColumnCount(); col++) {
                    if (matches(String.valueOf(table.getColumnName(col)), name)) {
                        found = col;
                        break;
                    }
                }
            }
            if (found < 0) {
                throw new IllegalArgumentException("No column " + Text.quote(name));
            }
            cols.add(found);
        }
        return cols;
    }

    private static String list(JList<?> list, int start, int limit, String find) {
        StringBuilder sb = new StringBuilder();
        int size = list.getModel().getSize();
        sb.append("items=").append(size).append(" selection=").append(selectionMode(list)).append(" selected: ")
                .append(selectionText(list)).append('\n');
        int shown = 0;
        int matched = 0;
        int next = -1;
        for (int i = find == null ? start : 0; i < size; i++) {
            String text = listText(list, i);
            if (find != null && !text.toLowerCase(Locale.ROOT).contains(find)) {
                continue;
            }
            matched++;
            if (find != null && matched <= start) {
                continue;
            }
            if (shown == limit) {
                next = find == null ? i : matched - 1;
                break;
            }
            sb.append("item ").append(i).append(": ").append(Text.quote(text))
                    .append(valueNote(list.getModel().getElementAt(i), text))
                    .append(list.isSelectedIndex(i) ? " (selected)" : "").append('\n');
            shown++;
        }
        footer(sb, shown, next, find, "items");
        return sb.toString();
    }

    private static String combo(JComboBox<?> combo, int start, int limit, String find) {
        StringBuilder sb = new StringBuilder();
        int size = combo.getItemCount();
        sb.append("options=").append(size).append(" value=")
                .append(Text.quote(comboText(combo, combo.getSelectedItem(), -1))).append('\n');
        int shown = 0;
        int matched = 0;
        int next = -1;
        for (int i = find == null ? start : 0; i < size; i++) {
            String text = comboText(combo, combo.getItemAt(i), i);
            if (find != null && !text.toLowerCase(Locale.ROOT).contains(find)) {
                continue;
            }
            matched++;
            if (find != null && matched <= start) {
                continue;
            }
            if (shown == limit) {
                next = find == null ? i : matched - 1;
                break;
            }
            sb.append("option ").append(i).append(": ").append(Text.quote(text))
                    .append(i == combo.getSelectedIndex() ? " (selected)" : "").append('\n');
            shown++;
        }
        footer(sb, shown, next, find, "options");
        return sb.toString();
    }

    /**
     * Without path: the visible rows, as on screen. With path: that node and its descendants to
     * depth levels, read from the tree model (collapsed nodes included, nothing gets expanded).
     */
    private static String tree(JTree tree, int start, int limit, String find, String path, int depth) {
        StringBuilder sb = new StringBuilder();
        sb.append("selection=").append(selectionMode(tree)).append(" selected: ").append(selectionText(tree)).append('\n');
        if (Text.isBlank(path)) {
            int rows = tree.getRowCount();
            sb.append("visibleRows=").append(rows).append('\n');
            int shown = 0;
            int next = -1;
            for (int row = start; row < rows; row++) {
                TreePath rowPath = tree.getPathForRow(row);
                Object node = rowPath.getLastPathComponent();
                String text = nodeText(tree, node);
                if (find != null && !text.toLowerCase(Locale.ROOT).contains(find)) {
                    continue;
                }
                if (shown == limit) {
                    next = row;
                    break;
                }
                indent(sb, rowPath.getPathCount() - 1);
                sb.append("node ").append(row).append(": ");
                nodeLine(sb, tree, rowPath, text);
                shown++;
            }
            footer(sb, shown, next, null, "rows");
            if (find != null) {
                sb.append("(find searches visible rows only; give path to search inside collapsed nodes)\n");
            }
            return sb.toString();
        }
        TreePath top = findPath(tree, path, false);
        int[] shown = {0};
        int[] total = {0};
        walk(sb, tree, top, 0, Math.max(0, depth), limit, find, shown, total);
        if (total[0] > shown[0]) {
            sb.append("… ").append(total[0] - shown[0]).append(" more nodes; raise limit or give a deeper path\n");
        }
        return sb.toString();
    }

    private static void walk(StringBuilder sb, JTree tree, TreePath path, int level, int depth, int limit, String find,
                             int[] shown, int[] total) {
        Object node = path.getLastPathComponent();
        String text = nodeText(tree, node);
        boolean hit = find == null || text.toLowerCase(Locale.ROOT).contains(find);
        if (hit) {
            total[0]++;
            if (shown[0] < limit) {
                indent(sb, level);
                nodeLine(sb, tree, path, text);
                shown[0]++;
            }
        }
        if (level >= depth) {
            return;
        }
        TreeModel model = tree.getModel();
        for (int i = 0; i < model.getChildCount(node); i++) {
            walk(sb, tree, path.pathByAddingChild(model.getChild(node, i)), level + 1, depth, limit, find, shown, total);
        }
    }

    private static void nodeLine(StringBuilder sb, JTree tree, TreePath path, String text) {
        Object node = path.getLastPathComponent();
        boolean leaf = tree.getModel().isLeaf(node);
        sb.append(Text.quote(text));
        if (!leaf) {
            sb.append(tree.isExpanded(path) ? " expanded" : " collapsed")
                    .append(" children=").append(tree.getModel().getChildCount(node));
        }
        if (tree.isPathSelected(path)) {
            sb.append(" (selected)");
        }
        sb.append('\n');
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }

    private static void footer(StringBuilder sb, int shown, int next, String find, String what) {
        if (shown == 0) {
            sb.append(find != null ? "(no " + what + " contain the text)\n" : "(no " + what + ")\n");
        }
        if (next >= 0) {
            sb.append("… more ").append(what).append("; continue with start=").append(next).append('\n');
        }
    }

    private static int intParam(Map<String, String> p, String key, int def) {
        String v = p.get(key);
        if (Text.isBlank(v)) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter " + key + " must be a number: " + v);
        }
    }
}
