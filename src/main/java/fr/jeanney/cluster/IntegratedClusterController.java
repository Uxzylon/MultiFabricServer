package fr.jeanney.cluster;

import com.mojang.authlib.GameProfile;
import fr.jeanney.MultiFabricServer;
import fr.jeanney.compat.DynmapCompat;
import fr.jeanney.cluster.network.ClusterRegisterPayload;
import fr.jeanney.cluster.network.ProxyConnectPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class IntegratedClusterController {

    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final long SEAMLESS_NODE_READY_TIMEOUT_MILLIS = 30000L;
    private static final long SEAMLESS_NODE_READY_POLL_MILLIS = 50L;
    private static final long PROXY_REGISTRATION_SETTLE_MILLIS = 150L;
    private static final long NODE_EVACUATION_SETTLE_MILLIS = 1000L;
    private static final long INTERNAL_TRAVEL_MARKER_TTL_MILLIS = TimeUnit.SECONDS.toMillis(20);
    private static final long STOPPING_NODE_MARKER_TTL_MILLIS = NODE_EVACUATION_SETTLE_MILLIS
            + INTERNAL_TRAVEL_MARKER_TTL_MILLIS;
    private static final int EXTERNAL_TRAVEL_LIFECYCLE_SUPPRESSION_EVENTS = 2;
    @SuppressWarnings("null")
    private static final Permission OP_FEEDBACK_PERMISSION = new Permission.HasCommandLevel(
            PermissionLevel.GAMEMASTERS);
    private static final EnumSet<ClientboundPlayerInfoUpdatePacket.Action> REMOTE_TAB_ADD_ACTIONS = EnumSet.of(
            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME);

    private final Map<String, ClusterNodeRuntime> runtimes = new LinkedHashMap<>();
    private final Set<String> activeNodeIds = new LinkedHashSet<>();
    private final Map<String, String> playerClusterAffinities = new LinkedHashMap<>();
    private final Set<String> forceHostRoutePlayerUuids = new LinkedHashSet<>();
    private final Map<String, ClusterPlayerPresence> sharedPlayerPresences = new LinkedHashMap<>();
    private final Map<String, MinecraftServer> runtimeServerInstances = new LinkedHashMap<>();
    private final Map<String, Long> emptyNodeSinceMillisByNodeId = new LinkedHashMap<>();
    private final Set<String> stoppingNodeIds = new LinkedHashSet<>();
    private final Map<String, Map<String, String>> viewerRemoteTabEntrySignatures = new LinkedHashMap<>();
    private final Map<String, InternalTravelMarker> pendingInternalTravelMarkersByPlayer = new LinkedHashMap<>();
    private final Map<String, ExternalLifecycleSuppressionMarker> pendingExternalLifecycleSuppressionsByPlayer = new LinkedHashMap<>();
    private final Map<String, DynmapLogoutSuppressionMarker> pendingDynmapLogoutSuppressionsByPlayer = new LinkedHashMap<>();
    private final Set<String> pendingRuntimeDirectoryDeletes = new LinkedHashSet<>();

    private boolean initialized;
    private Path clusterRuntimeRoot;
    private volatile MinecraftServer ownerServer;
    private volatile boolean stopping;
    private long nodeIdleStopMillis = TimeUnit.SECONDS.toMillis(ClusterConfig.DEFAULT_NODE_IDLE_STOP_SECONDS);
    private ClusterConfig configSnapshot = ClusterConfig.defaultConfig();
    private long nextNodeIdleCheckMillis;

    private static final int TAB_REFRESH_DEFERRED_EXECUTIONS = 2;

    public void onServerStarted(MinecraftServer server) {
        if (isClusterChildServer(server)) {
            MultiFabricServer.LOGGER.info(
                    "[cluster-stop-debug] ignoring onServerStarted from cluster child {}",
                    describeServer(server));
            return;
        }

        synchronized (this) {
            MultiFabricServer.LOGGER.info(
                    "[cluster-stop-debug] onServerStarted server={} owner={} stopping={} thread={}",
                    describeServer(server),
                    describeServer(ownerServer),
                    stopping,
                    Thread.currentThread().getName());

            if (ownerServer == null) {
                ownerServer = server;
                MultiFabricServer.LOGGER.info(
                        "[cluster-stop-debug] owner server assigned to {}",
                        describeServer(ownerServer));
            }

            if (ownerServer != server) {
                MultiFabricServer.LOGGER.info(
                        "[cluster-stop-debug] ignoring onServerStarted for non-owner {}",
                        describeServer(server));
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

    private void stopIdleNodes(MinecraftServer hostServer) {
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

    private static int onlinePlayersOn(MinecraftServer server) {
        if (server == null || server.getPlayerList() == null) {
            return 0;
        }
        return server.getPlayerList().getPlayers().size();
    }

    public void onServerStopping(MinecraftServer server) {
        if (isClusterChildServer(server)) {
            MultiFabricServer.LOGGER.info(
                    "[cluster-stop-debug] ignoring onServerStopping from cluster child {}",
                    describeServer(server));
            return;
        }

        synchronized (this) {
            MultiFabricServer.LOGGER.info(
                    "[cluster-stop-debug] onServerStopping server={} owner={} initialized={} nodes={} stopping={} thread={}",
                    describeServer(server),
                    describeServer(ownerServer),
                    initialized,
                    runtimes.size(),
                    stopping,
                    Thread.currentThread().getName());

            if (server != ownerServer) {
                MultiFabricServer.LOGGER.info(
                        "[cluster-stop-debug] ignoring onServerStopping for non-owner {}",
                        describeServer(server));
                return;
            }

            stopping = true;

            if (!initialized) {
                MultiFabricServer.LOGGER.info(
                        "[cluster-stop-debug] controller not initialized on host stop; waiting for onServerStopped");
                return;
            }

            disconnectPlayersFromHostServer(server, Component.literal("Server is shutting down"));
            disconnectPlayersFromRunningNodes(Component.literal("Server is shutting down"));
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

            MultiFabricServer.LOGGER.info("[cluster-stop-debug] deferring node shutdown to onServerStopped for host {}",
                    describeServer(server));
        }
    }

    public void onServerStopped(MinecraftServer server) {
        if (isClusterChildServer(server)) {
            MultiFabricServer.LOGGER.info(
                    "[cluster-stop-debug] ignoring onServerStopped from cluster child {}",
                    describeServer(server));
            return;
        }

        synchronized (this) {
            MultiFabricServer.LOGGER.info(
                    "[cluster-stop-debug] onServerStopped server={} owner={} initialized={} nodes={} stopping={} thread={}",
                    describeServer(server),
                    describeServer(ownerServer),
                    initialized,
                    runtimes.size(),
                    stopping,
                    Thread.currentThread().getName());

            if (server != ownerServer) {
                MultiFabricServer.LOGGER.info(
                        "[cluster-stop-debug] ignoring onServerStopped for non-owner {}",
                        describeServer(server));
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
            ownerServer = null;
            MultiFabricServer.LOGGER.info("[cluster-stop-debug] owner cleared after host fully stopped");
        }
    }

    public synchronized int reloadFromDisk(MinecraftServer server) {
        MinecraftServer controlServer = resolveControlServer(server);
        return reloadFromDiskInternal(controlServer);
    }

    private synchronized int reloadFromDiskInternal(MinecraftServer server) {
        MultiFabricServer.LOGGER.info(
                "[cluster-stop-debug] reloadFromDisk server={} initialized={} nodesBefore={}",
                describeServer(server),
                initialized,
                runtimes.size());

        if (initialized) {
            Set<String> delayedStops = new LinkedHashSet<>(stoppingNodeIds);
            Set<String> directoryDeletes = new LinkedHashSet<>(pendingRuntimeDirectoryDeletes);
            for (ClusterNodeRuntime runtime : runtimes.values()) {
                String nodeId = runtime.definition().id();
                long stopDelayMillis = delayedStops.contains(nodeId) ? NODE_EVACUATION_SETTLE_MILLIS : 0L;
                Path directoryToDelete = directoryDeletes.contains(nodeId)
                        ? runtimeRoot(server).resolve(nodeId)
                        : null;
                scheduleRuntimeStop(nodeId, runtime, stopDelayMillis, directoryToDelete);
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

        MultiFabricServer.LOGGER.info(
                "Integrated cluster config reloaded. nodes={} (lazy startup mode)",
                runtimes.size());

        persistRuntimeState(server);
        refreshSharedTabLists();

        return runtimes.size();
    }

    private static String describeServer(MinecraftServer server) {
        if (server == null) {
            return "null";
        }
        return server.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(server));
    }

    private MinecraftServer resolveControlServer(MinecraftServer fallbackServer) {
        if (ownerServer != null) {
            return ownerServer;
        }
        return fallbackServer;
    }

    private static boolean isClusterChildServer(MinecraftServer server) {
        Path rootPath;
        try {
            rootPath = server.getWorldPath(LevelResource.ROOT);
        } catch (Exception exception) {
            return false;
        }

        for (Path segment : rootPath.normalize()) {
            if ("cluster-runtime".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    public boolean isClusterChildServerForRuntime(MinecraftServer server) {
        return isClusterChildServer(server);
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

    private Path runtimeRoot(MinecraftServer server) {
        if (clusterRuntimeRoot != null) {
            return clusterRuntimeRoot;
        }
        return server.getWorldPath(LevelResource.ROOT).resolve("cluster-runtime");
    }

    private boolean trackRunningNode(String nodeId) {
        ClusterNodeRuntime runtime = runtimes.get(nodeId);
        if (runtime == null || runtime.state() != ClusterNodeState.RUNNING) {
            return false;
        }

        boolean changed = activeNodeIds.add(nodeId);
        emptyNodeSinceMillisByNodeId.putIfAbsent(nodeId, System.currentTimeMillis());
        return changed;
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
                configSnapshot.proxyHostServerName(),
                configSnapshot.nodeIdleStopSeconds(),
                List.copyOf(updatedNodes));
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
                configSnapshot.proxyHostServerName(),
                configSnapshot.nodeIdleStopSeconds(),
                List.copyOf(updatedNodes));
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
        DynmapCompat.removeClusterWorlds(nodeId);

        ClusterConfig updatedConfig = new ClusterConfig(
                configSnapshot.proxyHostServerName(),
                configSnapshot.nodeIdleStopSeconds(),
                List.copyOf(updatedNodes));
        ClusterConfigLoader.save(controlServer, updatedConfig);
        reloadFromDiskInternal(controlServer);
        return true;
    }

    public static boolean isValidNodeName(String value) {
        return normalizeNodeName(value) != null;
    }

    private static String normalizeNodeName(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 64 || !normalized.matches("[A-Za-z0-9_.-]+")) {
            return null;
        }

        return normalized.toLowerCase(Locale.ROOT);
    }

    public synchronized void rememberPlayerCluster(MinecraftServer server, ServerPlayer player, String clusterId) {
        rememberPlayerCluster(server, player.getUUID().toString(), clusterId);
    }

    public synchronized void rememberPlayerCluster(MinecraftServer server, String playerUuid, String clusterId) {
        if (playerUuid == null || playerUuid.isBlank() || clusterId == null || clusterId.isBlank()) {
            return;
        }

        forceHostRoutePlayerUuids.remove(playerUuid);
        playerClusterAffinities.put(playerUuid, clusterId);
        persistRuntimeState(server);
    }

    public synchronized void clearPlayerCluster(MinecraftServer server, ServerPlayer player) {
        playerClusterAffinities.remove(player.getUUID().toString());
        forceHostRoutePlayerUuids.remove(player.getUUID().toString());
        persistRuntimeState(server);
    }

    public synchronized void preparePlayerHostTravel(MinecraftServer server, ServerPlayer player) {
        preparePlayerHostTravel(server, player.getUUID().toString());
    }

    public synchronized void preparePlayerHostTravel(MinecraftServer server, String playerUuid) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return;
        }

        playerClusterAffinities.remove(playerUuid);
        forceHostRoutePlayerUuids.add(playerUuid);
        persistRuntimeState(server);
    }

    public synchronized void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
        String playerUuid = player.getUUID().toString();
        String playerName = player.getScoreboardName();
        String nodeIdForServer = nodeIdForServer(server);

        if (stopping) {
            return;
        }

        boolean suppressJoinMessage = consumePendingInternalTravelMarker(playerUuid, nodeIdForServer);
        forceHostRoutePlayerUuids.remove(playerUuid);

        if (nodeIdForServer != null) {
            runtimeServerInstances.put(nodeIdForServer, server);
            emptyNodeSinceMillisByNodeId.remove(nodeIdForServer);
        }
        sharedPlayerPresences.put(playerUuid,
                new ClusterPlayerPresence(playerUuid, playerName, nodeIdForServer));
        refreshSharedTabLists();
        scheduleDeferredSharedTabRefresh(server);

        if (!suppressJoinMessage) {
            broadcastSharedLifecycleMessage(
                    Component.literal(playerName + " joined the game").withStyle(ChatFormatting.YELLOW));
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
            MultiFabricServer.LOGGER.warn("Failed to restore player {} to cluster {} because the node is not running",
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
            broadcastSharedLifecycleMessage(
                    Component.literal(player.getScoreboardName() + " left the game")
                            .withStyle(ChatFormatting.YELLOW));
        }
    }

    public synchronized void onPlayerDisconnectFromNodeForTesting(MinecraftServer server, String playerUuid,
            String nodeId) {
        handlePlayerDisconnect(server, playerUuid, playerUuid, nodeId);
        sharedPlayerPresences.remove(playerUuid);
        refreshSharedTabLists();
        scheduleDeferredSharedTabRefresh(server);
    }

    public synchronized int sharedOnlinePlayersCount() {
        return sharedPlayerPresences.size();
    }

    public synchronized Collection<ClusterPlayerPresence> sharedOnlinePlayers() {
        return ListSnapshot.copy(sharedPlayerPresences.values());
    }

    public synchronized void upsertSharedPlayerPresenceForTesting(String playerUuid, String playerName,
            String nodeId) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return;
        }

        String effectiveName = (playerName == null || playerName.isBlank()) ? playerUuid : playerName;
        String effectiveNodeId = (nodeId == null || nodeId.isBlank()) ? null : nodeId;
        sharedPlayerPresences.put(playerUuid,
                new ClusterPlayerPresence(playerUuid, effectiveName, effectiveNodeId));
        refreshSharedTabLists();
    }

    public synchronized void removeSharedPlayerPresenceForTesting(String playerUuid) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return;
        }
        sharedPlayerPresences.remove(playerUuid);
        refreshSharedTabLists();
    }

    public synchronized void configureSingleNodeForTesting(MinecraftServer server, String nodeId) {
        ownerServer = server;
        initialized = true;
        stopping = false;
        nodeIdleStopMillis = TimeUnit.SECONDS.toMillis(ClusterConfig.DEFAULT_NODE_IDLE_STOP_SECONDS);
        clusterRuntimeRoot = server.getWorldPath(LevelResource.ROOT).resolve("cluster-runtime");

        ClusterNodeDefinition node = new ClusterNodeDefinition(nodeId, true);
        configSnapshot = new ClusterConfig(
                ClusterConfig.DEFAULT_PROXY_HOST_SERVER_NAME,
                (int) TimeUnit.MILLISECONDS.toSeconds(nodeIdleStopMillis),
                List.of(node));

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
    }

    public synchronized Collection<ClusterPlayerPresence> remotePlayersForViewerForTesting(String viewerUuid,
            String viewerNodeId) {
        String viewerClusterLabel = clusterLabelForNodeId(viewerNodeId);
        Map<String, ClusterPlayerPresence> desiredRemote = desiredRemotePresences(sharedPlayerPresences,
                viewerUuid,
                viewerClusterLabel);
        return ListSnapshot.copy(desiredRemote.values());
    }

    public static Component buildRemoteTabDisplayName(String playerName, String clusterLabel) {
        String effectiveName = (playerName == null || playerName.isBlank()) ? "unknown" : playerName;
        String effectiveCluster = clusterLabelForNodeId(clusterLabel);
        return Component.literal(effectiveName + " (" + effectiveCluster + ")")
                .withStyle(ChatFormatting.GRAY);
    }

    public synchronized Optional<String> playerClusterAffinity(String playerUuid) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(playerClusterAffinities.get(playerUuid));
    }

    public synchronized boolean hasNode(String nodeId) {
        return runtimes.containsKey(nodeId);
    }

    public synchronized boolean isNodeEnabled(String nodeId) {
        ClusterNodeRuntime runtime = runtimes.get(nodeId);
        return runtime != null && runtime.definition().enabled();
    }

    public synchronized boolean isNodeStoppingForTesting(String nodeId) {
        return stoppingNodeIds.contains(nodeId);
    }

    public synchronized boolean hasPendingInternalTravelForTesting(String playerUuid) {
        return hasPendingInternalTravelMarker(playerUuid);
    }

    public synchronized Collection<ClusterNodeRuntime> allNodes() {
        return ListSnapshot.copy(runtimes.values());
    }

    public synchronized Optional<ClusterNodeRuntime> node(String id) {
        return Optional.ofNullable(runtimes.get(id));
    }

    public synchronized boolean isInitialized() {
        return initialized;
    }

    public synchronized int onlinePlayers(MinecraftServer hostServer) {
        MinecraftServer effectiveServer = resolveControlServer(hostServer);
        if (effectiveServer == null) {
            return 0;
        }
        return effectiveServer.getPlayerList().getPlayers().size();
    }

    public synchronized OptionalInt ownerPort() {
        if (ownerServer == null) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(ownerServer.getPort());
    }

    public synchronized String hostTransferHost() {
        return ClusterConfig.DEFAULT_TRANSFER_HOST;
    }

    public synchronized int hostTransferPort() {
        return ownerServer == null ? 25565 : ownerServer.getPort();
    }

    public synchronized String runtimeTransferHost() {
        return LOOPBACK_HOST;
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

    public synchronized boolean shouldPruneDynmapSavedWorld(
            MinecraftServer hostServer,
            String worldName,
            String worldTitle,
            boolean loaded) {
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

    public synchronized long nodeIdleStopSeconds() {
        return TimeUnit.MILLISECONDS.toSeconds(nodeIdleStopMillis);
    }

    public synchronized boolean allowGameMessage(MinecraftServer server, Component message, boolean overlay) {
        if (overlay || !initialized) {
            return true;
        }

        return !isVanillaJoinLeaveGameMessage(message);
    }

    public void relayAdvancementGameMessage(MinecraftServer sourceServer, Component message, boolean overlay) {
        if (sourceServer == null || message == null || overlay) {
            return;
        }

        String sourceCluster;
        List<MinecraftServer> targets;
        synchronized (this) {
            if (!initialized || ownerServer == null) {
                return;
            }

            if (!isCrossClusterAdvancementGameMessage(message)) {
                return;
            }

            sourceCluster = clusterLabelForNodeId(nodeIdForServer(sourceServer));
            targets = relayTargets(sourceServer);
        }

        if (targets.isEmpty()) {
            return;
        }

        MutableComponent relayedMessage = buildCrossClusterMessagePrefix(sourceCluster)
                .append(message.copy());

        for (MinecraftServer target : targets) {
            if (target == null || target.isStopped()) {
                continue;
            }

            target.execute(() -> {
                for (ServerPlayer targetPlayer : target.getPlayerList().getPlayers()) {
                    if (targetPlayer == null) {
                        continue;
                    }
                    targetPlayer.sendSystemMessage(relayedMessage.copy());
                }
            });
        }
    }

    public synchronized boolean isOwnerServer(MinecraftServer server) {
        return server == ownerServer;
    }

    public synchronized Optional<GatewayRoute> resolveGatewayRoute(String requestedHost, String playerUuid) {
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
                Optional<GatewayRoute> preferredRoute = gatewayRouteForNode(preferredNodeId);
                if (preferredRoute.isPresent()) {
                    return preferredRoute;
                }
            }
        }

        return Optional.of(hostGatewayRoute());
    }

    private GatewayRoute hostGatewayRoute() {
        return new GatewayRoute(LOOPBACK_HOST, ownerServer.getPort(), null);
    }

    private Optional<String> resolveNodeIdFromVirtualHost(String normalizedHost) {
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

    public void relayChatMessage(MinecraftServer sourceServer, ServerPlayer sender, String rawMessage) {
        if (sourceServer == null || sender == null || rawMessage == null) {
            return;
        }

        String chatText = rawMessage.trim();
        if (chatText.isEmpty()) {
            return;
        }

        String sourceCluster;
        String renderedLine;
        List<MinecraftServer> targets;
        synchronized (this) {
            if (!initialized || ownerServer == null) {
                return;
            }

            sourceCluster = clusterLabelForNodeId(nodeIdForServer(sourceServer));
            renderedLine = "<" + sender.getScoreboardName() + "> " + chatText;
            targets = relayTargets(sourceServer);
        }

        if (targets.isEmpty()) {
            return;
        }

        MutableComponent relayMessage = buildCrossClusterMessagePrefix(sourceCluster)
                .append(Component.literal(renderedLine));

        for (MinecraftServer target : targets) {
            if (target == null || target.isStopped()) {
                continue;
            }
            target.execute(() -> target.getPlayerList().broadcastSystemMessage(relayMessage, false));
        }
    }

    public void relayConsoleCommandMessage(MinecraftServer sourceServer, Component message) {
        if (sourceServer == null || message == null) {
            return;
        }

        String sourceCluster;
        List<MinecraftServer> targets;
        synchronized (this) {
            if (!initialized || ownerServer == null) {
                return;
            }

            sourceCluster = clusterLabelForNodeId(nodeIdForServer(sourceServer));
            targets = relayTargets(sourceServer);
        }

        if (targets.isEmpty()) {
            return;
        }

        MutableComponent relayedMessage = buildCrossClusterMessagePrefix(sourceCluster)
                .append(message.copy());

        for (MinecraftServer target : targets) {
            if (target == null || target.isStopped()) {
                continue;
            }
            target.execute(() -> target.getPlayerList().broadcastSystemMessage(relayedMessage, false));
        }
    }

    public void relayCommandFeedback(MinecraftServer sourceServer, ServerPlayer sourcePlayer, Component feedback) {
        if (sourceServer == null || sourcePlayer == null || feedback == null) {
            return;
        }

        String sourceCluster;
        List<MinecraftServer> targets;
        synchronized (this) {
            if (!initialized || ownerServer == null) {
                return;
            }

            sourceCluster = clusterLabelForNodeId(nodeIdForServer(sourceServer));
            targets = relayTargets(sourceServer);
        }

        if (targets.isEmpty()) {
            return;
        }

        Component adminFeedback = Component
                .translatable("chat.type.admin", sourcePlayer.getDisplayName(), feedback.copy())
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        MutableComponent relayedMessage = buildCrossClusterMessagePrefix(sourceCluster)
                .append(adminFeedback);

        for (MinecraftServer target : targets) {
            if (target == null || target.isStopped()) {
                continue;
            }

            target.execute(() -> {
                for (ServerPlayer targetPlayer : target.getPlayerList().getPlayers()) {
                    if (targetPlayer == null || !hasOpFeedbackPermission(targetPlayer)) {
                        continue;
                    }
                    targetPlayer.sendSystemMessage(relayedMessage.copy());
                }
            });
        }
    }

    @SuppressWarnings("null")
    private static boolean hasOpFeedbackPermission(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return player.createCommandSourceStack().permissions().hasPermission(OP_FEEDBACK_PERMISSION);
    }

    private static MutableComponent buildCrossClusterMessagePrefix(String clusterLabel) {
        MutableComponent root = Component.empty();
        root.append(Component.literal("[" + clusterLabelForNodeId(clusterLabel) + "] ")
                .withStyle(ChatFormatting.GRAY));
        return root;
    }

    private void broadcastSharedLifecycleMessage(Component message) {
        if (message == null) {
            return;
        }

        for (MinecraftServer target : allServersWithPossibleViewers()) {
            if (target == null || target.isStopped()) {
                continue;
            }
            target.execute(() -> target.getPlayerList().broadcastSystemMessage(message, false));
        }
    }

    private Optional<GatewayRoute> gatewayRouteForNode(String nodeId) {
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

    private List<MinecraftServer> relayTargets(MinecraftServer sourceServer) {
        Set<MinecraftServer> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        List<MinecraftServer> targets = new ArrayList<>();

        if (ownerServer != null && ownerServer != sourceServer && unique.add(ownerServer)) {
            targets.add(ownerServer);
        }

        for (MinecraftServer runtimeServer : runtimeServerInstances.values()) {
            if (runtimeServer == null || runtimeServer == sourceServer) {
                continue;
            }
            if (unique.add(runtimeServer)) {
                targets.add(runtimeServer);
            }
        }

        return targets;
    }

    private synchronized void refreshSharedTabLists() {
        if (!initialized || ownerServer == null) {
            viewerRemoteTabEntrySignatures.clear();
            return;
        }

        Map<String, ClusterPlayerPresence> presenceSnapshot = new LinkedHashMap<>(sharedPlayerPresences);
        Set<String> activeViewerUuids = new LinkedHashSet<>();

        for (MinecraftServer server : allServersWithPossibleViewers()) {
            if (server == null || server.isStopped()) {
                continue;
            }

            String viewerClusterLabel = clusterLabelForNodeId(nodeIdForServer(server));
            List<ServerPlayer> viewers = server.getPlayerList().getPlayers();
            for (ServerPlayer viewer : viewers) {
                if (viewer == null || viewer.connection == null) {
                    continue;
                }

                String viewerUuid = viewer.getUUID().toString();
                activeViewerUuids.add(viewerUuid);

                Map<String, ClusterPlayerPresence> desiredRemotePresences = desiredRemotePresences(
                        presenceSnapshot,
                        viewerUuid,
                        viewerClusterLabel);
                Map<String, String> desiredSignatures = remoteSignatures(desiredRemotePresences);

                Map<String, String> currentSignatures = viewerRemoteTabEntrySignatures.computeIfAbsent(
                        viewerUuid,
                        ignored -> new LinkedHashMap<>());

                List<UUID> removals = new ArrayList<>();
                for (Map.Entry<String, String> currentEntry : currentSignatures.entrySet()) {
                    String desiredSignature = desiredSignatures.get(currentEntry.getKey());
                    if (desiredSignature != null && desiredSignature.equals(currentEntry.getValue())) {
                        continue;
                    }

                    try {
                        removals.add(UUID.fromString(currentEntry.getKey()));
                    } catch (IllegalArgumentException ignored) {
                        // Ignore malformed UUID keys in stale state and just drop them from tracking.
                    }
                }

                if (!removals.isEmpty()) {
                    viewer.connection.send(new ClientboundPlayerInfoRemovePacket(removals));
                }

                for (Map.Entry<String, ClusterPlayerPresence> desiredEntry : desiredRemotePresences.entrySet()) {
                    String desiredSignature = desiredSignatures.get(desiredEntry.getKey());
                    String existingSignature = currentSignatures.get(desiredEntry.getKey());
                    if (desiredSignature != null && desiredSignature.equals(existingSignature)) {
                        continue;
                    }

                    ClientboundPlayerInfoUpdatePacket updatePacket = createRemoteTabAddPacket(server,
                            desiredEntry.getValue());
                    if (updatePacket != null) {
                        viewer.connection.send(updatePacket);
                    }
                }

                currentSignatures.clear();
                currentSignatures.putAll(desiredSignatures);
            }
        }

        viewerRemoteTabEntrySignatures.keySet().removeIf(
                trackedViewerUuid -> !activeViewerUuids.contains(trackedViewerUuid));
    }

    @SuppressWarnings("null")
    private void scheduleDeferredSharedTabRefresh(MinecraftServer schedulerServer) {
        if (schedulerServer == null || schedulerServer.isStopped()) {
            return;
        }

        Runnable refreshTask = () -> {
            synchronized (this) {
                refreshSharedTabLists();
            }
        };

        Runnable chain = refreshTask;
        for (int i = 0; i < TAB_REFRESH_DEFERRED_EXECUTIONS; i++) {
            Runnable next = chain;
            chain = () -> schedulerServer.execute(next);
        }

        schedulerServer.execute(chain);
    }

    private List<MinecraftServer> allServersWithPossibleViewers() {
        Set<MinecraftServer> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        List<MinecraftServer> servers = new ArrayList<>();

        if (ownerServer != null && unique.add(ownerServer)) {
            servers.add(ownerServer);
        }

        for (MinecraftServer runtimeServer : runtimeServerInstances.values()) {
            if (runtimeServer == null) {
                continue;
            }
            if (unique.add(runtimeServer)) {
                servers.add(runtimeServer);
            }
        }

        return servers;
    }

    private static Map<String, ClusterPlayerPresence> desiredRemotePresences(
            Map<String, ClusterPlayerPresence> presences,
            String viewerUuid,
            String viewerClusterLabel) {
        Map<String, ClusterPlayerPresence> desired = new LinkedHashMap<>();
        for (ClusterPlayerPresence presence : presences.values()) {
            if (presence == null) {
                continue;
            }

            String remoteUuid = presence.playerUuid();
            if (remoteUuid == null || remoteUuid.isBlank()) {
                continue;
            }

            if (remoteUuid.equals(viewerUuid)) {
                continue;
            }

            if (clusterLabelForNodeId(presence.nodeId()).equals(viewerClusterLabel)) {
                continue;
            }

            desired.put(remoteUuid, presence);
        }

        return desired;
    }

    private static Map<String, String> remoteSignatures(Map<String, ClusterPlayerPresence> remotePresences) {
        Map<String, String> signatures = new LinkedHashMap<>();
        for (Map.Entry<String, ClusterPlayerPresence> entry : remotePresences.entrySet()) {
            ClusterPlayerPresence presence = entry.getValue();
            signatures.put(entry.getKey(),
                    presence.playerName() + "|" + clusterLabelForNodeId(presence.nodeId()));
        }
        return signatures;
    }

    @SuppressWarnings("null")
    private ClientboundPlayerInfoUpdatePacket createRemoteTabAddPacket(MinecraftServer server,
            ClusterPlayerPresence presence) {
        if (server == null || presence == null) {
            return null;
        }

        UUID profileId;
        try {
            profileId = UUID.fromString(presence.playerUuid());
        } catch (Exception exception) {
            return null;
        }

        String playerName = presence.playerName();
        if (playerName == null || playerName.isBlank()) {
            String compactUuid = profileId.toString().replace("-", "");
            playerName = compactUuid.substring(0, Math.min(16, compactUuid.length()));
        }

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        buffer.writeEnumSet(REMOTE_TAB_ADD_ACTIONS, ClientboundPlayerInfoUpdatePacket.Action.class);
        buffer.writeVarInt(1);
        buffer.writeUUID(profileId);

        GameProfile profile = new GameProfile(profileId, playerName);
        ByteBufCodecs.PLAYER_NAME.encode(buffer, profile.name());
        ByteBufCodecs.GAME_PROFILE_PROPERTIES.encode(buffer, profile.properties());
        buffer.writeBoolean(true);
        FriendlyByteBuf.writeNullable(
                buffer,
                buildRemoteTabDisplayName(playerName, presence.clusterLabel()),
                ComponentSerialization.TRUSTED_STREAM_CODEC);

        return ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buffer);
    }

    private static String clusterLabelForNodeId(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return "host";
        }
        return nodeId;
    }

    private static boolean isVanillaJoinLeaveGameMessage(Component message) {
        if (message == null) {
            return false;
        }

        if (!(message.getContents() instanceof TranslatableContents translatableContents)) {
            return false;
        }

        String key = translatableContents.getKey();
        if (key == null || key.isBlank()) {
            return false;
        }

        return key.startsWith("multiplayer.player.joined") || "multiplayer.player.left".equals(key);
    }

    private static boolean isCrossClusterAdvancementGameMessage(Component message) {
        if (message == null) {
            return false;
        }

        if (!(message.getContents() instanceof TranslatableContents translatableContents)) {
            return false;
        }

        String key = translatableContents.getKey();
        if (key == null || key.isBlank()) {
            return false;
        }

        return key.startsWith("chat.type.advancement.");
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

    private static String normalizeVirtualHost(String host) {
        if (host == null || host.isBlank()) {
            return "";
        }

        String normalized = host;
        int nulSeparator = normalized.indexOf('\u0000');
        if (nulSeparator >= 0) {
            normalized = normalized.substring(0, nulSeparator);
        }

        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized.toLowerCase(Locale.ROOT);
    }

    public synchronized boolean requestSeamlessProxyTravel(MinecraftServer server, ServerPlayer player,
            String targetNodeId) {
        if (!shouldUseProxySwitch(server) || server == null || player == null) {
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
            // disconnect handling cannot immediately restore the previous cluster affinity.
            preparePlayerHostTravel(server, playerUuid);

            boolean switched = executeSeamlessProxyTravel(
                    server,
                    player,
                    null,
                    configSnapshot.proxyHostServerName(),
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
                server,
                player.getUUID(),
                targetNodeId,
                proxyServerName,
                transferHost,
                transferPort);

        return true;
    }

    public boolean requestDirectClusterTravelWhenReady(MinecraftServer server, ServerPlayer player,
            String targetNodeId) {
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
            ReadyProxyTarget readyTarget = waitForRuntimeEndpoint(targetNodeId,
                    SEAMLESS_NODE_READY_TIMEOUT_MILLIS,
                    SEAMLESS_NODE_READY_POLL_MILLIS).orElse(null);

            server.execute(() -> {
                if (server.isStopped()) {
                    return;
                }

                ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerUuid);
                if (currentPlayer == null) {
                    return;
                }

                if (readyTarget == null) {
                    currentPlayer.sendSystemMessage(Component.literal(
                            "Cluster '" + targetNodeId + "' is still starting. Please retry in a moment."));
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

    private void scheduleSeamlessProxyTravelWhenNodeReady(MinecraftServer server,
            UUID playerUuid,
            String targetNodeId,
            String proxyServerName) {
        Thread.startVirtualThread(() -> {
            ReadyProxyTarget readyTarget = waitForRuntimeEndpoint(targetNodeId,
                    SEAMLESS_NODE_READY_TIMEOUT_MILLIS,
                    SEAMLESS_NODE_READY_POLL_MILLIS).orElse(null);

            server.execute(() -> {
                if (server.isStopped()) {
                    return;
                }

                ServerPlayer currentPlayer = server.getPlayerList().getPlayer(Objects.requireNonNull(playerUuid));
                if (currentPlayer == null) {
                    return;
                }

                if (readyTarget == null) {
                    currentPlayer.sendSystemMessage(Component.literal(
                            "Cluster '" + targetNodeId + "' is still starting. Please retry in a moment."));
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

    private Optional<ReadyProxyTarget> waitForRuntimeEndpoint(String targetNodeId, long timeoutMillis,
            long pollMillis) {
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

    private boolean shouldUseProxySwitch(MinecraftServer server) {
        return ProxyForwardingConfig.isFabricProxyLiteConfigured(server);
    }

    private static void logProxyTargetRequirement(ServerPlayer player,
            String targetNodeId,
            String proxyServerName) {
        String playerName = player == null ? "unknown" : player.getScoreboardName();
        MultiFabricServer.LOGGER.info(
                "Requesting seamless proxy switch for player {} to cluster '{}' through proxy server name '{}'",
                playerName,
                targetNodeId,
                proxyServerName);
    }

    private void scheduleSeamlessProxyTravelWhenReady(MinecraftServer server,
            UUID playerUuid,
            String targetNodeId,
            String proxyServerName,
            String targetHost,
            int targetPort) {
        Thread.startVirtualThread(() -> {
            boolean reachable = waitForEndpoint(targetHost, targetPort,
                    SEAMLESS_NODE_READY_TIMEOUT_MILLIS,
                    SEAMLESS_NODE_READY_POLL_MILLIS);

            server.execute(() -> {
                if (server.isStopped()) {
                    return;
                }

                ServerPlayer currentPlayer = server.getPlayerList().getPlayer(Objects.requireNonNull(playerUuid));
                if (currentPlayer == null) {
                    return;
                }

                if (!reachable) {
                    currentPlayer.sendSystemMessage(Component.literal(
                            "Cluster '" + targetNodeId + "' is still starting. Please retry in a moment."));
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

    private static boolean waitForEndpoint(String host, int port, long timeoutMillis, long pollMillis) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));

        while (System.nanoTime() < deadlineNanos) {
            if (isEndpointReachable(host, port, 150)) {
                return true;
            }

            try {
                Thread.sleep(Math.max(10L, pollMillis));
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return isEndpointReachable(host, port, 150);
    }

    private static boolean isEndpointReachable(String host, int port, int timeoutMillis) {
        if (host == null || host.isBlank() || port <= 0) {
            return false;
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Math.max(50, timeoutMillis));
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean executeSeamlessProxyTravel(MinecraftServer server,
            ServerPlayer player,
            String targetNodeId,
            String proxyServerName,
            boolean hostTarget) {
        String effectiveProxyServerName = normalizeProxyServerName(proxyServerName,
                hostTarget ? "host" : targetNodeId);
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
                                effectiveProxyServerName,
                                runtimeTransferHost(),
                                transferPort)));
                        registeredDynamicTarget = true;
                    }
                }
            }

            if (registeredDynamicTarget) {
                scheduleProxyConnectAfterRegistration(server, playerUuid, effectiveProxyServerName, targetNodeId,
                        hostTarget);
            } else {
                sendProxyConnect(server, player, effectiveProxyServerName, targetNodeId, hostTarget);
            }
            return true;
        } catch (Exception exception) {
            clearPendingInternalTravelMarker(player.getUUID().toString());
            MultiFabricServer.LOGGER.error("Failed seamless proxy switch for player {} to {} (node={})",
                    player.getScoreboardName(),
                    effectiveProxyServerName,
                    targetNodeId,
                    exception);
            return false;
        }
    }

    private void scheduleProxyConnectAfterRegistration(MinecraftServer server,
            UUID playerUuid,
            String proxyServerName,
            String targetNodeId,
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

    private void sendProxyConnect(MinecraftServer server,
            ServerPlayer player,
            String proxyServerName,
            String targetNodeId,
            boolean hostTarget) {
        try {
            player.connection.send(new ClientboundCustomPayloadPacket(new ProxyConnectPayload(proxyServerName)));
            if (!hostTarget) {
                rememberPlayerCluster(server, player, targetNodeId);
            }
        } catch (Exception exception) {
            clearPendingInternalTravelMarker(player.getUUID().toString());
            MultiFabricServer.LOGGER.error("Failed to send proxy switch for player {} to {} (node={})",
                    player.getScoreboardName(),
                    proxyServerName,
                    targetNodeId,
                    exception);
        }
    }

    private static String normalizeProxyServerName(String configuredName, String fallbackName) {
        String fallback = fallbackName == null ? "" : fallbackName.trim();
        String configured = configuredName == null ? "" : configuredName.trim();

        if (!configured.isEmpty()) {
            return configured;
        }

        if (!fallback.isEmpty()) {
            return fallback;
        }

        return null;
    }

    private static String resolveProxyServerName(ClusterNodeDefinition definition) {
        if (definition == null) {
            return null;
        }

        return definition.id();
    }

    private void executeTransfer(MinecraftServer server, ServerPlayer player, String host, int port,
            String targetNodeId) {
        String transferCommand = "transfer " + host + " " + port;
        String playerUuid = player.getUUID().toString();
        markPendingInternalTravel(playerUuid, targetNodeId, nodeIdForServer(server));
        try {
            int result = server.getCommands().getDispatcher().execute(transferCommand,
                    player.createCommandSourceStack());
            if (result <= 0) {
                clearPendingInternalTravelMarker(playerUuid);
                MultiFabricServer.LOGGER.warn("Transfer command did not execute for player {} to {}:{}",
                        player.getScoreboardName(),
                        host,
                        port);
            } else {
                rememberPlayerCluster(server, player, targetNodeId);
            }
        } catch (Exception exception) {
            clearPendingInternalTravelMarker(playerUuid);
            MultiFabricServer.LOGGER.error("Failed to transfer player {} to cluster {} via {}:{}",
                    player.getScoreboardName(),
                    targetNodeId,
                    host,
                    port,
                    exception);
        }
    }

    private boolean evacuateNodePlayersToHost(MinecraftServer controlServer, String nodeId) {
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
            if (player == null) {
                continue;
            }
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
                if (player == null || player.connection == null) {
                    continue;
                }
                sendPlayerToHost(runtimeServer, player, nodeId, useProxySwitch, proxyTarget, targetHost,
                        targetPort);
            }
        });

        return true;
    }

    private void scheduleRuntimeStop(String nodeId, ClusterNodeRuntime runtime, long delayMillis) {
        scheduleRuntimeStop(nodeId, runtime, delayMillis, null);
    }

    private void scheduleRuntimeStop(
            String nodeId,
            ClusterNodeRuntime runtime,
            long delayMillis,
            Path runtimeDirectoryToDelete) {
        if (runtime == null) {
            return;
        }

        Thread.ofVirtual()
                .name("MultiFabricServer cluster stopper " + nodeId)
                .start(() -> {
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

    private void scheduleRuntimeDirectoryDelete(String nodeId, Path nodeRoot, long delayMillis) {
        if (nodeRoot == null) {
            return;
        }

        Thread.ofVirtual()
                .name("MultiFabricServer cluster directory deleter " + nodeId)
                .start(() -> {
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

    private void deleteRuntimeDirectory(String nodeId, Path nodeRoot) {
        Path target = nodeRoot.toAbsolutePath().normalize();
        Path runtimeRoot = clusterRuntimeRoot == null ? target.getParent() : clusterRuntimeRoot;
        if (runtimeRoot == null) {
            MultiFabricServer.LOGGER.warn("Refusing to delete cluster runtime directory without known root: {}",
                    target);
            return;
        }

        Path root = runtimeRoot.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            MultiFabricServer.LOGGER.warn("Refusing to delete cluster runtime directory outside cluster root: {}",
                    target);
            return;
        }

        for (int attempt = 1; attempt <= 3; attempt++) {
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

                MultiFabricServer.LOGGER.info("Deleted cluster runtime directory for '{}': {}", nodeId, target);
                return;
            } catch (RuntimeException | IOException exception) {
                if (attempt == 3) {
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
            }
        }
    }

    private void scheduleStoppingNodeMarkerClear(String nodeId, long delayMillis) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }

        Thread.ofVirtual()
                .name("MultiFabricServer cluster stop marker clearer " + nodeId)
                .start(() -> {
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

    private void markPlayerForHostRoute(MinecraftServer persistenceServer, String playerUuid, String sourceNodeId) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return;
        }

        playerClusterAffinities.remove(playerUuid);
        forceHostRoutePlayerUuids.add(playerUuid);
        markPendingInternalTravel(playerUuid, null, sourceNodeId);
        persistRuntimeState(persistenceServer);
    }

    private void clearPlayerAffinitiesForNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        List<String> playerUuids = playerClusterAffinities.entrySet().stream()
                .filter(entry -> nodeId.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        for (String playerUuid : playerUuids) {
            playerClusterAffinities.remove(playerUuid);
            forceHostRoutePlayerUuids.add(playerUuid);
        }
    }

    private void sendPlayerToHost(MinecraftServer sourceServer,
            ServerPlayer player,
            String sourceNodeId,
            boolean useProxySwitch,
            String proxyTarget,
            String targetHost,
            int targetPort) {
        player.sendSystemMessage(Component.literal("Cluster '" + sourceNodeId + "' is stopping. Sending you to host.")
                .withStyle(ChatFormatting.YELLOW));
        if (useProxySwitch) {
            try {
                player.connection.send(new ClientboundCustomPayloadPacket(new ProxyConnectPayload(proxyTarget)));
            } catch (Exception exception) {
                MultiFabricServer.LOGGER.warn("Failed to proxy-switch player {} from node {} to host",
                        player.getScoreboardName(),
                        sourceNodeId,
                        exception);
            }
            return;
        }

        String transferCommand = "transfer " + targetHost + " " + targetPort;
        try {
            int result = sourceServer.getCommands().getDispatcher().execute(transferCommand,
                    player.createCommandSourceStack());
            if (result <= 0) {
                MultiFabricServer.LOGGER.warn("Host transfer command did not execute for player {} from node {}",
                        player.getScoreboardName(),
                        sourceNodeId);
            }
        } catch (Exception exception) {
            MultiFabricServer.LOGGER.warn("Failed to transfer player {} from node {} to host",
                    player.getScoreboardName(),
                    sourceNodeId,
                    exception);
        }
    }

    private static void disconnectPlayersFromHostServer(MinecraftServer hostServer, Component disconnectReason) {
        if (hostServer == null || hostServer.isStopped()) {
            return;
        }

        Component reason = Objects.requireNonNull(disconnectReason, "disconnectReason");
        for (ServerPlayer player : List.copyOf(hostServer.getPlayerList().getPlayers())) {
            if (player == null || player.connection == null) {
                continue;
            }
            player.connection.disconnect(reason);
        }
    }

    private void disconnectPlayersFromRunningNodes(Component disconnectReason) {
        Component reason = Objects.requireNonNull(disconnectReason, "disconnectReason");

        for (MinecraftServer runtimeServer : runtimeServerInstances.values()) {
            if (runtimeServer == null || runtimeServer.isStopped()) {
                continue;
            }

            runtimeServer.execute(() -> {
                for (ServerPlayer player : List.copyOf(runtimeServer.getPlayerList().getPlayers())) {
                    if (player == null || player.connection == null) {
                        continue;
                    }
                    player.connection.disconnect(reason);
                }
            });
        }
    }

    private Set<String> currentlyRunningNodeIds() {
        Set<String> running = new LinkedHashSet<>();
        for (ClusterNodeRuntime runtime : runtimes.values()) {
            if (runtime.state() == ClusterNodeState.RUNNING) {
                running.add(runtime.definition().id());
            }
        }
        return running;
    }

    private String nodeIdForServer(MinecraftServer server) {
        if (server == ownerServer) {
            return null;
        }

        for (Map.Entry<String, MinecraftServer> entry : runtimeServerInstances.entrySet()) {
            if (entry.getValue() == server) {
                return entry.getKey();
            }
        }

        int port = server.getPort();
        for (ClusterNodeRuntime runtime : runtimes.values()) {
            if (runtime.resolvedListenPort() == port) {
                return runtime.definition().id();
            }
        }
        return null;
    }

    private ServerPlayer findPlayerAcrossKnownServers(UUID playerUuid, MinecraftServer effectiveHostServer) {
        if (playerUuid == null) {
            return null;
        }

        ServerPlayer fromHost = findPlayerOnServer(playerUuid, effectiveHostServer);
        if (fromHost != null) {
            return fromHost;
        }

        for (MinecraftServer runtimeServer : runtimeServerInstances.values()) {
            ServerPlayer runtimePlayer = findPlayerOnServer(playerUuid, runtimeServer);
            if (runtimePlayer != null) {
                return runtimePlayer;
            }
        }

        return null;
    }

    private static ServerPlayer findPlayerOnServer(UUID playerUuid, MinecraftServer server) {
        if (playerUuid == null || server == null || server.getPlayerList() == null) {
            return null;
        }

        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (player == null) {
                continue;
            }

            if (playerUuid.equals(player.getUUID())) {
                return player;
            }
        }

        return null;
    }

    private static void addPlayersFromServer(Map<UUID, ServerPlayer> sink, MinecraftServer server) {
        if (sink == null || server == null || server.getPlayerList() == null) {
            return;
        }

        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (player == null) {
                continue;
            }

            sink.putIfAbsent(player.getUUID(), player);
        }
    }

    private Set<String> hostDynmapWorldNames(MinecraftServer hostServer) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add("world");
        names.add("dim-1");
        names.add("dim1");

        if (hostServer == null) {
            return names;
        }

        try {
            String levelName = hostServer.getWorldData().getLevelName();
            if (levelName != null && !levelName.isBlank()) {
                names.add(levelName.toLowerCase(Locale.ROOT));
            }
        } catch (Exception ignored) {
        }

        return names;
    }

    private static String dynmapClusterIdFromWorldName(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return "";
        }

        if (worldName.endsWith("_the_end")) {
            return worldName.substring(0, worldName.length() - "_the_end".length());
        }
        if (worldName.endsWith("_nether")) {
            return worldName.substring(0, worldName.length() - "_nether".length());
        }

        int customDimensionSeparator = worldName.indexOf("__");
        if (customDimensionSeparator > 0) {
            return worldName.substring(0, customDimensionSeparator);
        }

        return worldName;
    }

    private static boolean looksLikeClusterDynmapWorld(String worldName, String worldTitle) {
        if (worldName.endsWith("_nether") || worldName.endsWith("_the_end") || worldName.contains("__")) {
            return true;
        }

        if (worldTitle == null || worldTitle.isBlank()) {
            return false;
        }

        String normalizedTitle = worldTitle.toLowerCase(Locale.ROOT);
        return normalizedTitle.equals(worldName + " (overworld)")
                || normalizedTitle.equals(worldName + " (nether)")
                || normalizedTitle.equals(worldName + " (end)")
                || normalizedTitle.startsWith(worldName + " (minecraft:");
    }

    private void handlePlayerDisconnect(MinecraftServer server, String playerUuid, String playerName, String nodeId) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return;
        }

        if (forceHostRoutePlayerUuids.contains(playerUuid)) {
            MultiFabricServer.LOGGER.info(
                    "Skipping affinity save for player {} due pending explicit host travel",
                    playerName);
            return;
        }

        if (nodeId == null) {
            forceHostRoutePlayerUuids.remove(playerUuid);
            return;
        }

        if (stoppingNodeIds.contains(nodeId)) {
            playerClusterAffinities.remove(playerUuid);
            forceHostRoutePlayerUuids.add(playerUuid);
            persistRuntimeState(server);
            MultiFabricServer.LOGGER.info(
                    "Skipping affinity save for player {} because node {} is stopping",
                    playerName,
                    nodeId);
            return;
        }

        ClusterNodeRuntime runtime = runtimes.get(nodeId);
        if (runtime == null || !runtime.definition().enabled()) {
            playerClusterAffinities.remove(playerUuid);
            forceHostRoutePlayerUuids.add(playerUuid);
            persistRuntimeState(server);
            MultiFabricServer.LOGGER.info(
                    "Skipping affinity save for player {} because node {} is no longer available",
                    playerName,
                    nodeId);
            return;
        }

        rememberPlayerCluster(server, playerUuid, nodeId);
    }

    private synchronized void markPendingInternalTravel(String playerUuid, String targetNodeId, String sourceNodeId) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return;
        }

        pruneExpiredInternalTravelMarkers();
        long expiresAt = System.currentTimeMillis() + INTERNAL_TRAVEL_MARKER_TTL_MILLIS;
        pendingInternalTravelMarkersByPlayer.put(
                playerUuid,
                new InternalTravelMarker(clusterLabelForNodeId(targetNodeId), expiresAt));
        pendingExternalLifecycleSuppressionsByPlayer.put(
                playerUuid,
                new ExternalLifecycleSuppressionMarker(EXTERNAL_TRAVEL_LIFECYCLE_SUPPRESSION_EVENTS, expiresAt));
        pendingDynmapLogoutSuppressionsByPlayer.put(
                playerUuid,
                new DynmapLogoutSuppressionMarker(clusterLabelForNodeId(sourceNodeId), expiresAt));
    }

    public synchronized boolean consumeExternalJoinLeaveSuppression(String playerUuid) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return false;
        }

        pruneExpiredInternalTravelMarkers();

        ExternalLifecycleSuppressionMarker marker = pendingExternalLifecycleSuppressionsByPlayer.get(playerUuid);
        if (marker == null || marker.remainingEvents() <= 0) {
            return false;
        }

        int remainingEvents = marker.remainingEvents() - 1;
        if (remainingEvents <= 0) {
            pendingExternalLifecycleSuppressionsByPlayer.remove(playerUuid);
        } else {
            pendingExternalLifecycleSuppressionsByPlayer.put(
                    playerUuid,
                    new ExternalLifecycleSuppressionMarker(remainingEvents, marker.expiresAtMillis()));
        }

        return true;
    }

    public synchronized boolean consumeDynmapLogoutSuppression(ServerPlayer player) {
        if (player == null) {
            return false;
        }

        MinecraftServer server = player.level().getServer();
        String currentNodeId = nodeIdForServer(server);
        return consumeDynmapLogoutSuppression(player.getUUID().toString(), currentNodeId);
    }

    private synchronized boolean consumeDynmapLogoutSuppression(String playerUuid, String currentNodeId) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return false;
        }

        pruneExpiredInternalTravelMarkers();

        DynmapLogoutSuppressionMarker marker = pendingDynmapLogoutSuppressionsByPlayer.get(playerUuid);
        if (marker == null) {
            return false;
        }

        String currentClusterLabel = clusterLabelForNodeId(currentNodeId);
        if (!marker.expectedSourceClusterLabel().equals(currentClusterLabel)) {
            return false;
        }

        pendingDynmapLogoutSuppressionsByPlayer.remove(playerUuid);
        return true;
    }

    private synchronized boolean hasPendingInternalTravelMarker(String playerUuid) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return false;
        }

        pruneExpiredInternalTravelMarkers();
        return pendingInternalTravelMarkersByPlayer.containsKey(playerUuid);
    }

    private synchronized boolean consumePendingInternalTravelMarker(String playerUuid, String currentNodeId) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return false;
        }

        pruneExpiredInternalTravelMarkers();

        InternalTravelMarker marker = pendingInternalTravelMarkersByPlayer.get(playerUuid);
        if (marker == null) {
            return false;
        }

        String currentClusterLabel = clusterLabelForNodeId(currentNodeId);
        if (!marker.expectedClusterLabel().equals(currentClusterLabel)) {
            return false;
        }

        pendingInternalTravelMarkersByPlayer.remove(playerUuid);
        return true;
    }

    private synchronized void clearPendingInternalTravelMarker(String playerUuid) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return;
        }

        pendingInternalTravelMarkersByPlayer.remove(playerUuid);
        pendingExternalLifecycleSuppressionsByPlayer.remove(playerUuid);
        pendingDynmapLogoutSuppressionsByPlayer.remove(playerUuid);
    }

    private synchronized void pruneExpiredInternalTravelMarkers() {
        long now = System.currentTimeMillis();
        pendingInternalTravelMarkersByPlayer.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        pendingExternalLifecycleSuppressionsByPlayer.entrySet()
                .removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        pendingDynmapLogoutSuppressionsByPlayer.entrySet()
                .removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    private void persistRuntimeState(MinecraftServer fallbackServer) {
        MinecraftServer persistenceServer = ownerServer != null ? ownerServer : fallbackServer;
        if (persistenceServer == null) {
            return;
        }

        activeNodeIds.retainAll(runtimes.keySet());
        playerClusterAffinities.entrySet().removeIf(entry -> !runtimes.containsKey(entry.getValue()));

        ClusterRuntimeStateStore.save(persistenceServer, activeNodeIds, playerClusterAffinities);
    }

    private static final class ListSnapshot {
        private ListSnapshot() {
        }

        private static <T> java.util.List<T> copy(Collection<T> values) {
            return java.util.List.copyOf(values);
        }
    }

    public record GatewayRoute(String backendHost, int backendPort, String nodeId) {
    }

    private record InternalTravelMarker(String expectedClusterLabel, long expiresAtMillis) {
    }

    private record ExternalLifecycleSuppressionMarker(int remainingEvents, long expiresAtMillis) {
    }

    private record DynmapLogoutSuppressionMarker(String expectedSourceClusterLabel, long expiresAtMillis) {
    }

    private record ReadyProxyTarget(String host, int port) {
    }
}
