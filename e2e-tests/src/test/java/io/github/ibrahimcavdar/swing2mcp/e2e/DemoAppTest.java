package io.github.ibrahimcavdar.swing2mcp.e2e;

import static io.github.ibrahimcavdar.swing2mcp.e2e.McpSession.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ibrahimcavdar.swing2mcp.e2e.McpSession.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.TreeSet;

/**
 * One session of an AI tool with the demo application: start it, log in, add a customer, answer a
 * dialog, take a screenshot, stop it. The steps build on each other, so they run in order.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoAppTest {

    private McpSession mcp;
    private long pid = -1;

    @BeforeAll
    void startServer() throws Exception {
        TestEnvironment.requireDisplay();
        mcp = TestEnvironment.startServer("demo-app");
    }

    @AfterAll
    void stopEverything() throws Exception {
        TestEnvironment.kill(pid);
        if (mcp != null) {
            mcp.close();
        }
    }

    /** The tool list is the product's public interface: a change here must be a decision, not an accident. */
    @Test
    @Order(1)
    void offersTheDocumentedTools() throws Exception {
        List<String> expected = List.of("launch_app", "list_apps", "connect_app", "stop_app", "snapshot",
                "screenshot", "click", "type_text", "press_key", "select_option", "select_menu", "drag",
                "handle_dialog", "read_items", "expand_node", "wait_for", "hot_reload", "spring_beans",
                "open_spring_bean");
        assertEquals(new TreeSet<>(expected), new TreeSet<>(mcp.toolNames()));
    }

    /**
     * A client may run a read-only tool without asking, in parallel and twice. Only tools that change
     * nothing in the application may carry the hint; a tool that clicks or types never does.
     */
    @Test
    @Order(1)
    void marksOnlyTheReadingToolsAsReadOnly() throws Exception {
        List<String> reading = List.of("list_apps", "snapshot", "screenshot", "read_items", "wait_for", "spring_beans");
        assertEquals(new TreeSet<>(reading), new TreeSet<>(mcp.toolsWithHint("readOnlyHint")));
        assertEquals(List.of("stop_app"), mcp.toolsWithHint("destructiveHint"));
    }

    @Test
    @Order(2)
    void launchesTheApplicationAndShowsTheLoginDialog() throws Exception {
        Result launched = ok(mcp.call("launch_app", "command", TestEnvironment.demoCommand(),
                "cwd", TestEnvironment.demoDir().toString()));
        pid = TestEnvironment.pidIn(launched.text(), "Launched pid (\\d+)");

        Result login = ok(mcp.call("wait_for", "text", "Log In", "timeoutMs", 30000));
        assertContains(login, "dialog \"Login\"");
        assertContains(login, "textfield \"User name\"");
        assertContains(login, "field=txtUser");
        // The password label has no setLabelFor; the name comes from the label next to the field.
        assertContains(login, "passwordfield \"Password\"");
    }

    @Test
    @Order(3)
    void logsInWithTypedKeys() throws Exception {
        Result login = ok(mcp.call("snapshot"));
        ok(mcp.call("type_text", "ref", login.ref("field=txtUser"), "text", "admin"));
        Result typed = ok(mcp.call("type_text", "ref", login.ref("field=txtPassword"), "text", "admin"));
        assertContains(typed, "value=(5 chars, hidden)");
        typed.text().lines().filter(line -> line.contains("field=txtPassword")).forEach(line ->
                assertFalse(line.contains("admin"), "the password must not be shown: " + line));

        Result main = ok(mcp.call("click", "ref", login.ref("field=btnLogin")));
        assertContains(main, "frame \"ACME Customer Manager 3.2 - admin\"");
        assertContains(main, "row 0: 1 | Ayşe Yılmaz | ayse@example.com | Ankara | [x]");
    }

    @Test
    @Order(4)
    void addsACustomerAndFindsItInTheTable() throws Exception {
        Result main = ok(mcp.call("snapshot"));
        ok(mcp.call("type_text", "ref", main.ref("field=txtName"), "text", "Ali Veli"));
        Result selected = ok(mcp.call("select_option", "ref", main.ref("field=cmbCity"), "value", "İzmir"));
        assertContains(selected, "value=\"İzmir\"");

        Result saved = ok(mcp.call("click", "ref", main.ref("field=btnSave")));
        assertContains(saved, "label \"Customer saved: Ali Veli\"");
        assertContains(saved, "rows=4 cols=5");

        Result found = ok(mcp.call("read_items", "ref", main.ref("field=tblCustomers"), "find", "Ali Veli"));
        assertContains(found, "row 3: 4 | Ali Veli |  | İzmir | [x]");
    }

    @Test
    @Order(5)
    void answersTheDialogAnActionOpens() throws Exception {
        Result main = ok(mcp.call("snapshot"));
        ok(mcp.call("click", "ref", main.ref("field=btnClear")));
        Result answered = ok(mcp.call("click", "ref", main.ref("field=btnSave"), "dialog", "ok"));
        assertContains(answered, "Answered dialog \"Validation Error\"");
        assertContains(answered, "Full Name must not be empty.");
    }

    @Test
    @Order(6)
    void reportsAnActionThatCannotBeDone() throws Exception {
        Result main = ok(mcp.call("snapshot"));
        // Delete is disabled while no customer is loaded into the form.
        Result refused = mcp.call("click", "ref", main.ref("field=btnDelete"), "timeoutMs", 500);
        assertTrue(refused.error(), "clicking a disabled button must be an error:\n" + refused.text());
    }

    @Test
    @Order(7)
    void reportsAWaitThatTimedOutAsAnError() throws Exception {
        Result timedOut = mcp.call("wait_for", "text", "no such text on any screen", "timeoutMs", 300);
        assertTrue(timedOut.error(), "a wait that timed out must be an error:\n" + timedOut.text());
        assertContains(timedOut, "Timed out after 300 ms");
        // The snapshot at that moment still comes with it.
        assertContains(timedOut, "frame \"ACME Customer Manager 3.2 - admin\"");

        Result met = ok(mcp.call("wait_for", "text", "Customer Details", "timeoutMs", 300));
        assertContains(met, "Condition met.");
    }

    @Test
    @Order(8)
    void showsThatASpinnerHasNotTakenOverTypedTextYet() throws Exception {
        Result main = ok(mcp.call("snapshot"));
        Result orders = ok(mcp.call("select_option", "ref", main.ref("field=tabs"), "value", "Orders"));
        String spinner = orders.ref("field=spnQuantity");

        // A spinner commits typed text on Enter or when the focus leaves it, as for a person typing.
        Result typed = ok(mcp.call("type_text", "ref", spinner, "text", "5"));
        assertContains(typed, "The spinner still has the value \"1\"");
        assertContains(typed, "value=\"1\" typed=\"5\" (not committed)");

        Result committed = ok(mcp.call("press_key", "key", "Enter"));
        assertContains(committed, "spinner \"Quantity\" [ref=" + spinner + "] value=\"5\"");
        assertFalse(committed.text().contains("not committed"), committed.text());

        Result submitted = ok(mcp.call("type_text", "ref", spinner, "text", "7", "submit", true));
        assertContains(submitted, "value=\"7\"");
        assertFalse(submitted.text().contains("not committed"), submitted.text());
    }

    /** A cell that is being edited has a component of its own on top of it, which the snapshot must show. */
    @Test
    @Order(9)
    void showsTheEditorOfATableCellAndTypesIntoIt() throws Exception {
        Result main = ok(mcp.call("snapshot"));
        Result planner = ok(mcp.call("select_option", "ref", main.ref("field=tabs"), "value", "Planner"));
        String table = planner.ref("field=tblAssignments");
        assertFalse(planner.text().contains("editing row"), planner.text());

        Result editing = ok(mcp.call("click", "ref", table, "row", 0, "col", 0, "clickCount", 2));
        assertContains(editing, "editing row 0 col 0 \"Task\":");
        String textField = editing.ref("textfield [ref=");
        Result typed = ok(mcp.call("type_text", "ref", textField, "text", "Budget 2026", "submit", true));
        assertContains(typed, "row 0: Budget 2026 | Elif | 12");
        assertFalse(typed.text().contains("editing row"), typed.text());

        Result choosing = ok(mcp.call("click", "ref", table, "row", 0, "col", 1));
        assertContains(choosing, "editing row 0 col 1 \"Owner\":");
        assertContains(choosing, "options=[\"(unassigned)\", \"Elif\", \"Mert\", \"Selin\"]");
        Result chosen = ok(mcp.call("select_option", "ref", choosing.ref("combobox [ref="), "value", "Mert"));
        assertContains(chosen, "row 0: Budget 2026 | Mert | 12");
        assertFalse(chosen.text().contains("editing row"), chosen.text());
        assertFalse(chosen.text().contains("popupmenu"), chosen.text());
    }

    @Test
    @Order(10)
    void takesAScreenshot() throws Exception {
        Result shot = ok(mcp.call("screenshot"));
        assertEquals(1, shot.images().size());
        byte[] png = shot.images().get(0);
        assertTrue(png.length > 5000, "a 900x620 window does not fit into " + png.length + " bytes");
        assertEquals("\u0089PNG", new String(png, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    @Test
    @Order(11)
    void refusesToStopAProcessWithoutTheAgent() throws Exception {
        long own = ProcessHandle.current().pid(); // this test JVM: alive, but not a swing2mcp application
        Result refused = mcp.call("stop_app", "pid", own);
        assertTrue(refused.error(), refused.text());
        assertContains(refused, "Not stopped: pid " + own);
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the application was stopped");
    }

    @Test
    @Order(12)
    void stopsTheApplication() throws Exception {
        Result stopped = ok(mcp.call("stop_app", "pid", pid));
        assertContains(stopped, "Stopped pid " + pid);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private static void assertContains(Result result, String expected) {
        assertTrue(result.text().contains(expected), "Expected '" + expected + "' in:\n" + result.text());
    }
}
