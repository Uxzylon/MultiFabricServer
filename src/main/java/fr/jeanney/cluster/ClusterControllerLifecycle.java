package fr.jeanney.cluster;

import fr.jeanney.MultiFabricServer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.NonNull;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

abstract class ClusterControllerLifecycle extends ClusterControllerTravel {
    public void onServerStarted(MinecraftServer server) {
        if (isClusterChildServer(server)) {
            MultiFabricServer.LOGGER.debug("Ignoring onServerStarted from cluster child {}", describeServer(server));
            return;
        }

        synchronized (this) {
            MultiFabricServer.LOGGER.debug(
                    "onServerStarted server={} owner={} stopping={} thread={}",
                    describeServer(server),
                    describeServer(ownerServer),
                    stopping,
                    Thread.currentThread().getName());

            if (ownerServer == null) {
                ownerServer = server;
                MultiFabricServer.LOGGER.debug("Owner server assigned to {}", describeServer(ownerServer));
            }

            if (ownerServer != server) {
                MultiFabricServer.LOGGER.debug("Ignoring onServerStarted for non-owner {}", describeServer(server));
                return;
            }

            if (!(server instanceof GameTestServer)) {
                ClusterRuntimeStateStore.RuntimeState runtimeState = ClusterRuntimeStateStore.load(server);
                activeNodeIds.clear();
                activeNodeIds.addAll(runtimeState.activeNodes());

                playerClusterAffinities.clear();
                playerClusterAffinities.putAll(runtimeState.playerClusters());
            }

            stopping = false;
            reloadFromDiskInternal(server);
        }
    }

    public synchronized void onServerTick(MinecraftServer server) {
        if (server != ownerServer) {
            return;
        }
        if (!initialized || stopping) {
            return;
        }

        for (ClusterNodeRuntime runtime : runtimes.values()) {
            runtime.tick();
            if (runtime.state() == ClusterNodeState.RUNNING) {
                trackRunningNode(runtime.definition().id());
            }
        }

        stopIdleNodes(server);
    }

    protected void stopIdleNodes(MinecraftServer hostServer) {
        if (nodeIdleStopMillis <= 0L) {
            emptyNodeSinceMillisByNodeId.clear();
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextNodeIdleCheckMillis) {
            return;
        }
        nextNodeIdleCheckMillis = now + 1000L;

        boolean stoppedAny = false;
        for (ClusterNodeRuntime runtime : runtimes.values()) {
            String nodeId = runtime.definition().id();
            if (runtime.state() != ClusterNodeState.RUNNING) {
                emptyNodeSinceMillisByNodeId.remove(nodeId);
                continue;
            }

            MinecraftServer runtimeServer = runtimeServerInstances.get(nodeId);
            int playerCount = onlinePlayersOn(runtimeServer);
            if (playerCount > 0) {
                emptyNodeSinceMillisByNodeId.remove(nodeId);
                continue;
            }

            long emptySince = emptyNodeSinceMillisByNodeId.computeIfAbsent(nodeId, ignored -> now);
            if (now - emptySince < nodeIdleStopMillis) {
                continue;
            }

            MultiFabricServer.LOGGER.info(
                    "Node '{}' has been empty for {}s, stopping",
                    nodeId,
                    TimeUnit.MILLISECONDS.toSeconds(nodeIdleStopMillis));
            scheduleRuntimeStop(nodeId, runtime, 0L);
            activeNodeIds.remove(nodeId);
            runtimeServerInstances.remove(nodeId);
            emptyNodeSinceMillisByNodeId.remove(nodeId);
            sharedPlayerPresences.entrySet().removeIf(entry -> nodeId.equals(entry.getValue().nodeId()));
            stoppedAny = true;
        }

        if (stoppedAny) {
            persistRuntimeState(hostServer);
            refreshSharedTabLists();
        }
    }

