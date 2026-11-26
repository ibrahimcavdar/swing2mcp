package io.github.ibrahimcavdar.swing2mcp.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.legacy.DemoApp;
import io.github.ibrahimcavdar.swing2mcp.test.SwingApp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
            // a step on an application that has ended fails; it does not pass in silence
            AssertionError ended = assertThrows(AssertionError.class, () -> app.selectMenu("File > Exit"));
            assertTrue(ended.getMessage().contains("is not running any more"), ended.getMessage());
        }
    }

    /** A test may give the application a home folder of its own, so that its settings stay apart from the user's. */
    @Test
    void findsAnApplicationThatRunsWithAnotherUserHome(@TempDir Path home) {
        // Quotes at both ends, as for paths with spaces: on Windows the shell must not take the outer pair away.
        String command = "\"" + TestEnvironment.java() + "\" -Duser.home=\"" + home + "\" -jar \"target/demo-app.jar\"";
        try (SwingApp app = SwingApp.launchCommand(command, TestEnvironment.demoDir().toFile()).timeout(30000)) {
            app.textField("User name").shouldExist();
        }
        assertFalse(Files.exists(home.resolve(".swing2mcp")), "the agent registered in the application's home");
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
