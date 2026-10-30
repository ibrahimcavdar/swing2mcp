package com.acme.spring.ui;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JLabel;
import javax.swing.SpinnerNumberModel;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/** A screen that is only reachable through a menu: a typical target for "open this panel directly". */
@Component
@Scope("prototype")
public class SettingsDialog extends JDialog {

    private JCheckBox chkAutoSave;
    private JSpinner spnPageSize;

    public SettingsDialog() {
        super((java.awt.Frame) null, "Settings", true);
    }

    @PostConstruct
    void init() {
        JPanel content = new JPanel(new GridLayout(3, 2, 6, 6));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        chkAutoSave = new JCheckBox("Auto save", true);
        spnPageSize = new JSpinner(new SpinnerNumberModel(50, 10, 500, 10));
        content.add(chkAutoSave);
        content.add(new JLabel(""));
        content.add(new JLabel("Page size:"));
        content.add(spnPageSize);
        JButton btnClose = new JButton("Close");
        btnClose.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        content.add(btnClose);
        setContentPane(content);
        pack();
        setLocationRelativeTo(null);
    }
}