    public void onServerStopping(MinecraftServer server) {
        if (isClusterChildServer(server)) {
            MultiFabricServer.LOGGER.debug("Ignoring onServerStopping from cluster child {}", describeServer(server));
            return;
        }

        synchronized (this) {
            MultiFabricServer.LOGGER.debug(
                    "onServerStopping server={} owner={} initialized={} nodes={} stopping={} thread={}",
                    describeServer(server),
                    describeServer(ownerServer),
                    initialized,
                    runtimes.size(),
                    stopping,
                    Thread.currentThread().getName());

            if (server != ownerServer) {
                MultiFabricServer.LOGGER.debug("Ignoring onServerStopping for non-owner {}", describeServer(server));
                return;
            }

            stopping = true;

            if (!initialized) {
                MultiFabricServer.LOGGER.debug("Controller not initialized on host stop; waiting for onServerStopped");
                return;
            }

            disconnectPlayersFromHostServer(server, "disconnect.server_shutdown");
            disconnectPlayersFromRunningNodes("disconnect.server_shutdown");
            activeNodeIds.clear();
            activeNodeIds.addAll(currentlyRunningNodeIds());
            sharedPlayerPresences.entrySet().removeIf(entry -> {
                String nodeId = entry.getValue().nodeId();
                return nodeId != null && !nodeId.isBlank();
            });
            emptyNodeSinceMillisByNodeId.clear();
            viewerRemoteTabEntrySignatures.clear();
            pendingInternalTravelMarkersByPlayer.clear();
            pendingExternalLifecycleSuppressionsByPlayer.clear();
            pendingDynmapLogoutSuppressionsByPlayer.clear();
            persistRuntimeState(server);
            refreshSharedTabLists();

            MultiFabricServer.LOGGER.debug("Deferring node shutdown to onServerStopped for host {}",
                    describeServer(server));
        }
    }

    public void onServerStopped(MinecraftServer server) {
        if (isClusterChildServer(server)) {
            MultiFabricServer.LOGGER.debug("Ignoring onServerStopped from cluster child {}", describeServer(server));
            return;
        }

        synchronized (this) {
            MultiFabricServer.LOGGER.debug(
                    "onServerStopped server={} owner={} initialized={} nodes={} stopping={} thread={}",
                    describeServer(server),
                    describeServer(ownerServer),
                    initialized,
                    runtimes.size(),
                    stopping,
                    Thread.currentThread().getName());

            if (server != ownerServer) {
                MultiFabricServer.LOGGER.debug("Ignoring onServerStopped for non-owner {}", describeServer(server));
                return;
            }

            if (initialized) {
                for (ClusterNodeRuntime runtime : runtimes.values()) {
                    scheduleRuntimeStop(runtime.definition().id(), runtime, 0L);
                }
            }

            initialized = false;
            stopping = false;
            sharedPlayerPresences.clear();
            runtimeServerInstances.clear();
            emptyNodeSinceMillisByNodeId.clear();
            stoppingNodeIds.clear();
            viewerRemoteTabEntrySignatures.clear();
            pendingInternalTravelMarkersByPlayer.clear();
            pendingExternalLifecycleSuppressionsByPlayer.clear();
            pendingDynmapLogoutSuppressionsByPlayer.clear();
            pendingHostRouteMessagesByPlayer.clear();
            ownerServer = null;
            MultiFabricServer.LOGGER.debug("Owner cleared after host fully stopped");
        }
    }

    public synchronized int reloadFromDisk(MinecraftServer server) {
        MinecraftServer controlServer = resolveControlServer(server);
        return reloadFromDiskInternal(controlServer);
    }

