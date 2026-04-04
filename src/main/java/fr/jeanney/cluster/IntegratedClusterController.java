package fr.jeanney.cluster;

import com.mojang.authlib.GameProfile;
import fr.jeanney.MultiFabricServer;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class IntegratedClusterController {

    private static final long SEAMLESS_NODE_READY_TIMEOUT_MILLIS = 5000L;
    private static final long SEAMLESS_NODE_READY_POLL_MILLIS = 50L;
    private static final long INTERNAL_TRAVEL_MARKER_TTL_MILLIS = TimeUnit.SECONDS.toMillis(20);
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
    private final Map<String, Map<String, String>> viewerRemoteTabEntrySignatures = new LinkedHashMap<>();
    private final Map<String, InternalTravelMarker> pendingInternalTravelMarkersByPlayer = new LinkedHashMap<>();
    private final Map<String, ExternalLifecycleSuppressionMarker> pendingExternalLifecycleSuppressionsByPlayer = new LinkedHashMap<>();

    private boolean initialized;
    private boolean configEnabled;
    private Path clusterRuntimeRoot;
    private volatile MinecraftServer ownerServer;
    private volatile boolean stopping;
    private boolean gatewayEnabled;
    private String gatewayBindHost = "0.0.0.0";
    private int gatewayBindPort = 25565;
    private String hostTransferHost = "127.0.0.1";
    private int hostTransferPort = 25565;
    private boolean seamlessProxySwitchEnabled;
    private String proxyHostServerName = "host";
    private ClusterConfig configSnapshot = ClusterConfig.defaultConfig();

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
        }
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
                    "[cluster-stop-debug] onServerStopping server={} owner={} initialized={} configEnabled={} nodes={} stopping={} thread={}",
                    describeServer(server),
                    describeServer(ownerServer),
                    initialized,
                    configEnabled,
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
            viewerRemoteTabEntrySignatures.clear();
            pendingInternalTravelMarkersByPlayer.clear();
            pendingExternalLifecycleSuppressionsByPlayer.clear();
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
                    runtime.stop();
                }
            }

            initialized = false;
            configEnabled = false;
            stopping = false;
            sharedPlayerPresences.clear();
            runtimeServerInstances.clear();
            viewerRemoteTabEntrySignatures.clear();
            pendingInternalTravelMarkersByPlayer.clear();
            pendingExternalLifecycleSuppressionsByPlayer.clear();
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
            for (ClusterNodeRuntime runtime : runtimes.values()) {
                runtime.stop();
            }
        }

        runtimeServerInstances.clear();
        pendingInternalTravelMarkersByPlayer.clear();
        pendingExternalLifecycleSuppressionsByPlayer.clear();

        ClusterConfig config = ClusterConfigLoader.load(server);
        configEnabled = config.enabled();
        gatewayEnabled = config.gatewayEnabled();
        gatewayBindHost = config.gatewayBindHost();
        gatewayBindPort = config.gatewayBindPort();
        hostTransferHost = config.hostTransferHost();
        hostTransferPort = config.hostTransferPort();
        seamlessProxySwitchEnabled = config.seamlessProxySwitchEnabled();
        proxyHostServerName = config.proxyHostServerName();
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
            return nodeId != null && !nodeId.isBlank() && !runtimes.containsKey(nodeId);
        });
        activeNodeIds.clear();

        initialized = true;

        MultiFabricServer.LOGGER.info(
                "Integrated cluster config reloaded. enabled={}, nodes={} (lazy startup mode)",
                configEnabled,
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

        if (!configEnabled) {
            return false;
        }

        ClusterNodeRuntime runtime = runtimes.get(nodeId);
        if (runtime == null) {
            return false;
        }
        if (!runtime.definition().enabled()) {
            return false;
        }

        Path runtimeRoot = clusterRuntimeRoot != null
                ? clusterRuntimeRoot
                : effectiveHostServer.getWorldPath(LevelResource.ROOT).resolve("cluster-runtime");

        runtime.start(effectiveHostServer, runtimeRoot);
        if (runtime.state() == ClusterNodeState.RUNNING) {
            activeNodeIds.add(nodeId);
            persistRuntimeState(effectiveHostServer);
        }
        return true;
    }

    public synchronized boolean stopNode(String nodeId) {
        ClusterNodeRuntime runtime = runtimes.get(nodeId);
        if (runtime == null) {
            return false;
        }
        runtime.stop();
        activeNodeIds.remove(nodeId);
        runtimeServerInstances.remove(nodeId);
        sharedPlayerPresences.entrySet().removeIf(entry -> nodeId.equals(entry.getValue().nodeId()));
        persistRuntimeState(ownerServer);
        return true;
    }

    public synchronized boolean setClusterEnabled(MinecraftServer server, boolean enabled) {
        MinecraftServer controlServer = resolveControlServer(server);
        if (controlServer == null) {
            return false;
        }

        if (configSnapshot.enabled() == enabled) {
            return true;
        }

        if (!enabled) {
            activeNodeIds.clear();
        }

        ClusterConfig updatedConfig = new ClusterConfig(
                enabled,
                configSnapshot.gatewayEnabled(),
                configSnapshot.gatewayBindHost(),
                configSnapshot.gatewayBindPort(),
                configSnapshot.hostTransferHost(),
                configSnapshot.hostTransferPort(),
                configSnapshot.seamlessProxySwitchEnabled(),
                configSnapshot.proxyHostServerName(),
                configSnapshot.nodes());
        ClusterConfigLoader.save(controlServer, updatedConfig);
        reloadFromDiskInternal(controlServer);
        return configEnabled == enabled;
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
            updatedNodes.add(new ClusterNodeDefinition(
                    node.id(),
                    node.worldName(),
                    node.listenPort(),
                    enabled,
                    node.autoStart(),
                    node.transferHost(),
                    node.transferPort(),
                    node.proxyServerName()));
        }

        if (!found) {
            return false;
        }

        if (!enabled) {
            activeNodeIds.remove(nodeId);
            playerClusterAffinities.entrySet().removeIf(entry -> nodeId.equals(entry.getValue()));
        }

        ClusterConfig updatedConfig = new ClusterConfig(
                configSnapshot.enabled(),
                configSnapshot.gatewayEnabled(),
                configSnapshot.gatewayBindHost(),
                configSnapshot.gatewayBindPort(),
                configSnapshot.hostTransferHost(),
                configSnapshot.hostTransferPort(),
                configSnapshot.seamlessProxySwitchEnabled(),
                configSnapshot.proxyHostServerName(),
                List.copyOf(updatedNodes));
        ClusterConfigLoader.save(controlServer, updatedConfig);
        reloadFromDiskInternal(controlServer);
        return true;
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

        activeNodeIds.remove(nodeId);
        playerClusterAffinities.entrySet().removeIf(entry -> nodeId.equals(entry.getValue()));
        runtimeServerInstances.remove(nodeId);
        sharedPlayerPresences.entrySet().removeIf(entry -> nodeId.equals(entry.getValue().nodeId()));

        ClusterConfig updatedConfig = new ClusterConfig(
                configSnapshot.enabled(),
                configSnapshot.gatewayEnabled(),
                configSnapshot.gatewayBindHost(),
                configSnapshot.gatewayBindPort(),
                configSnapshot.hostTransferHost(),
                configSnapshot.hostTransferPort(),
                configSnapshot.seamlessProxySwitchEnabled(),
                configSnapshot.proxyHostServerName(),
                List.copyOf(updatedNodes));
        ClusterConfigLoader.save(controlServer, updatedConfig);
        reloadFromDiskInternal(controlServer);
        return true;
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
        }
        sharedPlayerPresences.put(playerUuid,
                new ClusterPlayerPresence(playerUuid, playerName, nodeIdForServer));
        refreshSharedTabLists();
        scheduleDeferredSharedTabRefresh(server);

        if (configEnabled && !suppressJoinMessage) {
            broadcastSharedLifecycleMessage(
                    Component.literal(playerName + " joined the game").withStyle(ChatFormatting.YELLOW));
        }

        if (nodeIdForServer != null) {
            rememberPlayerCluster(server, player, nodeIdForServer);
            return;
        }

        if (server != ownerServer || !configEnabled) {
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

        if (seamlessProxySwitchEnabled) {
            if (!requestSeamlessProxyTravel(server, player, targetNodeId)) {
                MultiFabricServer.LOGGER.warn(
                        "Seamless proxy restore failed for player {} to node {}",
                        playerName,
                        targetNodeId);
            }
            return;
        }

        if (runtime.state() != ClusterNodeState.RUNNING) {
            runtime.start(server, clusterRuntimeRoot);
            if (runtime.state() == ClusterNodeState.RUNNING) {
                activeNodeIds.add(targetNodeId);
                persistRuntimeState(server);
            }
        }

        if (runtime.state() != ClusterNodeState.RUNNING) {
            MultiFabricServer.LOGGER.warn("Failed to restore player {} to cluster {} because the node is not running",
                    playerName,
                    targetNodeId);
            return;
        }

        String transferHost = gatewayEnabled ? hostTransferHost : runtime.definition().transferHost();
        int transferPort = gatewayEnabled ? gatewayBindPort : runtime.definition().transferPort();
        server.execute(() -> executeTransfer(server, player, transferHost, transferPort, targetNodeId));
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

        if (configEnabled && !suppressDisconnectMessage) {
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

    public synchronized Collection<ClusterNodeRuntime> allNodes() {
        return ListSnapshot.copy(runtimes.values());
    }

    public synchronized Optional<ClusterNodeRuntime> node(String id) {
        return Optional.ofNullable(runtimes.get(id));
    }

    public synchronized boolean isInitialized() {
        return initialized;
    }

    public synchronized boolean isConfigEnabled() {
        return configEnabled;
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
        return hostTransferHost;
    }

    public synchronized int hostTransferPort() {
        return hostTransferPort;
    }

    public synchronized boolean isGatewayEnabled() {
        return gatewayEnabled;
    }

    public synchronized boolean isSeamlessProxySwitchEnabled() {
        return seamlessProxySwitchEnabled;
    }

    public synchronized String proxyHostServerName() {
        return proxyHostServerName;
    }

    public synchronized boolean allowGameMessage(MinecraftServer server, Component message, boolean overlay) {
        if (overlay || !initialized || !configEnabled) {
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
            if (!initialized || !configEnabled || ownerServer == null) {
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

    public synchronized String gatewayBindHost() {
        return gatewayBindHost;
    }

    public synchronized int gatewayBindPort() {
        return gatewayBindPort;
    }

    public synchronized boolean isOwnerServer(MinecraftServer server) {
        return server == ownerServer;
    }

    public synchronized Optional<GatewayRoute> resolveGatewayRoute(String requestedHost, String playerUuid) {
        if (!gatewayEnabled || ownerServer == null) {
            return Optional.empty();
        }

        String normalizedHost = normalizeVirtualHost(requestedHost);
        String normalizedHostTransfer = normalizeVirtualHost(hostTransferHost);

        if (normalizedHost.equals(normalizedHostTransfer) && playerUuid != null && !playerUuid.isBlank()) {
            if (forceHostRoutePlayerUuids.remove(playerUuid)) {
                return Optional.of(new GatewayRoute("127.0.0.1", ownerServer.getPort(), null));
            }

            String preferredNodeId = playerClusterAffinities.get(playerUuid);
            if (preferredNodeId != null && !preferredNodeId.isBlank()) {
                Optional<GatewayRoute> preferredRoute = gatewayRouteForNode(preferredNodeId);
                if (preferredRoute.isPresent()) {
                    return preferredRoute;
                }
            }
        }

        if (normalizedHost.equals(normalizedHostTransfer)) {
            return Optional.of(new GatewayRoute("127.0.0.1", ownerServer.getPort(), null));
        }

        for (ClusterNodeDefinition definition : configSnapshot.nodes()) {
            String nodeConfiguredHost = normalizeVirtualHost(definition.transferHost());
            String nodeSubdomainHost = definition.id().toLowerCase(Locale.ROOT) + "." + normalizedHostTransfer;
            if (normalizedHost.equals(nodeConfiguredHost) || normalizedHost.equals(nodeSubdomainHost)) {
                return gatewayRouteForNode(definition.id());
            }
        }

        return Optional.of(new GatewayRoute("127.0.0.1", ownerServer.getPort(), null));
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
            if (!initialized || !configEnabled || ownerServer == null) {
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
            if (!initialized || !configEnabled || ownerServer == null) {
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
            if (!initialized || !configEnabled || ownerServer == null) {
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
            runtime.start(ownerServer, clusterRuntimeRoot);
            if (runtime.state() == ClusterNodeState.RUNNING) {
                activeNodeIds.add(nodeId);
                persistRuntimeState(ownerServer);
            }
        }

        if (runtime.state() != ClusterNodeState.RUNNING) {
            return Optional.empty();
        }

        return Optional.of(new GatewayRoute("127.0.0.1", runtime.definition().listenPort(), runtime.definition().id()));
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
        if (!seamlessProxySwitchEnabled || server == null || player == null) {
            return false;
        }

        if (stopping) {
            return false;
        }

        MinecraftServer controlServer = resolveControlServer(server);

        if (gatewayEnabled) {
            MultiFabricServer.LOGGER.warn(
                    "Seamless proxy switch requires an external proxy. Disable gatewayEnabled before using seamlessProxySwitchEnabled.");
            return false;
        }

        if (targetNodeId == null || targetNodeId.isBlank()) {
            String playerUuid = player.getUUID().toString();
            String previousAffinity = playerClusterAffinities.get(playerUuid);

            // Preserve explicit host-travel intent through disconnect so source-node
            // disconnect handling cannot immediately restore the previous cluster affinity.
            preparePlayerHostTravel(server, playerUuid);

            boolean switched = executeSeamlessProxyTravel(server, player, null, proxyHostServerName, true);
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

        if (runtime.state() != ClusterNodeState.RUNNING) {
            MinecraftServer startupHost = controlServer != null ? controlServer : server;
            runtime.start(startupHost, clusterRuntimeRoot);
            if (runtime.state() == ClusterNodeState.RUNNING) {
                activeNodeIds.add(targetNodeId);
                persistRuntimeState(startupHost);
            }
        }

        if (runtime.state() != ClusterNodeState.RUNNING) {
            return false;
        }

        String transferHost = runtime.definition().transferHost();
        int transferPort = runtime.definition().transferPort();
        String proxyServerName = resolveProxyServerName(runtime.definition());

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
            markPendingInternalTravel(player.getUUID().toString(), hostTarget ? null : targetNodeId);
            player.connection
                    .send(new ClientboundCustomPayloadPacket(new ProxyConnectPayload(effectiveProxyServerName)));
            if (!hostTarget) {
                rememberPlayerCluster(server, player, targetNodeId);
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

        String configured = definition.proxyServerName();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        return definition.id();
    }

    private void executeTransfer(MinecraftServer server, ServerPlayer player, String host, int port,
            String targetNodeId) {
        String transferCommand = "transfer " + host + " " + port;
        String playerUuid = player.getUUID().toString();
        markPendingInternalTravel(playerUuid, targetNodeId);
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

            CountDownLatch latch = new CountDownLatch(1);
            runtimeServer.execute(() -> {
                try {
                    for (ServerPlayer player : runtimeServer.getPlayerList().getPlayers()) {
                        if (player == null || player.connection == null) {
                            continue;
                        }
                        player.connection.disconnect(reason);
                    }
                } finally {
                    latch.countDown();
                }
            });

            try {
                latch.await(500L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
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

        int port = server.getPort();
        for (ClusterNodeRuntime runtime : runtimes.values()) {
            if (runtime.definition().listenPort() == port) {
                return runtime.definition().id();
            }
        }
        return null;
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

        rememberPlayerCluster(server, playerUuid, nodeId);
    }

    private synchronized void markPendingInternalTravel(String playerUuid, String targetNodeId) {
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
    }

    private synchronized void pruneExpiredInternalTravelMarkers() {
        long now = System.currentTimeMillis();
        pendingInternalTravelMarkersByPlayer.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        pendingExternalLifecycleSuppressionsByPlayer.entrySet()
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
}
