package com.acme.legacy;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

public class OrderPanel extends JPanel {

    private JTree treeCategories;
    private JList lstOrders;
    private JTextArea txtDetails;
    private JSpinner spnQuantity;

    public OrderPanel() {
        initComponents();
    }

    private void initComponents() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Categories");
        DefaultMutableTreeNode electronics = new DefaultMutableTreeNode("Electronics");
        electronics.add(new DefaultMutableTreeNode("Computers"));
        electronics.add(new DefaultMutableTreeNode("Phones"));
        DefaultMutableTreeNode office = new DefaultMutableTreeNode("Office");
        office.add(new DefaultMutableTreeNode("Stationery"));
        root.add(electronics);
        root.add(office);
        treeCategories = new JTree(root);

        final String[] orders = {"SP-1001 Ayşe Yılmaz", "SP-1002 Mehmet Demir", "SP-1003 Zeynep Kaya"};
        lstOrders = new JList(orders);
        txtDetails = new JTextArea(6, 30);
        txtDetails.setEditable(false);
        lstOrders.addListSelectionListener(new ListSelectionListener() {
            public void valueChanged(ListSelectionEvent e) {
                Object selected = lstOrders.getSelectedValue();
                txtDetails.setText(selected == null ? "" : "Order: " + selected + "\nStatus: Processing");
            }
        });

        spnQuantity = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
        JPanel quantityRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        quantityRow.add(new JLabel("Quantity:"));
        quantityRow.add(spnQuantity);

        JPanel right = new JPanel(new BorderLayout(4, 4));
        right.add(new JScrollPane(lstOrders), BorderLayout.NORTH);
        right.add(new JScrollPane(txtDetails), BorderLayout.CENTER);
        right.add(quantityRow, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(treeCategories), right);
        split.setDividerLocation(220);
        add(split, BorderLayout.CENTER);
    }
}