    protected synchronized int reloadFromDiskInternal(MinecraftServer server) {
        MultiFabricServer.LOGGER.debug(
                "reloadFromDisk server={} initialized={} nodesBefore={}",
                describeServer(server),
                initialized,
                runtimes.size());

        if (initialized) {
            Set<String> delayedStops = new LinkedHashSet<>(stoppingNodeIds);
            Set<String> directoryDeletes = new LinkedHashSet<>(pendingRuntimeDirectoryDeletes);
            for (ClusterNodeRuntime runtime : runtimes.values()) {
                String nodeId = runtime.definition().id();
                Path directoryToDelete = directoryDeletes.contains(nodeId) ? runtimeRoot(server).resolve(nodeId) : null;
                boolean alreadyStopped = runtime.state() == ClusterNodeState.STOPPED;
                if (alreadyStopped && directoryToDelete != null) {
                    runtime.stop();
                    deleteRuntimeDirectory(nodeId, directoryToDelete);
                } else if (!alreadyStopped || directoryToDelete != null) {
                    long stopDelayMillis = delayedStops.contains(nodeId) ? NODE_EVACUATION_SETTLE_MILLIS : 0L;
                    scheduleRuntimeStop(nodeId, runtime, stopDelayMillis, directoryToDelete);
                }
                pendingRuntimeDirectoryDeletes.remove(nodeId);
            }
        }

        runtimeServerInstances.clear();
        emptyNodeSinceMillisByNodeId.clear();
        pruneExpiredInternalTravelMarkers();

        ClusterConfig config = ClusterConfigLoader.load(server);
        nodeIdleStopMillis = TimeUnit.SECONDS.toMillis(Math.max(0, config.nodeIdleStopSeconds()));
        configSnapshot = config;
        clusterRuntimeRoot = server.getWorldPath(LevelResource.ROOT).resolve("cluster-runtime");

        runtimes.clear();
        for (ClusterNodeDefinition node : config.nodes()) {
            runtimes.put(node.id(), new ClusterNodeRuntime(node));
        }

        activeNodeIds.retainAll(runtimes.keySet());
        playerClusterAffinities.entrySet().removeIf(entry -> !runtimes.containsKey(entry.getValue()));
        sharedPlayerPresences.entrySet().removeIf(entry -> {
            String nodeId = entry.getValue().nodeId();
            if (nodeId == null || nodeId.isBlank()) {
                return false;
            }
            ClusterNodeRuntime runtime = runtimes.get(nodeId);
            return runtime == null || !runtime.definition().enabled();
        });
        activeNodeIds.clear();

        for (String nodeId : List.copyOf(pendingRuntimeDirectoryDeletes)) {
            scheduleRuntimeDirectoryDelete(nodeId, runtimeRoot(server).resolve(nodeId), 0L);
            pendingRuntimeDirectoryDeletes.remove(nodeId);
        }

        initialized = true;

        MultiFabricServer.LOGGER.info("Integrated cluster config reloaded. nodes={} (lazy startup mode)",
                runtimes.size());

        persistRuntimeState(server);
        refreshSharedTabLists();
        ClusterDynmapSyncListeners.notifySynced(server);

        return runtimes.size();
    }

    public synchronized boolean startNode(MinecraftServer hostServer, String nodeId) {
        MinecraftServer effectiveHostServer = resolveControlServer(hostServer);
        if (effectiveHostServer == null) {
            return false;
        }

        if (stopping) {
            return false;
        }

        ClusterNodeRuntime runtime = runtimes.get(nodeId);
        if (runtime == null) {
            return false;
        }
        if (!runtime.definition().enabled()) {
            return false;
        }

        runtime.start(effectiveHostServer, runtimeRoot(effectiveHostServer));
        stoppingNodeIds.remove(nodeId);
        if (runtime.state() == ClusterNodeState.RUNNING) {
            trackRunningNode(nodeId);
            persistRuntimeState(effectiveHostServer);
        }
        return true;
    }

