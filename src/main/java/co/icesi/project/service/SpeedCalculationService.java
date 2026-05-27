package co.icesi.project.service;

import co.icesi.project.model.Datagram;
import co.icesi.project.model.SpeedRecord;

import java.time.Duration;
import java.time.YearMonth;
import java.util.*;

public class SpeedCalculationService {

    public List<SpeedRecord> calculateAverageSpeeds(List<Datagram> datagrams) {

        Map<String, List<Double>> groupedSpeeds = new HashMap<>();

        datagrams.sort(Comparator.comparingInt(Datagram::getBusId)
                .thenComparingInt(Datagram::getLineId)
                .thenComparing(Datagram::getDatagramDate));

        System.out.println(datagrams.toString());

        for (int i = 1; i < datagrams.size(); i++) {

            Datagram previous = datagrams.get(i - 1);
            Datagram current = datagrams.get(i);

            if (previous.getBusId() != current.getBusId()) {
                System.out.println("Skip por busId: " + previous.getBusId() + " != " + current.getBusId());
                continue;
            }

            if (previous.getLineId() != current.getLineId()) {
                System.out.println("Skip por lineId: " + previous.getLineId() + " != " + current.getLineId());
                continue;
            }

            long distanceMeters = current.getOdometer();

            long seconds = Duration.between(
                    previous.getDatagramDate(),
                    current.getDatagramDate()).getSeconds();

            if (seconds <= 0 || distanceMeters <= 0) {
                System.out.println("Skip por tiempo/distancia: seconds=" + seconds + " distancia=" + distanceMeters);
                continue;
            }

            double distanceKm = distanceMeters / 1000.0;
            double hours = seconds / 3600.0;

            double speed = distanceKm / hours;

            YearMonth month = YearMonth.from(current.getDatagramDate());

            String key = current.getLineId() + "-" + month;

            groupedSpeeds.putIfAbsent(key, new ArrayList<>());
            groupedSpeeds.get(key).add(speed);
        }

        List<SpeedRecord> results = new ArrayList<>();

        System.out.println("Grouped Speeds: " + groupedSpeeds.size());

        for (String key : groupedSpeeds.keySet()) {

            String[] parts = key.split("-");

            int lineId = Integer.parseInt(parts[0]);

            YearMonth month = YearMonth.parse(parts[1] + "-" + parts[2]);

            List<Double> speeds = groupedSpeeds.get(key);

            double average = speeds.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);

            results.add(new SpeedRecord(lineId, month, average));
        }

        return results;
    }
}
