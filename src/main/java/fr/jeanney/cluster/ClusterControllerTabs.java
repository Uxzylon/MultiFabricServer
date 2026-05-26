package fr.jeanney.cluster;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

abstract class ClusterControllerTabs extends ClusterControllerMarkers {
    public synchronized int sharedOnlinePlayersCount() {
        return sharedPlayerPresences.size();
    }

    public synchronized Collection<ClusterPlayerPresence> sharedOnlinePlayers() {
        return List.copyOf(sharedPlayerPresences.values());
    }

    public synchronized void upsertSharedPlayerPresenceForTesting(String playerUuid, String playerName, String nodeId) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return;
        }

        String effectiveName = (playerName == null || playerName.isBlank()) ? playerUuid : playerName;
        String effectiveNodeId = (nodeId == null || nodeId.isBlank()) ? null : nodeId;
        sharedPlayerPresences.put(playerUuid, new ClusterPlayerPresence(playerUuid, effectiveName, effectiveNodeId));
        refreshSharedTabLists();
    }

    public synchronized void removeSharedPlayerPresenceForTesting(String playerUuid) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return;
        }
        sharedPlayerPresences.remove(playerUuid);
        refreshSharedTabLists();
    }

    public synchronized Collection<ClusterPlayerPresence> remotePlayersForViewerForTesting(
            String viewerUuid, String viewerNodeId) {
        String viewerClusterLabel = clusterLabelForNodeId(viewerNodeId);
        Map<String, ClusterPlayerPresence> desiredRemote = desiredRemotePresences(sharedPlayerPresences, viewerUuid,
                viewerClusterLabel);
        return List.copyOf(desiredRemote.values());
    }

    public static Component buildRemoteTabDisplayName(String playerName, String clusterLabel) {
        String effectiveName = (playerName == null || playerName.isBlank()) ? "unknown" : playerName;
        String effectiveCluster = clusterLabelForNodeId(clusterLabel);
        return Component.literal(effectiveName + " (" + effectiveCluster + ")").withStyle(ChatFormatting.GRAY);
    }

    protected synchronized void refreshSharedTabLists() {
        if (!initialized || ownerServer == null) {
            viewerRemoteTabEntrySignatures.clear();
            return;
        }

        Map<String, ClusterPlayerPresence> presenceSnapshot = new LinkedHashMap<>(sharedPlayerPresences);
        Set<String> activeViewerUuids = new LinkedHashSet<>();

        for (MinecraftServer server : allServersWithPossibleViewers()) {
            if (server.isStopped()) {
                continue;
            }

            String viewerClusterLabel = clusterLabelForNodeId(nodeIdForServer(server));
            for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
                String viewerUuid = viewer.getUUID().toString();
                activeViewerUuids.add(viewerUuid);

                Map<String, ClusterPlayerPresence> desiredRemotePresences = desiredRemotePresences(presenceSnapshot,
                        viewerUuid, viewerClusterLabel);
                Map<String, String> desiredSignatures = remoteSignatures(desiredRemotePresences);

                Map<String, String> currentSignatures = viewerRemoteTabEntrySignatures.computeIfAbsent(viewerUuid,
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
                        // Ignore malformed UUID keys in stale state and just drop them from
                        // tracking.
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

                    createRemoteTabAddPacket(server, desiredEntry.getValue())
                            .ifPresent(packet -> sendRemoteTabPacket(viewer, packet));
                }

                currentSignatures.clear();
                currentSignatures.putAll(desiredSignatures);
            }
        }

        viewerRemoteTabEntrySignatures.keySet().removeIf(
                trackedViewerUuid -> !activeViewerUuids.contains(trackedViewerUuid));
    }

    protected void scheduleDeferredSharedTabRefresh(MinecraftServer schedulerServer) {
        if (schedulerServer == null || schedulerServer.isStopped()) {
            return;
        }

        executeOnServer(schedulerServer,
                deferredSharedTabRefreshTask(schedulerServer, TAB_REFRESH_DEFERRED_EXECUTIONS));
    }

    private Runnable deferredSharedTabRefreshTask(MinecraftServer schedulerServer, int remainingDeferrals) {
        if (remainingDeferrals <= 0) {
            return () -> {
                synchronized (this) {
                    refreshSharedTabLists();
                }
            };
        }

        return () -> executeOnServer(
                schedulerServer,
                deferredSharedTabRefreshTask(schedulerServer, remainingDeferrals - 1));
    }

    @SuppressWarnings("null")
    private static void executeOnServer(MinecraftServer server, Runnable task) {
        server.execute(task);
    }

    protected List<MinecraftServer> allServersWithPossibleViewers() {
        Set<MinecraftServer> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        List<MinecraftServer> servers = new ArrayList<>();

        if (ownerServer != null && unique.add(ownerServer)) {
            servers.add(ownerServer);
        }

        for (MinecraftServer runtimeServer : runtimeServerInstances.values()) {
            if (unique.add(runtimeServer)) {
                servers.add(runtimeServer);
            }
        }

        return servers;
    }

    protected static Map<String, ClusterPlayerPresence> desiredRemotePresences(
            Map<String, ClusterPlayerPresence> presences, String viewerUuid, String viewerClusterLabel) {
        Map<String, ClusterPlayerPresence> desired = new LinkedHashMap<>();
        for (ClusterPlayerPresence presence : presences.values()) {
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

    protected static Map<String, String> remoteSignatures(Map<String, ClusterPlayerPresence> remotePresences) {
        Map<String, String> signatures = new LinkedHashMap<>();
        for (Map.Entry<String, ClusterPlayerPresence> entry : remotePresences.entrySet()) {
            ClusterPlayerPresence presence = entry.getValue();
            signatures.put(entry.getKey(), presence.playerName() + "|" + clusterLabelForNodeId(presence.nodeId()));
        }
        return signatures;
    }

    protected Optional<ClientboundPlayerInfoUpdatePacket> createRemoteTabAddPacket(
            MinecraftServer server, ClusterPlayerPresence presence) {
        return parsePlayerUuid(presence.playerUuid())
                .map(profileId -> buildRemoteTabAddPacket(server, presence, profileId));
    }

    private static Optional<UUID> parsePlayerUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("null")
    private static ClientboundPlayerInfoUpdatePacket buildRemoteTabAddPacket(
            MinecraftServer server, ClusterPlayerPresence presence, UUID profileId) {
        String playerName = presence.playerName();
        if (playerName == null || playerName.isBlank()) {
            String compactUuid = profileId.toString().replace("-", "");
            playerName = compactUuid.substring(0, Math.min(16, compactUuid.length()));
        }

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), server.registryAccess());
        buffer.writeEnumSet(EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
                ClientboundPlayerInfoUpdatePacket.Action.class);
        buffer.writeVarInt(1);
        buffer.writeUUID(profileId);

        GameProfile profile = new GameProfile(profileId, playerName);
        ByteBufCodecs.PLAYER_NAME.encode(buffer, profile.name());
        ByteBufCodecs.GAME_PROFILE_PROPERTIES.encode(buffer, profile.properties());
        buffer.writeBoolean(true);
        FriendlyByteBuf.writeNullable(buffer, buildRemoteTabDisplayName(playerName, presence.clusterLabel()),
                ComponentSerialization.TRUSTED_STREAM_CODEC);

        return ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buffer);
    }

    @SuppressWarnings("null")
    private static void sendRemoteTabPacket(ServerPlayer viewer, ClientboundPlayerInfoUpdatePacket packet) {
        viewer.connection.send(packet);
    }
}
