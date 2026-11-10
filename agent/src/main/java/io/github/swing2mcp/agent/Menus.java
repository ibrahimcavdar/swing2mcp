package io.github.swing2mcp.agent;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Menu paths such as "File > Open" in a menu bar or an open context menu. Menus are opened the way
 * Swing opens them for the mouse (MenuSelectionManager), level by level, so listeners that fill
 * or enable items when a menu opens run as for a user. Must run on the EDT.
 */
final class Menus {

    private Menus() {
    }

    static String[] parts(String path) {
        String[] raw = path.contains(">") ? path.split(">") : path.split("/");
        List<String> parts = new ArrayList<String>();
        for (String part : raw) {
            if (part.trim().length() > 0) {
                parts.add(part.trim());
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Missing parameter: path (e.g. \"File > Open\")");
        }
        return parts.toArray(new String[0]);
    }

    /** The menu bar for a component (its innermost root pane with one) or for the key window. */
    static JMenuBar menuBar(Component c) {
        for (Component at = c; at != null; at = at.getParent()) {
            if (at instanceof JRootPane && ((JRootPane) at).getJMenuBar() != null) {
                return ((JRootPane) at).getJMenuBar();
            }
        }
        Window window = c == null ? Keys.targetWindow() : Keys.windowOf(c);
        JRootPane root = window == null ? null : SwingUtilities.getRootPane(window);
        return root == null ? null : root.getJMenuBar();
    }

    /** The context menu that is open now, if any. */
    static JPopupMenu openPopup() {
        MenuElement[] selected = MenuSelectionManager.defaultManager().getSelectedPath();
        if (selected.length > 0 && selected[0] instanceof JPopupMenu && ((JPopupMenu) selected[0]).isShowing()) {
            return (JPopupMenu) selected[0];
        }
        return null;
    }

    static String text(JMenuItem item) {
        return item.getText() == null ? "" : Text.plain(item.getText());
    }

    private static String loose(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        while (t.endsWith(".") || t.endsWith("…")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        return t;
    }

    /** The item with this text among the children (exact, then ignoring case and trailing dots). */
    static JMenuItem child(MenuElement parent, String name, String path) {
        List<JMenuItem> items = items(parent);
        for (JMenuItem item : items) {
            if (text(item).equals(name)) {
                return item;
            }
        }
        for (JMenuItem item : items) {
            if (loose(text(item)).equals(loose(name))) {
                return item;
            }
        }
        List<String> names = new ArrayList<String>();
        for (JMenuItem item : items) {
            names.add(text(item));
        }
        throw new IllegalArgumentException("No menu item " + Text.quote(name) + " in " + Text.quote(path)
                + "; items there: " + names);
    }

    /** Visible menu items of a menu bar, a menu (its popup) or a popup menu. */
    static List<JMenuItem> items(MenuElement parent) {
        List<JMenuItem> items = new ArrayList<JMenuItem>();
        Component[] children = parent instanceof JMenu ? ((JMenu) parent).getMenuComponents()
                : ((java.awt.Container) parent).getComponents();
        for (Component child : children) {
            if (child instanceof JMenuItem && child.isVisible()) {
                items.add((JMenuItem) child);
            }
        }
        return items;
    }

    /**
     * Opens the menus along the way to item: selects the path in the MenuSelectionManager as a
     * mouse over the menus would, with the popup of the last menu shown.
     */
    static void open(MenuElement root, List<JMenu> menus) {
        List<MenuElement> path = new ArrayList<MenuElement>();
        path.add(root);
        for (JMenu menu : menus) {
            path.add(menu);
            path.add(menu.getPopupMenu());
        }
        MenuSelectionManager.defaultManager().setSelectedPath(path.toArray(new MenuElement[0]));
    }

    static void closeAll() {
        MenuSelectionManager.defaultManager().clearSelectedPath();
    }
}
