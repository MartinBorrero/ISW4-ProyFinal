package co.icesi.project.repository;

import co.icesi.project.model.Datagram;

import java.io.BufferedReader;
import java.io.FileReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class DatagramRepository {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<Datagram> loadDatagrams(String path) {

        List<Datagram> datagrams = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {

            String line;
            br.readLine(); // saltar header

            while ((line = br.readLine()) != null) {

                String[] parts = line.split(",");

                int lineId = Integer.parseInt(parts[7]);
                int busId = Integer.parseInt(parts[11]);
                LocalDateTime date = LocalDateTime.parse(parts[10], FORMATTER);

                double latitude = Long.parseLong(parts[4]) / 1e7;
                double longitude = Long.parseLong(parts[5]) / 1e7;

                datagrams.add(new Datagram(lineId, busId, date, latitude, longitude));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return datagrams;
    }
}