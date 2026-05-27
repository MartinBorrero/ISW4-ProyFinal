package co.icesi.project.controller;

import co.icesi.project.model.Datagram;
import co.icesi.project.model.Route;
import co.icesi.project.model.SpeedRecord;
import co.icesi.project.repository.DatagramRepository;
import co.icesi.project.repository.RouteRepository;
import co.icesi.project.service.SpeedCalculationService;
import co.icesi.project.view.ConsoleView;

import java.util.List;

public class ProcessingController {

    private final DatagramRepository datagramRepository;
    private final SpeedCalculationService speedCalculationService;
    private final ConsoleView consoleView;
    private final RouteRepository routeRepository;

    public ProcessingController() {

        this.datagramRepository = new DatagramRepository();
        this.routeRepository = new RouteRepository();
        this.speedCalculationService = new SpeedCalculationService();
        this.consoleView = new ConsoleView();
    }

    public void execute(String datagramPath, String routesPath) {
        List<Route> routes = routeRepository.loadRoutes(routesPath);
        List<Datagram> datagrams = datagramRepository.loadDatagrams(datagramPath);
        List<SpeedRecord> results = speedCalculationService.calculateAverageSpeeds(datagrams);
        System.out.println(results);
        consoleView.showResults(results);
    }
}