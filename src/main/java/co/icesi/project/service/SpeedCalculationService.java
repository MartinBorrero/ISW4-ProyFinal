package co.icesi.project.service;

import co.icesi.project.model.Datagram;
import co.icesi.project.model.Route;
import co.icesi.project.model.SpeedRecord;

import java.time.Duration;
import java.time.YearMonth;
import java.util.*;

public class SpeedCalculationService {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double MAX_SPEED_KMH = 80.0;

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

        // Sort por busId -> lineId -> fecha
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

        // Construir resultados
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
}
