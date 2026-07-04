package co.icesi.project.v3.partitioner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DatagramPartitionerApp {
    private static final Logger LOGGER = Logger.getLogger(DatagramPartitionerApp.class.getName());
    private static final int IO_BUFFER_SIZE = 1024 * 1024;

    public static void main(String[] args) {
        int status = 0;
        try {
            String datagramsPath = arg(args, "--datagrams", "data/datagrams4Pilot.csv");
            String routesPath = arg(args, "--routes", "data/lines-241-ActiveGT.csv");
            String outputDir = arg(args, "--output", "data/partitions-8");
            int partitions = parseInt(arg(args, "--partitions", "8"), 8);
            int maxRows = parseInt(arg(args, "--maxRows", "0"), 0);
            PartitionSummary summary = partition(Path.of(datagramsPath), Path.of(routesPath), Path.of(outputDir), partitions, maxRows);
            System.out.println("Partitioning completed");
            System.out.println("Input rows scanned: " + summary.scannedRows);
            System.out.println("Rows written: " + summary.writtenRows);
            System.out.println("Output directory: " + Path.of(outputDir).toAbsolutePath());
        } catch (Exception e) {
            status = 1;
            LOGGER.log(Level.SEVERE, "Datagram partitioning failed", e);
        }
        System.exit(status);
    }

    public static PartitionSummary partition(Path datagramsPath, Path routesPath, Path outputDir, int partitions, int maxRows) throws IOException {
        if (partitions <= 0) {
            throw new IllegalArgumentException("partitions must be greater than zero");
        }
        Files.createDirectories(outputDir);
        Set<Integer> activeRoutes = loadRoutes(routesPath);
        List<BufferedWriter> writers = new ArrayList<>(partitions);
        try {
            for (int i = 0; i < partitions; i++) {
                writers.add(new BufferedWriter(Files.newBufferedWriter(outputDir.resolve("partition-" + i + ".csv")), IO_BUFFER_SIZE));
            }
            long scanned = 0;
            long written = 0;
            try (BufferedReader reader = new BufferedReader(Files.newBufferedReader(datagramsPath), IO_BUFFER_SIZE)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    DatagramColumns columns = extractDatagramColumns(line);
                    if (columns == null || !isInteger(columns.lineId)) {
                        continue;
                    }
                    scanned++;
                    if (maxRows > 0 && scanned > maxRows) {
                        break;
                    }
                    int lineId = Integer.parseInt(columns.lineId);
                    if (!activeRoutes.contains(lineId)) {
                        continue;
                    }
                    int busId = Integer.parseInt(columns.busId);
                    String day = columns.date.substring(0, 10);
                    String groupKey = busId + "|" + lineId + "|" + day;
                    int partition = Math.floorMod(groupKey.hashCode(), partitions);
                    BufferedWriter writer = writers.get(partition);
                    writer.write(line);
                    writer.newLine();
                    written++;
                }
            }
            return new PartitionSummary(scanned, written);
        } finally {
            for (BufferedWriter writer : writers) {
                try {
                    writer.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Could not close partition writer", e);
                }
            }
        }
    }

    private static Set<Integer> loadRoutes(Path path) throws IOException {
        Set<Integer> routes = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(Files.newBufferedReader(path), IO_BUFFER_SIZE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String routeId = firstColumn(line);
                if (isInteger(routeId)) {
                    routes.add(Integer.parseInt(routeId));
                }
            }
        }
        return routes;
    }

    private static DatagramColumns extractDatagramColumns(String line) {
        String lineId = null;
        String date = null;
        String busId = null;
        int column = 0;
        int start = 0;
        for (int i = 0; i <= line.length(); i++) {
            if (i == line.length() || line.charAt(i) == ',') {
                if (column == 7) {
                    lineId = line.substring(start, i);
                } else if (column == 10) {
                    date = line.substring(start, i);
                } else if (column == 11) {
                    busId = line.substring(start, i);
                    break;
                }
                column++;
                start = i + 1;
            }
        }
        if (lineId == null || date == null || date.length() < 10 || busId == null || !isInteger(busId)) {
            return null;
        }
        return new DatagramColumns(lineId, date, busId);
    }

    private static String firstColumn(String line) {
        int separator = line.indexOf(',');
        return separator < 0 ? line : line.substring(0, separator);
    }

    private static boolean isInteger(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int start = value.charAt(0) == '-' ? 1 : 0;
        if (start == value.length()) {
            return false;
        }
        for (int i = start; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String arg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static final class PartitionSummary {
        public final long scannedRows;
        public final long writtenRows;

        private PartitionSummary(long scannedRows, long writtenRows) {
            this.scannedRows = scannedRows;
            this.writtenRows = writtenRows;
        }
    }

    private static final class DatagramColumns {
        private final String lineId;
        private final String date;
        private final String busId;

        private DatagramColumns(String lineId, String date, String busId) {
            this.lineId = lineId;
            this.date = date;
            this.busId = busId;
        }
    }
}
