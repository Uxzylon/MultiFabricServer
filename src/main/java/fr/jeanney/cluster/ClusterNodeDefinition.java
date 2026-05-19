package fr.jeanney.cluster;

public record ClusterNodeDefinition(
        String id,
        String worldName,
        boolean enabled,
        String proxyServerName) {
    public ClusterNodeDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Node id cannot be blank");
        }
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("Node worldName cannot be blank");
        }
        if (proxyServerName == null) {
            proxyServerName = "";
        }
    }
}
