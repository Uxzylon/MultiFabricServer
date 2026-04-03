package fr.jeanney.cluster;

public interface EmbeddedServerHandle extends AutoCloseable {
    String nodeId();

    boolean isAlive();

    default void tick() {
        // Intentionally empty for now.
    }

    @Override
    void close();
}
