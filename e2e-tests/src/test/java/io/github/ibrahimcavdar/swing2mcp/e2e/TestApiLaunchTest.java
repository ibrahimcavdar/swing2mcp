package io.github.ibrahimcavdar.swing2mcp.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.legacy.DemoApp;
import io.github.ibrahimcavdar.swing2mcp.test.SwingApp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The ways a test gets hold of an application, other than a command line. */
class TestApiLaunchTest {

    @BeforeAll
    static void needsADisplay() {
        TestEnvironment.requireDisplay();
    }

    @Test
    void startsTheMainClassInItsOwnProcessAndEndsItOnClose() {
        long pid;
        try (SwingApp app = SwingApp.launch(DemoApp.class).timeout(30000)) {
            pid = app.pid();
            assertTrue(pid != ProcessHandle.current().pid(), "the application must not run inside the tests");
            app.textField("User name").shouldExist();
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "close() must end it");
    }

    @Test
    void tellsWhenTheApplicationEndsByItself() {
        try (SwingApp app = SwingApp.launch(DemoApp.class).timeout(30000)) {
            app.textField("User name").fill("admin");
            app.passwordField("Password").fill("admin");
            app.button("Log In").click();

            app.selectMenu("File > Exit");
            app.dialog("Exit").answer("no");
            app.dialog("Exit").shouldNotExist();
            assertTrue(app.isRunning());
            AssertionError stillThere = assertThrows(AssertionError.class,
                    () -> app.timeout(1000).shouldHaveExited());
            assertTrue(stillThere.getMessage().contains("still running"), stillThere.getMessage());

            app.timeout(30000).selectMenu("File > Exit");
            app.dialog("Exit").answer("yes");
            app.shouldHaveExited();
            assertFalse(app.isRunning());
        }
    }

    @Test
    void twoCopiesOfTheApplicationRunSideBySide() {
        try (SwingApp first = SwingApp.launch(DemoApp.class).timeout(30000);
                SwingApp second = SwingApp.launch(DemoApp.class).timeout(30000)) {
            assertTrue(first.pid() != second.pid());
            first.textField("User name").fill("first");
            second.textField("User name").fill("second");
            first.textField("User name").shouldHaveValue("first");
            second.textField("User name").shouldHaveValue("second");
        }
    }
}
