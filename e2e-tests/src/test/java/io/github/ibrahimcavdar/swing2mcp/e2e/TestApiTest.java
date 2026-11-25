package io.github.ibrahimcavdar.swing2mcp.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ibrahimcavdar.swing2mcp.test.SwingApp;
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

    /**
     * The API names each kind of component in its own code, and the agent has its own list of roles. A
     * role the agent does not know is refused, so asking each method for something keeps the two in step.
     */
    @Test
    @Order(8)
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
