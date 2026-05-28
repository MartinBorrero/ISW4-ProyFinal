package co.icesi.project.model;

import java.time.LocalDateTime;

public class Datagram {

    private int lineId;
    private int busId;
    private LocalDateTime datagramDate;
    private double latitude;
    private double longitude;

    public Datagram(int lineId, int busId, LocalDateTime datagramDate, double latitude, double longitude) {
        this.lineId = lineId;
        this.busId = busId;
        this.datagramDate = datagramDate;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public int getLineId() {
        return lineId;
    }

    public int getBusId() {
        return busId;
    }

    public LocalDateTime getDatagramDate() {
        return datagramDate;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }
}