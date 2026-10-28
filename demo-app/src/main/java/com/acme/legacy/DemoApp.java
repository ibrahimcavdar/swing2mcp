package com.acme.legacy;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Font;

/**
 * Entry point of the demo application.
 *
 * Mimics a typical legacy desktop product: a splash screen while "beans" are
 * initialised (slow startup), then a modal login dialog, then the main frame.
 *
 * System properties:
 *   demo.startupDelayMs  simulated startup time, default 4000
 */
public class DemoApp {

    public static void main(String[] args) throws Exception {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        long startupDelay = Long.getLong("demo.startupDelayMs", 4000L);
        final SplashWindow splash = new SplashWindow();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                splash.setVisible(true);
            }
        });

        // Simulates Spring context / service initialisation on the main thread.
        String[] steps = {"Reading configuration", "Connecting to database", "Starting services",
                "Preparing screens"};
        for (int i = 0; i < steps.length; i++) {
            splash.setStep(steps[i], (i + 1) * 100 / steps.length);
            Thread.sleep(startupDelay / steps.length);
        }

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                splash.dispose();
                LoginDialog login = new LoginDialog();
                login.setVisible(true); // blocks until closed
                if (!login.isAuthenticated()) {
                    System.exit(0);
                }
                MainFrame frame = new MainFrame(login.getUserName());
                frame.setVisible(true);
            }
        });
    }

    static class SplashWindow extends JWindow {
        private final JLabel lblStep = new JLabel("Starting...");
        private final JProgressBar progress = new JProgressBar(0, 100);

        SplashWindow() {
            JPanel content = new JPanel(new BorderLayout(8, 8));
            content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
            JLabel title = new JLabel("ACME Customer Manager 3.2");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
            content.add(title, BorderLayout.NORTH);
            content.add(lblStep, BorderLayout.CENTER);
            progress.setStringPainted(true);
            content.add(progress, BorderLayout.SOUTH);
            setContentPane(content);
            setSize(360, 120);
            setLocationRelativeTo(null);
        }

        void setStep(final String step, final int percent) {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    lblStep.setText(step + "...");
                    progress.setValue(percent);
                }
            });
        }
    }
}
