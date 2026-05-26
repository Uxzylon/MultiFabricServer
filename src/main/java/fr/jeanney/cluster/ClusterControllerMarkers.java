package fr.jeanney.cluster;

import fr.jeanney.MultiFabricServer;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

abstract class ClusterControllerMarkers extends ClusterControllerBase {
    protected void handlePlayerDisconnect(MinecraftServer server, String playerUuid, String playerName, String nodeId) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return;
        }

        if (forceHostRoutePlayerUuids.contains(playerUuid)) {
            MultiFabricServer.LOGGER.info("Skipping affinity save for player {} "
                    + "due pending explicit host travel",
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
                    "Skipping affinity save for player {} because node {} is stopping", playerName, nodeId);
            return;
        }

        ClusterNodeRuntime runtime = runtimes.get(nodeId);
        if (runtime == null || !runtime.definition().enabled()) {
            playerClusterAffinities.remove(playerUuid);
            forceHostRoutePlayerUuids.add(playerUuid);
            persistRuntimeState(server);
            MultiFabricServer.LOGGER.info("Skipping affinity save for player {} "
                    + "because node {} is no longer available",
                    playerName, nodeId);
            return;
        }

        rememberPlayerCluster(server, playerUuid, nodeId);
    }

    protected synchronized void markPendingInternalTravel(String playerUuid, String targetNodeId, String sourceNodeId) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return;
        }

        pruneExpiredInternalTravelMarkers();
        long expiresAt = System.currentTimeMillis() + INTERNAL_TRAVEL_MARKER_TTL_MILLIS;
        pendingInternalTravelMarkersByPlayer.put(
                playerUuid, new InternalTravelMarker(clusterLabelForNodeId(targetNodeId), expiresAt));
        pendingExternalLifecycleSuppressionsByPlayer.put(playerUuid,
                new ExternalLifecycleSuppressionMarker(EXTERNAL_TRAVEL_LIFECYCLE_SUPPRESSION_EVENTS, expiresAt));
        pendingDynmapLogoutSuppressionsByPlayer.put(
                playerUuid, new DynmapLogoutSuppressionMarker(clusterLabelForNodeId(sourceNodeId), expiresAt));
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
                    playerUuid, new ExternalLifecycleSuppressionMarker(remainingEvents, marker.expiresAtMillis()));
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

    protected synchronized boolean consumeDynmapLogoutSuppression(String playerUuid, String currentNodeId) {
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

    protected synchronized boolean hasPendingInternalTravelMarker(String playerUuid) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return false;
        }

        pruneExpiredInternalTravelMarkers();
        return pendingInternalTravelMarkersByPlayer.containsKey(playerUuid);
    }

    protected synchronized boolean consumePendingInternalTravelMarker(String playerUuid, String currentNodeId) {
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

    protected synchronized void clearPendingInternalTravelMarker(String playerUuid) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return;
        }

        pendingInternalTravelMarkersByPlayer.remove(playerUuid);
        pendingExternalLifecycleSuppressionsByPlayer.remove(playerUuid);
        pendingDynmapLogoutSuppressionsByPlayer.remove(playerUuid);
    }

    protected synchronized void pruneExpiredInternalTravelMarkers() {
        long now = System.currentTimeMillis();
        pendingInternalTravelMarkersByPlayer.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        pendingExternalLifecycleSuppressionsByPlayer.entrySet().removeIf(
                entry -> entry.getValue().expiresAtMillis() <= now);
        pendingDynmapLogoutSuppressionsByPlayer.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    protected void markPlayerForHostRoute(MinecraftServer persistenceServer, String playerUuid, String sourceNodeId) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return;
        }

        playerClusterAffinities.remove(playerUuid);
        forceHostRoutePlayerUuids.add(playerUuid);
        markPendingInternalTravel(playerUuid, null, sourceNodeId);
        persistRuntimeState(persistenceServer);
    }

    protected void clearPlayerAffinitiesForNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        List<String> playerUuids = playerClusterAffinities.entrySet()
                .stream()
                .filter(entry -> nodeId.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        for (String playerUuid : playerUuids) {
            playerClusterAffinities.remove(playerUuid);
            forceHostRoutePlayerUuids.add(playerUuid);
        }
    }
}
