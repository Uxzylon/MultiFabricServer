package fr.jeanney.cluster.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fr.jeanney.MultiFabricServer;
import fr.jeanney.cluster.ClusterPlayerPresence;
import fr.jeanney.cluster.ClusterNodeRuntime;
import fr.jeanney.cluster.ClusterNodeState;
import fr.jeanney.cluster.IntegratedClusterController;
import fr.jeanney.cluster.ProxyForwardingConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

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
                            return showStatus(source, controller);
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
                .then(Commands.literal("add")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .then(Commands.argument("node", wordArgumentType())
                                .executes(context -> {
                                    String node = StringArgumentType.getString(context, "node");
                                    return addNode(context.getSource(), controller, node);
                                })))
                .then(Commands.literal("start")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .then(Commands.argument("node", wordArgumentType())
                                .suggests((context, builder) -> suggestNodes(controller, builder))
                                .executes(context -> {
                                    String node = StringArgumentType.getString(context, "node");
                                    boolean started = controller.startNode(context.getSource().getServer(), node);
                                    if (!started) {
                                        if (!controller.hasNode(node)) {
                                            context.getSource().sendFailure(Component.literal("Unknown node: " + node));
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
                        .then(Commands.argument("node", wordArgumentType())
                                .suggests((context, builder) -> suggestNodes(controller, builder))
                                .executes(context -> {
                                    String node = StringArgumentType.getString(context, "node");
                                    boolean changed = controller.setNodeEnabled(context.getSource().getServer(), node,
                                            true);
                                    if (!changed) {
                                        context.getSource().sendFailure(Component.literal("Unknown node: " + node));
                                        return 0;
                                    }

                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Enabled node: " + node),
                                            true);
                                    return 1;
                                })))
                .then(Commands.literal("disable")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .then(Commands.argument("node", wordArgumentType())
                                .suggests((context, builder) -> suggestNodes(controller, builder))
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
                                .suggests((context, builder) -> suggestNodes(controller, builder))
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
                                .suggests((context, builder) -> suggestTravelTargets(controller, builder))
                                .executes(context -> {
                                    String target = StringArgumentType.getString(context, "target");
                                    return transferToTarget(context.getSource(), controller, target);
                                })))
                .then(Commands.literal("stop")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .then(Commands.argument("node", wordArgumentType())
                                .suggests((context, builder) -> suggestNodes(controller, builder))
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

    private static int addNode(CommandSourceStack source, IntegratedClusterController controller, String node) {
        if (!IntegratedClusterController.isValidNodeName(node)) {
            source.sendFailure(Component.literal(
                    "Invalid cluster name. Use 1-64 characters: letters, numbers, dot, dash, underscore."));
            return 0;
        }
        if (controller.hasNode(node)) {
            source.sendFailure(Component.literal("Cluster already exists: " + node));
            return 0;
        }

        boolean added = controller.addNode(source.getServer(), node);
        if (!added) {
            source.sendFailure(Component.literal("Failed to add cluster: " + node));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Added cluster ")
                .append(Component.literal(node).withStyle(ChatFormatting.AQUA)), true);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestNodes(IntegratedClusterController controller,
            SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(nodeIds(controller), builder);
    }

    private static CompletableFuture<Suggestions> suggestTravelTargets(IntegratedClusterController controller,
            SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                Stream.concat(Stream.of("host"), nodeIds(controller)),
                builder);
    }

    private static Stream<String> nodeIds(IntegratedClusterController controller) {
        return controller.allNodes().stream()
                .map(runtime -> runtime.definition().id())
                .sorted(String.CASE_INSENSITIVE_ORDER);
    }

    private static int showStatus(CommandSourceStack source, IntegratedClusterController controller) {
        int totalPlayers = controller.sharedOnlinePlayersCount();
        source.sendSuccess(() -> Component.literal("Cluster status")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("  " + totalPlayers + " online")
                        .withStyle(ChatFormatting.GRAY)),
                false);

        source.sendSuccess(() -> statusLine("host", true, true, countPlayers(controller, null)), false);

        var nodes = controller.allNodes().stream()
                .sorted(Comparator.comparing(runtime -> runtime.definition().id(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (nodes.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No clusters configured")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }

        for (ClusterNodeRuntime runtime : nodes) {
            String nodeId = runtime.definition().id();
            source.sendSuccess(
                    () -> statusLine(
                            nodeId,
                            runtime.definition().enabled(),
                            runtime.state() == ClusterNodeState.RUNNING,
                            countPlayers(controller, nodeId)),
                    false);
        }
        return 1;
    }

    private static int countPlayers(IntegratedClusterController controller, String nodeId) {
        return (int) controller.sharedOnlinePlayers().stream()
                .filter(player -> Objects.equals(player.nodeId(), nodeId))
                .count();
    }

    private static Component statusLine(String name, boolean enabled, boolean running, int players) {
        return Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(name).withStyle("host".equals(name)
                        ? ChatFormatting.GOLD
                        : ChatFormatting.AQUA))
                .append(Component.literal("  "))
                .append(flag(enabled, "enabled", "disabled"))
                .append(Component.literal("  "))
                .append(flag(running, "running", "stopped"))
                .append(Component.literal("  "))
                .append(Component.literal(players + " " + (players == 1 ? "player" : "players"))
                        .withStyle(ChatFormatting.GRAY));
    }

    private static MutableComponent flag(boolean value, String trueText, String falseText) {
        return Component.literal(value ? trueText : falseText)
                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static int transferToTarget(CommandSourceStack source, IntegratedClusterController controller,
            String target) {
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player"));
            return 0;
        }
        String playerName = player.getScoreboardName();

        boolean useSeamlessProxySwitch = ProxyForwardingConfig.isFabricProxyLiteConfigured(source.getServer());
        int port;
        String host;
        String targetNodeId;
        if ("host".equalsIgnoreCase(target) || "main".equalsIgnoreCase(target)) {
            host = controller.hostTransferHost();
            port = controller.hostTransferPort();
            targetNodeId = null;
        } else {
            ClusterNodeRuntime runtime = controller.node(target).orElse(null);
            if (runtime == null) {
                return failTravel(source, playerName, target, "Unknown target: " + target);
            }
            if (!runtime.definition().enabled()) {
                return failTravel(source, playerName, target, "Target node is disabled: " + target);
            }
            if (useSeamlessProxySwitch) {
                return requestSeamlessSwitch(source, controller, player, playerName, target, target);
            }
            if (runtime.state() != ClusterNodeState.RUNNING) {
                boolean started = controller.startNode(source.getServer(), target);
                ClusterNodeRuntime refreshedRuntime = controller.node(target).orElse(runtime);
                if (started && refreshedRuntime.state() == ClusterNodeState.STARTING
                        && controller.requestDirectClusterTravelWhenReady(source.getServer(), player, target)) {
                    source.sendSuccess(() -> Component.literal("Cluster is starting; transfer requested: " + target),
                            false);
                    return 1;
                }
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
            host = controller.runtimeTransferHost();
            port = runtime.resolvedTransferPort();

            if (port <= 0) {
                return failTravel(
                        source,
                        playerName,
                        target,
                        "Target node has no resolved transfer port yet: " + target);
            }
            targetNodeId = target;
        }

        if (useSeamlessProxySwitch) {
            return requestSeamlessSwitch(source, controller, player, playerName, target, targetNodeId);
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

    private static int requestSeamlessSwitch(CommandSourceStack source,
            IntegratedClusterController controller,
            ServerPlayer player,
            String playerName,
            String target,
            String targetNodeId) {
        MultiFabricServer.LOGGER.info(
                "Using seamless proxy switch for player '{}' to target '{}' because FabricProxy-Lite is configured",
                playerName,
                target);
        boolean switched = controller.requestSeamlessProxyTravel(source.getServer(), player, targetNodeId);
        if (!switched) {
            return failTravel(source, playerName, target, "Seamless proxy switch failed for target: " + target);
        }
        source.sendSuccess(() -> Component.literal("Seamless switch requested: " + target), false);
        return 1;
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
