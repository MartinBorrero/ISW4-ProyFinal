package co.icesi.project.v3.broker;

import com.zeroc.Ice.Current;
import sitmmio.v3.slice.Broker;
import sitmmio.v3.slice.BusEvent;
import sitmmio.v3.slice.MasterPrx;
import sitmmio.v3.slice.SpeedResult;
import sitmmio.v3.slice.SpeedStat;
import sitmmio.v3.slice.SpeedTask;
import sitmmio.v3.slice.VisualizationPrx;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BrokerI implements Broker {
    private static final Logger LOGGER = Logger.getLogger(BrokerI.class.getName());

    private final MasterPrx master;
    private final VisualizationPrx visualization;
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();

    public BrokerI(MasterPrx master, VisualizationPrx visualization) {
        this.master = master;
        this.visualization = visualization;
    }

    @Override
    public SpeedResult submit(SpeedTask task, Current current) {
        publish("Client", "Broker", "REQUEST_RECEIVED", task.taskId);
        if (master == null) {
            publish("Broker", "Client", "REQUEST_REJECTED", "Master proxy is unavailable");
            return new SpeedResult(task.taskId, false, "Master proxy is unavailable", task.outputPath, 0, 0, new SpeedStat[0]);
        }
        try {
            publish("Broker", "Master", "REQUEST_ROUTED", task.taskId);
            SpeedResult result = master.execute(task);
            publish("Broker", "Client", result.success ? "RESPONSE_OK" : "RESPONSE_ERROR", result.message);
            return result;
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Broker could not route request to Master", e);
            publish("Broker", "Client", "RESPONSE_ERROR", e.getMessage());
            return new SpeedResult(task.taskId, false, "Broker routing failed: " + e.getMessage(), task.outputPath, 0, 0, new SpeedStat[0]);
        }
    }

    public void shutdown() {
        eventExecutor.shutdownNow();
    }

    private void publish(String source, String destination, String type, String detail) {
        if (visualization == null) {
            return;
        }
        BusEvent event = new BusEvent(source, destination, type, Instant.now().toString(), detail);
        eventExecutor.submit(() -> {
            try {
                visualization.publish(event);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Visualization event delivery failed", e);
            }
        });
    }
}
