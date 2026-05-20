package fr.jeanney.cluster;

import java.util.List;

public record ClusterConfig(
        boolean enabled,
        boolean gatewayEnabled,
        boolean seamlessProxySwitchEnabled,
        String proxyHostServerName,
        String transferHost,
        String gatewayBindHost,
        int gatewayBindPort,
        int nodeIdleStopSeconds,
        List<ClusterNodeDefinition> nodes) {

    public static final String DEFAULT_PROXY_HOST_SERVER_NAME = "host";
    public static final boolean DEFAULT_ENABLED = true;
    public static final String DEFAULT_TRANSFER_HOST = System.getProperty(
            "multifabricserver.cluster.hostTransferHost",
            "127.0.0.1");
    public static final String DEFAULT_GATEWAY_BIND_HOST = System.getProperty(
            "multifabricserver.cluster.gatewayBindHost",
            "0.0.0.0");
    public static final int DEFAULT_GATEWAY_BIND_PORT = readIntSystemProperty(
            "multifabricserver.cluster.gatewayBindPort",
            25565);

    public ClusterConfig {
        if (proxyHostServerName == null || proxyHostServerName.isBlank()) {
            proxyHostServerName = DEFAULT_PROXY_HOST_SERVER_NAME;
        }
        if (transferHost == null || transferHost.isBlank()) {
            transferHost = DEFAULT_TRANSFER_HOST;
        }
        if (gatewayBindHost == null || gatewayBindHost.isBlank()) {
            gatewayBindHost = DEFAULT_GATEWAY_BIND_HOST;
        }
        if (gatewayBindPort <= 0 || gatewayBindPort > 65535) {
            gatewayBindPort = DEFAULT_GATEWAY_BIND_PORT;
        }
        if (nodeIdleStopSeconds < 0) {
            nodeIdleStopSeconds = 300;
        }
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public static ClusterConfig defaultConfig() {
        return new ClusterConfig(
                DEFAULT_ENABLED,
                false,
                false,
                DEFAULT_PROXY_HOST_SERVER_NAME,
                DEFAULT_TRANSFER_HOST,
                DEFAULT_GATEWAY_BIND_HOST,
                DEFAULT_GATEWAY_BIND_PORT,
                300,
                List.of());
    }

    private static int readIntSystemProperty(String key, int fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 && parsed <= 65535 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
