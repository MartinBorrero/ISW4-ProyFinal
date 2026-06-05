package co.icesi.project.v3.broker;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;
import sitmmio.v3.slice.MasterPrx;
import sitmmio.v3.slice.VisualizationPrx;

import java.util.logging.Level;
import java.util.logging.Logger;

public class BrokerServer {
    private static final Logger LOGGER = Logger.getLogger(BrokerServer.class.getName());

    public static void main(String[] args) {
        int status = 0;
        Communicator communicator = null;
        BrokerI servant = null;
        try {
            communicator = Util.initialize(args);
            MasterPrx master = resolveMaster(communicator);
            VisualizationPrx visualization = resolveVisualization(communicator);
            servant = new BrokerI(master, visualization);
            ObjectAdapter adapter = communicator.createObjectAdapter("BrokerAdapter");
            adapter.add(servant, Util.stringToIdentity("Broker"));
            adapter.activate();
            LOGGER.info("Broker ready at " + communicator.getProperties().getProperty("BrokerAdapter.Endpoints"));
            communicator.waitForShutdown();
        } catch (RuntimeException e) {
            status = 1;
            LOGGER.log(Level.SEVERE, "Broker process failed", e);
        } finally {
            if (servant != null) {
                servant.shutdown();
            }
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

    private static MasterPrx resolveMaster(Communicator communicator) {
        try {
            ObjectPrx base = communicator.propertyToProxy("Master.Proxy");
            MasterPrx proxy = MasterPrx.checkedCast(base);
            if (proxy == null) {
                LOGGER.warning("Configured Master proxy is not a Master servant");
            }
            return proxy;
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Master proxy is not reachable", e);
            return null;
        }
    }

    private static VisualizationPrx resolveVisualization(Communicator communicator) {
        try {
            String proxyText = communicator.getProperties().getProperty("Visualization.Proxy");
            if (proxyText == null || proxyText.isBlank()) {
                return null;
            }
            ObjectPrx base = communicator.propertyToProxy("Visualization.Proxy");
            VisualizationPrx proxy = VisualizationPrx.checkedCast(base);
            if (proxy == null) {
                LOGGER.warning("Configured Visualization proxy is not a Visualization servant");
            }
            return proxy;
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Visualization is not reachable; continuing without UI events", e);
            return null;
        }
    }
}
