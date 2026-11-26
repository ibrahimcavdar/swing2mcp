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
import javax.swing.Timer;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

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
        // Children of the archive are loaded only when it is expanded, a little later (as from a server).
        final DefaultMutableTreeNode archive = new DefaultMutableTreeNode("Archive");
        archive.add(new DefaultMutableTreeNode("Loading…"));
        root.add(electronics);
        root.add(office);
        root.add(archive);
        treeCategories = new JTree(root);
        treeCategories.setEditable(true); // a category is renamed in place: F2, or three clicks on its name
        treeCategories.addTreeWillExpandListener(new TreeWillExpandListener() {
            public void treeWillExpand(TreeExpansionEvent event) {
                if (event.getPath().getLastPathComponent() != archive || archive.getChildCount() != 1
                        || !"Loading…".equals(String.valueOf(((DefaultMutableTreeNode) archive.getChildAt(0)).getUserObject()))) {
                    return;
                }
                Timer timer = new Timer(400, new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        archive.removeAllChildren();
                        for (int year = 2019; year <= 2023; year++) {
                            DefaultMutableTreeNode yearNode = new DefaultMutableTreeNode(String.valueOf(year));
                            yearNode.add(new DefaultMutableTreeNode("Q1"));
                            yearNode.add(new DefaultMutableTreeNode("Q2"));
                            archive.add(yearNode);
                        }
                        ((DefaultTreeModel) treeCategories.getModel()).nodeStructureChanged(archive);
                        treeCategories.expandRow(treeCategories.getRowForPath(new javax.swing.tree.TreePath(archive.getPath())));
                    }
                });
                timer.setRepeats(false);
                timer.start();
            }

            public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });

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
