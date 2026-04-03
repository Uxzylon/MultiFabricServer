package fr.jeanney.cluster;

public record ClusterNodeDefinition(
        String id,
        String worldName,
        int listenPort,
        boolean enabled,
        boolean autoStart,
        String transferHost,
        int transferPort) {
    public ClusterNodeDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Node id cannot be blank");
        }
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("Node worldName cannot be blank");
        }
        if (transferHost == null || transferHost.isBlank()) {
            throw new IllegalArgumentException("Node transferHost cannot be blank");
        }
    }
}
