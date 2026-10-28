package com.acme.legacy;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Modal login dialog. Demo credentials: admin / admin.
 */
public class LoginDialog extends JDialog {

    private JTextField txtUser;
    private JPasswordField txtPassword;
    private JButton btnLogin;
    private JButton btnCancel;
    private boolean authenticated;

    public LoginDialog() {
        super((java.awt.Frame) null, "Login", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        initComponents();
        pack();
        setLocationRelativeTo(null);
    }

    private void initComponents() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;

        JLabel lblUser = new JLabel("User name:");
        txtUser = new JTextField(16);
        lblUser.setLabelFor(txtUser);
        gc.gridx = 0; gc.gridy = 0;
        form.add(lblUser, gc);
        gc.gridx = 1;
        form.add(txtUser, gc);

        // Intentionally no setLabelFor: name must be inferred from layout.
        JLabel lblPassword = new JLabel("Password:");
        txtPassword = new JPasswordField(16);
        gc.gridx = 0; gc.gridy = 1;
        form.add(lblPassword, gc);
        gc.gridx = 1;
        form.add(txtPassword, gc);

        btnLogin = new JButton("Log In");
        btnCancel = new JButton("Cancel");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(btnLogin);
        buttons.add(btnCancel);
        gc.gridx = 0; gc.gridy = 2; gc.gridwidth = 2; gc.anchor = GridBagConstraints.EAST;
        form.add(buttons, gc);

        btnLogin.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doLogin();
            }
        });
        btnCancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        txtPassword.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doLogin();
            }
        });
        getRootPane().setDefaultButton(btnLogin);
        setContentPane(form);
    }

    private void doLogin() {
        String user = txtUser.getText().trim();
        String password = new String(txtPassword.getPassword());
        if ("admin".equals(user) && "admin".equals(password)) {
            authenticated = true;
            dispose();
        } else {
            JOptionPane.showMessageDialog(this, "Wrong user name or password.", "Login Error",
                    JOptionPane.ERROR_MESSAGE);
            txtPassword.setText("");
        }
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public String getUserName() {
        return txtUser.getText().trim();
    }
}
