package co.icesi.project.model;

import java.time.LocalDateTime;

public class Datagram {

    private int lineId;
    private int busId;
    private LocalDateTime datagramDate;
    private long odometer;

    public Datagram(int lineId, int busId, LocalDateTime datagramDate, long odometer) {
        this.lineId = lineId;
        this.busId = busId;
        this.datagramDate = datagramDate;
        this.odometer = odometer;
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

    public long getOdometer() {
        return odometer;
    }
}
