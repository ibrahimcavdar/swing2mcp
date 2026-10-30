package com.acme.spring;

import com.acme.spring.ui.LoginDialog;
import com.acme.spring.ui.MainFrame;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import javax.swing.JLabel;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Entry point. Like many legacy Spring desktop apps: the context is built on the main thread
 * (slowly), screens are Spring beans, and the context is only held in a local variable.
 */
public class SpringDemoApp {

    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());

        final JWindow splash = new JWindow();
        splash.add(new JLabel("Starting ACME Customer Manager 4.0 (Spring)...", SwingConstants.CENTER));
        splash.setSize(380, 90);
        splash.setLocationRelativeTo(null);
        splash.setVisible(true);

        final ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("applicationContext.xml");
        context.registerShutdownHook();

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                splash.dispose();
                LoginDialog login = context.getBean(LoginDialog.class);
                login.setVisible(true); // modal
                if (!login.isAuthenticated()) {
                    context.close();
                    System.exit(0);
                }
                context.getBean(MainFrame.class).open(login.getUserName());
            }
        });
    }
}
