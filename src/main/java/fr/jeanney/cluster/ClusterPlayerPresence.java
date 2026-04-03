package fr.jeanney.cluster;

public record ClusterPlayerPresence(String playerUuid, String playerName, String nodeId) {

    public String clusterLabel() {
        if (nodeId == null || nodeId.isBlank()) {
            return "host";
        }
        return nodeId;
    }
}