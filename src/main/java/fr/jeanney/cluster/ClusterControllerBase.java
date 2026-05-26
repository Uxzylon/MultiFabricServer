package fr.jeanney.cluster;

import fr.jeanney.MultiFabricServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.storage.LevelResource;

abstract class ClusterControllerBase {
    private static final Pattern NODE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]+");

    protected static final String LOOPBACK_HOST = "127.0.0.1";
    protected static final long SEAMLESS_NODE_READY_TIMEOUT_MILLIS = 30000L;
    protected static final long SEAMLESS_NODE_READY_POLL_MILLIS = 50L;
    protected static final long PROXY_REGISTRATION_SETTLE_MILLIS = 150L;
    protected static final long NODE_EVACUATION_SETTLE_MILLIS = 1000L;
    protected static final long INTERNAL_TRAVEL_MARKER_TTL_MILLIS = TimeUnit.SECONDS.toMillis(20);
    protected static final long STOPPING_NODE_MARKER_TTL_MILLIS = NODE_EVACUATION_SETTLE_MILLIS
            + INTERNAL_TRAVEL_MARKER_TTL_MILLIS;
    protected static final int EXTERNAL_TRAVEL_LIFECYCLE_SUPPRESSION_EVENTS = 2;
    protected static final Permission OP_FEEDBACK_PERMISSION = new Permission.HasCommandLevel(
            PermissionLevel.GAMEMASTERS);

    protected final Map<String, ClusterNodeRuntime> runtimes = new LinkedHashMap<>();
    protected final Set<String> activeNodeIds = new LinkedHashSet<>();
    protected final Map<String, String> playerClusterAffinities = new LinkedHashMap<>();
    protected final Set<String> forceHostRoutePlayerUuids = new LinkedHashSet<>();
    protected final Map<String, ClusterPlayerPresence> sharedPlayerPresences = new LinkedHashMap<>();
    protected final Map<String, MinecraftServer> runtimeServerInstances = new LinkedHashMap<>();
    protected final Map<String, Long> emptyNodeSinceMillisByNodeId = new LinkedHashMap<>();
    protected final Set<String> stoppingNodeIds = new LinkedHashSet<>();
    protected final Map<String, Map<String, String>> viewerRemoteTabEntrySignatures = new LinkedHashMap<>();
    protected final Map<String, InternalTravelMarker> pendingInternalTravelMarkersByPlayer = new LinkedHashMap<>();
    protected final Map<String, ExternalLifecycleSuppressionMarker> pendingExternalLifecycleSuppressionsByPlayer = new LinkedHashMap<>();
    protected final Map<String, DynmapLogoutSuppressionMarker> pendingDynmapLogoutSuppressionsByPlayer = new LinkedHashMap<>();
    protected final Set<String> pendingRuntimeDirectoryDeletes = new LinkedHashSet<>();

    protected boolean initialized;
    protected Path clusterRuntimeRoot;
    protected volatile MinecraftServer ownerServer;
    protected volatile boolean stopping;
    protected long nodeIdleStopMillis = TimeUnit.SECONDS.toMillis(ClusterConfig.DEFAULT_NODE_IDLE_STOP_SECONDS);
    protected ClusterConfig configSnapshot = ClusterConfig.defaultConfig();
    protected long nextNodeIdleCheckMillis;

    protected static final int TAB_REFRESH_DEFERRED_EXECUTIONS = 2;

    protected static int onlinePlayersOn(MinecraftServer server) {
        if (server == null) {
            return 0;
        }
        return server.getPlayerList().getPlayers().size();
    }

    protected static String describeServer(MinecraftServer server) {
        return ClusterServerIdentity.describe(server);
    }

    protected MinecraftServer resolveControlServer(MinecraftServer fallbackServer) {
        if (ownerServer != null) {
            return ownerServer;
        }
        return fallbackServer;
    }

    protected static boolean isClusterChildServer(MinecraftServer server) {
        if (server == null) {
            return false;
        }

        Path rootPath;
        try {
            rootPath = server.getWorldPath(LevelResource.ROOT);
        } catch (RuntimeException ignored) {
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

    protected Path runtimeRoot(MinecraftServer server) {
        if (clusterRuntimeRoot != null) {
            return clusterRuntimeRoot;
        }
        return server.getWorldPath(LevelResource.ROOT).resolve("cluster-runtime");
    }

    protected boolean trackRunningNode(String nodeId) {
        ClusterNodeRuntime runtime = runtimes.get(nodeId);
        if (runtime == null || runtime.state() != ClusterNodeState.RUNNING) {
            return false;
        }

        boolean changed = activeNodeIds.add(nodeId);
        emptyNodeSinceMillisByNodeId.putIfAbsent(nodeId, System.currentTimeMillis());
        return changed;
    }

    protected static String normalizeNodeName(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 64 || !NODE_NAME_PATTERN.matcher(normalized).matches()) {
            return null;
        }

        return normalized.toLowerCase(Locale.ROOT);
    }

    protected static String clusterLabelForNodeId(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return "host";
        }
        return nodeId;
    }

    protected static boolean isVanillaJoinLeaveGameMessage(Component message) {
        if (message == null) {
            return false;
        }

        if (!(message.getContents() instanceof TranslatableContents translatableContents)) {
            return false;
        }

        String key = translatableContents.getKey();
        return key.startsWith("multiplayer.player.joined") || "multiplayer.player.left".equals(key);
    }

    protected static boolean isCrossClusterAdvancementGameMessage(Component message) {
        if (message == null) {
            return false;
        }

        if (!(message.getContents() instanceof TranslatableContents translatableContents)) {
            return false;
        }

        return translatableContents.getKey().startsWith("chat.type.advancement.");
    }

    protected static String normalizeVirtualHost(String host) {
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

    protected boolean shouldUseProxySwitch(MinecraftServer server) {
        return ProxyForwardingConfig.isFabricProxyLiteConfigured(server);
    }

    protected static void logProxyTargetRequirement(ServerPlayer player, String targetNodeId, String proxyServerName) {
        String playerName = player == null ? "unknown" : player.getScoreboardName();
        MultiFabricServer.LOGGER.info("Requesting seamless proxy switch for player {} to cluster '{}' "
                + "through proxy server name '{}'",
                playerName, targetNodeId, proxyServerName);
    }

    protected static boolean waitForEndpoint(String host, int port, long timeoutMillis, long pollMillis) {
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

    protected static boolean isEndpointReachable(String host, int port, int timeoutMillis) {
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

    protected static String normalizeProxyServerName(String configuredName, String fallbackName) {
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

    protected static String resolveProxyServerName(ClusterNodeDefinition definition) {
        if (definition == null) {
            return null;
        }

        return definition.id();
    }

    public synchronized String runtimeTransferHost() {
        return LOOPBACK_HOST;
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

    protected static void disconnectPlayersFromHostServer(MinecraftServer hostServer,
            @NonNull Component disconnectReason) {
        if (hostServer == null || hostServer.isStopped()) {
            return;
        }

        for (ServerPlayer player : List.copyOf(hostServer.getPlayerList().getPlayers())) {
            player.connection.disconnect(disconnectReason);
        }
    }

    protected void disconnectPlayersFromRunningNodes(@NonNull Component disconnectReason) {
        for (MinecraftServer runtimeServer : runtimeServerInstances.values()) {
            if (runtimeServer.isStopped()) {
                continue;
            }

            runtimeServer.execute(() -> {
                for (ServerPlayer player : List.copyOf(runtimeServer.getPlayerList().getPlayers())) {
                    player.connection.disconnect(disconnectReason);
                }
            });
        }
    }

    protected Set<String> currentlyRunningNodeIds() {
        Set<String> running = new LinkedHashSet<>();
        for (ClusterNodeRuntime runtime : runtimes.values()) {
            if (runtime.state() == ClusterNodeState.RUNNING) {
                running.add(runtime.definition().id());
            }
        }
        return running;
    }

    protected String nodeIdForServer(MinecraftServer server) {
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

    protected static void addPlayersFromServer(Map<UUID, ServerPlayer> sink, MinecraftServer server) {
        if (sink == null || server == null) {
            return;
        }

        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            sink.putIfAbsent(player.getUUID(), player);
        }
    }

    protected Set<String> hostDynmapWorldNames(MinecraftServer hostServer) {
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
        } catch (RuntimeException ignored) {
        }

        return names;
    }

    protected static String dynmapClusterIdFromWorldName(String worldName) {
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

    protected static boolean looksLikeClusterDynmapWorld(String worldName, String worldTitle) {
        if (worldName.endsWith("_nether") || worldName.endsWith("_the_end") || worldName.contains("__")) {
            return true;
        }

        if (worldTitle == null || worldTitle.isBlank()) {
            return false;
        }

        String normalizedTitle = worldTitle.toLowerCase(Locale.ROOT);
        return normalizedTitle.equals(worldName + " (overworld)") || normalizedTitle.equals(worldName + " (nether)")
                || normalizedTitle.equals(worldName + " (end)")
                || normalizedTitle.startsWith(worldName + " (minecraft:");
    }

    protected void persistRuntimeState(MinecraftServer fallbackServer) {
        MinecraftServer persistenceServer = ownerServer != null ? ownerServer : fallbackServer;
        if (persistenceServer == null) {
            return;
        }

        activeNodeIds.retainAll(runtimes.keySet());
        playerClusterAffinities.entrySet().removeIf(entry -> !runtimes.containsKey(entry.getValue()));

        ClusterRuntimeStateStore.save(persistenceServer, activeNodeIds, playerClusterAffinities);
    }

    protected record InternalTravelMarker(String expectedClusterLabel, long expiresAtMillis) {
    }

    protected record ExternalLifecycleSuppressionMarker(int remainingEvents, long expiresAtMillis) {
    }

    protected record DynmapLogoutSuppressionMarker(String expectedSourceClusterLabel, long expiresAtMillis) {
    }

    protected record ReadyProxyTarget(String host, int port) {
    }
}
