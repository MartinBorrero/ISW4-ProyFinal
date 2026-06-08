package co.icesi.project.v3.worker;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;
import sitmmio.v3.slice.MasterPrx;
import sitmmio.v3.slice.VisualizationPrx;
import sitmmio.v3.slice.WorkerPrx;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WorkerServer {
    private static final Logger LOGGER = Logger.getLogger(WorkerServer.class.getName());

    public static void main(String[] args) {
        int status = 0;
        Communicator communicator = null;
        ScheduledExecutorService registrationExecutor = Executors.newSingleThreadScheduledExecutor();
        AtomicBoolean registered = new AtomicBoolean(false);
        AtomicInteger registrationAttempts = new AtomicInteger(0);
        try {
            communicator = Util.initialize(args);
            String configuredId = communicator.getProperties().getProperty("Worker.Id");
            String workerId = configuredId == null || configuredId.isBlank() ? "worker-" + UUID.randomUUID() : configuredId;
            VisualizationPrx visualization = VisualizationPrx.uncheckedCast(communicator.propertyToProxy("Visualization.Proxy"));
            WorkerI servant = new WorkerI(workerId, visualization);
            ObjectAdapter adapter = communicator.createObjectAdapter("WorkerAdapter");
            ObjectPrx workerBase = adapter.add(servant, Util.stringToIdentity(workerId));
            WorkerPrx workerProxy = WorkerPrx.uncheckedCast(workerBase);
            adapter.activate();

            Communicator finalCommunicator = communicator;
            Runnable register = () -> {
                if (registered.get()) {
                    return;
                }
                try {
                    MasterPrx master = MasterPrx.checkedCast(finalCommunicator.propertyToProxy("Master.Proxy"));
                    if (master != null) {
                        master.registerWorker(workerId, workerProxy);
                        registered.set(true);
                        LOGGER.info(workerId + " registered with Master");
                    }
                } catch (RuntimeException e) {
                    int attempt = registrationAttempts.incrementAndGet();
                    if (attempt == 1 || attempt % 10 == 0) {
                        LOGGER.info(workerId + " waiting for Master at "
                                + finalCommunicator.getProperties().getProperty("Master.Proxy")
                                + " (attempt " + attempt + ")");
                    }
                }
            };
            registrationExecutor.scheduleWithFixedDelay(register, 0, 2, TimeUnit.SECONDS);

            Communicator shutdownCommunicator = communicator;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                registrationExecutor.shutdownNow();
                servant.shutdown();
                try {
                    MasterPrx master = MasterPrx.checkedCast(shutdownCommunicator.propertyToProxy("Master.Proxy"));
                    if (master != null && registered.get()) {
                        master.unregisterWorker(workerId);
                    }
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Could not unregister worker during shutdown", e);
                }
            }, "worker-shutdown"));

            LOGGER.info(workerId + " ready at " + communicator.getProperties().getProperty("WorkerAdapter.Endpoints"));
            communicator.waitForShutdown();
        } catch (RuntimeException e) {
            status = 1;
            LOGGER.log(Level.SEVERE, "Worker process failed", e);
        } finally {
            registrationExecutor.shutdownNow();
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
}
