package co.icesi.project.v3.visualization;

import com.zeroc.Ice.Current;
import sitmmio.v3.slice.BusEvent;
import sitmmio.v3.slice.Visualization;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.util.logging.Logger;

public class VisualizationI implements Visualization {
    private static final Logger LOGGER = Logger.getLogger(VisualizationI.class.getName());

    private final DefaultTableModel tableModel;
    private final boolean guiEnabled;

    public VisualizationI() {
        this.guiEnabled = !GraphicsEnvironment.isHeadless();
        this.tableModel = new DefaultTableModel(new Object[]{"Timestamp", "Source", "Destination", "Type", "Detail"}, 0);
        if (guiEnabled) {
            SwingUtilities.invokeLater(this::openWindow);
        } else {
            LOGGER.info("Headless mode detected; bus events will be printed to console");
        }
    }

    @Override
    public void publish(BusEvent event, Current current) {
        if (guiEnabled) {
            SwingUtilities.invokeLater(() -> tableModel.addRow(new Object[]{
                    event.timestamp,
                    event.source,
                    event.destination,
                    event.messageType,
                    event.detail
            }));
        }
        LOGGER.info(event.timestamp + " | " + event.source + " -> " + event.destination + " | " + event.messageType + " | " + event.detail);
    }

    private void openWindow() {
        JFrame frame = new JFrame("SITM-MIO V3 Message Bus Monitor");
        JTable table = new JTable(tableModel);
        frame.setLayout(new BorderLayout());
        frame.add(new JScrollPane(table), BorderLayout.CENTER);
        frame.setSize(980, 420);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setVisible(true);
    }
}
