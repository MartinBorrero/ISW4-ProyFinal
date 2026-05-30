package co.icesi.project.controller;

import co.icesi.project.model.Datagram;
import co.icesi.project.model.Route;
import co.icesi.project.model.SpeedRecord;
import co.icesi.project.repository.DatagramRepository;
import co.icesi.project.repository.RouteRepository;
import co.icesi.project.service.SpeedCalculationService;
import co.icesi.project.view.ExcelView;

import java.util.List;

public class ProcessingController {

    private final DatagramRepository datagramRepository;
    private final RouteRepository routeRepository;
    private final SpeedCalculationService speedCalculationService;
    private final ExcelView excelView;

    public ProcessingController() {
        this.datagramRepository = new DatagramRepository();
        this.routeRepository = new RouteRepository();
        this.speedCalculationService = new SpeedCalculationService();
        this.excelView = new ExcelView();
    }

    public void execute(String datagramPath, String routesPath, String excelOutputPath) {

        long t0 = System.currentTimeMillis();

        List<Route> routes = routeRepository.loadRoutes(routesPath);
        List<Datagram> datagrams = datagramRepository.loadDatagrams(datagramPath);

        long tLoad = System.currentTimeMillis();

        List<SpeedRecord> results = speedCalculationService.calculateAverageSpeeds(datagrams, routes);

        long tCalc = System.currentTimeMillis();

        excelView.exportResults(results, excelOutputPath);

        long tTotal = System.currentTimeMillis();

        System.out.println("\n===== TIEMPOS DE EJECUCION =====");
        System.out.println("Carga de datos : " + (tLoad - t0) + " ms");
        System.out.println("Calculo        : " + (tCalc - tLoad) + " ms");
        System.out.println("Total          : " + (tTotal - t0) + " ms");
        System.out.println("Registros      : " + results.size());
    }
}
