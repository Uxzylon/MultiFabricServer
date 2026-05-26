package fr.jeanney;

import fr.jeanney.cluster.IntegratedClusterController;
import fr.jeanney.cluster.command.ClusterCommand;
import fr.jeanney.cluster.network.ClusterRegisterPayload;
import fr.jeanney.cluster.network.ProxyConnectPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MultiFabricServer implements ModInitializer {

        public static final Logger LOGGER = LoggerFactory.getLogger(MultiFabricServer.class);

        private static final IntegratedClusterController CLUSTER_CONTROLLER = new IntegratedClusterController();

        public static IntegratedClusterController clusterController() {
                return CLUSTER_CONTROLLER;
        }

        @Override
        public void onInitialize() {
                registerPayloads();
                registerEvents();

                LOGGER.info("Initialized experimental integrated multi-server cluster runtime");
        }

        private static void registerPayloads() {
                PayloadTypeRegistry.clientboundPlay().register(
                                ClusterRegisterPayload.TYPE,
                                ClusterRegisterPayload.STREAM_CODEC);
                PayloadTypeRegistry.clientboundPlay().register(
                                ProxyConnectPayload.TYPE,
                                ProxyConnectPayload.STREAM_CODEC);
        }

        private static void registerEvents() {
                CommandRegistrationCallback.EVENT
                                .register((dispatcher, _, _) -> ClusterCommand.register(
                                                dispatcher,
                                                CLUSTER_CONTROLLER));

                ServerLifecycleEvents.SERVER_STARTED.register(CLUSTER_CONTROLLER::onServerStarted);
                ServerTickEvents.END_SERVER_TICK.register(CLUSTER_CONTROLLER::onServerTick);
                ServerLifecycleEvents.SERVER_STOPPING.register(CLUSTER_CONTROLLER::onServerStopping);
                ServerLifecycleEvents.SERVER_STOPPED.register(CLUSTER_CONTROLLER::onServerStopped);
                ServerPlayConnectionEvents.JOIN
                                .register((handler, _, server) -> CLUSTER_CONTROLLER.onPlayerJoin(server,
                                                handler.getPlayer()));
                ServerPlayConnectionEvents.DISCONNECT
                                .register((handler, server) -> CLUSTER_CONTROLLER.onPlayerDisconnect(server,
                                                handler.getPlayer()));
                ServerMessageEvents.ALLOW_GAME_MESSAGE
                                .register(CLUSTER_CONTROLLER::allowGameMessage);
                ServerMessageEvents.GAME_MESSAGE
                                .register(CLUSTER_CONTROLLER::relayAdvancementGameMessage);
                ServerMessageEvents.COMMAND_MESSAGE.register((message, source, _) -> CLUSTER_CONTROLLER
                                .relayConsoleCommandMessage(
                                                source.getServer(),
                                                message.decoratedContent()));
                ServerMessageEvents.CHAT_MESSAGE
                                .register((message, sender, _) -> CLUSTER_CONTROLLER.relayChatMessage(
                                                sender.level().getServer(),
                                                sender,
                                                message.decoratedContent().getString()));
        }
}
