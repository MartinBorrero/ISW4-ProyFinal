package co.icesi.project.service;

import co.icesi.project.model.Datagram;
import co.icesi.project.model.Route;
import co.icesi.project.model.SpeedRecord;

import java.time.Duration;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class SpeedCalculationService {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double MAX_SPEED_KMH = 80.0;
    private static final int DEFAULT_THREAD_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors());

    private static final double TO_RAD = Math.PI / 180.0;

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = (lat2 - lat1) * TO_RAD;
        double dLon = (lon2 - lon1) * TO_RAD;
        double lat1Rad = lat1 * TO_RAD;
        double lat2Rad = lat2 * TO_RAD;

        double sinDLat = Math.sin(dLat / 2);
        double sinDLon = Math.sin(dLon / 2);

        double a = sinDLat * sinDLat
                + Math.cos(lat1Rad) * Math.cos(lat2Rad) * sinDLon * sinDLon;

        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public List<SpeedRecord> calculateAverageSpeeds(List<Datagram> datagrams, List<Route> activeRoutes) {
        return calculateAverageSpeeds(datagrams, activeRoutes, DEFAULT_THREAD_COUNT);
    }

    public List<SpeedRecord> calculateAverageSpeeds(List<Datagram> datagrams, List<Route> activeRoutes, int threadCount) {
        Set<Integer> activeRouteIds = new HashSet<>();
        for (Route r : activeRoutes) {
            activeRouteIds.add(r.getRouteId());
        }

        Map<String, List<Datagram>> datagramsByBusAndLine = new HashMap<>();
        for (Datagram d : datagrams) {
            if (d.getLineId() >= -1 && activeRouteIds.contains(d.getLineId())) {
                String key = d.getBusId() + "|" + d.getLineId();
                datagramsByBusAndLine.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
            }
        }

        if (datagramsByBusAndLine.isEmpty()) {
            return Collections.emptyList();
        }

        int workers = Math.max(1, Math.min(threadCount, datagramsByBusAndLine.size()));
        List<List<Map.Entry<String, List<Datagram>>>> partitions = partition(datagramsByBusAndLine, workers);

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Map<String, DoubleSummaryStatistics>>> futures = new ArrayList<>(partitions.size());
            for (List<Map.Entry<String, List<Datagram>>> partition : partitions) {
                Callable<Map<String, DoubleSummaryStatistics>> task = () -> calculatePartialStats(partition);
                futures.add(executor.submit(task));
            }

            Map<String, DoubleSummaryStatistics> groupedStats = new HashMap<>(256);
            for (Future<Map<String, DoubleSummaryStatistics>> future : futures) {
                mergeStats(groupedStats, future.get());
            }

            return buildResults(groupedStats);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Speed calculation was interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Speed calculation failed", e.getCause());
        } finally {
            executor.shutdown();
        }
    }

    private List<List<Map.Entry<String, List<Datagram>>>> partition(
            Map<String, List<Datagram>> datagramsByBusAndLine,
            int workers) {

        List<List<Map.Entry<String, List<Datagram>>>> partitions = new ArrayList<>(workers);
        for (int i = 0; i < workers; i++) {
            partitions.add(new ArrayList<>());
        }

        int index = 0;
        for (Map.Entry<String, List<Datagram>> entry : datagramsByBusAndLine.entrySet()) {
            partitions.get(index % workers).add(entry);
            index++;
        }

        return partitions;
    }

    private Map<String, DoubleSummaryStatistics> calculatePartialStats(
            List<Map.Entry<String, List<Datagram>>> groupedDatagrams) {

        Map<String, DoubleSummaryStatistics> groupedStats = new HashMap<>();

        for (Map.Entry<String, List<Datagram>> entry : groupedDatagrams) {
            List<Datagram> group = entry.getValue();
            group.sort(Comparator.comparing(Datagram::getDatagramDate));

            for (int i = 1; i < group.size(); i++) {
                Datagram previous = group.get(i - 1);
                Datagram current = group.get(i);

                double distanceKm = haversine(
                        previous.getLatitude(), previous.getLongitude(),
                        current.getLatitude(), current.getLongitude());

                long seconds = Duration.between(
                        previous.getDatagramDate(),
                        current.getDatagramDate()).getSeconds();

                if (seconds <= 0 || distanceKm <= 0)
                    continue;

                double speed = distanceKm / (seconds / 3600.0);

                if (speed > MAX_SPEED_KMH)
                    continue;

                YearMonth month = YearMonth.from(current.getDatagramDate());
                String key = current.getLineId() + "|" + month;

                groupedStats.computeIfAbsent(key, k -> new DoubleSummaryStatistics())
                        .accept(speed);
            }
        }

        return groupedStats;
    }

    private void mergeStats(
            Map<String, DoubleSummaryStatistics> target,
            Map<String, DoubleSummaryStatistics> source) {

        for (Map.Entry<String, DoubleSummaryStatistics> entry : source.entrySet()) {
            target.computeIfAbsent(entry.getKey(), k -> new DoubleSummaryStatistics())
                    .combine(entry.getValue());
        }
    }

    private List<SpeedRecord> buildResults(Map<String, DoubleSummaryStatistics> groupedStats) {
        List<SpeedRecord> results = new ArrayList<>(groupedStats.size());

        for (Map.Entry<String, DoubleSummaryStatistics> entry : groupedStats.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            int lineId = Integer.parseInt(parts[0]);
            YearMonth month = YearMonth.parse(parts[1]);
            double average = entry.getValue().getAverage();
            results.add(new SpeedRecord(lineId, month, average));
        }

        return results;
    }

    public List<SpeedRecord> calculateAverageSpeedsSequential(List<Datagram> datagrams, List<Route> activeRoutes) {
        Set<Integer> activeRouteIds = new HashSet<>();
        for (Route r : activeRoutes) {
            activeRouteIds.add(r.getRouteId());
        }

        List<Datagram> filtered = new ArrayList<>(datagrams.size());
        for (Datagram d : datagrams) {
            if (d.getLineId() >= -1 && activeRouteIds.contains(d.getLineId())) {
                filtered.add(d);
            }
        }

        filtered.sort(Comparator.comparingInt(Datagram::getBusId)
                .thenComparingInt(Datagram::getLineId)
                .thenComparing(Datagram::getDatagramDate));

        Map<String, DoubleSummaryStatistics> groupedStats = new HashMap<>(256);
        Datagram previous = null;

        for (Datagram current : filtered) {
            if (previous == null
                    || previous.getBusId() != current.getBusId()
                    || previous.getLineId() != current.getLineId()) {
                previous = current;
                continue;
            }

            double distanceKm = haversine(
                    previous.getLatitude(), previous.getLongitude(),
                    current.getLatitude(), current.getLongitude());

            long seconds = Duration.between(
                    previous.getDatagramDate(),
                    current.getDatagramDate()).getSeconds();

            previous = current;

            if (seconds <= 0 || distanceKm <= 0)
                continue;

            double speed = distanceKm / (seconds / 3600.0);

            if (speed > MAX_SPEED_KMH)
                continue;

            YearMonth month = YearMonth.from(current.getDatagramDate());
            String key = current.getLineId() + "|" + month;

            groupedStats.computeIfAbsent(key, k -> new DoubleSummaryStatistics())
                    .accept(speed);
        }

        return buildResults(groupedStats);
    }
}
