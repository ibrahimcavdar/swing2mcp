package com.acme.spring.ui;

import com.acme.spring.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

@Component
@Scope("prototype")
public class LoginDialog extends JDialog {

    @Autowired
    private AuthService authService;

    private JTextField txtUser;
    private JPasswordField txtPassword;
    private boolean authenticated;

    public LoginDialog() {
        super((java.awt.Frame) null, "Sign In", true);
    }

    @PostConstruct
    void init() {
        JPanel form = new JPanel(new GridLayout(3, 2, 6, 6));
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        txtUser = new JTextField(14);
        txtPassword = new JPasswordField(14);
        form.add(new JLabel("User:"));
        form.add(txtUser);
        form.add(new JLabel("Password:"));
        form.add(txtPassword);
        JButton btnLogin = new JButton("Login");
        JButton btnCancel = new JButton("Cancel");
        form.add(btnLogin);
        form.add(btnCancel);
        btnLogin.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (authService.login(txtUser.getText().trim(), new String(txtPassword.getPassword()))) {
                    authenticated = true;
                    dispose();
                } else {
                    JOptionPane.showMessageDialog(LoginDialog.this, "Wrong user or password.", "Sign In",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        btnCancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        getRootPane().setDefaultButton(btnLogin);
        setContentPane(form);
        pack();
        setLocationRelativeTo(null);
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public String getUserName() {
        return txtUser.getText().trim();
    }
}
