package fr.jeanney.cluster;

import java.util.List;

public record ClusterConfig(
        boolean enabled,
        boolean gatewayEnabled,
        String gatewayBindHost,
        int gatewayBindPort,
        String hostTransferHost,
        int hostTransferPort,
        List<ClusterNodeDefinition> nodes) {

    public static ClusterConfig defaultConfig() {
        return new ClusterConfig(
                false,
                false,
                "0.0.0.0",
                25565,
                "127.0.0.1",
                25565,
                List.of(new ClusterNodeDefinition("creative", "creativeworld", 25580, true, false, "127.0.0.1",
                        25580)));
    }
}
