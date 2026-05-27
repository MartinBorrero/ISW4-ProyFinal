package co.icesi.project.model;

import java.time.YearMonth;

public class SpeedRecord {

    private int lineId;
    private YearMonth month;
    private double averageSpeed;

    public SpeedRecord(int lineId, YearMonth month, double averageSpeed) {
        this.lineId = lineId;
        this.month = month;
        this.averageSpeed = averageSpeed;
    }

    public int getLineId() {
        return lineId;
    }

    public YearMonth getMonth() {
        return month;
    }

    public double getAverageSpeed() {
        return averageSpeed;
    }

    @Override
    public String toString() {
        return "Route: " + lineId +
                " | Month: " + month +
                " | Avg Speed: " + String.format("%.2f", averageSpeed) + " km/h";
    }
}
