package fr.jeanney.cluster;

import java.util.List;

public record ClusterConfig(
        String proxyHostServerName,
        int nodeIdleStopSeconds,
        List<ClusterNodeDefinition> nodes) {

    public static final String DEFAULT_PROXY_HOST_SERVER_NAME = "host";
    public static final String DEFAULT_TRANSFER_HOST = "127.0.0.1";
    public static final int DEFAULT_NODE_IDLE_STOP_SECONDS = 300;

    public ClusterConfig {
        if (proxyHostServerName == null || proxyHostServerName.isBlank()) {
            proxyHostServerName = DEFAULT_PROXY_HOST_SERVER_NAME;
        }
        if (nodeIdleStopSeconds < 0) {
            nodeIdleStopSeconds = DEFAULT_NODE_IDLE_STOP_SECONDS;
        }
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public static ClusterConfig defaultConfig() {
        return new ClusterConfig(
                DEFAULT_PROXY_HOST_SERVER_NAME,
                DEFAULT_NODE_IDLE_STOP_SECONDS,
                List.of());
    }
}
