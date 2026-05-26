package fr.jeanney.cluster;

import java.util.OptionalInt;

interface EmbeddedServerHandle extends AutoCloseable {
    String nodeId();

    boolean isStopped();

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
