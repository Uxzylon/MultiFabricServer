package fr.jeanney.cluster;

public record ClusterNodeDefinition(
        String id,
        boolean enabled) {

    public static final String WORLD_NAME = "world";

    public ClusterNodeDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Node id cannot be blank");
        }
    }

    public String worldName() {
        return WORLD_NAME;
    }
}
