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

    /** What the cell shows. Rows count from 0, as on screen; the column is its header text. */
    public String cell(int row, String column) {
        return cellAt(row, column);
    }

    /** What the cell shows. Rows and columns count from 0, as on screen. */
    public String cell(int row, int column) {
        return cellAt(row, column);
    }

    private String cellAt(int row, Object column) {
        Map<String, Object> params = target();
        params.put("row", row);
        params.put("col", column);
        return app.get("/cell", params);
    }

    /** The first selected row, or -1. */
    public int selectedRow() {
        return require().firstOf("selected");
    }

    public void shouldHaveCell(final int row, final String column, final String expected) {
        final String[] last = {null};
        try {
            should("show \"" + expected + "\" in row " + row + ", column \"" + column + "\"", new Check() {
                public boolean ok(State state) {
                    if (state == null || state.number("rows") <= row) {
                        last[0] = null;
                        return false;
                    }
                    last[0] = cellAt(row, column);
                    return expected.equals(last[0]);
                }
            });
        } catch (AssertionError e) {
            throw last[0] == null ? e : new AssertionError(e.getMessage() + "\nThat cell shows \"" + last[0] + "\".");
        }
    }

    public void shouldHaveSelectedRow(final int expected) {
        should("have row " + expected + " selected", new Check() {
            public boolean ok(State state) {
                return state != null && state.firstOf("selected") == expected;
            }
        });
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
