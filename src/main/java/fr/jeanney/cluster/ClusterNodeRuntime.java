package fr.jeanney.cluster;

import fr.jeanney.MultiFabricServer;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class ClusterNodeRuntime {

    private final ClusterNodeDefinition definition;
    private ClusterNodeState state = ClusterNodeState.STOPPED;
    private String failureReason = "";
    private Optional<EmbeddedServerHandle> handle = Optional.empty();
    private int resolvedListenPort = -1;
    private int resolvedTransferPort = -1;
    private long lifecycleGeneration;

    public ClusterNodeRuntime(ClusterNodeDefinition definition) {
        this.definition = definition;
    }

    public ClusterNodeDefinition definition() {
        return definition;
    }

    public synchronized ClusterNodeState state() {
        return state;
    }

    public synchronized String failureReason() {
        return failureReason;
    }

    public synchronized int resolvedListenPort() {
        if (resolvedListenPort > 0) {
            return resolvedListenPort;
        }
        return -1;
    }

    public synchronized int resolvedTransferPort() {
        if (resolvedTransferPort > 0) {
            return resolvedTransferPort;
        }
        return resolvedListenPort();
    }

    public synchronized void start(MinecraftServer hostServer, Path clusterRoot) {
        if (state == ClusterNodeState.RUNNING || state == ClusterNodeState.STARTING) {
            MultiFabricServer.LOGGER.info(
                    "[cluster-stop-debug] start skipped for node={} currentState={} host={} thread={}",
                    definition.id(),
                    state,
                    describeServer(hostServer),
                    Thread.currentThread().getName());
            return;
        }

        MultiFabricServer.LOGGER.info(
                "[cluster-stop-debug] node start requested node={} host={} root={} thread={}",
                definition.id(),
                describeServer(hostServer),
                clusterRoot,
                Thread.currentThread().getName());

        state = ClusterNodeState.STARTING;
        failureReason = "";
        resolvedListenPort = -1;
        resolvedTransferPort = -1;

        Path nodeRoot = clusterRoot.resolve(definition.id());
        long generation = ++lifecycleGeneration;
        Thread.ofVirtual()
                .name("MultiFabricServer cluster starter " + definition.id())
                .start(() -> startInBackground(hostServer, nodeRoot, generation));
    }

    private void startInBackground(MinecraftServer hostServer, Path nodeRoot, long generation) {
        Optional<EmbeddedServerHandle> startedHandle = Optional.empty();
        try {
            Files.createDirectories(nodeRoot);
            startedHandle = EmbeddedServerFactory.tryStartEmbeddedServer(hostServer, definition, nodeRoot);

            synchronized (this) {
                if (generation != lifecycleGeneration || state != ClusterNodeState.STARTING) {
                    closeStaleHandle(startedHandle);
                    return;
                }

                handle = startedHandle;
                if (handle.isPresent()) {
                    EmbeddedServerHandle embeddedServerHandle = handle.get();
                    resolvedListenPort = embeddedServerHandle.listenPort().orElse(-1);
                    resolvedTransferPort = embeddedServerHandle.transferPort().orElse(resolvedListenPort);
                    state = ClusterNodeState.RUNNING;
                    MultiFabricServer.LOGGER.info(
                            "Node '{}' started (listenPort={}, transferPort={})",
                            definition.id(),
                            resolvedListenPort,
                            resolvedTransferPort);
                    return;
                }

                state = ClusterNodeState.FAILED;
                failureReason = "Embedded child server startup failed";
            }
            MultiFabricServer.LOGGER.warn(
                    "[cluster-stop-debug] node start failed node={} reason={} host={}",
                    definition.id(),
                    failureReason,
                    describeServer(hostServer));
        } catch (IOException ioException) {
            synchronized (this) {
                if (generation != lifecycleGeneration || state != ClusterNodeState.STARTING) {
                    return;
                }
                state = ClusterNodeState.FAILED;
                failureReason = "I/O error while preparing node root: " + ioException.getMessage();
            }
            MultiFabricServer.LOGGER.error("Failed to start node '{}'", definition.id(), ioException);
        } catch (Exception exception) {
            closeStaleHandle(startedHandle);
            synchronized (this) {
                if (generation != lifecycleGeneration || state != ClusterNodeState.STARTING) {
                    return;
                }
                state = ClusterNodeState.FAILED;
                failureReason = "Error while starting child server: " + exception.getMessage();
            }
            MultiFabricServer.LOGGER.error("Failed to start node '{}'", definition.id(), exception);
        }
    }

    public synchronized void tick() {
        handle.ifPresent(embeddedServerHandle -> {
            embeddedServerHandle.tick();
            if (!embeddedServerHandle.isAlive()) {
                state = ClusterNodeState.FAILED;
                failureReason = "Embedded child server terminated unexpectedly";
                handle = Optional.empty();
            }
        });
    }

    public synchronized void stop() {
        MultiFabricServer.LOGGER.info(
                "[cluster-stop-debug] node stop requested node={} state={} hasHandle={} thread={}",
                definition.id(),
                state,
                handle.isPresent(),
                Thread.currentThread().getName());

        lifecycleGeneration++;
        handle.ifPresent(embeddedServerHandle -> {
            try {
                embeddedServerHandle.close();
            } catch (Exception exception) {
                MultiFabricServer.LOGGER.warn("Error while stopping node '{}'", definition.id(), exception);
            }
        });
        handle = Optional.empty();
        resolvedListenPort = -1;
        resolvedTransferPort = -1;
        if (state != ClusterNodeState.FAILED) {
            state = ClusterNodeState.STOPPED;
            failureReason = "";
        }

        MultiFabricServer.LOGGER.info(
                "[cluster-stop-debug] node stop completed node={} state={} failureReason={}",
                definition.id(),
                state,
                failureReason);
    }

    private static void closeStaleHandle(Optional<EmbeddedServerHandle> staleHandle) {
        staleHandle.ifPresent(embeddedServerHandle -> {
            try {
                embeddedServerHandle.close();
            } catch (Exception exception) {
                MultiFabricServer.LOGGER.warn("Error while stopping stale child server handle", exception);
            }
        });
    }

    private static String describeServer(MinecraftServer server) {
        return server.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(server));
    }
}
