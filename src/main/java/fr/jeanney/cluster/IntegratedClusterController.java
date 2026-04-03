package fr.jeanney.cluster;

import fr.jeanney.MultiFabricServer;
import fr.jeanney.cluster.network.ProxyConnectPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
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

    private static final long SEAMLESS_NODE_READY_TIMEOUT_MILLIS = 5000L;
    private static final long SEAMLESS_NODE_READY_POLL_MILLIS = 50L;

    private final Map<String, ClusterNodeRuntime> runtimes = new LinkedHashMap<>();
    private final Set<String> activeNodeIds = new LinkedHashSet<>();
    private final Map<String, String> playerClusterAffinities = new LinkedHashMap<>();
    private final Set<String> forceHostRoutePlayerUuids = new LinkedHashSet<>();
    private final Map<String, ClusterPlayerPresence> sharedPlayerPresences = new LinkedHashMap<>();
    private final Map<String, MinecraftServer> runtimeServerInstances = new LinkedHashMap<>();

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

            activeNodeIds.clear();
            activeNodeIds.addAll(currentlyRunningNodeIds());
            persistRuntimeState(server);

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
        forceHostRoutePlayerUuids.remove(playerUuid);

        String nodeIdForServer = nodeIdForServer(server);
        if (nodeIdForServer != null) {
            runtimeServerInstances.put(nodeIdForServer, server);
        }
        sharedPlayerPresences.put(playerUuid,
                new ClusterPlayerPresence(playerUuid, player.getScoreboardName(), nodeIdForServer));

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

        if (runtime.state() != ClusterNodeState.RUNNING) {
            runtime.start(server, clusterRuntimeRoot);
            if (runtime.state() == ClusterNodeState.RUNNING) {
                activeNodeIds.add(targetNodeId);
                persistRuntimeState(server);
            }
        }

        if (runtime.state() != ClusterNodeState.RUNNING) {
            MultiFabricServer.LOGGER.warn("Failed to restore player {} to cluster {} because the node is not running",
                    player.getScoreboardName(),
                    targetNodeId);
            return;
        }

        String transferHost = gatewayEnabled ? hostTransferHost : runtime.definition().transferHost();
        int transferPort = gatewayEnabled ? gatewayBindPort : runtime.definition().transferPort();
        String proxyServerName = resolveProxyServerName(runtime.definition());
        server.execute(() -> {
            if (seamlessProxySwitchEnabled) {
                if (!executeSeamlessProxyTravel(server, player, targetNodeId, proxyServerName, false)) {
                    MultiFabricServer.LOGGER.warn(
                            "Seamless proxy restore failed for player {} to node {}",
                            player.getScoreboardName(),
                            targetNodeId);
                }
                return;
            }
            executeTransfer(server, player, transferHost, transferPort, targetNodeId);
        });
    }

    public synchronized void onPlayerDisconnect(MinecraftServer server, ServerPlayer player) {
        handlePlayerDisconnect(server, player.getUUID().toString(), player.getScoreboardName(),
                nodeIdForServer(server));
        sharedPlayerPresences.remove(player.getUUID().toString());
    }

    public synchronized void onPlayerDisconnectFromNodeForTesting(MinecraftServer server, String playerUuid,
            String nodeId) {
        handlePlayerDisconnect(server, playerUuid, playerUuid, nodeId);
        sharedPlayerPresences.remove(playerUuid);
    }

    public synchronized int sharedOnlinePlayersCount() {
        return sharedPlayerPresences.size();
    }

    public synchronized Collection<ClusterPlayerPresence> sharedOnlinePlayers() {
        return ListSnapshot.copy(sharedPlayerPresences.values());
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

            String nodeId = nodeIdForServer(sourceServer);
            sourceCluster = (nodeId == null || nodeId.isBlank()) ? "host" : nodeId;
            renderedLine = "[" + sourceCluster + "] <" + sender.getScoreboardName() + "> " + chatText;
            targets = relayTargets(sourceServer);
        }

        if (targets.isEmpty()) {
            return;
        }

        Component relayMessage = Component.literal(renderedLine);

        for (MinecraftServer target : targets) {
            if (target == null || target.isStopped()) {
                continue;
            }
            target.execute(() -> target.getPlayerList().broadcastSystemMessage(relayMessage, false));
        }
    }

    private Optional<GatewayRoute> gatewayRouteForNode(String nodeId) {
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
            runtime.start(server, clusterRuntimeRoot);
            if (runtime.state() == ClusterNodeState.RUNNING) {
                activeNodeIds.add(targetNodeId);
                persistRuntimeState(server);
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
            player.connection
                    .send(new ClientboundCustomPayloadPacket(new ProxyConnectPayload(effectiveProxyServerName)));
            if (!hostTarget) {
                rememberPlayerCluster(server, player, targetNodeId);
            }
            return true;
        } catch (Exception exception) {
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
        try {
            int result = server.getCommands().getDispatcher().execute(transferCommand,
                    player.createCommandSourceStack());
            if (result <= 0) {
                MultiFabricServer.LOGGER.warn("Transfer command did not execute for player {} to {}:{}",
                        player.getScoreboardName(),
                        host,
                        port);
            } else {
                rememberPlayerCluster(server, player, targetNodeId);
            }
        } catch (Exception exception) {
            MultiFabricServer.LOGGER.error("Failed to transfer player {} to cluster {} via {}:{}",
                    player.getScoreboardName(),
                    targetNodeId,
                    host,
                    port,
                    exception);
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
}
