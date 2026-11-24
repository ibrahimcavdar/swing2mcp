package io.github.ibrahimcavdar.swing2mcp.test;

import java.util.Map;

/**
 * A component found by its Java field or by a locator, so its kind is not known here: every action is
 * offered, and one that does not fit the component fails when the test runs.
 */
public final class AnyComponent extends Locator {

    AnyComponent(SwingApp app, String locator) {
        super(app, locator);
    }

    /** Replaces the content of a text component by typing. */
    public void fill(String text) {
        Map<String, Object> params = target();
        params.put("text", text);
        act("/type", params);
    }

    /** Picks an entry of a combo box, list, tabbed pane, table or tree by its text. */
    public void select(String value) {
        Map<String, Object> params = target();
        params.put("value", value);
        act("/select", params);
    }

    /** The quoted value the snapshot shows for it, or null. */
    public String value() {
        return require().quoted("value");
    }

    public boolean isChecked() {
        return require().has("checked");
    }

    /** The contents of a table, list, tree or combo box, one row per line. */
    public String read() {
        return app.get("/read", target());
    }
}
