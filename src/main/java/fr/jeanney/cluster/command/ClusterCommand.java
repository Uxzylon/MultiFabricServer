package fr.jeanney.cluster.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fr.jeanney.MultiFabricServer;
import fr.jeanney.cluster.ClusterMessages;
import fr.jeanney.cluster.ClusterNodeRuntime;
import fr.jeanney.cluster.ClusterNodeState;
import fr.jeanney.cluster.ClusterPlayerPresence;
import fr.jeanney.cluster.IntegratedClusterController;
import fr.jeanney.cluster.ProxyForwardingConfig;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import org.jspecify.annotations.NonNull;

public final class ClusterCommand {

    private static final @NonNull Permission NODE_ADMIN_PERMISSION = new Permission.HasCommandLevel(
            PermissionLevel.GAMEMASTERS);

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
                            showStatus(source, controller);
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
                                source.sendSuccess(() -> ClusterMessages.component(source, "command.players.none"),
                                        false);
                                return 1;
                            }

                            source.sendSuccess(
                                    () -> ClusterMessages.component(source, "command.players.header",
                                            ClusterMessages.arg("count", sharedPlayers.size())),
                                    false);
                            for (ClusterPlayerPresence player : sharedPlayers) {
                                source.sendSuccess(
                                        () -> ClusterMessages.component(source, "command.players.entry",
                                                ClusterMessages.arg("player", player.playerName()),
                                                ClusterMessages.arg("cluster", player.clusterLabel())),
                                        false);
                            }
                            return 1;
                        }))
                .then(Commands.literal("reload")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .executes(context -> {
                            ClusterMessages.reload();
                            int count = controller.reloadFromDisk(context.getSource().getServer());
                            context.getSource().sendSuccess(
                                    () -> ClusterMessages.component(context.getSource(), "command.reload",
                                            ClusterMessages.arg("count", count)),
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
                                .suggests((_, builder) -> suggestNodes(controller, builder))
                                .executes(context -> {
                                    String node = StringArgumentType.getString(context, "node");
                                    boolean started = controller.startNode(context.getSource().getServer(), node);
                                    if (!started) {
                                        if (!controller.hasNode(node)) {
                                            sendUnknownNode(context.getSource(), node);
                                        } else if (!controller.isNodeEnabled(node)) {
                                            context.getSource().sendFailure(ClusterMessages.component(
                                                    context.getSource(),
                                                    "command.node.disabled_with_hint",
                                                    ClusterMessages.arg("node", node)));
                                        } else {
                                            context.getSource().sendFailure(ClusterMessages.component(
                                                    context.getSource(),
                                                    "command.node.start_failed",
                                                    ClusterMessages.arg("node", node)));
                                        }
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(
                                            () -> ClusterMessages.component(context.getSource(),
                                                    "command.node.start_requested",
                                                    ClusterMessages.arg("node", node)),
                                            true);
                                    return 1;
                                })))
                .then(Commands.literal("enable")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .then(Commands.argument("node", wordArgumentType())
                                .suggests((_, builder) -> suggestNodes(controller, builder))
                                .executes(context -> {
                                    String node = StringArgumentType.getString(context, "node");
                                    boolean changed = controller.setNodeEnabled(context.getSource().getServer(), node,
                                            true);
                                    if (!changed) {
                                        sendUnknownNode(context.getSource(), node);
                                        return 0;
                                    }

                                    context.getSource().sendSuccess(
                                            () -> ClusterMessages.component(context.getSource(), "command.node.enabled",
                                                    ClusterMessages.arg("node", node)),
                                            true);
                                    return 1;
                                })))
                .then(Commands.literal("disable")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .then(Commands.argument("node", wordArgumentType())
                                .suggests((_, builder) -> suggestNodes(controller, builder))
                                .executes(context -> {
                                    String node = StringArgumentType.getString(context, "node");
                                    boolean changed = controller.setNodeEnabled(context.getSource().getServer(), node,
                                            false);
                                    if (!changed) {
                                        sendUnknownNode(context.getSource(), node);
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(
                                            () -> ClusterMessages.component(context.getSource(),
                                                    "command.node.disabled",
                                                    ClusterMessages.arg("node", node)),
                                            true);
                                    return 1;
                                })))
                .then(Commands.literal("remove")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .then(Commands.argument("node", wordArgumentType())
                                .suggests((_, builder) -> suggestNodes(controller, builder))
                                .executes(context -> {
                                    String node = StringArgumentType.getString(context, "node");
                                    boolean removed = controller.removeNode(context.getSource().getServer(), node);
                                    if (!removed) {
                                        sendUnknownNode(context.getSource(), node);
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(
                                            () -> ClusterMessages.component(context.getSource(),
                                                    "command.node.removed",
                                                    ClusterMessages.arg("node", node)),
                                            true);
                                    return 1;
                                })))
                .then(Commands.literal("tp")
                        .then(Commands.argument("target", wordArgumentType())
                                .suggests((_, builder) -> suggestTravelTargets(controller, builder))
                                .executes(context -> {
                                    String target = StringArgumentType.getString(context, "target");
                                    return transferToTarget(context.getSource(), controller, target);
                                })))
                .then(Commands.literal("stop")
                        .requires(ClusterCommand::hasNodeAdminPermission)
                        .then(Commands.argument("node", wordArgumentType())
                                .suggests((_, builder) -> suggestNodes(controller, builder))
                                .executes(context -> {
                                    String node = StringArgumentType.getString(context, "node");
                                    boolean stopped = controller.stopNode(node);
                                    if (!stopped) {
                                        sendUnknownNode(context.getSource(), node);
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(
                                            () -> ClusterMessages.component(context.getSource(),
                                                    "command.node.stop_requested",
                                                    ClusterMessages.arg("node", node)),
                                            true);
                                    return 1;
                                }))));
    }

    private static boolean hasNodeAdminPermission(CommandSourceStack source) {
        return hasPermission(source, NODE_ADMIN_PERMISSION);
    }

    private static boolean hasPermission(CommandSourceStack source, @NonNull Permission permission) {
        return source.permissions().hasPermission(permission);
    }

    private static ArgumentType<String> wordArgumentType() {
        return StringArgumentType.word();
    }

    private static int addNode(CommandSourceStack source, IntegratedClusterController controller, String node) {
        if (!IntegratedClusterController.isValidNodeName(node)) {
            source.sendFailure(ClusterMessages.component(source, "command.cluster.invalid_name"));
            return 0;
        }
        if (controller.hasNode(node)) {
            source.sendFailure(ClusterMessages.component(source, "command.cluster.exists",
                    ClusterMessages.arg("node", node)));
            return 0;
        }

        boolean added = controller.addNode(source.getServer(), node);
        if (!added) {
            source.sendFailure(ClusterMessages.component(source, "command.cluster.add_failed",
                    ClusterMessages.arg("node", node)));
            return 0;
        }

        source.sendSuccess(() -> ClusterMessages.component(source, "command.cluster.added",
                ClusterMessages.arg("node", node)), true);
        return 1;
    }

    @SuppressWarnings("null")
    private static CompletableFuture<Suggestions> suggestNodes(IntegratedClusterController controller,
            SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(nodeIds(controller), builder);
    }

    @SuppressWarnings("null")
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

    private static void showStatus(CommandSourceStack source, IntegratedClusterController controller) {
        int totalPlayers = controller.sharedOnlinePlayersCount();
        source.sendSuccess(() -> ClusterMessages.component(source, "command.status.header",
                ClusterMessages.arg("players", totalPlayers)),
                false);

        source.sendSuccess(() -> statusLine(source, "host", true, true, countPlayers(controller, null)), false);

        var nodes = controller.allNodes().stream()
                .sorted(Comparator.comparing(runtime -> runtime.definition().id(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (nodes.isEmpty()) {
            source.sendSuccess(() -> ClusterMessages.component(source, "command.status.none"), false);
            return;
        }

        for (ClusterNodeRuntime runtime : nodes) {
            String nodeId = runtime.definition().id();
            source.sendSuccess(
                    () -> statusLine(
                            source,
                            nodeId,
                            runtime.definition().enabled(),
                            runtime.state() == ClusterNodeState.RUNNING,
                            countPlayers(controller, nodeId)),
                    false);
        }
    }

    private static int countPlayers(IntegratedClusterController controller, String nodeId) {
        return (int) controller.sharedOnlinePlayers().stream()
                .filter(player -> Objects.equals(player.nodeId(), nodeId))
                .count();
    }

    private static Component statusLine(
            CommandSourceStack source, String name, boolean enabled, boolean running, int players) {
        String locale = ClusterMessages.locale(source);
        return ClusterMessages.component(source, "command.status.line",
                ClusterMessages.formattedArg("name", ("host".equals(name) ? "&6" : "&b") + name + "&r"),
                ClusterMessages.formattedArg("enabled", ClusterMessages.raw(locale,
                        enabled ? "command.status.enabled" : "command.status.disabled")),
                ClusterMessages.formattedArg("running", ClusterMessages.raw(locale,
                        running ? "command.status.running" : "command.status.stopped")),
                ClusterMessages.arg("players", players),
                ClusterMessages.arg("player_word", ClusterMessages.raw(locale,
                        players == 1 ? "command.status.player_singular" : "command.status.player_plural")));
    }

    private static int transferToTarget(CommandSourceStack source, IntegratedClusterController controller,
            String target) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(ClusterMessages.component(source, "command.source.player_only"));
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
                return failTravel(source, playerName, target, "command.travel.unknown_target",
                        ClusterMessages.arg("target", target));
            }
            if (!runtime.definition().enabled()) {
                return failTravel(source, playerName, target, "command.travel.target_disabled",
                        ClusterMessages.arg("target", target));
            }
            if (useSeamlessProxySwitch) {
                return requestSeamlessSwitch(source, controller, player, playerName, target, target);
            }
            if (runtime.state() != ClusterNodeState.RUNNING) {
                boolean started = controller.startNode(source.getServer(), target);
                ClusterNodeRuntime refreshedRuntime = controller.node(target).orElse(runtime);
                if (started && refreshedRuntime.state() == ClusterNodeState.STARTING
                        && controller.requestDirectClusterTravelWhenReady(source.getServer(), player, target)) {
                    source.sendSuccess(() -> ClusterMessages.component(source, "command.travel.starting",
                            ClusterMessages.arg("target", target)),
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
                            "command.travel.target_failed",
                            ClusterMessages.arg("target", target),
                            ClusterMessages.arg("reason", reason));
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
                        "command.travel.no_transfer_port",
                        ClusterMessages.arg("target", target));
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
                return failTravel(source, playerName, target, "command.travel.transfer_not_executed");
            }

            if (targetNodeId == null) {
                controller.preparePlayerHostTravel(source.getServer(), player);
            } else {
                controller.rememberPlayerCluster(source.getServer(), player, targetNodeId);
            }

            return result;
        } catch (Exception exception) {
            return failTravel(source, playerName, target, "command.travel.transfer_failed",
                    ClusterMessages.arg("reason", exception.getMessage()));
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
            return failTravel(source, playerName, target, "command.travel.seamless_failed",
                    ClusterMessages.arg("target", target));
        }
        source.sendSuccess(() -> ClusterMessages.component(source, "command.travel.seamless_requested",
                ClusterMessages.arg("target", target)), false);
        return 1;
    }

    private static int failTravel(CommandSourceStack source,
            String playerName,
            String target,
            String messageKey,
            ClusterMessages.Arg... args) {
        source.sendFailure(ClusterMessages.component(source, messageKey, args));
        MultiFabricServer.LOGGER.warn(
                "Cluster travel failed for player '{}' to target '{}' (messageKey={})",
                playerName,
                target,
                messageKey);
        return 0;
    }

    private static void sendUnknownNode(CommandSourceStack source, String node) {
        source.sendFailure(ClusterMessages.component(source, "command.node.unknown",
                ClusterMessages.arg("node", node)));
    }
}