    public synchronized boolean stopNode(String nodeId) {
        ClusterNodeRuntime runtime = runtimes.get(nodeId);
        if (runtime == null) {
            return false;
        }
        evacuateNodePlayersToHost(ownerServer, nodeId);
        stoppingNodeIds.add(nodeId);
        scheduleRuntimeStop(nodeId, runtime, NODE_EVACUATION_SETTLE_MILLIS);
        scheduleStoppingNodeMarkerClear(nodeId, STOPPING_NODE_MARKER_TTL_MILLIS);
        activeNodeIds.remove(nodeId);
        runtimeServerInstances.remove(nodeId);
        emptyNodeSinceMillisByNodeId.remove(nodeId);
        clearPlayerAffinitiesForNode(nodeId);
        sharedPlayerPresences.entrySet().removeIf(entry -> nodeId.equals(entry.getValue().nodeId()));
        persistRuntimeState(ownerServer);
        return true;
    }

    public synchronized boolean setNodeEnabled(MinecraftServer server, String nodeId, boolean enabled) {
        MinecraftServer controlServer = resolveControlServer(server);
        if (controlServer == null) {
            return false;
        }

        boolean found = false;
        List<ClusterNodeDefinition> updatedNodes = new ArrayList<>();
        for (ClusterNodeDefinition node : configSnapshot.nodes()) {
            if (!node.id().equals(nodeId)) {
                updatedNodes.add(node);
                continue;
            }

            found = true;
            updatedNodes.add(new ClusterNodeDefinition(node.id(), enabled));
        }

        if (!found) {
            return false;
        }

        if (!enabled) {
            evacuateNodePlayersToHost(controlServer, nodeId);
            stoppingNodeIds.add(nodeId);
            scheduleStoppingNodeMarkerClear(nodeId, STOPPING_NODE_MARKER_TTL_MILLIS);
            activeNodeIds.remove(nodeId);
            emptyNodeSinceMillisByNodeId.remove(nodeId);
            clearPlayerAffinitiesForNode(nodeId);
            sharedPlayerPresences.entrySet().removeIf(entry -> nodeId.equals(entry.getValue().nodeId()));
        }

        ClusterConfig updatedConfig = new ClusterConfig(
                configSnapshot.proxyHostServerName(), configSnapshot.nodeIdleStopSeconds(), List.copyOf(updatedNodes));
        ClusterConfigLoader.save(controlServer, updatedConfig);
        reloadFromDiskInternal(controlServer);
        return true;
    }

    public synchronized boolean addNode(MinecraftServer server, String nodeId) {
        MinecraftServer controlServer = resolveControlServer(server);
        if (controlServer == null) {
            return false;
        }

        String normalizedNodeId = normalizeNodeName(nodeId);
        if (normalizedNodeId == null || runtimes.containsKey(normalizedNodeId)) {
            return false;
        }

        List<ClusterNodeDefinition> updatedNodes = new ArrayList<>(configSnapshot.nodes());
        updatedNodes.add(new ClusterNodeDefinition(normalizedNodeId, true));

        ClusterConfig updatedConfig = new ClusterConfig(
                configSnapshot.proxyHostServerName(), configSnapshot.nodeIdleStopSeconds(), List.copyOf(updatedNodes));
        ClusterConfigLoader.save(controlServer, updatedConfig);
        reloadFromDiskInternal(controlServer);
        return runtimes.containsKey(normalizedNodeId);
    }

    public synchronized boolean removeNode(MinecraftServer server, String nodeId) {
        MinecraftServer controlServer = resolveControlServer(server);
        if (controlServer == null) {
            return false;
        }

        boolean found = false;
        List<ClusterNodeDefinition> updatedNodes = new ArrayList<>();
        for (ClusterNodeDefinition node : configSnapshot.nodes()) {
            if (node.id().equals(nodeId)) {
                found = true;
                continue;
            }
            updatedNodes.add(node);
        }

        if (!found) {
            return false;
        }

        evacuateNodePlayersToHost(controlServer, nodeId);
        stoppingNodeIds.add(nodeId);
        scheduleStoppingNodeMarkerClear(nodeId, STOPPING_NODE_MARKER_TTL_MILLIS);
        activeNodeIds.remove(nodeId);
        emptyNodeSinceMillisByNodeId.remove(nodeId);
        clearPlayerAffinitiesForNode(nodeId);
        runtimeServerInstances.remove(nodeId);
        sharedPlayerPresences.entrySet().removeIf(entry -> nodeId.equals(entry.getValue().nodeId()));
        pendingRuntimeDirectoryDeletes.add(nodeId);
        ClusterRemovalListeners.notifyRemoved(nodeId);

        ClusterConfig updatedConfig = new ClusterConfig(
                configSnapshot.proxyHostServerName(), configSnapshot.nodeIdleStopSeconds(), List.copyOf(updatedNodes));
        ClusterConfigLoader.save(controlServer, updatedConfig);
        reloadFromDiskInternal(controlServer);
        return true;
    }

