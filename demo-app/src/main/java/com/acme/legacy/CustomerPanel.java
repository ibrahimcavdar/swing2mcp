package com.acme.legacy;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.InputVerifier;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class CustomerPanel extends JPanel {

    private final JLabel lblStatus;

    private JTextField txtName;
    private JTextField txtEmail;
    private JTextField txtPhone;
    private JComboBox cmbCity;
    private JCheckBox chkActive;
    private JTextArea txtNotes;
    private JButton btnSave;
    private JButton btnClear;
    private JButton btnDelete;
    private JTable tblCustomers;
    private DefaultTableModel customerModel;
    private int nextId = 4;

    public CustomerPanel(JLabel lblStatus) {
        this.lblStatus = lblStatus;
        initComponents();
    }

    private void initComponents() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Customer Details"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 4, 3, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        JLabel lblName = new JLabel("Full Name: *");
        txtName = new JTextField(24);
        lblName.setLabelFor(txtName);
        addRow(form, gc, 0, lblName, txtName);

        // No setLabelFor on purpose (very common in legacy code).
        txtEmail = new JTextField(24);
        addRow(form, gc, 1, new JLabel("Email:"), txtEmail);
        // Typical legacy validation: the focus cannot leave an invalid email address.
        final Color normal = txtEmail.getBackground();
        txtEmail.setInputVerifier(new InputVerifier() {
            public boolean verify(JComponent input) {
                String email = ((JTextField) input).getText().trim();
                boolean valid = email.length() == 0 || email.indexOf('@') > 0;
                input.setBackground(valid ? normal : new Color(255, 200, 200));
                return valid;
            }
        });

        // Typical legacy key filter: only digits get through (setText would bypass it).
        txtPhone = new JTextField(24);
        txtPhone.addKeyListener(new KeyAdapter() {
            public void keyTyped(KeyEvent e) {
                if (!Character.isDigit(e.getKeyChar())) {
                    e.consume();
                }
            }
        });
        addRow(form, gc, 2, new JLabel("Phone:"), txtPhone);

        cmbCity = new JComboBox(new String[]{"Ankara", "İstanbul", "İzmir", "Bursa", "Antalya"});
        addRow(form, gc, 3, new JLabel("City:"), cmbCity);

        chkActive = new JCheckBox("Active customer", true);
        gc.gridx = 1; gc.gridy = 4;
        form.add(chkActive, gc);

        // Label above the field rather than to its left.
        gc.gridx = 0; gc.gridy = 5; gc.gridwidth = 2;
        form.add(new JLabel("Notes:"), gc);
        txtNotes = new JTextArea(3, 24);
        txtNotes.setLineWrap(true);
        gc.gridy = 6;
        form.add(new JScrollPane(txtNotes), gc);
        gc.gridwidth = 1;

        btnSave = new JButton("Save");
        btnClear = new JButton("Clear");
        btnDelete = new JButton("Delete");
        btnDelete.setEnabled(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(btnSave);
        buttons.add(btnClear);
        buttons.add(btnDelete);
        gc.gridx = 0; gc.gridy = 7; gc.gridwidth = 2;
        form.add(buttons, gc);

        customerModel = new DefaultTableModel(new Object[]{"ID", "Full Name", "Email", "City", "Active"}, 0) {
            public Class getColumnClass(int columnIndex) {
                return columnIndex == 4 ? Boolean.class : Object.class;
            }

            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        customerModel.addRow(new Object[]{1, "Ayşe Yılmaz", "ayse@example.com", "Ankara", Boolean.TRUE});
        customerModel.addRow(new Object[]{2, "Mehmet Demir", "mehmet@example.com", "İzmir", Boolean.TRUE});
        customerModel.addRow(new Object[]{3, "Zeynep Kaya", "zeynep@example.com", "İstanbul", Boolean.FALSE});
        tblCustomers = new JTable(customerModel);
        tblCustomers.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        add(form, BorderLayout.NORTH);
        add(new JScrollPane(tblCustomers), BorderLayout.CENTER);

        btnSave.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                saveCustomer();
            }
        });
        btnClear.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                clearForm();
            }
        });
        btnDelete.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                deleteSelected();
            }
        });
        tblCustomers.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
            public void valueChanged(ListSelectionEvent e) {
                btnDelete.setEnabled(tblCustomers.getSelectedRow() >= 0);
            }
        });
        final JPopupMenu tableMenu = new JPopupMenu();
        JMenuItem miLoad = new JMenuItem("Load into Form");
        miLoad.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                loadSelected();
            }
        });
        JMenuItem miDelete = new JMenuItem("Delete...");
        miDelete.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                deleteSelected();
            }
        });
        tableMenu.add(miLoad);
        tableMenu.add(miDelete);
        tblCustomers.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    loadSelected();
                }
            }

            public void mousePressed(MouseEvent e) {
                showMenu(e);
            }

            public void mouseReleased(MouseEvent e) {
                showMenu(e);
            }

            private void showMenu(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = tblCustomers.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        tblCustomers.setRowSelectionInterval(row, row);
                    }
                    tableMenu.show(tblCustomers, e.getX(), e.getY());
                }
            }
        });
    }

    private static void addRow(JPanel form, GridBagConstraints gc, int row, JLabel label, java.awt.Component field) {
        gc.gridx = 0; gc.gridy = row;
        form.add(label, gc);
        gc.gridx = 1;
        form.add(field, gc);
    }

    private void saveCustomer() {
        String name = txtName.getText().trim();
        String email = txtEmail.getText().trim();
        if (name.length() == 0) {
            JOptionPane.showMessageDialog(this, "Full Name must not be empty.", "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (email.length() > 0 && email.indexOf('@') < 0) {
            JOptionPane.showMessageDialog(this, "Please enter a valid email address.", "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        customerModel.addRow(new Object[]{nextId++, name, email, cmbCity.getSelectedItem(),
                Boolean.valueOf(chkActive.isSelected())});
        lblStatus.setText("Customer saved: " + name);
        clearForm();
    }

    private void loadSelected() {
        int row = tblCustomers.getSelectedRow();
        if (row < 0) {
            return;
        }
        txtName.setText(String.valueOf(customerModel.getValueAt(row, 1)));
        txtEmail.setText(String.valueOf(customerModel.getValueAt(row, 2)));
        cmbCity.setSelectedItem(customerModel.getValueAt(row, 3));
        chkActive.setSelected(Boolean.TRUE.equals(customerModel.getValueAt(row, 4)));
        lblStatus.setText("Customer loaded: " + txtName.getText());
    }

    private void deleteSelected() {
        int row = tblCustomers.getSelectedRow();
        if (row < 0) {
            return;
        }
        Object name = customerModel.getValueAt(row, 1);
        int answer = JOptionPane.showConfirmDialog(this, name + ": delete this customer?", "Confirm Delete",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.YES_OPTION) {
            customerModel.removeRow(row);
            lblStatus.setText("Customer deleted: " + name);
        }
    }

    public void clearForm() {
        txtName.setText("");
        txtEmail.setText("");
        txtEmail.setBackground(txtName.getBackground());
        txtPhone.setText("");
        cmbCity.setSelectedIndex(0);
        chkActive.setSelected(true);
        txtNotes.setText("");
        txtName.requestFocusInWindow();
    }
}
