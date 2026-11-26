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

    public void shouldHaveValue(final String expected) {
        should("have the value \"" + expected + "\"", new Check() {
            public boolean ok(State state) {
                return state != null && expected.equals(state.quoted("value"));
            }
        });
    }

    public void shouldBeChecked() {
        should("be checked", new Check() {
            public boolean ok(State state) {
                return state != null && state.has("checked");
            }
        });
    }

    public void shouldBeUnchecked() {
        should("be unchecked", new Check() {
            public boolean ok(State state) {
                return state != null && state.has("unchecked");
            }
        });
    }

    /**
     * The same component as a table, with a table's commands and checks: for a table that has no name, or
     * one of several, e.g. {@code app.field("tblJobs").asTable()} or {@code app.locator("table #2").asTable()}.
     */
    public Table asTable() {
        return new Table(app, locator);
    }

    public Tree asTree() {
        return new Tree(app, locator);
    }

    public ItemList asList() {
        return new ItemList(app, locator);
    }

    public Tabs asTabs() {
        return new Tabs(app, locator);
    }

    /** The contents of a table, list, tree or combo box, one row per line. */
    public String read() {
        return app.get("/read", target());
    }
}
