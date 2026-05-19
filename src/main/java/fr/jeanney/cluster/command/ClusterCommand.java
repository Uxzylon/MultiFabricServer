package fr.jeanney.cluster.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.jeanney.MultiFabricServer;
import fr.jeanney.cluster.ClusterPlayerPresence;
import fr.jeanney.cluster.ClusterNodeRuntime;
import fr.jeanney.cluster.ClusterNodeState;
import fr.jeanney.cluster.IntegratedClusterController;
import fr.jeanney.cluster.ProxyForwardingConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.Comparator;
import java.util.Objects;

public final class ClusterCommand {

    private static final Permission NODE_ADMIN_PERMISSION = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);

    private ClusterCommand() {
    }

    @SuppressWarnings("null")
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
            IntegratedClusterController controller) {
        dispatcher.register(Commands.literal("cluster")
                .then(Commands.literal("status")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            int localPlayers = source.getServer().getPlayerList().getPlayers().size();
                            int sharedPlayers = controller.sharedOnlinePlayersCount();
                            source.sendSuccess(() -> Component.literal(
                                    "Integrated cluster status. initialized=" + controller.isInitialized()
                                            + ", configEnabled=" + controller.isConfigEnabled()
                                            + ", localPlayers=" + localPlayers
                                            + ", sharedPlayers=" + sharedPlayers
                                            + ", gatewayEnabled=" + controller.isGatewayEnabled()
                                            + ", gatewayBind=" + controller.gatewayBindHost() + ":"
                                            + controller.gatewayBindPort()
                                            + ", hostTransfer=" + controller.hostTransferHost() + ":"
                                            + controller.hostTransferPort()
                                            + ", seamlessProxySwitch="
                                            + controller.isSeamlessProxySwitchEnabled()
                                            + ", proxyHostServer=" + controller.proxyHostServerName()
                                            + ", nodeIdleStopSeconds=" + controller.nodeIdleStopSeconds()),
                                    false);
                            for (ClusterNodeRuntime runtime : controller.allNodes()) {
                                int transferPort = runtime.resolvedTransferPort();
                                String line = "- " + runtime.definition().id() +
                                        " world=" + runtime.definition().worldName() +
                                        " enabled=" + runtime.definition().enabled() +
                                        " transfer=" + controller.runtimeTransferHost() + ":"
                                    + transferPort +
                                        " state=" + runtime.state() +
                                        (runtime.failureReason().isBlank() ? "" : " reason=" + runtime.failureReason());
                                source.sendSuccess(() -> Component.literal(line), false);
                            }
                            return 1;
                        }))
                .then(Commands.literal("players")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            var sharedPlayers = controller.sharedOnlinePlayers().stream()
                                    .sorted(Comparator.comparing(ClusterPlayerPresence::playerName,
                                            String.CASE_INSENSITIVE_ORDER))
                                    .toList();

                            if (sharedPlayers.isEmpty()) {
                                source.sendSuccess(() -> Component.literal("No players online across the cluster."),
                                        false);
                                return 1;
                            }

                            source.sendSuccess(
                                    () -> Component.literal("Players online across cluster (" + sharedPlayers.size()
                                            + "):"),
                                    false);
                            for (ClusterPlayerPresence player : sharedPlayers) {
                                source.sendSuccess(
                                        () -> Component.literal("- " + player.playerName() + " @ "
                                                + player.clusterLabel()),
                                        false);
                            }
                            return 1;
                        }))
                .then(Commands.literal("reload")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .executes(context -> {
                            int count = controller.reloadFromDisk(context.getSource().getServer());
                            context.getSource().sendSuccess(
                                    () -> Component.literal("Cluster config reloaded. Nodes registered: " + count),
                                    true);
                            return 1;
                        }))
                .then(Commands.literal("start")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .then(Commands.argument("node", wordArgumentType())
                                .executes(context -> {
                                    String node = StringArgumentType.getString(context, "node");
                                    boolean started = controller.startNode(context.getSource().getServer(), node);
                                    if (!started) {
                                        if (!controller.hasNode(node)) {
                                            context.getSource().sendFailure(Component.literal("Unknown node: " + node));
                                        } else if (!controller.isConfigEnabled()) {
                                            context.getSource().sendFailure(Component
                                                    .literal("Cluster runtime is disabled (use /cluster enable)"));
                                        } else if (!controller.isNodeEnabled(node)) {
                                            context.getSource().sendFailure(Component.literal("Node is disabled: "
                                                    + node + " (use /cluster enable " + node + ")"));
                                        } else {
                                            context.getSource()
                                                    .sendFailure(Component.literal("Failed to start node: " + node));
                                        }
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Start requested for node: " + node), true);
                                    return 1;
                                })))
                .then(Commands.literal("enable")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .executes(context -> {
                            boolean enabled = controller.setClusterEnabled(context.getSource().getServer(), true);
                            if (!enabled) {
                                context.getSource().sendFailure(Component.literal("Failed to enable cluster runtime"));
                                return 0;
                            }
                            context.getSource().sendSuccess(() -> Component.literal("Cluster runtime enabled"), true);
                            return 1;
                        })
                        .then(Commands.argument("node", wordArgumentType())
                                .executes(context -> {
                                    String node = StringArgumentType.getString(context, "node");
                                    boolean changed = controller.setNodeEnabled(context.getSource().getServer(), node,
                                            true);
                                    if (!changed) {
                                        context.getSource().sendFailure(Component.literal("Unknown node: " + node));
                                        return 0;
                                    }

                                    boolean runtimeEnabled = controller.isConfigEnabled();
                                    if (!runtimeEnabled) {
                                        runtimeEnabled = controller.setClusterEnabled(context.getSource().getServer(),
                                                true);
                                    }

                                    if (!runtimeEnabled) {
                                        context.getSource().sendFailure(Component.literal(
                                                "Enabled node '" + node + "' but failed to enable cluster runtime"));
                                        return 0;
                                    }

                                    context.getSource().sendSuccess(
                                            () -> Component
                                                    .literal("Enabled node: " + node + " (cluster runtime enabled)"),
                                            true);
                                    return 1;
                                })))
                .then(Commands.literal("disable")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .executes(context -> {
                            boolean disabled = controller.setClusterEnabled(context.getSource().getServer(), false);
                            if (!disabled) {
                                context.getSource().sendFailure(Component.literal("Failed to disable cluster runtime"));
                                return 0;
                            }
                            context.getSource().sendSuccess(() -> Component.literal("Cluster runtime disabled"), true);
                            return 1;
                        })
                        .then(Commands.argument("node", wordArgumentType())
                                .executes(context -> {
                                    String node = StringArgumentType.getString(context, "node");
                                    boolean changed = controller.setNodeEnabled(context.getSource().getServer(), node,
                                            false);
                                    if (!changed) {
                                        context.getSource().sendFailure(Component.literal("Unknown node: " + node));
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(() -> Component.literal("Disabled node: " + node),
                                            true);
                                    return 1;
                                })))
                .then(Commands.literal("remove")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .then(Commands.argument("node", wordArgumentType())
                                .executes(context -> {
                                    String node = StringArgumentType.getString(context, "node");
                                    boolean removed = controller.removeNode(context.getSource().getServer(), node);
                                    if (!removed) {
                                        context.getSource().sendFailure(Component.literal("Unknown node: " + node));
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(() -> Component.literal("Removed node: " + node),
                                            true);
                                    return 1;
                                })))
                .then(Commands.literal("tp")
                        .then(Commands.argument("target", wordArgumentType())
                                .executes(context -> {
                                    String target = StringArgumentType.getString(context, "target");
                                    return transferToTarget(context.getSource(), controller, target);
                                })))
                .then(Commands.literal("travel")
                        .then(Commands.argument("target", wordArgumentType())
                                .executes(context -> {
                                    String target = StringArgumentType.getString(context, "target");
                                    return transferToTarget(context.getSource(), controller, target);
                                })))
                .then(Commands.literal("stop")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .then(Commands.argument("node", wordArgumentType())
                                .executes(context -> {
                                    String node = StringArgumentType.getString(context, "node");
                                    boolean stopped = controller.stopNode(node);
                                    if (!stopped) {
                                        context.getSource().sendFailure(Component.literal("Unknown node: " + node));
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Stop requested for node: " + node), true);
                                    return 1;
                                }))));
    }

    private static Permission nodeAdminPermission() {
        return Objects.requireNonNull(NODE_ADMIN_PERMISSION);
    }

    @SuppressWarnings("null")
    private static boolean hasNodeAdminPermission(CommandSourceStack source) {
        return source.permissions().hasPermission(nodeAdminPermission());
    }

    private static ArgumentType<String> wordArgumentType() {
        return Objects.requireNonNull(StringArgumentType.word());
    }

    private static int transferToTarget(CommandSourceStack source, IntegratedClusterController controller,
            String target) {
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player"));
            return 0;
        }
        String playerName = player.getScoreboardName();

        int port;
        String host;
        String targetNodeId;
        if ("host".equalsIgnoreCase(target) || "main".equalsIgnoreCase(target)) {
            host = controller.hostTransferHost();
            if (controller.isGatewayEnabled()) {
                port = controller.gatewayBindPort();
            } else {
                port = controller.hostTransferPort();
            }
            targetNodeId = null;
        } else {
            ClusterNodeRuntime runtime = controller.node(target).orElse(null);
            if (runtime == null) {
                return failTravel(source, playerName, target, "Unknown target: " + target);
            }
            if (!runtime.definition().enabled()) {
                return failTravel(source, playerName, target, "Target node is disabled: " + target);
            }
            if (runtime.state() != ClusterNodeState.RUNNING) {
                if (!controller.isConfigEnabled()) {
                    return failTravel(
                            source,
                            playerName,
                            target,
                            "Cluster runtime is disabled (use /cluster enable)");
                }

                boolean started = controller.startNode(source.getServer(), target);
                ClusterNodeRuntime refreshedRuntime = controller.node(target).orElse(runtime);
                if (!started || refreshedRuntime.state() != ClusterNodeState.RUNNING) {
                    String reason = refreshedRuntime.failureReason().isBlank()
                            ? "check server logs"
                            : refreshedRuntime.failureReason();
                    return failTravel(
                            source,
                            playerName,
                            target,
                            "Target node failed to start: " + target + " (" + reason + ")");
                }
                runtime = refreshedRuntime;
            }
            if (controller.isGatewayEnabled()) {
                host = controller.hostTransferHost();
                port = controller.gatewayBindPort();
            } else {
                host = controller.runtimeTransferHost();
                port = runtime.resolvedTransferPort();
            }

            if (port <= 0) {
                return failTravel(
                        source,
                        playerName,
                        target,
                        "Target node has no resolved transfer port yet: " + target);
            }
            targetNodeId = target;
        }

        boolean useSeamlessProxySwitch = controller.isSeamlessProxySwitchEnabled()
                || ProxyForwardingConfig.isFabricProxyLiteConfigured(source.getServer());
        if (useSeamlessProxySwitch) {
            if (!controller.isSeamlessProxySwitchEnabled()) {
                MultiFabricServer.LOGGER.info(
                        "Using seamless proxy switch for player '{}' to target '{}' because FabricProxy-Lite is configured",
                        playerName,
                        target);
            }
            boolean switched = controller.requestSeamlessProxyTravel(source.getServer(), player, targetNodeId);
            if (!switched) {
                return failTravel(source, playerName, target, "Seamless proxy switch failed for target: " + target);
            }
            source.sendSuccess(() -> Component.literal("Seamless switch requested: " + target), false);
            return 1;
        }

        String transferCommand = "transfer " + host + " " + port;
        try {
            int result = source.getServer().getCommands().getDispatcher().execute(transferCommand, source);
            if (result <= 0) {
                return failTravel(source, playerName, target, "Transfer command did not execute");
            }

            if (targetNodeId == null) {
                controller.preparePlayerHostTravel(source.getServer(), player);
            } else {
                controller.rememberPlayerCluster(source.getServer(), player, targetNodeId);
            }

            return result;
        } catch (Exception exception) {
            return failTravel(source, playerName, target, "Transfer failed: " + exception.getMessage());
        }
    }

    private static int failTravel(CommandSourceStack source, String playerName, String target, String message) {
        source.sendFailure(Component.literal(message));
        MultiFabricServer.LOGGER.warn(
                "Cluster travel failed for player '{}' to target '{}': {}",
                playerName,
                target,
                message);
        return 0;
    }
}
