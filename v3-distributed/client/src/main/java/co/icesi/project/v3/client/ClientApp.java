package co.icesi.project.v3.client;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;
import sitmmio.v3.slice.BrokerPrx;
import sitmmio.v3.slice.SpeedResult;
import sitmmio.v3.slice.SpeedTask;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientApp {
    private static final Logger LOGGER = Logger.getLogger(ClientApp.class.getName());

    public static void main(String[] args) {
        int status = 0;
        Communicator communicator = null;
        try {
            communicator = Util.initialize(args);
            BrokerPrx broker = resolveBroker(communicator);
            if (broker == null) {
                System.err.println("Broker is not available. Check config/client.cfg and startup order.");
                System.exit(1);
            }

            String taskId = property(communicator, "Client.TaskId", "task-" + UUID.randomUUID());
            String datagramsPath = property(communicator, "Client.DatagramsPath", "data/datagrams4Pilot.csv");
            String routesPath = property(communicator, "Client.RoutesPath", "data/lines-241-ActiveGT.csv");
            String outputPath = property(communicator, "Client.OutputPath", "output/resultados-v3.csv");
            int maxRows = parseInt(property(communicator, "Client.MaxRows", "100000"), 100000);
            int partitionCount = parseInt(property(communicator, "Client.PartitionCount", "1"), 1);

            SpeedTask task = new SpeedTask(taskId, datagramsPath, routesPath, outputPath, maxRows, 0, partitionCount);
            LOGGER.info("Submitting task " + taskId + " through Broker");
            SpeedResult result = broker.submit(task);
            System.out.println("Task: " + result.taskId);
            System.out.println("Success: " + result.success);
            System.out.println("Message: " + result.message);
            System.out.println("Output: " + result.outputPath);
            System.out.println("Records: " + result.records);
            System.out.println("ElapsedMs: " + result.elapsedMs);
            if (!result.success) {
                status = 2;
            }
        } catch (RuntimeException e) {
            status = 1;
            LOGGER.log(Level.SEVERE, "Client request failed", e);
        } finally {
            if (communicator != null) {
                try {
                    communicator.destroy();
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Error while destroying ICE communicator", e);
                }
            }
        }
        System.exit(status);
    }

    private static BrokerPrx resolveBroker(Communicator communicator) {
        try {
            ObjectPrx base = communicator.propertyToProxy("Broker.Proxy");
            BrokerPrx proxy = BrokerPrx.checkedCast(base);
            if (proxy == null) {
                LOGGER.warning("Configured Broker proxy is not a Broker servant");
            }
            return proxy;
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Broker proxy is not reachable", e);
            return null;
        }
    }

    private static String property(Communicator communicator, String key, String fallback) {
        String value = communicator.getProperties().getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOGGER.log(Level.WARNING, "Invalid integer config value: " + value, e);
            return fallback;
        }
    }
}
