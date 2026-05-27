package co.icesi.project.view;

import co.icesi.project.model.SpeedRecord;

import java.util.List;

public class ConsoleView {

    public void showResults(List<SpeedRecord> results) {

        System.out.println("===== AVERAGE SPEED RESULTS =====");

        for (SpeedRecord record : results) {
            System.out.println(record);
        }
    }
}
