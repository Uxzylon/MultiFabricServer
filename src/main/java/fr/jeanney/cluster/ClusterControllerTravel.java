package fr.jeanney.cluster;

import fr.jeanney.MultiFabricServer;
import fr.jeanney.cluster.network.ClusterRegisterPayload;
import fr.jeanney.cluster.network.ProxyConnectPayload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.NonNull;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

abstract class ClusterControllerTravel extends ClusterControllerMessaging {
    private static final int RUNTIME_DIRECTORY_DELETE_ATTEMPTS = 3;

    public synchronized Optional<@NonNull GatewayRoute> resolveGatewayRoute(String requestedHost, String playerUuid) {
        if (ownerServer == null) {
            return Optional.empty();
        }

        String normalizedHost = normalizeVirtualHost(requestedHost);

        Optional<String> requestedNodeId = resolveNodeIdFromVirtualHost(normalizedHost);
        if (requestedNodeId.isPresent()) {
            return gatewayRouteForNode(requestedNodeId.get());
        }

        if (playerUuid != null && !playerUuid.isBlank()) {
            if (forceHostRoutePlayerUuids.remove(playerUuid)) {
                return Optional.of(hostGatewayRoute());
            }

            String preferredNodeId = playerClusterAffinities.get(playerUuid);
            if (preferredNodeId != null && !preferredNodeId.isBlank()) {
                Optional<@NonNull GatewayRoute> preferredRoute = gatewayRouteForNode(preferredNodeId);
                if (preferredRoute.isPresent()) {
                    return preferredRoute;
                }
            }
        }

        return Optional.of(hostGatewayRoute());
    }

    protected @NonNull GatewayRoute hostGatewayRoute() {
        return new GatewayRoute(LOOPBACK_HOST, ownerServer.getPort(), null);
    }

    protected Optional<String> resolveNodeIdFromVirtualHost(String normalizedHost) {
        if (normalizedHost == null || normalizedHost.isBlank()) {
            return Optional.empty();
        }

        for (ClusterNodeDefinition definition : configSnapshot.nodes()) {
            String nodeId = definition.id().toLowerCase(Locale.ROOT);
            if (normalizedHost.equals(nodeId) || normalizedHost.startsWith(nodeId + ".")) {
                return Optional.of(definition.id());
            }
        }

        return Optional.empty();
    }

    protected Optional<@NonNull GatewayRoute> gatewayRouteForNode(String nodeId) {
        if (stopping) {
            return Optional.empty();
        }

        ClusterNodeRuntime runtime = runtimes.get(nodeId);
        if (runtime == null || !runtime.definition().enabled()) {
            return Optional.empty();
        }

        if (runtime.state() != ClusterNodeState.RUNNING) {
            stoppingNodeIds.remove(nodeId);
            runtime.start(ownerServer, runtimeRoot(ownerServer));
            if (runtime.state() == ClusterNodeState.RUNNING) {
                trackRunningNode(nodeId);
                persistRuntimeState(ownerServer);
            }
        }

        if (runtime.state() != ClusterNodeState.RUNNING) {
            return Optional.empty();
        }

        int listenPort = runtime.resolvedListenPort();
        if (listenPort <= 0) {
            return Optional.empty();
        }

        return Optional.of(new GatewayRoute("127.0.0.1", listenPort, runtime.definition().id()));
    }

    public boolean shouldSkipPersistenceForServerStop(MinecraftServer server) {
        return isClusterChildServer(server);
    }

    public boolean shouldShortCircuitChildStopServer(MinecraftServer server) {
        if (!isClusterChildServer(server)) {
            return false;
        }

        if (ownerServer == null) {
            return true;
        }

        if (server == ownerServer) {
            return false;
        }

        return stopping;
    }

    public boolean shouldSkipGlobalExecutorShutdownForServerStop(MinecraftServer server) {
        return isClusterChildServer(server);
    }

    public boolean shouldSkipChunkWorkLoopForServerStop(MinecraftServer server) {
        return isClusterChildServer(server);
    }

