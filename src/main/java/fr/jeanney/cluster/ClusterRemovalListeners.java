package fr.jeanney.cluster;

import fr.jeanney.MultiFabricServer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class ClusterRemovalListeners {
    private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();

    private ClusterRemovalListeners() {
    }

    public static void register(Consumer<String> listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void notifyRemoved(String nodeId) {
        for (Consumer<String> listener : LISTENERS) {
            try {
                listener.accept(nodeId);
            } catch (RuntimeException exception) {
                MultiFabricServer.LOGGER.warn("Cluster removal listener failed for node {}", nodeId, exception);
            }
        }
    }
}
