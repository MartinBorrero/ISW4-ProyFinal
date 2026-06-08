package co.icesi.project.v3.visualization;

import com.zeroc.Ice.Current;
import sitmmio.v3.slice.BusEvent;
import sitmmio.v3.slice.Visualization;

import javax.swing.JFrame;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class VisualizationI implements Visualization {
    private static final Logger LOGGER = Logger.getLogger(VisualizationI.class.getName());

    private final DefaultTableModel tableModel;
    private final MapPanel mapPanel;
    private final JLabel statusLabel;
    private final JComboBox<String> filterMode;
    private final JTextField filterValue;
    private final List<BusPosition> positionHistory;
    private final boolean guiEnabled;

    public VisualizationI() {
        this.guiEnabled = !GraphicsEnvironment.isHeadless();
        this.tableModel = new DefaultTableModel(new Object[]{"Timestamp", "Source", "Destination", "Type", "Detail"}, 0);
        this.mapPanel = new MapPanel();
        this.statusLabel = new JLabel("Waiting for distributed bus events...");
        this.filterMode = new JComboBox<>(new String[]{"All", "Day", "Month"});
        this.filterValue = new JTextField(10);
        this.positionHistory = new ArrayList<>();
        if (guiEnabled) {
            SwingUtilities.invokeLater(this::openWindow);
        } else {
            LOGGER.info("Headless mode detected; bus events will be printed to console");
        }
    }

    @Override
    public void publish(BusEvent event, Current current) {
        if (guiEnabled) {
            SwingUtilities.invokeLater(() -> {
                tableModel.addRow(new Object[]{
                        event.timestamp,
                        event.source,
                        event.destination,
                        event.messageType,
                        event.detail
                });
                if ("BUS_POSITION".equals(event.messageType)) {
                    BusPosition position = parsePosition(event);
                    if (position != null) {
                        positionHistory.add(position);
                        if (matchesCurrentFilter(position)) {
                            mapPanel.updatePosition(position);
                        }
                    }
                }
                updateStatus();
            });
        }
        LOGGER.info(event.timestamp + " | " + event.source + " -> " + event.destination + " | " + event.messageType + " | " + event.detail);
    }

    private void openWindow() {
        JFrame frame = new JFrame("SITM-MIO V3 Real-Time Distributed Monitor");
        JTable table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        JScrollPane tableScroll = new JScrollPane(table);
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mapPanel, tableScroll);
        splitPane.setResizeWeight(0.62);
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(statusLabel, BorderLayout.NORTH);
        topPanel.add(filterPanel(), BorderLayout.SOUTH);
        frame.setLayout(new BorderLayout());
        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(splitPane, BorderLayout.CENTER);
        frame.setSize(1120, 760);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setVisible(true);
    }

    private JPanel filterPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton applyButton = new JButton("Apply filter");
        JButton clearButton = new JButton("Clear");
        filterValue.setToolTipText("Day: yyyy-MM-dd | Month: yyyy-MM");
        applyButton.addActionListener(ignored -> applyCurrentFilter());
        clearButton.addActionListener(ignored -> {
            filterMode.setSelectedItem("All");
            filterValue.setText("");
            applyCurrentFilter();
        });
        panel.add(new JLabel("Map filter:"));
        panel.add(filterMode);
        panel.add(filterValue);
        panel.add(new JLabel("Use yyyy-MM-dd for day or yyyy-MM for month"));
        panel.add(applyButton);
        panel.add(clearButton);
        return panel;
    }

    private void applyCurrentFilter() {
        mapPanel.clearPositions();
        for (BusPosition position : positionHistory) {
            if (matchesCurrentFilter(position)) {
                mapPanel.updatePosition(position);
            }
        }
        updateStatus();
    }

    private boolean matchesCurrentFilter(BusPosition position) {
        String mode = String.valueOf(filterMode.getSelectedItem());
        String value = filterValue.getText().trim();
        if ("All".equals(mode) || value.isEmpty()) {
            return true;
        }
        if ("Day".equals(mode)) {
            return position.date.startsWith(value);
        }
        if ("Month".equals(mode)) {
            return position.date.startsWith(value);
        }
        return true;
    }

    private void updateStatus() {
        String mode = String.valueOf(filterMode.getSelectedItem());
        String value = filterValue.getText().trim();
        String filter = "All".equals(mode) || value.isEmpty() ? "all positions" : mode.toLowerCase() + "=" + value;
        statusLabel.setText("Events: " + tableModel.getRowCount()
                + " | BUS_POSITION received: " + positionHistory.size()
                + " | Active buses on map: " + mapPanel.positionCount()
                + " | Filter: " + filter);
    }

    private BusPosition parsePosition(BusEvent event) {
        Map<String, String> fields = new HashMap<>();
        String[] parts = event.detail.split(";");
        for (String part : parts) {
            int separator = part.indexOf('=');
            if (separator > 0 && separator < part.length() - 1) {
                fields.put(part.substring(0, separator), part.substring(separator + 1));
            }
        }
        try {
            int busId = Integer.parseInt(fields.getOrDefault("busId", "-1"));
            int lineId = Integer.parseInt(fields.getOrDefault("lineId", "-1"));
            double latitude = Double.parseDouble(fields.getOrDefault("lat", "0"));
            double longitude = Double.parseDouble(fields.getOrDefault("lon", "0"));
            String worker = fields.getOrDefault("worker", event.source);
            String date = fields.getOrDefault("date", event.timestamp);
            return new BusPosition(busId, lineId, latitude, longitude, worker, date);
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid BUS_POSITION event detail: " + event.detail);
            return null;
        }
    }

    private static final class BusPosition {
        private final int busId;
        private final int lineId;
        private final double latitude;
        private final double longitude;
        private final String worker;
        private final String date;

        private BusPosition(int busId, int lineId, double latitude, double longitude, String worker, String date) {
            this.busId = busId;
            this.lineId = lineId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.worker = worker;
            this.date = date;
        }
    }

    private static final class MapPanel extends JPanel {
        private static final Color BACKGROUND = new Color(231, 235, 228);
        private static final int TILE_SIZE = 256;
        private static final int ZOOM = 13;
        private static final double CALI_LAT = 3.4516;
        private static final double CALI_LON = -76.5320;

        private final Map<Integer, AnimatedBus> buses = new HashMap<>();
        private final Map<String, Image> tileCache = new ConcurrentHashMap<>();
        private final Map<String, Boolean> tileRequests = new ConcurrentHashMap<>();
        private final ExecutorService tileExecutor = Executors.newFixedThreadPool(3);

        private MapPanel() {
            setPreferredSize(new Dimension(1000, 430));
            setBackground(BACKGROUND);
            new Timer(40, ignored -> animate()).start();
        }

        private void updatePosition(BusPosition position) {
            if (position == null || position.busId < 0 || position.latitude == 0 || position.longitude == 0) {
                return;
            }
            AnimatedBus bus = buses.computeIfAbsent(position.busId, ignored -> new AnimatedBus(position));
            bus.moveTo(position);
            repaint();
        }

        private void clearPositions() {
            buses.clear();
            repaint();
        }

        private int positionCount() {
            return buses.size();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawMapTiles(g);
            drawMapHeader(g);
            drawBuses(g);
            g.dispose();
        }

        private void drawMapTiles(Graphics2D g) {
            int width = getWidth();
            int height = getHeight();
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, width, height);

            double centerX = lonToPixelX(CALI_LON, ZOOM);
            double centerY = latToPixelY(CALI_LAT, ZOOM);
            int topLeftPixelX = (int) Math.round(centerX - width / 2.0);
            int topLeftPixelY = (int) Math.round(centerY - height / 2.0);
            int firstTileX = Math.floorDiv(topLeftPixelX, TILE_SIZE);
            int firstTileY = Math.floorDiv(topLeftPixelY, TILE_SIZE);
            int lastTileX = Math.floorDiv(topLeftPixelX + width, TILE_SIZE);
            int lastTileY = Math.floorDiv(topLeftPixelY + height, TILE_SIZE);

            for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                for (int tileY = firstTileY; tileY <= lastTileY; tileY++) {
                    String key = ZOOM + "/" + tileX + "/" + tileY;
                    int screenX = tileX * TILE_SIZE - topLeftPixelX;
                    int screenY = tileY * TILE_SIZE - topLeftPixelY;
                    Image image = tileCache.get(key);
                    if (image != null) {
                        g.drawImage(image, screenX, screenY, TILE_SIZE, TILE_SIZE, this);
                    } else {
                        requestTile(tileX, tileY);
                        g.setColor(new Color(220, 226, 216));
                        g.fillRect(screenX, screenY, TILE_SIZE, TILE_SIZE);
                        g.setColor(new Color(188, 198, 184));
                        g.drawRect(screenX, screenY, TILE_SIZE, TILE_SIZE);
                    }
                }
            }
        }

        private void drawMapHeader(Graphics2D g) {
            g.setColor(new Color(255, 255, 255, 220));
            g.fillRoundRect(16, 14, 520, 58, 14, 14);
            g.setColor(new Color(42, 63, 55));
            g.setFont(new Font("SansSerif", Font.BOLD, 18));
            g.drawString("SITM-MIO buses over Cali map", 30, 38);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.drawString("OpenStreetMap base + real datagram lat/lon emitted by distributed workers", 30, 58);
        }

        private void drawBuses(Graphics2D g) {
            if (buses.isEmpty()) {
                g.setColor(new Color(255, 255, 255, 220));
                g.fillRoundRect(24, getHeight() / 2 - 28, 460, 56, 14, 14);
                g.setColor(new Color(42, 63, 55));
                g.setFont(new Font("SansSerif", Font.BOLD, 16));
                g.drawString("Waiting for BUS_POSITION events from SpeedPartitionWorkers...", 42, getHeight() / 2 + 5);
                return;
            }
            for (AnimatedBus bus : buses.values()) {
                int x = projectX(bus.displayLongitude);
                int y = projectY(bus.displayLatitude);
                Color color = colorForLine(bus.latest.lineId);
                drawTrail(g, bus, color);
                g.setColor(color);
                g.fillOval(x - 9, y - 9, 18, 18);
                g.setColor(Color.WHITE);
                g.fillOval(x - 4, y - 4, 8, 8);
                g.setColor(new Color(31, 39, 35));
                g.setFont(new Font("SansSerif", Font.BOLD, 11));
                g.drawString("B" + bus.latest.busId + " L" + bus.latest.lineId, x + 12, y - 8);
                g.setFont(new Font("SansSerif", Font.PLAIN, 10));
                g.drawString(bus.latest.worker, x + 12, y + 6);
            }
        }

        private int projectX(double longitude) {
            double centerX = lonToPixelX(CALI_LON, ZOOM);
            double pointX = lonToPixelX(longitude, ZOOM);
            return (int) Math.round(getWidth() / 2.0 + pointX - centerX);
        }

        private int projectY(double latitude) {
            double centerY = latToPixelY(CALI_LAT, ZOOM);
            double pointY = latToPixelY(latitude, ZOOM);
            return (int) Math.round(getHeight() / 2.0 + pointY - centerY);
        }

        private Color colorForLine(int lineId) {
            float hue = Math.floorMod(lineId * 37, 360) / 360f;
            return Color.getHSBColor(hue, 0.72f, 0.72f);
        }

        private void drawTrail(Graphics2D g, AnimatedBus bus, Color color) {
            if (bus.trail.size() < 2) {
                return;
            }
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 110));
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            BusPosition previous = null;
            for (BusPosition point : bus.trail) {
                if (previous != null) {
                    g.drawLine(projectX(previous.longitude), projectY(previous.latitude), projectX(point.longitude), projectY(point.latitude));
                }
                previous = point;
            }
        }

        private void animate() {
            boolean changed = false;
            for (AnimatedBus bus : buses.values()) {
                changed |= bus.step();
            }
            if (changed) {
                repaint();
            }
        }

        private void requestTile(int tileX, int tileY) {
            String key = ZOOM + "/" + tileX + "/" + tileY;
            if (tileRequests.putIfAbsent(key, true) != null) {
                return;
            }
            tileExecutor.submit(() -> {
                try {
                    URL url = new URL("https://tile.openstreetmap.org/" + key + ".png");
                    URLConnection connection = url.openConnection();
                    connection.setRequestProperty("User-Agent", "ISW4-SITM-MIO-V3-Visualization/1.0");
                    connection.setConnectTimeout(3_000);
                    connection.setReadTimeout(5_000);
                    try (InputStream input = connection.getInputStream()) {
                        Image image = ImageIO.read(input);
                        if (image != null) {
                            tileCache.put(key, image);
                            SwingUtilities.invokeLater(this::repaint);
                        }
                    }
                } catch (Exception e) {
                    tileRequests.remove(key);
                }
            });
        }

        private double lonToPixelX(double longitude, int zoom) {
            double scale = TILE_SIZE * Math.pow(2, zoom);
            return (longitude + 180.0) / 360.0 * scale;
        }

        private double latToPixelY(double latitude, int zoom) {
            double sinLatitude = Math.sin(Math.toRadians(latitude));
            double scale = TILE_SIZE * Math.pow(2, zoom);
            return (0.5 - Math.log((1 + sinLatitude) / (1 - sinLatitude)) / (4 * Math.PI)) * scale;
        }

        private static final class AnimatedBus {
            private static final double STEP = 0.16;
            private BusPosition latest;
            private double displayLatitude;
            private double displayLongitude;
            private double targetLatitude;
            private double targetLongitude;
            private final Deque<BusPosition> trail = new ArrayDeque<>();

            private AnimatedBus(BusPosition initial) {
                this.latest = initial;
                this.displayLatitude = initial.latitude;
                this.displayLongitude = initial.longitude;
                this.targetLatitude = initial.latitude;
                this.targetLongitude = initial.longitude;
                this.trail.addLast(initial);
            }

            private void moveTo(BusPosition position) {
                this.latest = position;
                this.targetLatitude = position.latitude;
                this.targetLongitude = position.longitude;
                this.trail.addLast(position);
                while (trail.size() > 18) {
                    trail.removeFirst();
                }
            }

            private boolean step() {
                double latDelta = targetLatitude - displayLatitude;
                double lonDelta = targetLongitude - displayLongitude;
                if (Math.abs(latDelta) < 0.000001 && Math.abs(lonDelta) < 0.000001) {
                    displayLatitude = targetLatitude;
                    displayLongitude = targetLongitude;
                    return false;
                }
                displayLatitude += latDelta * STEP;
                displayLongitude += lonDelta * STEP;
                return true;
            }
        }
    }
}
