package co.icesi.project.v3.worker;

import sitmmio.v3.slice.SpeedResult;
import sitmmio.v3.slice.SpeedStat;
import sitmmio.v3.slice.SpeedTask;
import sitmmio.v3.slice.Worker;

import com.zeroc.Ice.Current;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WorkerI implements Worker {
    private static final Logger LOGGER = Logger.getLogger(WorkerI.class.getName());
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double MAX_SPEED_KMH = 80.0;
    private static final double TO_RAD = Math.PI / 180.0;

    private final String workerId;

    public WorkerI(String workerId) {
        this.workerId = workerId;
    }

    @Override
    public String workerId(Current current) {
        return workerId;
    }

    @Override
    public SpeedResult process(SpeedTask task, Current current) {
        long start = System.nanoTime();
        try {
            Set<Integer> activeRoutes = loadRoutes(task.routesPath);
            Map<String, List<DatagramPoint>> grouped = loadPartition(task, activeRoutes);
            Map<String, Aggregate> aggregates = calculate(grouped);
            List<SpeedStat> stats = new ArrayList<>();
            for (Aggregate aggregate : aggregates.values()) {
                stats.add(aggregate.toStat());
            }
            stats.sort(Comparator.comparingInt((SpeedStat s) -> s.lineId).thenComparing(s -> s.month));
            return new SpeedResult(task.taskId, true, workerId + " processed " + grouped.size() + " bus-route groups", task.outputPath, stats.size(), elapsedMs(start), stats.toArray(new SpeedStat[0]));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, workerId + " could not process task " + task.taskId, e);
            return new SpeedResult(task.taskId, false, e.getMessage(), task.outputPath, 0, elapsedMs(start), new SpeedStat[0]);
        }
    }

    private Set<Integer> loadRoutes(String path) throws Exception {
        Set<Integer> routes = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length > 0 && isInteger(parts[0])) {
                    routes.add(Integer.parseInt(parts[0]));
                }
            }
        }
        return routes;
    }

    private Map<String, List<DatagramPoint>> loadPartition(SpeedTask task, Set<Integer> activeRoutes) throws Exception {
        Map<String, List<DatagramPoint>> grouped = new HashMap<>();
        int acceptedRows = 0;
        int partitionCount = Math.max(1, task.partitionCount);
        int partitionIndex = Math.max(0, task.partitionIndex);
        Path inputPath = resolvePartitionInput(task.datagramsPath, partitionIndex);
        boolean prepartitionedInput = Files.isDirectory(Path.of(task.datagramsPath));

        try (BufferedReader reader = new BufferedReader(new FileReader(inputPath.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 12 || !isInteger(parts[7])) {
                    continue;
                }
                acceptedRows++;
                if (task.maxRows > 0 && acceptedRows > task.maxRows) {
                    break;
                }

                int lineId = Integer.parseInt(parts[7]);
                if (!activeRoutes.contains(lineId)) {
                    continue;
                }
                int busId = Integer.parseInt(parts[11]);
                String groupKey = busId + "|" + lineId;
                int partition = Math.floorMod(groupKey.hashCode(), partitionCount);
                if (!prepartitionedInput && partition != partitionIndex) {
                    continue;
                }

                LocalDateTime date = LocalDateTime.parse(parts[10], FORMATTER);
                double latitude = Long.parseLong(parts[4]) / 1e7;
                double longitude = Long.parseLong(parts[5]) / 1e7;
                grouped.computeIfAbsent(groupKey, ignored -> new ArrayList<>())
                        .add(new DatagramPoint(lineId, busId, date, latitude, longitude));
            }
        }
        return grouped;
    }

    private Path resolvePartitionInput(String datagramsPath, int partitionIndex) {
        Path path = Path.of(datagramsPath);
        if (Files.isDirectory(path)) {
            return path.resolve("partition-" + partitionIndex + ".csv");
        }
        return path;
    }

    private Map<String, Aggregate> calculate(Map<String, List<DatagramPoint>> grouped) {
        Map<String, Aggregate> aggregates = new HashMap<>();
        for (List<DatagramPoint> points : grouped.values()) {
            points.sort(Comparator.comparing(point -> point.date));
            for (int i = 1; i < points.size(); i++) {
                DatagramPoint previous = points.get(i - 1);
                DatagramPoint current = points.get(i);
                long seconds = Duration.between(previous.date, current.date).getSeconds();
                double distanceKm = haversine(previous.latitude, previous.longitude, current.latitude, current.longitude);
                if (seconds <= 0 || distanceKm <= 0) {
                    continue;
                }
                double speed = distanceKm / (seconds / 3600.0);
                if (speed > MAX_SPEED_KMH) {
                    continue;
                }
                YearMonth month = YearMonth.from(current.date);
                String key = current.lineId + "|" + month;
                aggregates.computeIfAbsent(key, ignored -> new Aggregate(current.lineId, month.toString())).add(speed);
            }
        }
        return aggregates;
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = (lat2 - lat1) * TO_RAD;
        double dLon = (lon2 - lon1) * TO_RAD;
        double lat1Rad = lat1 * TO_RAD;
        double lat2Rad = lat2 * TO_RAD;
        double sinDLat = Math.sin(dLat / 2);
        double sinDLon = Math.sin(dLon / 2);
        double a = sinDLat * sinDLat + Math.cos(lat1Rad) * Math.cos(lat2Rad) * sinDLon * sinDLon;
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private boolean isInteger(String value) {
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

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static final class DatagramPoint {
        private final int lineId;
        private final int busId;
        private final LocalDateTime date;
        private final double latitude;
        private final double longitude;

        private DatagramPoint(int lineId, int busId, LocalDateTime date, double latitude, double longitude) {
            this.lineId = lineId;
            this.busId = busId;
            this.date = date;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static final class Aggregate {
        private final int lineId;
        private final String month;
        private long count;
        private double sum;

        private Aggregate(int lineId, String month) {
            this.lineId = lineId;
            this.month = month;
        }

        private void add(double speed) {
            count++;
            sum += speed;
        }

        private SpeedStat toStat() {
            return new SpeedStat(lineId, month, count, sum);
        }
    }
}
