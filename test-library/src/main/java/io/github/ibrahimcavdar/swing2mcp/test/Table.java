package io.github.ibrahimcavdar.swing2mcp.test;

import java.util.Map;

/** A table. A row is named by the text of one of its cells. */
public final class Table extends Locator {

    Table(SwingApp app, String locator) {
        super(app, locator);
    }

    public int rowCount() {
        return require().number("rows");
    }

    /** Clicks the first cell that shows this text, which selects its row. */
    public void selectRow(String cellText) {
        Map<String, Object> params = target();
        params.put("value", cellText);
        act("/select", params);
    }

    /** Double-clicks the first cell that shows this text. */
    public void doubleClickRow(String cellText) {
        Map<String, Object> params = target();
        params.put("value", cellText);
        params.put("clickCount", 2);
        act("/click", params);
    }

    /** All rows as the user sees them, one per line: {@code row 0: 1 | Ali Veli | Ankara}. */
    public String read() {
        return app.get("/read", target());
    }

    /** The rows with a cell that contains this text. */
    public String find(String text) {
        Map<String, Object> params = target();
        params.put("find", text);
        return app.get("/read", params);
    }

    public void shouldHaveRowCount(final int expected) {
        should("have " + expected + " rows", new Check() {
            public boolean ok(State state) {
                return state != null && state.number("rows") == expected;
            }
        });
    }

    /** Passes once some row shows this text, e.g. a cell's text or {@code "Ali Veli | Ankara"}. */
    public void shouldContain(final String text) {
        should("contain \"" + text + "\"", new Check() {
            public boolean ok(State state) {
                return state != null && read().contains(text);
            }
        });
    }
}