    public static boolean isValidNodeName(String value) {
        return normalizeNodeName(value) != null;
    }

    public synchronized void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
        String playerUuid = player.getUUID().toString();
        String playerName = player.getScoreboardName();
        String nodeIdForServer = nodeIdForServer(server);

        if (stopping) {
            return;
        }

        boolean suppressJoinMessage = consumePendingInternalTravelMarker(playerUuid, nodeIdForServer);
        String hostRouteMessageNodeId = nodeIdForServer == null
                ? pendingHostRouteMessagesByPlayer.remove(playerUuid)
                : null;
        forceHostRoutePlayerUuids.remove(playerUuid);

        if (nodeIdForServer != null) {
            runtimeServerInstances.put(nodeIdForServer, server);
            emptyNodeSinceMillisByNodeId.remove(nodeIdForServer);
        }
        sharedPlayerPresences.put(playerUuid, new ClusterPlayerPresence(playerUuid, playerName, nodeIdForServer));
        refreshSharedTabLists();
        scheduleDeferredSharedTabRefresh(server);

        if (!suppressJoinMessage) {
            broadcastSharedLifecycleMessage("lifecycle.join", ClusterMessages.arg("player", playerName));
        }

        if (hostRouteMessageNodeId != null && !hostRouteMessageNodeId.isBlank()) {
            player.sendSystemMessage(ClusterMessages.component(player, "cluster.host_return.unavailable",
                    ClusterMessages.arg("cluster", hostRouteMessageNodeId)));
        }

        if (nodeIdForServer != null) {
            rememberPlayerCluster(server, player, nodeIdForServer);
            return;
        }

        if (server != ownerServer) {
            return;
        }

        String targetNodeId = playerClusterAffinities.get(playerUuid);
        if (targetNodeId == null || targetNodeId.isBlank()) {
            return;
        }

        ClusterNodeRuntime runtime = runtimes.get(targetNodeId);
        if (runtime == null || !runtime.definition().enabled()) {
            playerClusterAffinities.remove(playerUuid);
            persistRuntimeState(server);
            return;
        }

        if (shouldUseProxySwitch(server)) {
            MultiFabricServer.LOGGER.info(
                    "Restoring player {} to cluster {} through proxy switch because FabricProxy-Lite is configured",
                    playerName,
                    targetNodeId);
            if (!requestSeamlessProxyTravel(server, player, targetNodeId)) {
                MultiFabricServer.LOGGER.warn(
                        "Seamless proxy restore failed for player {} to node {}",
                        playerName,
                        targetNodeId);
            }
            return;
        }

        if (runtime.state() != ClusterNodeState.RUNNING) {
            stoppingNodeIds.remove(targetNodeId);
            runtime.start(server, runtimeRoot(server));
            if (runtime.state() == ClusterNodeState.RUNNING) {
                trackRunningNode(targetNodeId);
                persistRuntimeState(server);
            }
        }

        if (runtime.state() != ClusterNodeState.RUNNING) {
            MultiFabricServer.LOGGER.warn(
                    "Failed to restore player {} to cluster {} because the node is not running",
                    playerName,
                    targetNodeId);
            return;
        }

