package com.acme.spring.ui;

import com.acme.spring.service.Customer;
import com.acme.spring.service.CustomerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

@Component
public class CustomerPanel extends JPanel {

    @Autowired
    private CustomerService customerService;

    private JTextField txtName;
    private JComboBox cmbCity;
    private JCheckBox chkActive;
    private JTable tblCustomers;
    private DefaultTableModel model;

    @PostConstruct
    void init() {
        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT));
        form.setBorder(BorderFactory.createTitledBorder("New Customer"));
        txtName = new JTextField(16);
        cmbCity = new JComboBox(new String[]{"Ankara", "İstanbul", "İzmir", "Bursa"});
        chkActive = new JCheckBox("Active", true);
        JButton btnAdd = new JButton("Add");
        form.add(new JLabel("Full Name:"));
        form.add(txtName);
        form.add(new JLabel("City:"));
        form.add(cmbCity);
        form.add(chkActive);
        form.add(btnAdd);

        model = new DefaultTableModel(new Object[]{"ID", "Full Name", "City", "Active"}, 0) {
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        tblCustomers = new JTable(model);
        add(form, BorderLayout.NORTH);
        add(new JScrollPane(tblCustomers), BorderLayout.CENTER);

        btnAdd.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                try {
                    customerService.createCustomer(txtName.getText(), (String) cmbCity.getSelectedItem(),
                            chkActive.isSelected());
                    txtName.setText("");
                    refresh();
                } catch (IllegalArgumentException ex) {
                    JOptionPane.showMessageDialog(CustomerPanel.this, ex.getMessage(), "Validation",
                            JOptionPane.WARNING_MESSAGE);
                }
            }
        });
        refresh();
    }

    public void refresh() {
        model.setRowCount(0);
        for (Customer c : customerService.listCustomers()) {
            model.addRow(new Object[]{c.getId(), c.getName(), c.getCity(), c.isActive() ? "Yes" : "No"});
        }
    }
}