    public synchronized boolean requestSeamlessProxyTravel(
            MinecraftServer server, ServerPlayer player, String targetNodeId) {
        if (server == null || player == null || !shouldUseProxySwitch(server)) {
            return false;
        }

        if (stopping) {
            return false;
        }

        MinecraftServer controlServer = resolveControlServer(server);

        if (targetNodeId == null || targetNodeId.isBlank()) {
            String playerUuid = player.getUUID().toString();
            String previousAffinity = playerClusterAffinities.get(playerUuid);

            // Preserve explicit host-travel intent through disconnect so source-node
            // disconnect handling cannot immediately restore the previous cluster
            // affinity.
            preparePlayerHostTravel(server, playerUuid);

            boolean switched = executeSeamlessProxyTravel(server, player, null, configSnapshot.proxyHostServerName(),
                    true);
            if (!switched) {
                forceHostRoutePlayerUuids.remove(playerUuid);
                if (previousAffinity != null && !previousAffinity.isBlank()) {
                    playerClusterAffinities.put(playerUuid, previousAffinity);
                }
                persistRuntimeState(server);
            }

            return switched;
        }
        ClusterNodeRuntime runtime = runtimes.get(targetNodeId);
        if (runtime == null || !runtime.definition().enabled()) {
            return false;
        }

        String proxyServerName = resolveProxyServerName(runtime.definition());
        logProxyTargetRequirement(player, targetNodeId, proxyServerName);

        if (runtime.state() != ClusterNodeState.RUNNING) {
            MinecraftServer startupHost = controlServer != null ? controlServer : server;
            stoppingNodeIds.remove(targetNodeId);
            runtime.start(startupHost, runtimeRoot(startupHost));
            if (runtime.state() == ClusterNodeState.RUNNING) {
                trackRunningNode(targetNodeId);
                persistRuntimeState(startupHost);
            }
        }

        if (runtime.state() == ClusterNodeState.STARTING) {
            scheduleSeamlessProxyTravelWhenNodeReady(server, player.getUUID(), targetNodeId, proxyServerName);
            return true;
        }

        if (runtime.state() != ClusterNodeState.RUNNING) {
            return false;
        }

        String transferHost = runtimeTransferHost();
        int transferPort = runtime.resolvedTransferPort();

        if (isEndpointReachable(transferHost, transferPort, 150)) {
            return executeSeamlessProxyTravel(server, player, targetNodeId, proxyServerName, false);
        }

        scheduleSeamlessProxyTravelWhenReady(
                server, player.getUUID(), targetNodeId, proxyServerName, transferHost, transferPort);

        return true;
    }

