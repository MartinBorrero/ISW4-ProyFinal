package co.icesi.project.app;

import co.icesi.project.controller.ProcessingController;

public class Main {

    public static void main(String[] args) {

        String datagramPath = "data/datagrams-MiniPilot.csv";
        String routesPath = "data/lines-241-ActiveGT.csv";

        ProcessingController controller = new ProcessingController();

        controller.execute(datagramPath, routesPath);
    }
}