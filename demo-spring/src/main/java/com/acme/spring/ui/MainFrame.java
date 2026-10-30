package com.acme.spring.ui;

import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

@Component
public class MainFrame extends JFrame {

    @Autowired
    private CustomerPanel customerPanel;

    @Autowired
    private ObjectFactory<SettingsDialog> settingsDialogs;

    public void open(String userName) {
        setTitle("ACME Customer Manager 4.0 - " + userName);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        getContentPane().add(customerPanel, BorderLayout.CENTER);
        getContentPane().add(new JLabel(" User: " + userName), BorderLayout.SOUTH);

        JMenuBar bar = new JMenuBar();
        JMenu mnuTools = new JMenu("Tools");
        JMenuItem miSettings = new JMenuItem("Settings...");
        miSettings.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                settingsDialogs.getObject().setVisible(true);
            }
        });
        mnuTools.add(miSettings);
        bar.add(mnuTools);
        setJMenuBar(bar);

        setSize(760, 480);
        setLocationRelativeTo(null);
        setVisible(true);
    }
}