    public boolean requestDirectClusterTravelWhenReady(
            MinecraftServer server, ServerPlayer player, String targetNodeId) {
        if (server == null || player == null || targetNodeId == null || targetNodeId.isBlank()) {
            return false;
        }

        synchronized (this) {
            if (stopping) {
                return false;
            }

            ClusterNodeRuntime runtime = runtimes.get(targetNodeId);
            if (runtime == null || !runtime.definition().enabled()) {
                return false;
            }

            if (runtime.state() != ClusterNodeState.RUNNING) {
                MinecraftServer controlServer = resolveControlServer(server);
                stoppingNodeIds.remove(targetNodeId);
                runtime.start(controlServer, runtimeRoot(controlServer));
            }
        }

        UUID playerUuid = player.getUUID();
        Thread.startVirtualThread(() -> {
            ReadyProxyTarget readyTarget = waitForRuntimeEndpoint(
                    targetNodeId, SEAMLESS_NODE_READY_TIMEOUT_MILLIS, SEAMLESS_NODE_READY_POLL_MILLIS)
                    .orElse(null);

            server.execute(() -> {
                if (server.isStopped()) {
                    return;
                }

                ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerUuid);
                if (currentPlayer == null) {
                    return;
                }

                if (readyTarget == null) {
                    currentPlayer.sendSystemMessage(ClusterMessages.component(
                            currentPlayer,
                            "cluster.starting.retry",
                            ClusterMessages.arg("cluster", targetNodeId)));
                    MultiFabricServer.LOGGER.warn(
                            "Timed out waiting for node {} before direct transfer for player {}",
                            targetNodeId,
                            currentPlayer.getScoreboardName());
                    return;
                }

                synchronized (this) {
                    trackRunningNode(targetNodeId);
                    persistRuntimeState(resolveControlServer(server));
                }
                executeTransfer(server, currentPlayer, readyTarget.host(), readyTarget.port(), targetNodeId);
            });
        });
        return true;
    }

    protected void scheduleSeamlessProxyTravelWhenNodeReady(
            MinecraftServer server, @NonNull UUID playerUuid, String targetNodeId, String proxyServerName) {
        Thread.startVirtualThread(() -> {
            ReadyProxyTarget readyTarget = waitForRuntimeEndpoint(
                    targetNodeId, SEAMLESS_NODE_READY_TIMEOUT_MILLIS, SEAMLESS_NODE_READY_POLL_MILLIS)
                    .orElse(null);

            server.execute(() -> {
                if (server.isStopped()) {
                    return;
                }

                ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerUuid);
                if (currentPlayer == null) {
                    return;
                }

                if (readyTarget == null) {
                    currentPlayer.sendSystemMessage(ClusterMessages.component(
                            currentPlayer,
                            "cluster.starting.retry",
                            ClusterMessages.arg("cluster", targetNodeId)));
                    MultiFabricServer.LOGGER.warn(
                            "Timed out waiting for node {} to become reachable before seamless switch for player {}",
                            targetNodeId,
                            currentPlayer.getScoreboardName());
                    return;
                }

                synchronized (this) {
                    trackRunningNode(targetNodeId);
                    persistRuntimeState(resolveControlServer(server));
                }

                if (!executeSeamlessProxyTravel(server, currentPlayer, targetNodeId, proxyServerName, false)) {
                    MultiFabricServer.LOGGER.warn(
                            "Seamless switch failed after node became reachable for player {} to node {}",
                            currentPlayer.getScoreboardName(),
                            targetNodeId);
                }
            });
        });
    }

    protected Optional<ReadyProxyTarget> waitForRuntimeEndpoint(
            String targetNodeId, long timeoutMillis, long pollMillis) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));

        while (System.nanoTime() < deadlineNanos) {
            ReadyProxyTarget readyTarget = null;
            synchronized (this) {
                ClusterNodeRuntime runtime = runtimes.get(targetNodeId);
                if (runtime == null || !runtime.definition().enabled()) {
                    return Optional.empty();
                }
                if (runtime.state() == ClusterNodeState.FAILED || runtime.state() == ClusterNodeState.STOPPED) {
                    return Optional.empty();
                }
                if (runtime.state() == ClusterNodeState.RUNNING) {
                    int transferPort = runtime.resolvedTransferPort();
                    if (transferPort > 0) {
                        readyTarget = new ReadyProxyTarget(runtimeTransferHost(), transferPort);
                    }
                }
            }

            if (readyTarget != null && isEndpointReachable(readyTarget.host(), readyTarget.port(), 150)) {
                return Optional.of(readyTarget);
            }

            try {
                Thread.sleep(Math.max(10L, pollMillis));
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    protected void scheduleSeamlessProxyTravelWhenReady(MinecraftServer server, @NonNull UUID playerUuid,
            String targetNodeId,
            String proxyServerName, String targetHost, int targetPort) {
        Thread.startVirtualThread(() -> {
            boolean reachable = waitForEndpoint(
                    targetHost, targetPort, SEAMLESS_NODE_READY_TIMEOUT_MILLIS, SEAMLESS_NODE_READY_POLL_MILLIS);

            server.execute(() -> {
                if (server.isStopped()) {
                    return;
                }

                ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerUuid);
                if (currentPlayer == null) {
                    return;
                }

                if (!reachable) {
                    currentPlayer.sendSystemMessage(ClusterMessages.component(
                            currentPlayer,
                            "cluster.starting.retry",
                            ClusterMessages.arg("cluster", targetNodeId)));
                    MultiFabricServer.LOGGER.warn(
                            "Timed out waiting for node {} endpoint {}:{} before seamless switch for player {}",
                            targetNodeId,
                            targetHost,
                            targetPort,
                            currentPlayer.getScoreboardName());
                    return;
                }

                if (!executeSeamlessProxyTravel(server, currentPlayer, targetNodeId, proxyServerName, false)) {
                    MultiFabricServer.LOGGER.warn(
                            "Seamless switch failed after endpoint became reachable for player {} to node {}",
                            currentPlayer.getScoreboardName(),
                            targetNodeId);
                }
            });
        });
    }

    protected boolean executeSeamlessProxyTravel(MinecraftServer server, ServerPlayer player, String targetNodeId,
            String proxyServerName, boolean hostTarget) {
        String effectiveProxyServerName = normalizeProxyServerName(proxyServerName, hostTarget ? "host" : targetNodeId);
        if (effectiveProxyServerName == null) {
            return false;
        }

        try {
            UUID playerUuid = player.getUUID();
            markPendingInternalTravel(playerUuid.toString(), hostTarget ? null : targetNodeId, nodeIdForServer(server));
            boolean registeredDynamicTarget = false;
            if (!hostTarget && targetNodeId != null) {
                ClusterNodeRuntime runtime = runtimes.get(targetNodeId);
                if (runtime != null) {
                    int transferPort = runtime.resolvedTransferPort();
                    if (transferPort > 0) {
                        player.connection.send(new ClientboundCustomPayloadPacket(new ClusterRegisterPayload(
                                effectiveProxyServerName, runtimeTransferHost(), transferPort)));
                        registeredDynamicTarget = true;
                    }
                }
            }

            if (registeredDynamicTarget) {
                scheduleProxyConnectAfterRegistration(
                        server, playerUuid, effectiveProxyServerName, targetNodeId, hostTarget);
            } else {
                sendProxyConnect(server, player, effectiveProxyServerName, targetNodeId, hostTarget);
            }
            return true;
        } catch (Exception exception) {
            clearPendingInternalTravelMarker(player.getUUID().toString());
            MultiFabricServer.LOGGER.error(
                    "Failed seamless proxy switch for player {} to {} (node={})",
                    player.getScoreboardName(),
                    effectiveProxyServerName,
                    targetNodeId,
                    exception);
            return false;
        }
    }

    protected void scheduleProxyConnectAfterRegistration(
            MinecraftServer server, @NonNull UUID playerUuid, String proxyServerName, String targetNodeId,
            boolean hostTarget) {
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(PROXY_REGISTRATION_SETTLE_MILLIS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                clearPendingInternalTravelMarker(playerUuid.toString());
                return;
            }

            server.execute(() -> {
                ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerUuid);
                if (currentPlayer == null) {
                    clearPendingInternalTravelMarker(playerUuid.toString());
                    return;
                }
                sendProxyConnect(server, currentPlayer, proxyServerName, targetNodeId, hostTarget);
            });
        });
    }

    protected void sendProxyConnect(MinecraftServer server, ServerPlayer player, String proxyServerName,
            String targetNodeId, boolean hostTarget) {
        try {
            player.connection.send(new ClientboundCustomPayloadPacket(new ProxyConnectPayload(proxyServerName)));
            if (!hostTarget) {
                rememberPlayerCluster(server, player, targetNodeId);
            }
        } catch (Exception exception) {
            clearPendingInternalTravelMarker(player.getUUID().toString());
            MultiFabricServer.LOGGER.error(
                    "Failed to send proxy switch for player {} to {} (node={})",
                    player.getScoreboardName(),
                    proxyServerName,
                    targetNodeId,
                    exception);
        }
    }

    protected void executeTransfer(
            MinecraftServer server, ServerPlayer player, String host, int port, String targetNodeId) {
        String transferCommand = "transfer " + host + " " + port;
        String playerUuid = player.getUUID().toString();
        markPendingInternalTravel(playerUuid, targetNodeId, nodeIdForServer(server));
        try {
            int result = server.getCommands().getDispatcher().execute(transferCommand,
                    player.createCommandSourceStack());
            if (result <= 0) {
                clearPendingInternalTravelMarker(playerUuid);
                MultiFabricServer.LOGGER.warn(
                        "Transfer command did not execute for player {} to {}:{}",
                        player.getScoreboardName(),
                        host,
                        port);
            } else {
                rememberPlayerCluster(server, player, targetNodeId);
            }
        } catch (Exception exception) {
            clearPendingInternalTravelMarker(playerUuid);
            MultiFabricServer.LOGGER.error(
                    "Failed to transfer player {} to cluster {} via {}:{}",
                    player.getScoreboardName(),
                    targetNodeId,
                    host,
                    port,
                    exception);
        }
    }

    protected boolean evacuateNodePlayersToHost(MinecraftServer controlServer, String nodeId) {
        boolean routedAnyPlayer = false;
        for (ClusterPlayerPresence presence : List.copyOf(sharedPlayerPresences.values())) {
            if (!nodeId.equals(presence.nodeId())) {
                continue;
            }
            markPlayerForHostRoute(controlServer, presence.playerUuid(), nodeId);
            routedAnyPlayer = true;
        }

        MinecraftServer runtimeServer = runtimeServerInstances.get(nodeId);
        if (runtimeServer == null || runtimeServer.isStopped()) {
            sharedPlayerPresences.entrySet().removeIf(entry -> nodeId.equals(entry.getValue().nodeId()));
            refreshSharedTabLists();
            return routedAnyPlayer;
        }

        List<ServerPlayer> players = List.copyOf(runtimeServer.getPlayerList().getPlayers());
        for (ServerPlayer player : players) {
            markPlayerForHostRoute(controlServer, player.getUUID().toString(), nodeId);
            routedAnyPlayer = true;
        }
        sharedPlayerPresences.entrySet().removeIf(entry -> nodeId.equals(entry.getValue().nodeId()));
        refreshSharedTabLists();

        if (players.isEmpty()) {
            return routedAnyPlayer;
        }

        boolean useProxySwitch = shouldUseProxySwitch(runtimeServer);
        String proxyTarget = configSnapshot.proxyHostServerName();
        String targetHost = ClusterConfig.DEFAULT_TRANSFER_HOST;
        int targetPort = ownerServer == null ? 25565 : ownerServer.getPort();

        runtimeServer.execute(() -> {
            for (ServerPlayer player : List.copyOf(runtimeServer.getPlayerList().getPlayers())) {
                sendPlayerToHost(runtimeServer, player, nodeId, useProxySwitch, proxyTarget, targetHost, targetPort);
            }
        });

        return true;
    }

    protected void scheduleRuntimeStop(String nodeId, ClusterNodeRuntime runtime, long delayMillis) {
        scheduleRuntimeStop(nodeId, runtime, delayMillis, null);
    }

    protected void scheduleRuntimeStop(
            String nodeId, ClusterNodeRuntime runtime, long delayMillis, Path runtimeDirectoryToDelete) {
        if (runtime == null) {
            return;
        }

        Thread.ofVirtual().name("MultiFabricServer cluster stopper " + nodeId).start(() -> {
            if (delayMillis > 0L) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            runtime.stop();
            if (runtimeDirectoryToDelete != null) {
                deleteRuntimeDirectory(nodeId, runtimeDirectoryToDelete);
            }
        });
    }

    protected void scheduleRuntimeDirectoryDelete(String nodeId, Path nodeRoot, long delayMillis) {
        if (nodeRoot == null) {
            return;
        }

        Thread.ofVirtual().name("MultiFabricServer cluster directory deleter " + nodeId).start(() -> {
            if (delayMillis > 0L) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            deleteRuntimeDirectory(nodeId, nodeRoot);
        });
    }

    protected void deleteRuntimeDirectory(String nodeId, Path nodeRoot) {
        Path target = nodeRoot.toAbsolutePath().normalize();
        Path runtimeRoot = clusterRuntimeRoot == null ? target.getParent() : clusterRuntimeRoot;
        if (runtimeRoot == null) {
            MultiFabricServer.LOGGER.warn(
                    "Refusing to delete cluster runtime directory without known root: {}",
                    target);
            return;
        }

        Path root = runtimeRoot.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            MultiFabricServer.LOGGER.warn(
                    "Refusing to delete cluster runtime directory outside cluster root: {}",
                    target);
            return;
        }

        int attempt = 1;
        while (true) {
            try {
                if (!Files.exists(target)) {
                    return;
                }

                try (var paths = Files.walk(target)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ioException) {
                            throw new RuntimeException(ioException);
                        }
                    });
                }

                MultiFabricServer.LOGGER.info(
                        "Deleted cluster runtime directory for '{}': {}",
                        nodeId,
                        target);
                return;
            } catch (RuntimeException | IOException exception) {
                if (attempt >= RUNTIME_DIRECTORY_DELETE_ATTEMPTS) {
                    MultiFabricServer.LOGGER.warn(
                            "Failed to delete cluster runtime directory for '{}' at {}",
                            nodeId,
                            target,
                            exception);
                    return;
                }

                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
                attempt++;
            }
        }
    }

    protected void scheduleStoppingNodeMarkerClear(String nodeId, long delayMillis) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }

        Thread.ofVirtual().name("MultiFabricServer cluster stop marker clearer " + nodeId).start(() -> {
            if (delayMillis > 0L) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            synchronized (this) {
                stoppingNodeIds.remove(nodeId);
            }
        });
    }

    protected void sendPlayerToHost(MinecraftServer sourceServer, ServerPlayer player, String sourceNodeId,
            boolean useProxySwitch, String proxyTarget, String targetHost, int targetPort) {
        player.sendSystemMessage(ClusterMessages.component(player, "cluster.stopping.host_transfer",
                ClusterMessages.arg("cluster", sourceNodeId)));
        if (useProxySwitch) {
            try {
                player.connection.send(new ClientboundCustomPayloadPacket(new ProxyConnectPayload(proxyTarget)));
            } catch (Exception exception) {
                MultiFabricServer.LOGGER.warn(
                        "Failed to proxy-switch player {} from node {} to host",
                        player.getScoreboardName(),
                        sourceNodeId,
                        exception);
            }
            return;
        }

        String transferCommand = "transfer " + targetHost + " " + targetPort;
        try {
            int result = sourceServer.getCommands().getDispatcher().execute(
                    transferCommand, player.createCommandSourceStack());
            if (result <= 0) {
                MultiFabricServer.LOGGER.warn(
                        "Host transfer command did not execute for player {} from node {}",
                        player.getScoreboardName(),
                        sourceNodeId);
            }
        } catch (Exception exception) {
            MultiFabricServer.LOGGER.warn(
                    "Failed to transfer player {} from node {} to host",
                    player.getScoreboardName(),
                    sourceNodeId,
                    exception);
        }
    }
}
