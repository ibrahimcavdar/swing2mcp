package io.github.ibrahimcavdar.swing2mcp.test;

import java.util.LinkedHashMap;
import java.util.Map;

/** A table. A row is named by the text of one of its cells. */
public final class Table extends Locator {

    /** A cell's editor is there right after the click that opens it, or it does not come at all. */
    private static final long EDITOR_WAIT_MS = 2000;

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

    /** Clicks a cell. Rows count from 0, as on screen; the column is its header text. */
    public void clickCell(int row, String column) {
        clickCellAt(row, column, 1);
    }

    /** Clicks a cell. Rows and columns count from 0, as on screen. */
    public void clickCell(int row, int column) {
        clickCellAt(row, column, 1);
    }

    public void doubleClickCell(int row, String column) {
        clickCellAt(row, column, 2);
    }

    public void doubleClickCell(int row, int column) {
        clickCellAt(row, column, 2);
    }

    /**
     * Types into a cell as a user does: a double-click opens the cell's text field, the text replaces what
     * is there and Enter ends the editing. For cells that are edited with a text field.
     */
    public void fillCell(int row, String column, String text) {
        edit(row, column, 2, "textfield", "/type", "text", text);
    }

    public void fillCell(int row, int column, String text) {
        edit(row, column, 2, "textfield", "/type", "text", text);
    }

    /** Picks an option in a cell that is edited with a combo box, which a click on the cell opens. */
    public void selectInCell(int row, String column, String option) {
        edit(row, column, 1, "combobox", "/select", "value", option);
    }

    public void selectInCell(int row, int column, String option) {
        edit(row, column, 1, "combobox", "/select", "value", option);
    }

    private void clickCellAt(int row, Object column, int clicks) {
        Map<String, Object> params = target();
        params.put("row", row);
        params.put("col", column);
        if (clicks > 1) {
            params.put("clickCount", clicks);
        }
        act("/click", params);
    }

    /** The clicks open the cell's editor, a component of its own inside the table, which then gets the value. */
    private void edit(int row, Object column, int clicks, String editor, String endpoint, String key, String value) {
        clickCellAt(row, column, clicks);
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("ref", locator + " > " + editor);
        params.put("timeoutMs", Math.min(app.timeoutMs(), EDITOR_WAIT_MS));
        params.put(key, value);
        if (endpoint.equals("/type")) {
            params.put("submit", true);
        }
        try {
            act(endpoint, params);
        } catch (AssertionError e) {
            String cell = "row " + row + ", column " + (column instanceof String ? "\"" + column + "\"" : column);
            throw new AssertionError("The cell in " + cell + " of " + locator + " could not be edited with a "
                    + editor + (clicks > 1 ? " after a double-click" : " after a click")
                    + " (is the cell editable, and edited this way?).\n" + e.getMessage());
        }
    }

    /** All rows as the user sees them, one per line: {@code row 0: 1 | Ali Veli | Ankara}. */
    public String read() {
        return app.get("/read", allRows());
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

    /** Waits until the cell shows the text. Rows count from 0; the column is its header text. */
    public void shouldHaveCell(int row, String column, String expected) {
        shouldHaveCellAt(row, column, "column \"" + column + "\"", expected);
    }

    /** Waits until the cell shows the text. Rows and columns count from 0. */
    public void shouldHaveCell(int row, int column, String expected) {
        shouldHaveCellAt(row, column, "column " + column, expected);
    }

    private void shouldHaveCellAt(final int row, final Object column, String columnText, final String expected) {
        final String[] last = {null};
        try {
            should("show \"" + expected + "\" in row " + row + ", " + columnText, new Check() {
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
            if (last[0] == null) {
                throw e;
            }
            // what the cell shows instead goes right under the expectation, before the table
            String message = e.getMessage();
            int firstBreak = message.indexOf('\n');
            String shows = "\nThat cell shows \"" + last[0] + "\".";
            throw new AssertionError(firstBreak < 0 ? message + shows
                    : message.substring(0, firstBreak) + shows + message.substring(firstBreak));
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
