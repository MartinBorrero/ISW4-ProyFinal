package co.icesi.project.v3.master;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;
import sitmmio.v3.slice.VisualizationPrx;

import java.util.ArrayList;
import java.util.List;
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
            List<VisualizationPrx> visualizations = resolveVisualizations(communicator);
            servant = new MasterI(visualizations);
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
        if (visualizations.isEmpty()) {
            LOGGER.info("Visualization proxy not configured; bus events will be logged only");
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
