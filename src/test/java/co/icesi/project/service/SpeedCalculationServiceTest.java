package co.icesi.project.service;

import co.icesi.project.model.Datagram;
import co.icesi.project.model.Route;
import co.icesi.project.model.SpeedRecord;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SpeedCalculationServiceTest {

    private final SpeedCalculationService service = new SpeedCalculationService();

    // Punto base Cali, Colombia
    // 10 km hacia el norte ≈ +0.08983 grados de latitud
    private static final double LAT_A = 3.4516;
    private static final double LON_A = -76.5320;
    private static final double LAT_B = 3.5414; // ~10 km al norte de A
    private static final double LON_B = -76.5320;

    @Test
    void testCalculoBasico() {
        // 10 km en 30 min = 20 km/h esperado
        List<Datagram> datagrams = new ArrayList<>(List.of(
                new Datagram(131, 1, LocalDateTime.of(2019, 5, 1, 8, 0, 0), LAT_A, LON_A),
                new Datagram(131, 1, LocalDateTime.of(2019, 5, 1, 8, 30, 0), LAT_B, LON_B)));

        List<Route> routes = new ArrayList<>(List.of(new Route(131)));

        List<SpeedRecord> results = service.calculateAverageSpeeds(datagrams, routes);

        assertEquals(1, results.size());
        assertEquals(131, results.get(0).getLineId());
        assertEquals(20.0, results.get(0).getAverageSpeed(), 0.5); // tolerancia 0.5 por Haversine
    }

    @Test
    void testBusesDiferentesNoSeMezclan() {
        List<Datagram> datagrams = new ArrayList<>(List.of(
                new Datagram(131, 1, LocalDateTime.of(2019, 5, 1, 8, 0, 0), LAT_A, LON_A),
                new Datagram(131, 2, LocalDateTime.of(2019, 5, 1, 8, 30, 0), LAT_B, LON_B)));

        List<Route> routes = new ArrayList<>(List.of(new Route(131)));

        List<SpeedRecord> results = service.calculateAverageSpeeds(datagrams, routes);

        assertTrue(results.isEmpty());
    }

    @Test
    void testRutaNoActivaSeIgnora() {
        List<Datagram> datagrams = new ArrayList<>(List.of(
                new Datagram(999, 1, LocalDateTime.of(2019, 5, 1, 8, 0, 0), LAT_A, LON_A),
                new Datagram(999, 1, LocalDateTime.of(2019, 5, 1, 8, 30, 0), LAT_B, LON_B)));

        List<Route> routes = new ArrayList<>(List.of(new Route(131)));

        List<SpeedRecord> results = service.calculateAverageSpeeds(datagrams, routes);

        assertTrue(results.isEmpty());
    }

    @Test
    void testMismaPosicionSeIgnora() {
        // Distancia cero debe ser ignorada
        List<Datagram> datagrams = new ArrayList<>(List.of(
                new Datagram(131, 1, LocalDateTime.of(2019, 5, 1, 8, 0, 0), LAT_A, LON_A),
                new Datagram(131, 1, LocalDateTime.of(2019, 5, 1, 8, 30, 0), LAT_A, LON_A)));

        List<Route> routes = new ArrayList<>(List.of(new Route(131)));

        List<SpeedRecord> results = service.calculateAverageSpeeds(datagrams, routes);

        assertTrue(results.isEmpty());
    }

    @Test
    void testPromedioDeTresVelocidades() {
        // Tramo 1: ~10km en 30min = ~20 km/h
        // Tramo 2: ~10km en 30min = ~20 km/h
        // Promedio esperado: ~20 km/h
        double LAT_C = 3.6312;
        List<Datagram> datagrams = new ArrayList<>(List.of(
                new Datagram(131, 1, LocalDateTime.of(2019, 5, 1, 8, 0, 0), LAT_A, LON_A),
                new Datagram(131, 1, LocalDateTime.of(2019, 5, 1, 8, 30, 0), LAT_B, LON_B),
                new Datagram(131, 1, LocalDateTime.of(2019, 5, 1, 9, 0, 0), LAT_C, LON_B)));

        List<Route> routes = new ArrayList<>(List.of(new Route(131)));

        List<SpeedRecord> results = service.calculateAverageSpeeds(datagrams, routes);

        assertEquals(1, results.size());
        assertEquals(20.0, results.get(0).getAverageSpeed(), 1.0);
    }
}