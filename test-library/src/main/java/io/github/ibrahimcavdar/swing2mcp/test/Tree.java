package io.github.ibrahimcavdar.swing2mcp.test;

import java.util.Map;

/** A tree. A node is named by its path, e.g. {@code "Root/Child/Leaf"}; the root may be left out. */
public final class Tree extends Locator {

    Tree(SwingApp app, String locator) {
        super(app, locator);
    }

    /** Selects the node; its ancestors are expanded on the way. */
    public void select(String path) {
        Map<String, Object> params = target();
        params.put("value", path);
        act("/select", params);
    }

    public void expand(String path) {
        Map<String, Object> params = target();
        params.put("path", path);
        act("/expand", params);
    }

    public void collapse(String path) {
        Map<String, Object> params = target();
        params.put("path", path);
        params.put("collapse", true);
        act("/expand", params);
    }

    /** The path of the selected node, e.g. {@code "Root/Child"}; null if none is selected. */
    public String selectedPath() {
        return require().quoted("selected");
    }

    /** The visible rows, one per line. */
    public String read() {
        return app.get("/read", allRows());
    }
}
