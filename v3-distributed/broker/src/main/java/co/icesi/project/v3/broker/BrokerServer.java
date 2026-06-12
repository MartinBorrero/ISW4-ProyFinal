package co.icesi.project.v3.broker;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;
import sitmmio.v3.slice.MasterPrx;
import sitmmio.v3.slice.VisualizationPrx;

import java.util.ArrayList;
import java.util.List;
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
            List<VisualizationPrx> visualizations = resolveVisualizations(communicator);
            servant = new BrokerI(master, visualizations);
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
        String proxyText = communicator.getProperties().getProperty("Master.Proxy");
        try {
            ObjectPrx base = communicator.propertyToProxy("Master.Proxy");
            MasterPrx proxy = MasterPrx.checkedCast(base);
            if (proxy == null) {
                LOGGER.warning("Configured Master proxy is not a Master servant");
            }
            return proxy;
        } catch (LocalException e) {
            LOGGER.severe("Master proxy is not reachable at " + proxyText);
            return null;
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Could not resolve Master proxy", e);
            return null;
        }
    }

    private static List<VisualizationPrx> resolveVisualizations(Communicator communicator) {
        List<VisualizationPrx> visualizations = new ArrayList<>();
        String proxiesText = communicator.getProperties().getProperty("Visualization.Proxies");
        if (proxiesText != null && !proxiesText.isBlank()) {
            for (String proxyText : proxiesText.split(";")) {
                addVisualization(communicator, visualizations, proxyText.trim());
            }
        } else {
            addVisualization(communicator, visualizations, communicator.getProperties().getProperty("Visualization.Proxy"));
        }
        return visualizations;
    }

    private static void addVisualization(Communicator communicator, List<VisualizationPrx> visualizations, String proxyText) {
        if (proxyText == null || proxyText.isBlank()) {
            return;
        }
        try {
            ObjectPrx base = communicator.stringToProxy(proxyText);
            VisualizationPrx proxy = VisualizationPrx.checkedCast(base);
            if (proxy == null) {
                LOGGER.warning("Configured Visualization proxy is not a Visualization servant: " + proxyText);
                return;
            }
            visualizations.add(proxy);
        } catch (LocalException e) {
            LOGGER.warning("Visualization is not reachable at " + proxyText + "; continuing without UI events");
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not resolve Visualization proxy; continuing without UI events", e);
        }
    }
}
