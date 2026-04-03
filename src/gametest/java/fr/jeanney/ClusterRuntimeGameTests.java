package fr.jeanney;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.jeanney.cluster.ClusterPlayerPresence;
import fr.jeanney.cluster.IntegratedClusterController;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import fr.jeanney.cluster.ClusterNodeState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ClusterRuntimeGameTests {

    @GameTest(maxTicks = 400)
    public void clusterConfigFileIsCreatedOnBoot(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path configPath = server.getWorldPath(LevelResource.ROOT).resolve("multifabricserver-cluster.json");
        if (!Files.exists(configPath)) {
            helper.fail("Expected cluster config file to be generated at " + configPath);
            return;
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 4000)
    public void nodeEnableCommandAlsoEnablesClusterRuntime(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var controller = MultiFabricServer.clusterController();

        Path configPath = server.getWorldPath(LevelResource.ROOT).resolve("multifabricserver-cluster.json");
        String nodeId = "testnode";
        String worldName = "testnode_world";

        try {
            writeConfig(configPath, false, new NodeSpec(nodeId, worldName, 0, false, false));
        } catch (IOException ioException) {
            helper.fail("Failed to write cluster config for test: " + ioException.getMessage());
            return;
        }

        controller.reloadFromDisk(server);

        if (controller.isConfigEnabled()) {
            helper.fail("Cluster runtime should be disabled before running node enable command");
            return;
        }

        int commandResult;
        try {
            commandResult = server.getCommands().getDispatcher().execute(
                    "cluster enable " + nodeId,
                    server.createCommandSourceStack());
        } catch (Exception exception) {
            helper.fail("cluster enable <node> command failed: " + exception.getMessage());
            return;
        }
        if (commandResult <= 0) {
            helper.fail("cluster enable <node> command had no effect");
            return;
        }

        if (!controller.isConfigEnabled()) {
            helper.fail("cluster enable <node> should enable the cluster runtime");
            return;
        }

        if (!controller.isNodeEnabled(nodeId)) {
            helper.fail("cluster enable <node> should enable the node");
            return;
        }

        if (!controller.startNode(server, nodeId)) {
            helper.fail("Failed to start node after cluster enable <node>");
            return;
        }

        var runtimeOpt = controller.node(nodeId);
        if (runtimeOpt.isEmpty()) {
            helper.fail("Node missing after command enable/start: " + nodeId);
            return;
        }

        var runtime = runtimeOpt.get();
        if (runtime.state() != ClusterNodeState.RUNNING && runtime.state() != ClusterNodeState.STARTING) {
            helper.fail("Expected RUNNING/STARTING node state after command enable/start, got " + runtime.state()
                    + " reason=" + runtime.failureReason());
            return;
        }

        helper.succeed();
    }

    @GameTest(maxTicks = 4000)
    public void explicitHostTravelBypassesAffinityRoute(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var controller = MultiFabricServer.clusterController();

        Path configPath = server.getWorldPath(LevelResource.ROOT).resolve("multifabricserver-cluster.json");
        String nodeId = "testnode";
        String worldName = "testnode_world";
        String hostName = "gateway.test";
        String playerUuid = "00000000-0000-0000-0000-000000000123";

        try {
            writeConfig(configPath, true, true, hostName, 25565,
                    new NodeSpec(nodeId, worldName, 0, true, false));
        } catch (IOException ioException) {
            helper.fail("Failed to write cluster config for test: " + ioException.getMessage());
            return;
        }

        controller.reloadFromDisk(server);
        controller.rememberPlayerCluster(server, playerUuid, nodeId);

        controller.preparePlayerHostTravel(server, playerUuid);
        controller.onPlayerDisconnectFromNodeForTesting(server, playerUuid, nodeId);

        if (controller.playerClusterAffinity(playerUuid).isPresent()) {
            helper.fail("Explicit host travel should keep affinity cleared across child disconnect");
            return;
        }

        var forcedHostRoute = controller.resolveGatewayRoute(hostName, playerUuid);
        if (forcedHostRoute.isEmpty()) {
            helper.fail("Expected gateway host route after explicit host travel");
            return;
        }
        if (forcedHostRoute.get().nodeId() != null) {
            helper.fail("Expected explicit host travel to bypass affinity and route to host, got node "
                    + forcedHostRoute.get().nodeId());
            return;
        }

        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void sharedPresenceTracksAndFiltersRemotePlayers(GameTestHelper helper) {
        var controller = MultiFabricServer.clusterController();

        String hostViewerUuid = "00000000-0000-0000-0000-000000000111";
        String hostPeerUuid = "00000000-0000-0000-0000-000000000112";
        String creativePlayerUuid = "00000000-0000-0000-0000-000000000113";

        controller.upsertSharedPlayerPresenceForTesting(hostViewerUuid, "HostViewer", null);
        controller.upsertSharedPlayerPresenceForTesting(hostPeerUuid, "HostPeer", null);
        controller.upsertSharedPlayerPresenceForTesting(creativePlayerUuid, "CreativeUser", "creative");

        if (controller.sharedOnlinePlayersCount() != 3) {
            helper.fail("Expected shared player count=3, got " + controller.sharedOnlinePlayersCount());
            return;
        }

        var hostViewerRemote = controller.remotePlayersForViewerForTesting(hostViewerUuid, null);
        if (hostViewerRemote.size() != 1) {
            helper.fail("Host viewer should see exactly one remote player, got " + hostViewerRemote.size());
            return;
        }

        ClusterPlayerPresence hostRemoteEntry = hostViewerRemote.iterator().next();
        if (!creativePlayerUuid.equals(hostRemoteEntry.playerUuid())) {
            helper.fail("Host viewer remote entry should be creative player, got " + hostRemoteEntry.playerUuid());
            return;
        }
        if (!"creative".equals(hostRemoteEntry.clusterLabel())) {
            helper.fail("Expected remote cluster label 'creative', got " + hostRemoteEntry.clusterLabel());
            return;
        }

        var creativeViewerRemote = controller.remotePlayersForViewerForTesting(creativePlayerUuid, "creative");
        if (creativeViewerRemote.size() != 2) {
            helper.fail("Creative viewer should see two host players as remote, got " + creativeViewerRemote.size());
            return;
        }

        controller.removeSharedPlayerPresenceForTesting(hostViewerUuid);
        controller.removeSharedPlayerPresenceForTesting(hostPeerUuid);
        controller.removeSharedPlayerPresenceForTesting(creativePlayerUuid);

        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void remoteTabDisplayNameIncludesClusterAndGrayStyle(GameTestHelper helper) {
        Component displayName = IntegratedClusterController.buildRemoteTabDisplayName("Uxzylon", "creative");

        if (!"Uxzylon (creative)".equals(displayName.getString())) {
            helper.fail("Unexpected remote tab display text: " + displayName.getString());
            return;
        }

        if (displayName.getStyle().getColor() == null) {
            helper.fail("Remote tab display should use gray style for cross-cluster entries");
            return;
        }

        helper.succeed();
    }

    private static void writeConfig(Path configPath, boolean enabled, NodeSpec... nodeSpecs) throws IOException {
        writeConfig(configPath, enabled, false, "0.0.0.0", 25565, nodeSpecs);
    }

    private static void writeConfig(Path configPath,
            boolean enabled,
            boolean gatewayEnabled,
            String hostTransferHost,
            int hostTransferPort,
            NodeSpec... nodeSpecs) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", enabled);
        root.addProperty("gatewayEnabled", gatewayEnabled);
        root.addProperty("gatewayBindHost", "0.0.0.0");
        root.addProperty("gatewayBindPort", 25565);
        root.addProperty("hostTransferHost", hostTransferHost);
        root.addProperty("hostTransferPort", hostTransferPort);

        JsonArray nodes = new JsonArray();
        for (NodeSpec nodeSpec : nodeSpecs) {
            JsonObject node = new JsonObject();
            node.addProperty("id", nodeSpec.id());
            node.addProperty("worldName", nodeSpec.worldName());
            node.addProperty("listenPort", nodeSpec.listenPort());
            node.addProperty("enabled", nodeSpec.enabled());
            node.addProperty("autoStart", nodeSpec.autoStart());
            node.addProperty("transferHost", "127.0.0.1");
            node.addProperty("transferPort", nodeSpec.listenPort());
            nodes.add(node);
        }

        root.add("nodes", nodes);

        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, root.toString(), StandardCharsets.UTF_8);
    }

    private record NodeSpec(String id, String worldName, int listenPort, boolean enabled, boolean autoStart) {
    }
}
