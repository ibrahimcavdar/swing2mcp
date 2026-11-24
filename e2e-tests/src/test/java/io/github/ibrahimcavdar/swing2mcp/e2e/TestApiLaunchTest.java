package io.github.ibrahimcavdar.swing2mcp.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

            // A second handle on the same application; closing it leaves the application running.
            try (SwingApp same = SwingApp.connect(pid)) {
                assertEquals(pid, same.pid());
                same.button("Log In").shouldBeEnabled();
            }
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "close() must end it");
    }

    @Test
    void refusesToConnectToAProcessWithoutTheAgent() {
        long own = ProcessHandle.current().pid();
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> SwingApp.connect(own));
        assertTrue(refused.getMessage().contains("No application with the swing2mcp agent"), refused.getMessage());
    }
}
