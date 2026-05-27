package co.icesi.project.repository;

import co.icesi.project.model.Route;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class RouteRepository {

    public List<Route> loadRoutes(String path) {

        List<Route> routes = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {

            String line;

            br.readLine();

            while ((line = br.readLine()) != null) {

                String[] parts = line.split(",");

                int routeId = Integer.parseInt(parts[0]);

                routes.add(new Route(routeId));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return routes;
    }
}
