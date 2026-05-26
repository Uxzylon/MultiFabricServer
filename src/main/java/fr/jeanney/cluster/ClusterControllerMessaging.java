package fr.jeanney.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;

abstract class ClusterControllerMessaging extends ClusterControllerTabs {
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

        MutableComponent relayedMessage = buildCrossClusterMessagePrefix(sourceCluster).append(message.copy());

        for (MinecraftServer target : targets) {
            if (target.isStopped()) {
                continue;
            }

            target.execute(() -> {
                for (ServerPlayer targetPlayer : target.getPlayerList().getPlayers()) {
                    targetPlayer.sendSystemMessage(relayedMessage.copy());
                }
            });
        }
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
            if (target.isStopped()) {
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

        MutableComponent relayedMessage = buildCrossClusterMessagePrefix(sourceCluster).append(message.copy());

        for (MinecraftServer target : targets) {
            if (target.isStopped()) {
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
        MutableComponent relayedMessage = buildCrossClusterMessagePrefix(sourceCluster).append(adminFeedback);

        for (MinecraftServer target : targets) {
            if (target.isStopped()) {
                continue;
            }

            target.execute(() -> {
                for (ServerPlayer targetPlayer : target.getPlayerList().getPlayers()) {
                    if (!hasOpFeedbackPermission(targetPlayer)) {
                        continue;
                    }
                    targetPlayer.sendSystemMessage(relayedMessage.copy());
                }
            });
        }
    }

    protected static boolean hasOpFeedbackPermission(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return hasPermission(player, OP_FEEDBACK_PERMISSION);
    }

    @SuppressWarnings("null")
    private static boolean hasPermission(ServerPlayer player, Permission permission) {
        return player.createCommandSourceStack().permissions().hasPermission(permission);
    }

    protected static MutableComponent buildCrossClusterMessagePrefix(String clusterLabel) {
        MutableComponent root = Component.empty();
        root.append(Component.literal("[" + clusterLabelForNodeId(clusterLabel) + "] ").withStyle(ChatFormatting.GRAY));
        return root;
    }

    protected void broadcastSharedLifecycleMessage(Component message) {
        if (message == null) {
            return;
        }

        for (MinecraftServer target : allServersWithPossibleViewers()) {
            if (target.isStopped()) {
                continue;
            }
            target.execute(() -> target.getPlayerList().broadcastSystemMessage(message, false));
        }
    }

    protected List<MinecraftServer> relayTargets(MinecraftServer sourceServer) {
        Set<MinecraftServer> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        List<MinecraftServer> targets = new ArrayList<>();

        if (ownerServer != null && ownerServer != sourceServer && unique.add(ownerServer)) {
            targets.add(ownerServer);
        }

        for (MinecraftServer runtimeServer : runtimeServerInstances.values()) {
            if (runtimeServer == sourceServer) {
                continue;
            }
            if (unique.add(runtimeServer)) {
                targets.add(runtimeServer);
            }
        }

        return targets;
    }
}
