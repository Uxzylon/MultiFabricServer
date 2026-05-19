package fr.jeanney.cluster;

import java.util.OptionalInt;

public interface EmbeddedServerHandle extends AutoCloseable {
    String nodeId();

    boolean isAlive();

    default OptionalInt listenPort() {
        return OptionalInt.empty();
    }

    default OptionalInt transferPort() {
        return listenPort();
    }

    default void tick() {
        // Intentionally empty for now.
    }

    @Override
    void close();
}
