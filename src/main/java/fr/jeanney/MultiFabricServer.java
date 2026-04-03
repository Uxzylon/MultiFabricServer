package fr.jeanney;

import fr.jeanney.cluster.IntegratedClusterController;
import fr.jeanney.cluster.ClusterGatewayProxy;
import fr.jeanney.cluster.command.ClusterCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MultiFabricServer implements ModInitializer {

    public static final String MOD_ID = "multifabricserver";
    public static final Logger LOGGER = LoggerFactory.getLogger(MultiFabricServer.class);

    private static final IntegratedClusterController CLUSTER_CONTROLLER = new IntegratedClusterController();
    private static final ClusterGatewayProxy CLUSTER_GATEWAY = new ClusterGatewayProxy();

    public static IntegratedClusterController clusterController() {
        return CLUSTER_CONTROLLER;
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT
                .register((dispatcher, access, environment) -> ClusterCommand.register(dispatcher, CLUSTER_CONTROLLER));

        ServerLifecycleEvents.SERVER_STARTED.register(CLUSTER_CONTROLLER::onServerStarted);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!CLUSTER_CONTROLLER.isClusterChildServerForRuntime(server)) {
                CLUSTER_GATEWAY.onServerStarted(server, CLUSTER_CONTROLLER);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(CLUSTER_CONTROLLER::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(CLUSTER_CONTROLLER::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (!CLUSTER_CONTROLLER.isClusterChildServerForRuntime(server)) {
                CLUSTER_GATEWAY.onServerStopped(server, CLUSTER_CONTROLLER);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(CLUSTER_CONTROLLER::onServerStopped);
        ServerPlayConnectionEvents.JOIN
                .register((handler, sender, server) -> CLUSTER_CONTROLLER.onPlayerJoin(server, handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT
                .register((handler, server) -> CLUSTER_CONTROLLER.onPlayerDisconnect(server, handler.getPlayer()));

        LOGGER.info("Initialized experimental integrated multi-server cluster runtime");
    }
}
