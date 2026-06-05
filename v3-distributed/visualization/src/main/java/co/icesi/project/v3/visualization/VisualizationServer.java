package co.icesi.project.v3.visualization;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

import java.util.logging.Level;
import java.util.logging.Logger;

public class VisualizationServer {
    private static final Logger LOGGER = Logger.getLogger(VisualizationServer.class.getName());

    public static void main(String[] args) {
        int status = 0;
        Communicator communicator = null;
        try {
            communicator = Util.initialize(args);
            ObjectAdapter adapter = communicator.createObjectAdapter("VisualizationAdapter");
            adapter.add(new VisualizationI(), Util.stringToIdentity("Visualization"));
            adapter.activate();
            LOGGER.info("Visualization ready at " + communicator.getProperties().getProperty("VisualizationAdapter.Endpoints"));
            communicator.waitForShutdown();
        } catch (RuntimeException e) {
            status = 1;
            LOGGER.log(Level.SEVERE, "Visualization process failed", e);
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
}
