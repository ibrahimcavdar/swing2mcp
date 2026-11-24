package io.github.ibrahimcavdar.swing2mcp.e2e;

import static io.github.ibrahimcavdar.swing2mcp.e2e.McpSession.ok;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ibrahimcavdar.swing2mcp.e2e.McpSession.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Components named by what the snapshot says about them (role, name, field) instead of by a ref, which
 * is what a test that runs again tomorrow has to do. No snapshot is taken to look up a ref anywhere here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocatorTest {

    private McpSession mcp;
    private long pid = -1;

    @BeforeAll
    void startApplication() throws Exception {
        TestEnvironment.requireDisplay();
        mcp = TestEnvironment.startServer("locators");
        Result launched = ok(mcp.call("launch_app", "command", TestEnvironment.demoCommand(),
                "cwd", TestEnvironment.demoDir().toString()));
        pid = TestEnvironment.pidIn(launched.text(), "Launched pid (\\d+)");
    }

    @AfterAll
    void stopEverything() throws Exception {
        TestEnvironment.kill(pid);
        if (mcp != null) {
            mcp.close();
        }
    }

    @Test
    @Order(1)
    void waitsForAComponentThatIsNotThereYet() throws Exception {
        // Right after the start there is only a splash screen; the login dialog comes later.
        ok(mcp.call("type_text", "ref", "textfield \"User name\"", "text", "admin", "timeoutMs", 30000));
        ok(mcp.call("type_text", "ref", "passwordfield \"Password\"", "text", "admin"));
        Result main = ok(mcp.call("click", "ref", "dialog \"Login\" > button \"Log In\""));
        assertContains(main, "frame \"ACME Customer Manager 3.2 - admin\"");
    }

    @Test
    @Order(2)
    void findsByFieldByNameAndInsideAGroup() throws Exception {
        ok(mcp.call("type_text", "ref", "field=txtName", "text", "Ali Veli"));
        ok(mcp.call("select_option", "ref", "combobox \"City\"", "value", "İzmir"));
        Result saved = ok(mcp.call("click", "ref", "frame > button \"Save\""));
        assertContains(saved, "label \"Customer saved: Ali Veli\"");

        Result found = ok(mcp.call("read_items", "ref", "table field=tblCustomers", "find", "Ali Veli"));
        assertContains(found, "Ali Veli");
    }

    @Test
    @Order(3)
    void saysWhatThereIsWhenNothingMatches() throws Exception {
        Result missing = mcp.call("click", "ref", "button \"Sav\"", "timeoutMs", 300);
        assertTrue(missing.error(), missing.text());
        assertContains(missing, "Nothing on screen matches button \"Sav\"");
        assertContains(missing, "\"Save\", \"Clear\"");
    }

    @Test
    @Order(4)
    void refusesALocatorThatMatchesSeveralComponents() throws Exception {
        Result several = mcp.call("click", "ref", "button", "timeoutMs", 300);
        assertTrue(several.error(), several.text());
        assertContains(several, "components match button:");
        assertContains(several, "#1  button \"Save\"");

        // The position picks one of them.
        Result cleared = ok(mcp.call("click", "ref", "button #2"));
        assertContains(cleared, "Clicked JButton \"Clear\"");
    }

    @Test
    @Order(5)
    void refusesAMisspelledRoleAtOnce() throws Exception {
        long started = System.nanoTime();
        Result typo = mcp.call("click", "ref", "buton \"Save\"");
        assertTrue(typo.error(), typo.text());
        assertContains(typo, "\"buton\" is not a role");
        assertTrue(System.nanoTime() - started < 3_000_000_000L, "a misspelled role must not be waited for");
    }

    @Test
    @Order(6)
    void findsAnItemOfAClosedMenu() throws Exception {
        Result about = ok(mcp.call("click", "ref", "menuitem \"About\"", "dialog", "ok"));
        assertContains(about, "Answered dialog");
    }

    @Test
    @Order(7)
    void findsByARegularExpressionInTheName() throws Exception {
        Result cleared = ok(mcp.call("click", "ref", "button /^cle/i"));
        assertContains(cleared, "Clicked JButton \"Clear\"");

        Result invalid = mcp.call("click", "ref", "button /(/");
        assertTrue(invalid.error(), invalid.text());
        assertContains(invalid, "is not a regular expression");
    }

    private static void assertContains(Result result, String expected) {
        assertTrue(result.text().contains(expected), "Expected '" + expected + "' in:\n" + result.text());
    }
}
