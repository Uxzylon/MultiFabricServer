package fr.jeanney.cluster;

import net.minecraft.server.MinecraftServer;

public final class ClusterServerIdentity {
    private ClusterServerIdentity() {
    }

    public static String describe(MinecraftServer server) {
        if (server == null) {
            return "null";
        }
        return server.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(server));
    }
}
