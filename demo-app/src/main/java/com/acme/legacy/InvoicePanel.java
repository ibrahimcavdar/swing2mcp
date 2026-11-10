package com.acme.legacy;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** A long, sortable table whose cells are formatted by renderers (as in most business screens). */
public class InvoicePanel extends JPanel {

    private static final String[] CUSTOMERS = {"Ayşe Yılmaz", "Mehmet Demir", "Zeynep Kaya", "Ali Çelik", "Elif Şahin"};

    private JTable tblInvoices;
    private DefaultTableModel invoiceModel;

    public InvoicePanel() {
        initComponents();
    }

    private void initComponents() {
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        invoiceModel = new DefaultTableModel(new Object[]{"No", "Customer", "Date", "Amount", "Paid"}, 0) {
            public Class getColumnClass(int columnIndex) {
                switch (columnIndex) {
                    case 0:
                        return Integer.class;
                    case 2:
                        return Date.class;
                    case 3:
                        return Double.class;
                    case 4:
                        return Boolean.class;
                    default:
                        return String.class;
                }
            }

            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        Calendar day = Calendar.getInstance();
        day.set(2024, Calendar.JANUARY, 2, 0, 0, 0);
        for (int i = 1; i <= 120; i++) {
            double amount = 100 + (i * 7919 % 50000) / 10.0;
            invoiceModel.addRow(new Object[]{1000 + i, CUSTOMERS[i % CUSTOMERS.length], day.getTime(), amount,
                    Boolean.valueOf(i % 3 != 0)});
            day.add(Calendar.DAY_OF_MONTH, 3);
        }
        tblInvoices = new JTable(invoiceModel);
        tblInvoices.setAutoCreateRowSorter(true);
        final NumberFormat money = NumberFormat.getNumberInstance(new Locale("tr", "TR"));
        money.setMinimumFractionDigits(2);
        money.setMaximumFractionDigits(2);
        tblInvoices.setDefaultRenderer(Double.class, new DefaultTableCellRenderer() {
            protected void setValue(Object value) {
                setHorizontalAlignment(RIGHT);
                setText(value == null ? "" : money.format(value) + " TL");
            }
        });
        final SimpleDateFormat date = new SimpleDateFormat("dd.MM.yyyy");
        tblInvoices.setDefaultRenderer(Date.class, new DefaultTableCellRenderer() {
            protected void setValue(Object value) {
                setText(value == null ? "" : date.format((Date) value));
            }
        });
        tblInvoices.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && tblInvoices.getSelectedRow() >= 0) {
                    int row = tblInvoices.convertRowIndexToModel(tblInvoices.getSelectedRow());
                    JOptionPane.showMessageDialog(InvoicePanel.this,
                            "Invoice " + invoiceModel.getValueAt(row, 0) + "\nCustomer: " + invoiceModel.getValueAt(row, 1)
                                    + "\nAmount: " + money.format(invoiceModel.getValueAt(row, 3)) + " TL",
                            "Invoice Details", JOptionPane.INFORMATION_MESSAGE);
                }
            }
        });

        add(new JLabel("Double-click an invoice to see its details."), BorderLayout.NORTH);
        add(new JScrollPane(tblInvoices), BorderLayout.CENTER);
    }

    /** Selects the invoice with this number; false if there is none. */
    public boolean select(String number) {
        for (int row = 0; row < tblInvoices.getRowCount(); row++) {
            if (String.valueOf(tblInvoices.getValueAt(row, 0)).equals(number)) {
                tblInvoices.setRowSelectionInterval(row, row);
                tblInvoices.scrollRectToVisible(tblInvoices.getCellRect(row, 0, true));
                return true;
            }
        }
        return false;
    }
}
