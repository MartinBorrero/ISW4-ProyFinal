package co.icesi.project.v3.master;

import com.zeroc.Ice.Current;
import com.zeroc.Ice.ObjectPrx;
import sitmmio.v3.slice.BusEvent;
import sitmmio.v3.slice.Master;
import sitmmio.v3.slice.SpeedResult;
import sitmmio.v3.slice.SpeedStat;
import sitmmio.v3.slice.SpeedTask;
import sitmmio.v3.slice.VisualizationPrx;
import sitmmio.v3.slice.WorkerPrx;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MasterI implements Master {
    private static final Logger LOGGER = Logger.getLogger(MasterI.class.getName());

    private final ConcurrentHashMap<String, WorkerPrx> workers = new ConcurrentHashMap<>();
    private final ExecutorService taskExecutor = Executors.newCachedThreadPool();
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();
    private final VisualizationPrx visualization;

    public MasterI(VisualizationPrx visualization) {
        this.visualization = visualization;
    }

    @Override
    public void registerWorker(String workerId, WorkerPrx worker, Current current) {
        workers.put(workerId, worker);
        LOGGER.info("Worker registered: " + workerId);
        publish("Master", workerId, "WORKER_REGISTERED", "Worker available");
    }

    @Override
    public void unregisterWorker(String workerId, Current current) {
        workers.remove(workerId);
        LOGGER.info("Worker unregistered: " + workerId);
        publish("Master", workerId, "WORKER_UNREGISTERED", "Worker removed from registry");
    }

    @Override
    public SpeedResult execute(SpeedTask task, Current current) {
        long start = System.nanoTime();
        List<Map.Entry<String, WorkerPrx>> snapshot = new ArrayList<>(workers.entrySet());
        if (snapshot.isEmpty()) {
            publish("Master", "Broker", "TASK_REJECTED", task.taskId + " has no active workers");
            return new SpeedResult(task.taskId, false, "No active workers registered in Master", task.outputPath, 0, 0, new SpeedStat[0]);
        }

        int totalPartitions = task.partitionCount > 0 ? task.partitionCount : snapshot.size();
        publish("Master", "WorkerPool", "TASK_SPLIT", task.taskId + " partitions=" + totalPartitions + " activeWorkers=" + snapshot.size());
        List<CompletableFuture<SpeedResult>> futures = new ArrayList<>();

        for (int i = 0; i < totalPartitions; i++) {
            String workerId = "worker-" + (i + 1);
            WorkerPrx worker = workers.get(workerId);
            SpeedTask partitionTask = new SpeedTask(
                    task.taskId + "-p" + i,
                    task.datagramsPath,
                    task.routesPath,
                    task.outputPath,
                    task.maxRows,
                    i,
                    totalPartitions);
            if (worker == null) {
                publish("Master", workerId, "TASK_REJECTED", partitionTask.taskId + " has no registered " + workerId);
                futures.add(CompletableFuture.completedFuture(new SpeedResult(partitionTask.taskId, false,
                        workerId + " is not registered; fixed partition assignment requires worker-" + (i + 1),
                        task.outputPath, 0, 0, new SpeedStat[0])));
                continue;
            }
            publish("Master", workerId, "TASK_DISPATCH", partitionTask.taskId);
            futures.add(CompletableFuture.supplyAsync(() -> callWorker(workerId, worker, partitionTask), taskExecutor));
        }

        Map<String, Aggregate> aggregates = new ConcurrentHashMap<>();
        int completedPartitions = 0;
        for (CompletableFuture<SpeedResult> future : futures) {
            SpeedResult partial = future.join();
            if (partial.success) {
                completedPartitions++;
                for (SpeedStat stat : partial.stats) {
                    String key = stat.lineId + "|" + stat.month;
                    aggregates.computeIfAbsent(key, ignored -> new Aggregate(stat.lineId, stat.month))
                            .add(stat.count, stat.sum);
                }
            } else {
                LOGGER.warning("Worker partial failure: " + partial.message);
            }
        }

        List<SpeedStat> finalStats = new ArrayList<>();
        for (Aggregate aggregate : aggregates.values()) {
            finalStats.add(aggregate.toStat());
        }
        finalStats.sort(Comparator.comparingInt((SpeedStat s) -> s.lineId).thenComparing(s -> s.month));

        try {
            writeCsv(task.outputPath, finalStats);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Could not write distributed output CSV", e);
            return new SpeedResult(task.taskId, false, "Could not write output: " + e.getMessage(), task.outputPath, finalStats.size(), elapsedMs(start), finalStats.toArray(new SpeedStat[0]));
        }

        publish("Master", "Broker", "TASK_DONE", task.taskId + " partitions=" + completedPartitions + "/" + totalPartitions + " records=" + finalStats.size());
        boolean success = completedPartitions == totalPartitions;
        String message = "Distributed calculation completed with " + snapshot.size()
                + " active workers and " + completedPartitions + "/" + totalPartitions + " completed partitions";
        return new SpeedResult(task.taskId, success, message, task.outputPath, finalStats.size(), elapsedMs(start), finalStats.toArray(new SpeedStat[0]));
    }

    @Override
    public String[] activeWorkers(Current current) {
        return workers.keySet().stream().sorted().toArray(String[]::new);
    }

    public void shutdown() {
        taskExecutor.shutdownNow();
        eventExecutor.shutdownNow();
    }

    private SpeedResult callWorker(String workerId, WorkerPrx worker, SpeedTask task) {
        try {
            SpeedResult result = worker.process(task);
            publish(workerId, "Master", result.success ? "PARTIAL_DONE" : "PARTIAL_FAILED", result.message);
            return result;
        } catch (RuntimeException e) {
            workers.remove(workerId);
            LOGGER.log(Level.WARNING, "Worker failed and was removed: " + workerId, e);
            publish(workerId, "Master", "WORKER_FAILURE", e.getMessage());
            return new SpeedResult(task.taskId, false, "Worker " + workerId + " failed: " + e.getMessage(), task.outputPath, 0, 0, new SpeedStat[0]);
        }
    }

    private void publish(String source, String destination, String type, String detail) {
        if (visualization == null) {
            return;
        }
        BusEvent event = new BusEvent(source, destination, type, Instant.now().toString(), detail);
        eventExecutor.submit(() -> {
            try {
                visualization.publish(event);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Visualization event delivery failed", e);
            }
        });
    }

    private void writeCsv(String outputPath, List<SpeedStat> stats) throws IOException {
        Path path = Path.of(outputPath);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write("routeId,month,averageSpeed,count");
            writer.newLine();
            for (SpeedStat stat : stats) {
                double average = stat.count == 0 ? 0.0 : stat.sum / stat.count;
                writer.write(stat.lineId + "," + stat.month + "," + String.format(java.util.Locale.US, "%.4f", average) + "," + stat.count);
                writer.newLine();
            }
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
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

        private synchronized void add(long count, double sum) {
            this.count += count;
            this.sum += sum;
        }

        private SpeedStat toStat() {
            return new SpeedStat(lineId, month, count, sum);
        }
    }
}
