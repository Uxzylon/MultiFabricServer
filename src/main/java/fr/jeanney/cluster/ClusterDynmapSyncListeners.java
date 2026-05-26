package fr.jeanney.cluster;

import fr.jeanney.MultiFabricServer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

public final class ClusterDynmapSyncListeners {
    private static final List<Consumer<MinecraftServer>> LISTENERS = new ArrayList<>();

    private ClusterDynmapSyncListeners() {
    }

    public static synchronized void register(Consumer<MinecraftServer> listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void notifySynced(MinecraftServer server) {
        List<Consumer<MinecraftServer>> snapshot;
        synchronized (ClusterDynmapSyncListeners.class) {
            snapshot = List.copyOf(LISTENERS);
        }

        for (Consumer<MinecraftServer> listener : snapshot) {
            try {
                listener.accept(server);
            } catch (RuntimeException exception) {
                MultiFabricServer.LOGGER.warn("Dynmap cluster world sync listener failed", exception);
            }
        }
    }
}
