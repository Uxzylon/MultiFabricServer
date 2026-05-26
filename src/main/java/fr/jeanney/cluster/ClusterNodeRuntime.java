package fr.jeanney.cluster;

import fr.jeanney.MultiFabricServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.server.MinecraftServer;

public final class ClusterNodeRuntime {

    private final ClusterNodeDefinition definition;
    private ClusterNodeState state = ClusterNodeState.STOPPED;
    private String failureReason = "";
    private @Nullable EmbeddedServerHandle handle;
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
            MultiFabricServer.LOGGER.debug(
                    "Start skipped for node={} currentState={} host={} thread={}",
                    definition.id(),
                    state,
                    ClusterServerIdentity.describe(hostServer),
                    Thread.currentThread().getName());
            return;
        }

        MultiFabricServer.LOGGER.debug(
                "Node start requested node={} host={} root={} thread={}",
                definition.id(),
                ClusterServerIdentity.describe(hostServer),
                clusterRoot,
                Thread.currentThread().getName());

        state = ClusterNodeState.STARTING;
        failureReason = "";
        resolvedListenPort = -1;
        resolvedTransferPort = -1;

        Path nodeRoot = Objects.requireNonNull(clusterRoot.resolve(definition.id()));
        long generation = ++lifecycleGeneration;
        Thread.ofVirtual()
                .name("MultiFabricServer cluster starter " + definition.id())
                .start(() -> startInBackground(hostServer, nodeRoot, generation));
    }

    private void startInBackground(MinecraftServer hostServer, @NonNull Path nodeRoot, long generation) {
        @Nullable
        EmbeddedServerHandle startedHandle = null;

        try {
            Files.createDirectories(nodeRoot);

            Optional<@NonNull EmbeddedServerHandle> maybeStartedHandle = EmbeddedServerFactory
                    .tryStartEmbeddedServer(hostServer, definition, nodeRoot);

            if (maybeStartedHandle.isPresent()) {
                startedHandle = maybeStartedHandle.get();
            }

            synchronized (this) {
                if (generation != lifecycleGeneration || state != ClusterNodeState.STARTING) {
                    closeStaleHandle(startedHandle);
                    return;
                }

                if (startedHandle == null) {
                    state = ClusterNodeState.FAILED;
                    failureReason = "Embedded child server startup failed";
                } else {
                    handle = startedHandle;
                    resolvedListenPort = startedHandle.listenPort().orElse(-1);
                    resolvedTransferPort = startedHandle.transferPort().orElse(resolvedListenPort);
                    state = ClusterNodeState.RUNNING;

                    MultiFabricServer.LOGGER.info(
                            "Node '{}' started (listenPort={}, transferPort={})",
                            definition.id(),
                            resolvedListenPort,
                            resolvedTransferPort);
                    return;
                }
            }

            MultiFabricServer.LOGGER.warn(
                    "Node start failed node={} reason={} host={}",
                    definition.id(),
                    failureReason,
                    ClusterServerIdentity.describe(hostServer));
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
        EmbeddedServerHandle currentHandle = handle;
        if (currentHandle == null) {
            return;
        }

        currentHandle.tick();
        if (currentHandle.isStopped()) {
            state = ClusterNodeState.FAILED;
            failureReason = "Embedded child server terminated unexpectedly";
            handle = null;
        }
    }

    public synchronized void stop() {
        MultiFabricServer.LOGGER.debug(
                "Node stop requested node={} state={} hasHandle={} thread={}",
                definition.id(),
                state,
                handle != null,
                Thread.currentThread().getName());

        lifecycleGeneration++;
        if (handle != null) {
            try {
                handle.close();
            } catch (Exception exception) {
                MultiFabricServer.LOGGER.warn("Error while stopping node '{}'", definition.id(), exception);
            }
        }
        handle = null;
        resolvedListenPort = -1;
        resolvedTransferPort = -1;
        if (state != ClusterNodeState.FAILED) {
            state = ClusterNodeState.STOPPED;
            failureReason = "";
        }

        MultiFabricServer.LOGGER.debug(
                "Node stop completed node={} state={} failureReason={}",
                definition.id(),
                state,
                failureReason);
    }

    private static void closeStaleHandle(@Nullable EmbeddedServerHandle staleHandle) {
        if (staleHandle == null) {
            return;
        }

        try {
            staleHandle.close();
        } catch (Exception exception) {
            MultiFabricServer.LOGGER.warn("Error while stopping stale child server handle", exception);
        }
    }

}
