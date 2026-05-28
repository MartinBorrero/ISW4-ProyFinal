package co.icesi.project.service;

import co.icesi.project.model.Datagram;
import co.icesi.project.model.Route;
import co.icesi.project.model.SpeedRecord;

import java.time.Duration;
import java.time.YearMonth;
import java.util.*;

public class SpeedCalculationService {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private double haversine(double lat1, double lon1, double lat2, double lon2) {

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public List<SpeedRecord> calculateAverageSpeeds(List<Datagram> datagrams, List<Route> activeRoutes) {

        Set<Integer> activeRouteIds = new HashSet<>();
        for (Route r : activeRoutes) {
            activeRouteIds.add(r.getRouteId());
        }

        Map<String, List<Double>> groupedSpeeds = new HashMap<>();

        datagrams.sort(Comparator.comparingInt(Datagram::getBusId)
                .thenComparingInt(Datagram::getLineId)
                .thenComparing(Datagram::getDatagramDate));

        for (int i = 1; i < datagrams.size(); i++) {

            Datagram previous = datagrams.get(i - 1);
            Datagram current = datagrams.get(i);

            if (previous.getBusId() != current.getBusId())
                continue;
            if (previous.getLineId() != current.getLineId())
                continue;
            if (current.getLineId() <= 0)
                continue;
            if (!activeRouteIds.contains(current.getLineId()))
                continue;

            double distanceKm = haversine(
                    previous.getLatitude(), previous.getLongitude(),
                    current.getLatitude(), current.getLongitude());

            long seconds = Duration.between(
                    previous.getDatagramDate(),
                    current.getDatagramDate()).getSeconds();

            if (seconds <= 0 || distanceKm <= 0)
                continue;

            double hours = seconds / 3600.0;
            double speed = distanceKm / hours;

            if (speed > 80)
                continue; // filtrar outliers

            YearMonth month = YearMonth.from(current.getDatagramDate());
            String key = current.getLineId() + "|" + month;

            groupedSpeeds.putIfAbsent(key, new ArrayList<>());
            groupedSpeeds.get(key).add(speed);
        }

        List<SpeedRecord> results = new ArrayList<>();

        for (String key : groupedSpeeds.keySet()) {

            String[] parts = key.split("\\|");
            int lineId = Integer.parseInt(parts[0]);
            YearMonth month = YearMonth.parse(parts[1]);

            double average = groupedSpeeds.get(key).stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);

            results.add(new SpeedRecord(lineId, month, average));
        }

        return results;
    }
}