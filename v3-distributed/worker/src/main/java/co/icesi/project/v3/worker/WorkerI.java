package co.icesi.project.v3.worker;

import sitmmio.v3.slice.SpeedResult;
import sitmmio.v3.slice.SpeedStat;
import sitmmio.v3.slice.SpeedTask;
import sitmmio.v3.slice.Worker;

import com.zeroc.Ice.Current;
import sitmmio.v3.slice.BusEvent;
import sitmmio.v3.slice.VisualizationPrx;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WorkerI implements Worker {
    private static final Logger LOGGER = Logger.getLogger(WorkerI.class.getName());
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double MAX_SPEED_KMH = 80.0;
    private static final double TO_RAD = Math.PI / 180.0;
    private static final int POSITION_EVENT_INTERVAL = 2_000;
    private static final int MAX_POSITION_EVENTS_PER_TASK = 750;

    private final String workerId;
    private final VisualizationPrx visualization;
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();

    public WorkerI(String workerId, VisualizationPrx visualization) {
        this.workerId = workerId;
        this.visualization = visualization;
    }

    @Override
    public String workerId(Current current) {
        return workerId;
    }

    public void shutdown() {
        eventExecutor.shutdownNow();
    }

    @Override
    public SpeedResult process(SpeedTask task, Current current) {
        long start = System.nanoTime();
        try {
            Set<Integer> activeRoutes = loadRoutes(task.routesPath);
            ProcessingSummary summary = processPartition(task, activeRoutes);
            List<SpeedStat> stats = new ArrayList<>();
            for (Aggregate aggregate : summary.aggregates.values()) {
                stats.add(aggregate.toStat());
            }
            stats.sort(Comparator.comparingInt((SpeedStat s) -> s.lineId).thenComparing(s -> s.month));
            return new SpeedResult(task.taskId, true, workerId + " processed " + summary.groupCount + " bus-route groups", task.outputPath, stats.size(), elapsedMs(start), stats.toArray(new SpeedStat[0]));
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

    private ProcessingSummary processPartition(SpeedTask task, Set<Integer> activeRoutes) throws Exception {
        Map<String, DatagramPoint> lastPointByGroup = new HashMap<>();
        Map<String, Aggregate> aggregates = new HashMap<>();
        int acceptedRows = 0;
        int publishedPositions = 0;
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
                if (acceptedRows % POSITION_EVENT_INTERVAL == 0 && publishedPositions < MAX_POSITION_EVENTS_PER_TASK) {
                    publishedPositions++;
                    publishPosition(busId, lineId, latitude, longitude, date);
                }

                DatagramPoint current = new DatagramPoint(lineId, date, latitude, longitude);
                DatagramPoint previous = lastPointByGroup.get(groupKey);
                if (previous == null) {
                    lastPointByGroup.put(groupKey, current);
                    continue;
                }

                long seconds = Duration.between(previous.date, current.date).getSeconds();
                if (seconds <= 0) {
                    continue;
                }

                addSpeedSample(aggregates, previous, current, seconds);
                lastPointByGroup.put(groupKey, current);
            }
        }
        return new ProcessingSummary(lastPointByGroup.size(), aggregates);
    }

    private Path resolvePartitionInput(String datagramsPath, int partitionIndex) {
        Path path = Path.of(datagramsPath);
        if (Files.isDirectory(path)) {
            return path.resolve("partition-" + partitionIndex + ".csv");
        }
        return path;
    }

    private void addSpeedSample(Map<String, Aggregate> aggregates, DatagramPoint previous, DatagramPoint current, long seconds) {
        double distanceKm = haversine(previous.latitude, previous.longitude, current.latitude, current.longitude);
        if (distanceKm <= 0) {
            return;
        }
        double speed = distanceKm / (seconds / 3600.0);
        if (speed > MAX_SPEED_KMH) {
            return;
        }
        YearMonth month = YearMonth.from(current.date);
        String key = current.lineId + "|" + month;
        aggregates.computeIfAbsent(key, ignored -> new Aggregate(current.lineId, month.toString())).add(speed);
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

    private void publishPosition(int busId, int lineId, double latitude, double longitude, LocalDateTime date) {
        if (visualization == null) {
            return;
        }
        String detail = "worker=" + workerId
                + ";busId=" + busId
                + ";lineId=" + lineId
                + ";lat=" + latitude
                + ";lon=" + longitude
                + ";date=" + date;
        BusEvent event = new BusEvent(workerId, "BusEventMonitor", "BUS_POSITION", Instant.now().toString(), detail);
        eventExecutor.submit(() -> {
            try {
                visualization.publish(event);
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not publish bus position event", e);
            }
        });
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static final class DatagramPoint {
        private final int lineId;
        private final LocalDateTime date;
        private final double latitude;
        private final double longitude;

        private DatagramPoint(int lineId, LocalDateTime date, double latitude, double longitude) {
            this.lineId = lineId;
            this.date = date;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static final class ProcessingSummary {
        private final int groupCount;
        private final Map<String, Aggregate> aggregates;

        private ProcessingSummary(int groupCount, Map<String, Aggregate> aggregates) {
            this.groupCount = groupCount;
            this.aggregates = aggregates;
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