        server.execute(() -> executeTransfer(server, player, runtimeTransferHost(), runtime.resolvedTransferPort(),
                targetNodeId));
    }

    public synchronized void onPlayerDisconnect(MinecraftServer server, ServerPlayer player) {
        String playerUuid = player.getUUID().toString();
        String nodeId = nodeIdForServer(server);

        if (stopping) {
            return;
        }

        boolean suppressDisconnectMessage = hasPendingInternalTravelMarker(playerUuid);

        handlePlayerDisconnect(server, playerUuid, player.getScoreboardName(), nodeId);
        sharedPlayerPresences.remove(playerUuid);
        refreshSharedTabLists();
        scheduleDeferredSharedTabRefresh(server);

        if (!suppressDisconnectMessage) {
            broadcastSharedLifecycleMessage("lifecycle.leave",
                    ClusterMessages.arg("player", player.getScoreboardName()));
        }
    }

    public synchronized void onPlayerDisconnectFromNodeForTesting(
            MinecraftServer server, String playerUuid, String nodeId) {
        handlePlayerDisconnect(server, playerUuid, playerUuid, nodeId);
        sharedPlayerPresences.remove(playerUuid);
        refreshSharedTabLists();
        scheduleDeferredSharedTabRefresh(server);
    }

    public synchronized void configureSingleNodeForTesting(MinecraftServer server, String nodeId) {
        ownerServer = server;
        initialized = true;
        stopping = false;
        nodeIdleStopMillis = TimeUnit.SECONDS.toMillis(ClusterConfig.DEFAULT_NODE_IDLE_STOP_SECONDS);
        clusterRuntimeRoot = server.getWorldPath(LevelResource.ROOT).resolve("cluster-runtime");

        ClusterNodeDefinition node = new ClusterNodeDefinition(nodeId, true);
        configSnapshot = new ClusterConfig(ClusterConfig.DEFAULT_PROXY_HOST_SERVER_NAME,
                (int) TimeUnit.MILLISECONDS.toSeconds(nodeIdleStopMillis), List.of(node));

        runtimes.clear();
        runtimes.put(nodeId, new ClusterNodeRuntime(node));
        activeNodeIds.clear();
        playerClusterAffinities.clear();
        forceHostRoutePlayerUuids.clear();
        sharedPlayerPresences.clear();
        runtimeServerInstances.clear();
        emptyNodeSinceMillisByNodeId.clear();
        stoppingNodeIds.clear();
        viewerRemoteTabEntrySignatures.clear();
        pendingInternalTravelMarkersByPlayer.clear();
        pendingExternalLifecycleSuppressionsByPlayer.clear();
        pendingDynmapLogoutSuppressionsByPlayer.clear();
        pendingHostRouteMessagesByPlayer.clear();
    }

    public synchronized Optional<String> playerClusterAffinity(String playerUuid) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(playerClusterAffinities.get(playerUuid));
    }

    public synchronized Optional<String> pendingHostRouteMessageForTesting(String playerUuid) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(pendingHostRouteMessagesByPlayer.get(playerUuid));
    }

    public synchronized boolean hasNode(String nodeId) {
        return runtimes.containsKey(nodeId);
    }

    public synchronized boolean isNodeEnabled(String nodeId) {
        ClusterNodeRuntime runtime = runtimes.get(nodeId);
        return runtime != null && runtime.definition().enabled();
    }

    public synchronized boolean isNodeStopMarkerAbsentForTesting(String nodeId) {
        return !stoppingNodeIds.contains(nodeId);
    }

    public synchronized boolean isInternalTravelMarkerAbsentForTesting(String playerUuid) {
        return !hasPendingInternalTravelMarker(playerUuid);
    }

    public synchronized Collection<ClusterNodeRuntime> allNodes() {
        return List.copyOf(runtimes.values());
    }

    public synchronized Optional<ClusterNodeRuntime> node(String id) {
        return Optional.ofNullable(runtimes.get(id));
    }

    public synchronized String hostTransferHost() {
        return ClusterConfig.DEFAULT_TRANSFER_HOST;
    }

    public synchronized int hostTransferPort() {
        return ownerServer == null ? 25565 : ownerServer.getPort();
    }

    public synchronized List<ServerPlayer> dynmapOnlinePlayers(MinecraftServer hostServer) {
        MinecraftServer effectiveHostServer = resolveControlServer(hostServer);
        if (hostServer == null || hostServer != effectiveHostServer) {
            return List.of();
        }

        Map<UUID, ServerPlayer> playersByUuid = new LinkedHashMap<>();

        addPlayersFromServer(playersByUuid, effectiveHostServer);
        for (MinecraftServer runtimeServer : runtimeServerInstances.values()) {
            addPlayersFromServer(playersByUuid, runtimeServer);
        }

        return List.copyOf(playersByUuid.values());
    }

    public synchronized List<ClusterDynmapWorld> enabledDynmapClusterWorlds(MinecraftServer hostServer) {
        MinecraftServer effectiveHostServer = resolveControlServer(hostServer);
        if (effectiveHostServer == null) {
            return List.of();
        }

        DimensionTemplate overworld = dimensionTemplate(effectiveHostServer, Level.OVERWORLD);
        DimensionTemplate nether = dimensionTemplate(effectiveHostServer, Level.NETHER);
        DimensionTemplate end = dimensionTemplate(effectiveHostServer, Level.END);

        return buildEnabledDynmapClusterWorlds(overworld, nether, end);
    }

    private List<ClusterDynmapWorld> buildEnabledDynmapClusterWorlds(
            DimensionTemplate overworld,
            DimensionTemplate nether,
            DimensionTemplate end) {
        List<ClusterDynmapWorld> worlds = new ArrayList<>();

        for (ClusterNodeRuntime runtime : runtimes.values()) {
            ClusterNodeDefinition definition = runtime.definition();
            if (!definition.enabled()) {
                continue;
            }

            String nodeId = definition.id();
            worlds.add(overworld.toDynmapWorld(nodeId, nodeId + " (overworld)", false, false));
            worlds.add(nether.toDynmapWorld(nodeId + "_nether", nodeId + " (nether)", true, false));
            worlds.add(end.toDynmapWorld(nodeId + "_the_end", nodeId + " (end)", false, true));
        }

        return List.copyOf(worlds);
    }

    public synchronized boolean shouldPruneDynmapSavedWorld(
            MinecraftServer hostServer, String worldName, String worldTitle, boolean loaded) {
        if (loaded || worldName == null || worldName.isBlank()) {
            return false;
        }

        String normalizedWorldName = worldName.toLowerCase(Locale.ROOT);
        if (hostDynmapWorldNames(hostServer).contains(normalizedWorldName)) {
            return false;
        }

        String clusterId = dynmapClusterIdFromWorldName(normalizedWorldName);
        ClusterNodeRuntime runtime = runtimes.get(clusterId);
        if (runtime != null && runtime.definition().enabled()) {
            return false;
        }

        return looksLikeClusterDynmapWorld(normalizedWorldName, worldTitle);
    }

    private static DimensionTemplate dimensionTemplate(
            MinecraftServer server,
            @NonNull ResourceKey<Level> dimension) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return DimensionTemplate.DEFAULT;
        }
        return new DimensionTemplate(level.getHeight(), level.getMinY(), level.getSeaLevel());
    }

    private record DimensionTemplate(int height, int minY, int seaLevel) {
        private static final DimensionTemplate DEFAULT = new DimensionTemplate(384, -64, 63);

        private ClusterDynmapWorld toDynmapWorld(String name, String title, boolean nether, boolean theEnd) {
            return new ClusterDynmapWorld(name, title, height, minY, seaLevel, nether, theEnd);
        }
    }
}
