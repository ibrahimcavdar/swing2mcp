package com.acme.legacy;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.TransferHandler;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.util.List;

/**
 * Drag and drop between lists, a table and a drop zone (Swing TransferHandlers, as in many
 * legacy planning screens), multi-select lists, a split pane, a zoom slider and a map that pans
 * with the mouse.
 */
public class PlannerPanel extends JPanel {

    private final JLabel status;
    private JList<String> lstBacklog;
    private JList<String> lstWeek;
    private JTable tblAssignments;
    private DefaultTableModel assignmentModel;
    private JLabel lblTrash;
    private JSplitPane splitPlanner;
    private MapCanvas mapCanvas;
    private JLabel lblPointer;
    private JSlider sldZoom;
    private JLabel lblMapStatus;

    public PlannerPanel(JLabel status) {
        this.status = status;
        initComponents();
    }

    private void initComponents() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        TaskTransferHandler taskHandler = new TaskTransferHandler();
        lstBacklog = taskList(taskHandler, "Design review", "Write tests", "Fix login bug", "Update docs",
                "Plan sprint", "Release 3.3", "Clean up logs");
        lstWeek = taskList(taskHandler, "Customer call");

        // Edited in place, as in many legacy grids: the task by typing, the owner from a combo box.
        assignmentModel = new DefaultTableModel(new Object[]{"Task", "Owner", "Hours"}, 0) {
            public boolean isCellEditable(int row, int column) {
                return column < 2;
            }
        };
        assignmentModel.addRow(new Object[]{"Budget 2025", "Elif", "12"});
        tblAssignments = new JTable(assignmentModel);
        tblAssignments.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(
                new JComboBox<String>(new String[]{"(unassigned)", "Elif", "Mert", "Selin"})));
        tblAssignments.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblAssignments.setDropMode(DropMode.INSERT_ROWS);
        tblAssignments.setFillsViewportHeight(true);
        tblAssignments.setTransferHandler(new AssignmentTransferHandler());

        lblTrash = new JLabel("Drop tasks here to delete them", SwingConstants.CENTER);
        lblTrash.setBorder(BorderFactory.createDashedBorder(Color.GRAY));
        lblTrash.setPreferredSize(new Dimension(200, 36));
        lblTrash.setTransferHandler(new TrashTransferHandler());

        JPanel left = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.fill = GridBagConstraints.BOTH;
        gc.weightx = 1;
        gc.insets = new Insets(2, 0, 2, 0);
        addLabeled(left, gc, "Backlog:", lstBacklog, 1);
        addLabeled(left, gc, "This week:", lstWeek, 1);
        addLabeled(left, gc, "Assignments:", tblAssignments, 1);
        gc.weighty = 0;
        left.add(lblTrash, gc);

        mapCanvas = new MapCanvas();
        sldZoom = new JSlider(25, 400, 100);
        sldZoom.setMajorTickSpacing(75);
        sldZoom.setPaintTicks(true);
        lblMapStatus = new JLabel(mapCanvas.describeView());
        lblPointer = new JLabel(mapCanvas.describePointer());
        mapCanvas.setOnChange(new Runnable() {
            public void run() {
                lblMapStatus.setText(mapCanvas.describeView());
                lblPointer.setText(mapCanvas.describePointer());
                int percent = (int) Math.round(mapCanvas.getZoom() * 100);
                if (sldZoom.getValue() != percent) {
                    sldZoom.setValue(percent);
                }
            }
        });
        sldZoom.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent e) {
                if (Math.round(mapCanvas.getZoom() * 100) != sldZoom.getValue()) {
                    mapCanvas.setZoom(sldZoom.getValue() / 100.0);
                }
            }
        });
        JPanel zoomRow = new JPanel(new BorderLayout(6, 0));
        JLabel lblZoom = new JLabel("Zoom:");
        lblZoom.setLabelFor(sldZoom);
        zoomRow.add(lblZoom, BorderLayout.WEST);
        zoomRow.add(sldZoom, BorderLayout.CENTER);
        JPanel right = new JPanel(new BorderLayout(0, 4));
        right.add(mapCanvas, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(zoomRow, BorderLayout.NORTH);
        bottom.add(lblMapStatus, BorderLayout.CENTER);
        bottom.add(lblPointer, BorderLayout.SOUTH);
        right.add(bottom, BorderLayout.SOUTH);

        splitPlanner = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        splitPlanner.setDividerLocation(300);
        splitPlanner.setContinuousLayout(true);
        add(splitPlanner, BorderLayout.CENTER);
    }

    private static JList<String> taskList(TransferHandler handler, String... tasks) {
        DefaultListModel<String> model = new DefaultListModel<String>();
        for (String task : tasks) {
            model.addElement(task);
        }
        JList<String> list = new JList<String>(model);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setDragEnabled(true);
        list.setDropMode(DropMode.INSERT);
        list.setTransferHandler(handler);
        return list;
    }

    private static void addLabeled(JPanel panel, GridBagConstraints gc, String text, JComponent field, double weight) {
        JLabel label = new JLabel(text);
        label.setLabelFor(field);
        gc.weighty = 0;
        panel.add(label, gc);
        gc.weighty = weight;
        JScrollPane scroll = new JScrollPane(field);
        scroll.setPreferredSize(new Dimension(200, 90));
        panel.add(scroll, gc);
    }

    private static String[] tasksOf(TransferHandler.TransferSupport support) {
        try {
            String data = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
            return data.split("\n");
        } catch (Exception e) {
            return new String[0];
        }
    }

    /** Moves or copies tasks between the two lists and reorders them inside one list. */
    private class TaskTransferHandler extends TransferHandler {
        private JList<String> source;
        private int[] indices;
        private JComponent importedInto;
        private int addIndex = -1;
        private int addCount;

        public int getSourceActions(JComponent c) {
            return COPY_OR_MOVE;
        }

        protected Transferable createTransferable(JComponent c) {
            @SuppressWarnings("unchecked")
            JList<String> list = (JList<String>) c;
            source = list;
            indices = list.getSelectedIndices();
            List<String> values = list.getSelectedValuesList();
            StringBuilder sb = new StringBuilder();
            for (String value : values) {
                sb.append(sb.length() > 0 ? "\n" : "").append(value);
            }
            return new StringSelection(sb.toString());
        }

        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.stringFlavor);
        }

        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            JList<String> target = (JList<String>) support.getComponent();
            DefaultListModel<String> model = (DefaultListModel<String>) target.getModel();
            int index = support.isDrop() ? ((JList.DropLocation) support.getDropLocation()).getIndex() : model.getSize();
            if (target == source && indices != null && indices.length > 0
                    && index >= indices[0] && index <= indices[indices.length - 1] + 1) {
                indices = null; // dropped onto itself: nothing to do
                return false;
            }
            String[] tasks = tasksOf(support);
            importedInto = target;
            addIndex = index;
            addCount = tasks.length;
            for (String task : tasks) {
                model.add(index++, task);
            }
            target.setSelectionInterval(addIndex, addIndex + addCount - 1);
            status.setText((support.getDropAction() == COPY ? "Copied " : "Moved ") + tasks.length
                    + (tasks.length == 1 ? " task" : " tasks") + " to " + (target == lstWeek ? "This week" : "Backlog"));
            return true;
        }

        protected void exportDone(JComponent c, Transferable data, int action) {
            if (action == MOVE && indices != null && source != null) {
                DefaultListModel<String> model = (DefaultListModel<String>) source.getModel();
                if (importedInto == source && addCount > 0) {
                    for (int i = 0; i < indices.length; i++) {
                        if (indices[i] >= addIndex) {
                            indices[i] += addCount;
                        }
                    }
                }
                for (int i = indices.length - 1; i >= 0; i--) {
                    model.remove(indices[i]);
                }
            }
            source = null;
            indices = null;
            importedInto = null;
            addIndex = -1;
            addCount = 0;
        }
    }

    /** Dropped tasks become rows of the assignments table; the table itself is not a drag source. */
    private class AssignmentTransferHandler extends TransferHandler {
        public boolean canImport(TransferSupport support) {
            return support.isDrop() && support.isDataFlavorSupported(DataFlavor.stringFlavor);
        }

        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            int row = ((JTable.DropLocation) support.getDropLocation()).getRow();
            String[] tasks = tasksOf(support);
            for (String task : tasks) {
                assignmentModel.insertRow(row++, new Object[]{task, "(unassigned)", ""});
            }
            status.setText("Assigned " + tasks.length + (tasks.length == 1 ? " task" : " tasks"));
            return true;
        }
    }

    /** A plain label that accepts tasks only as a move, i.e. deletes them from their list. */
    private class TrashTransferHandler extends TransferHandler {
        public boolean canImport(TransferSupport support) {
            if (!support.isDrop() || !support.isDataFlavorSupported(DataFlavor.stringFlavor)
                    || (support.getSourceDropActions() & MOVE) == 0) {
                return false;
            }
            support.setDropAction(MOVE);
            return true;
        }

        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            int count = tasksOf(support).length;
            status.setText("Deleted " + count + (count == 1 ? " task" : " tasks"));
            return true;
        }
    }
}
