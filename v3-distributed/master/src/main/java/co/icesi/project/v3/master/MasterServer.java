package co.icesi.project.v3.master;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;
import sitmmio.v3.slice.VisualizationPrx;

import java.util.logging.Level;
import java.util.logging.Logger;

public class MasterServer {
    private static final Logger LOGGER = Logger.getLogger(MasterServer.class.getName());

    public static void main(String[] args) {
        int status = 0;
        Communicator communicator = null;
        MasterI servant = null;
        try {
            communicator = Util.initialize(args);
            VisualizationPrx visualization = resolveVisualization(communicator);
            servant = new MasterI(visualization);
            ObjectAdapter adapter = communicator.createObjectAdapter("MasterAdapter");
            adapter.add(servant, Util.stringToIdentity("Master"));
            adapter.activate();
            LOGGER.info("Master ready at " + communicator.getProperties().getProperty("MasterAdapter.Endpoints"));
            communicator.waitForShutdown();
        } catch (RuntimeException e) {
            status = 1;
            LOGGER.log(Level.SEVERE, "Master process failed", e);
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

    private static VisualizationPrx resolveVisualization(Communicator communicator) {
        String proxyText = communicator.getProperties().getProperty("Visualization.Proxy");
        if (proxyText == null || proxyText.isBlank()) {
            LOGGER.info("Visualization proxy not configured; bus events will be logged only");
            return null;
        }
        try {
            ObjectPrx base = communicator.propertyToProxy("Visualization.Proxy");
            VisualizationPrx proxy = VisualizationPrx.checkedCast(base);
            if (proxy == null) {
                LOGGER.warning("Configured Visualization proxy is not a Visualization servant");
            }
            return proxy;
        } catch (LocalException e) {
            LOGGER.warning("Visualization is not reachable at " + proxyText + "; continuing without UI events");
            return null;
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not resolve Visualization proxy; continuing without UI events", e);
            return null;
        }
    }
}
