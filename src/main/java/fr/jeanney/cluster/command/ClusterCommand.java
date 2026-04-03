package fr.jeanney.cluster.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.jeanney.cluster.ClusterNodeRuntime;
import fr.jeanney.cluster.ClusterNodeState;
import fr.jeanney.cluster.IntegratedClusterController;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.Objects;

public final class ClusterCommand {

    private static final Permission NODE_ADMIN_PERMISSION = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);

    private ClusterCommand() {
    }

    @SuppressWarnings("null")
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
            IntegratedClusterController controller) {
        dispatcher.register(Commands.literal("cluster")
                .requires(source -> source.permissions().hasPermission(nodeAdminPermission()))
                .then(Commands.literal("status")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            int players = controller.onlinePlayers(source.getServer());
                            source.sendSuccess(() -> Component.literal(
                                    "Integrated cluster status. initialized=" + controller.isInitialized()
                                            + ", configEnabled=" + controller.isConfigEnabled()
                                            + ", players=" + players
                                            + ", gatewayEnabled=" + controller.isGatewayEnabled()
                                            + ", gatewayBind=" + controller.gatewayBindHost() + ":"
                                            + controller.gatewayBindPort()
                                            + ", hostTransfer=" + controller.hostTransferHost() + ":"
                                            + controller.hostTransferPort()),
                                    false);
                            for (ClusterNodeRuntime runtime : controller.allNodes()) {
                                String line = "- " + runtime.definition().id() +
                                        " world=" + runtime.definition().worldName() +
                                        " enabled=" + runtime.definition().enabled() +
                                        " transfer=" + runtime.definition().transferHost() + ":"
                                        + runtime.definition().transferPort() +
                                        " state=" + runtime.state() +
                                        (runtime.failureReason().isBlank() ? "" : " reason=" + runtime.failureReason());
                                source.sendSuccess(() -> Component.literal(line), false);
                            }
                            return 1;
                        }))
                .then(Commands.literal("reload")
                        .executes(context -> {
                            int count = controller.reloadFromDisk(context.getSource().getServer());
                            context.getSource().sendSuccess(
                                    () -> Component.literal("Cluster config reloaded. Nodes registered: " + count),
                                    true);
                            return 1;
                        }))
                .then(Commands.literal("start")
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
                .then(Commands.literal("travel")
                        .then(Commands.argument("target", wordArgumentType())
                                .executes(context -> {
                                    String target = StringArgumentType.getString(context, "target");
                                    return transferToTarget(context.getSource(), controller, target);
                                })))
                .then(Commands.literal("stop")
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

    private static ArgumentType<String> wordArgumentType() {
        return Objects.requireNonNull(StringArgumentType.word());
    }

    private static int transferToTarget(CommandSourceStack source, IntegratedClusterController controller,
            String target) {
        if (source.getEntity() == null) {
            source.sendFailure(Component.literal("This command must be run by a player"));
            return 0;
        }

        int port;
        String host;
        if ("host".equalsIgnoreCase(target) || "main".equalsIgnoreCase(target)) {
            host = controller.hostTransferHost();
            int defaultHostPort = controller.ownerPort().orElse(source.getServer().getPort());
            int configuredHostPort = controller.hostTransferPort();
            if (controller.isGatewayEnabled()) {
                port = controller.gatewayBindPort();
            } else {
                port = configuredHostPort > 0 ? configuredHostPort : defaultHostPort;
            }
        } else {
            ClusterNodeRuntime runtime = controller.node(target).orElse(null);
            if (runtime == null) {
                source.sendFailure(Component.literal("Unknown target: " + target));
                return 0;
            }
            if (runtime.state() != ClusterNodeState.RUNNING) {
                source.sendFailure(Component.literal(
                        "Target node is not running: " + target + " (run /cluster start " + target + " first)"));
                return 0;
            }
            if (controller.isGatewayEnabled()) {
                host = controller.hostTransferHost();
                port = controller.gatewayBindPort();
            } else {
                host = runtime.definition().transferHost();
                port = runtime.definition().transferPort();
            }
        }

        String transferCommand = "transfer " + host + " " + port;
        try {
            int result = source.getServer().getCommands().getDispatcher().execute(transferCommand, source);
            if (result <= 0) {
                source.sendFailure(Component.literal("Transfer command did not execute"));
                return 0;
            }

            var player = source.getPlayer();
            if (player != null) {
                if ("host".equalsIgnoreCase(target) || "main".equalsIgnoreCase(target)) {
                    controller.preparePlayerHostTravel(source.getServer(), player);
                } else {
                    controller.rememberPlayerCluster(source.getServer(), player, target);
                }
            }

            return result;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("Transfer failed: " + exception.getMessage()));
            return 0;
        }
    }
}
