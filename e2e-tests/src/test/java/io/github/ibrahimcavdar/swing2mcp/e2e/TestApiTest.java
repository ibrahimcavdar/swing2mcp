package io.github.ibrahimcavdar.swing2mcp.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ibrahimcavdar.swing2mcp.test.SwingApp;
import io.github.ibrahimcavdar.swing2mcp.test.Table;
import io.github.ibrahimcavdar.swing2mcp.test.Tree;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.regex.Pattern;

/**
 * The demo application tested the way a user of the test API would: no MCP server, no refs, no AI tool.
 * The steps build on each other, so they run in order.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TestApiTest {

    private SwingApp app;

    @BeforeAll
    void startApplication() {
        TestEnvironment.requireDisplay();
        app = SwingApp.launchCommand(TestEnvironment.demoCommand(), TestEnvironment.demoDir().toFile());
    }

    @AfterAll
    void stopApplication() {
        if (app != null) {
            app.close();
            assertFalse(ProcessHandle.of(app.pid()).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    @Order(1)
    void logsIn() {
        // The login dialog opens after a splash screen: the first step waits for it.
        app.timeout(30000).textField("User name").fill("admin");
        app.timeout(5000);
        app.passwordField("Password").fill("admin");
        app.dialog("Login").button("Log In").click();

        app.frame("ACME Customer Manager 3.2 - admin").shouldExist();
        app.dialog("Login").shouldNotExist();
        app.table().shouldContain("Ayşe Yılmaz | ayse@example.com | Ankara");
    }

    @Test
    @Order(2)
    void addsACustomer() {
        app.textField("Full Name").fill("Ali Veli");
        app.comboBox("City").select("İzmir");
        app.comboBox("City").shouldHaveValue("İzmir");
        app.checkBox("Active customer").shouldBeChecked();
        app.button("Save").click();

        app.label("Customer saved: Ali Veli").shouldExist();
        app.label(Pattern.compile("saved: Ali")).shouldExist();
        app.button(Pattern.compile("^sav", Pattern.CASE_INSENSITIVE)).shouldBeEnabled();
        app.table().shouldHaveRowCount(4);
        app.table().shouldContain("Ali Veli |  | İzmir");
        app.textField("Full Name").shouldHaveValue("");
    }

    @Test
    @Order(3)
    void answersADialog() {
        app.button("Clear").click();
        String said = app.button("Save").clickAndAnswer("ok");
        assertTrue(said.contains("Full Name must not be empty."), said);
        app.dialog("Validation Error").shouldNotExist();
    }

    @Test
    @Order(4)
    void readsStates() {
        app.button("Delete").shouldBeDisabled();
        assertFalse(app.button("Delete").isEnabled());
        assertTrue(app.button("Save").isEnabled());
        assertTrue(app.checkBox("Active customer").isChecked());
        assertEquals("Ankara", app.comboBox("City").value());
        assertEquals(4, app.table().rowCount());

        assertEquals("Ali Veli", app.table().cell(3, "Full Name"));
        assertEquals("Ali Veli", app.table().cell(3, 1));
        app.table().shouldHaveCell(3, "City", "İzmir");
        app.table().shouldHaveCell(3, 1, "Ali Veli");
        assertEquals(-1,app.table().selectedRow());
        app.table().selectRow("Mehmet Demir");
        app.table().shouldHaveSelectedRow(1);
        assertFalse(app.dialog("Login").exists());
    }

    @Test
    @Order(5)
    void findsByJavaFieldAndInsideAContainer() {
        app.field("txtEmail").fill("ali@example.com");
        app.group("Customer Details").textField("Email").shouldHaveValue("ali@example.com");
        app.group("Customer Details").button("Clear").click();
        app.textField("Email").shouldHaveValue("");

        // a table, tree, list or tabbed pane found this way still has its own commands and checks
        Table customers = app.field("tblCustomers").asTable();
        customers.shouldHaveCell(0, "Full Name", customers.cell(0, 1));
        assertEquals(customers.rowCount(), app.locator("table #1").asTable().rowCount());
        app.field("tabs").asTabs().select("Orders");
        app.field("treeCategories").asTree().select("Office/Stationery");
        assertEquals("Categories/Office/Stationery", app.locator("tree").asTree().selectedPath());
        app.field("lstOrders").asList().select("SP-1002 Mehmet Demir");
        app.field("lstOrders").asList().shouldHaveSelectedIndex(1);
        app.field("tabs").asTabs().select("Customers");
    }

    @Test
    @Order(6)
    void failsWithWhatIsOnScreenInstead() {
        app.timeout(300);
        AssertionError missing = assertThrows(AssertionError.class, () -> app.button("Sav").click());
        assertTrue(missing.getMessage().contains("Nothing on screen matches button \"Sav\""), missing.getMessage());
        assertTrue(missing.getMessage().contains("\"Save\", \"Clear\""), missing.getMessage());

        AssertionError wrong = assertThrows(AssertionError.class,
                () -> app.comboBox("City").shouldHaveValue("Bursa"));
        assertTrue(wrong.getMessage().contains("should have the value \"Bursa\""), wrong.getMessage());
        assertTrue(wrong.getMessage().contains("value=\"Ankara\""), wrong.getMessage());

        AssertionError cell = assertThrows(AssertionError.class,
                () -> app.table().shouldHaveCell(0, "City", "Bursa"));
        assertTrue(cell.getMessage().contains("That cell shows \"Ankara\"."), cell.getMessage());

        java.io.File pictures = new java.io.File(System.getProperty("swing2mcp.workDir"), "failure-screenshots");
        app.screenshotsOnFailure(pictures);
        AssertionError pictured = assertThrows(AssertionError.class, () -> app.button("Sav").click());
        app.screenshotsOnFailure(null);
        String path = pictured.getMessage().substring(pictured.getMessage().indexOf("Screenshot: ") + 12).trim();
        assertTrue(new java.io.File(path).length() > 5000, pictured.getMessage());
        app.timeout(5000);
    }

    @Test
    @Order(7)
    void usesTabsMenusAndKeys() {
        app.tabs().select("Orders");
        app.spinner("Quantity").fillAndSubmit("7");
        app.spinner("Quantity").shouldHaveValue("7");
        app.tabs().select("Customers");

        app.selectMenu("Help > About");
        app.dialog("About").shouldExist();
        app.dialog("About").shouldShow("(c) 2009 ACME");
        String said = app.answerDialog("ok");
        assertTrue(said.contains("ACME Customer Manager 3.2"), said);

        byte[] png = app.screenshot();
        assertEquals("\u0089PNG", new String(png, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    /** What a test must be able to do with a component that paints itself: the mouse at a point of it. */
    @Test
    @Order(8)
    void usesTheMouseAtAPointOfAComponent() {
        // The rows of the customers table are 16 pixels high: y 24 is in the second row, y 40 in the third.
        app.table().clickAt(60, 24);
        app.table().shouldHaveSelectedRow(1);
        app.table().rightClickAt(60, 40);
        app.table().shouldHaveSelectedRow(2);
        app.menuItem("Load into Form").click();
        app.textField("Full Name").shouldHaveValue("Zeynep Kaya");
        app.table().doubleClickAt(60, 8);
        app.textField("Full Name").shouldHaveValue("Ayşe Yılmaz");

        app.tabs().select("Planner");
        app.label("View center: 0, 0 | zoom 100%").shouldExist();
        app.field("mapCanvas").drag(200, 150, 260, 190);
        app.label("View center: -60, -40 | zoom 100%").shouldExist();
    }

    @Test
    @Order(9)
    void editsTableCells() {
        Table assignments = app.table("Assignments");
        assignments.fillCell(0, "Task", "Budget 2026");
        assignments.shouldHaveCell(0, "Task", "Budget 2026");
        assignments.selectInCell(0, "Owner", "Mert");
        assignments.shouldHaveCell(0, 1, "Mert");
        assignments.selectInCell(0, 1, "Selin");
        assignments.fillCell(0, 0, "Budget 2027");
        assignments.shouldContain("Budget 2027 | Selin | 12");
        assertFalse(app.snapshot().contains("editing row"), app.snapshot());

        assignments.clickCell(0, "Hours");
        assignments.shouldHaveSelectedRow(0);
        app.timeout(300);
        AssertionError readOnly = assertThrows(AssertionError.class, () -> assignments.fillCell(0, "Hours", "40"));
        assertTrue(readOnly.getMessage().contains("row 0, column \"Hours\""), readOnly.getMessage());
        assertTrue(readOnly.getMessage().contains("is the cell editable"), readOnly.getMessage());
        assignments.shouldHaveCell(0, "Hours", "12");
        app.timeout(5000);
        app.tabs().select("Customers");
    }

    /** A tree node is renamed in place: F2 in the tree opens its text field, which the snapshot lists. */
    @Test
    @Order(10)
    void renamesATreeNodeInPlace() {
        app.tabs().select("Orders");
        Tree categories = app.tree();
        categories.select("Electronics/Phones");
        categories.pressKey("F2");
        assertTrue(app.snapshot().contains("editing node 3 \"Categories/Electronics/Phones\":"), app.snapshot());
        app.locator("tree > textfield").fill("Mobile");
        app.pressKey("Enter");
        assertEquals("Categories/Electronics/Mobile", categories.selectedPath());
        assertFalse(app.snapshot().contains("editing node"), app.snapshot());
        app.tabs().select("Customers");
    }

    /** The operating system's file dialog is not Swing: a test sees that it is open and can only close it. */
    @Test
    @Order(11)
    void closesTheSystemFileDialog() {
        app.selectMenu("File > Attach File...");
        app.dialog("Attach a File").shouldExist();
        app.timeout(300);
        AssertionError behind = assertThrows(AssertionError.class, () -> app.tabs().select("Orders"));
        assertTrue(behind.getMessage().contains("the operating system's file dialog"), behind.getMessage());
        AssertionError picking = assertThrows(AssertionError.class,
                () -> app.chooseFile(new java.io.File("notes.txt")));
        assertTrue(picking.getMessage().contains("no file can be picked"), picking.getMessage());
        app.timeout(5000);

        String closed = app.answerDialog("cancel");
        assertTrue(closed.contains("without a file"), closed);
        app.label("No file attached").shouldExist();
        app.dialog("Attach a File").shouldNotExist();
    }

    /**
     * The API names each kind of component in its own code, and the agent has its own list of roles. A
     * role the agent does not know is refused, so asking each method for something keeps the two in step.
     */
    @Test
    @Order(12)
    void everyKindOfComponentIsARoleTheAgentKnows() throws Exception {
        int asked = 0;
        for (java.lang.reflect.Method method : io.github.ibrahimcavdar.swing2mcp.test.Scope.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers()) || method.getName().equals("locator")) {
                continue;
            }
            Object found = method.getParameterCount() == 0 ? method.invoke(app)
                    : method.getParameterTypes()[0] == Pattern.class ? method.invoke(app, Pattern.compile("no such name"))
                    : method.invoke(app, "no such name");
            try {
                found.getClass().getMethod("exists").invoke(found);
            } catch (java.lang.reflect.InvocationTargetException e) {
                String message = String.valueOf(e.getCause().getMessage());
                // Several tables or lists on screen are fine here; an unknown role is not.
                assertFalse(message.contains("is not a role"), method.getName() + ": " + message);
            }
            asked++;
        }
        assertTrue(asked >= 40, "only " + asked + " methods found");
    }
}
