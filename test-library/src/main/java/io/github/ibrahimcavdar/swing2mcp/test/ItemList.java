package io.github.ibrahimcavdar.swing2mcp.test;

import java.util.Map;

/** A list of items. */
public final class ItemList extends Locator {

    ItemList(SwingApp app, String locator) {
        super(app, locator);
    }

    /** Clicks the item with this text. */
    public void select(String item) {
        Map<String, Object> params = target();
        params.put("value", item);
        act("/select", params);
    }

    public int itemCount() {
        return require().number("items");
    }

    /** The position of the first selected item, counting from 0; -1 if none is selected. */
    public int selectedIndex() {
        return require().firstOf("selected");
    }

    /** All items, one per line, as the user sees them. */
    public String read() {
        return app.get("/read", allRows());
    }

    public void shouldHaveSelectedIndex(final int expected) {
        should("have item " + expected + " selected", new Check() {
            public boolean ok(State state) {
                return state != null && state.firstOf("selected") == expected;
            }
        });
    }

    public void shouldContain(final String text) {
        should("contain \"" + text + "\"", new Check() {
            public boolean ok(State state) {
                return state != null && read().contains(text);
            }
        });
    }
}
