package com.acme.legacy;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

public class MainFrame extends JFrame {

    private JTabbedPane tabs;
    private CustomerPanel customerPanel;
    private OrderPanel orderPanel;
    private ReportPanel reportPanel;
    private JLabel lblStatus;

    public MainFrame(String userName) {
        super("ACME Customer Manager 3.2 - " + userName);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        lblStatus = new JLabel("Ready");
        lblStatus.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        customerPanel = new CustomerPanel(lblStatus);
        orderPanel = new OrderPanel();
        reportPanel = new ReportPanel();

        tabs = new JTabbedPane();
        tabs.addTab("Customers", customerPanel);
        tabs.addTab("Orders", orderPanel);
        tabs.addTab("Reports", reportPanel);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tabs, BorderLayout.CENTER);
        getContentPane().add(lblStatus, BorderLayout.SOUTH);
        setJMenuBar(createMenuBar());
        setSize(900, 620);
        setLocationRelativeTo(null);
    }

    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu mnuFile = new JMenu("File");
        JMenuItem miNewCustomer = new JMenuItem("New Customer");
        miNewCustomer.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_MASK));
        miNewCustomer.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                tabs.setSelectedComponent(customerPanel);
                customerPanel.clearForm();
            }
        });
        JMenuItem miExit = new JMenuItem("Exit");
        miExit.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int answer = JOptionPane.showConfirmDialog(MainFrame.this, "Do you want to exit the application?",
                        "Exit", JOptionPane.YES_NO_OPTION);
                if (answer == JOptionPane.YES_OPTION) {
                    System.exit(0);
                }
            }
        });
        mnuFile.add(miNewCustomer);
        mnuFile.addSeparator();
        mnuFile.add(miExit);

        JMenu mnuReports = new JMenu("Reports");
        JMenuItem miMonthly = new JMenuItem("Monthly Sales Report");
        miMonthly.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                tabs.setSelectedComponent(reportPanel);
            }
        });
        JMenuItem miExport = new JMenuItem("Export to Excel");
        miExport.setEnabled(false);
        mnuReports.add(miMonthly);
        mnuReports.add(miExport);

        JMenu mnuHelp = new JMenu("Help");
        JMenuItem miAbout = new JMenuItem("About");
        miAbout.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                JOptionPane.showMessageDialog(MainFrame.this, "ACME Customer Manager 3.2\n(c) 2009 ACME",
                        "About", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        mnuHelp.add(miAbout);

        bar.add(mnuFile);
        bar.add(mnuReports);
        bar.add(mnuHelp);
        return bar;
    }
}
