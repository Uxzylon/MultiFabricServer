package fr.jeanney;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.jeanney.cluster.ClusterConfig;
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
        if (!ClusterConfig.defaultConfig().nodes().isEmpty()) {
            helper.fail("Default cluster config should not create sample nodes");
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
            writeConfig(configPath, false, new NodeSpec(nodeId, worldName, false));
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

        var runtimeOpt = controller.node(nodeId);
        if (runtimeOpt.isEmpty()) {
            helper.fail("Node missing after command enable: " + nodeId);
            return;
        }

        var runtime = runtimeOpt.get();
        if (runtime.state() != ClusterNodeState.STOPPED) {
            helper.fail("Enabling a node should not eagerly start it, got " + runtime.state()
                    + " reason=" + runtime.failureReason());
            return;
        }

        helper.succeed();
    }

    @GameTest(maxTicks = 4000)
    public void clusterAddCommandCreatesLazyNode(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var controller = MultiFabricServer.clusterController();

        Path configPath = server.getWorldPath(LevelResource.ROOT).resolve("multifabricserver-cluster.json");
        String nodeId = "cmdnode";
        String worldName = "cmdworld";

        try {
            writeConfig(configPath, true);
        } catch (IOException ioException) {
            helper.fail("Failed to write cluster config for test: " + ioException.getMessage());
            return;
        }

        controller.reloadFromDisk(server);

        int commandResult;
        try {
            commandResult = server.getCommands().getDispatcher().execute(
                    "cluster add " + nodeId + " " + worldName,
                    server.createCommandSourceStack());
        } catch (Exception exception) {
            helper.fail("cluster add command failed: " + exception.getMessage());
            return;
        }

        if (commandResult <= 0) {
            helper.fail("cluster add command had no effect");
            return;
        }

        var runtimeOpt = controller.node(nodeId);
        if (runtimeOpt.isEmpty()) {
            helper.fail("Added node is missing: " + nodeId);
            return;
        }

        var runtime = runtimeOpt.get();
        if (!worldName.equals(runtime.definition().worldName())) {
            helper.fail("Added node world mismatch, got " + runtime.definition().worldName());
            return;
        }
        if (!runtime.definition().enabled()) {
            helper.fail("Added node should be enabled");
            return;
        }
        if (runtime.state() != ClusterNodeState.STOPPED) {
            helper.fail("Added node should remain lazy/stopped, got " + runtime.state());
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
            writeConfig(configPath, true, true, new NodeSpec(nodeId, worldName, true));
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

    @GameTest(maxTicks = 4000)
    public void disablingNodeClearsMultiplePlayerAffinities(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var controller = MultiFabricServer.clusterController();

        Path configPath = server.getWorldPath(LevelResource.ROOT).resolve("multifabricserver-cluster.json");
        String nodeId = "testnode";
        String firstPlayerUuid = "00000000-0000-0000-0000-000000000211";
        String secondPlayerUuid = "00000000-0000-0000-0000-000000000212";

        try {
            writeConfig(configPath, true, true, new NodeSpec(nodeId, "testnode_world", true));
        } catch (IOException ioException) {
            helper.fail("Failed to write cluster config for test: " + ioException.getMessage());
            return;
        }

        controller.reloadFromDisk(server);
        controller.rememberPlayerCluster(server, firstPlayerUuid, nodeId);
        controller.rememberPlayerCluster(server, secondPlayerUuid, nodeId);
        controller.upsertSharedPlayerPresenceForTesting(firstPlayerUuid, "FirstPlayer", nodeId);
        controller.upsertSharedPlayerPresenceForTesting(secondPlayerUuid, "SecondPlayer", nodeId);

        if (!controller.setNodeEnabled(server, nodeId, false)) {
            helper.fail("Expected node disable to succeed");
            return;
        }

        if (!controller.isNodeStoppingForTesting(nodeId)) {
            helper.fail("Disabled node should keep an evacuation marker during the transfer window");
            return;
        }
        if (!controller.hasPendingInternalTravelForTesting(firstPlayerUuid)
                || !controller.hasPendingInternalTravelForTesting(secondPlayerUuid)) {
            helper.fail("Disabled node should preserve internal travel markers across config reload");
            return;
        }
        assertNodeEvacuated(helper, controller, nodeId, firstPlayerUuid, secondPlayerUuid);
        if (!controller.hasNode(nodeId)) {
            helper.fail("Disabled node should still exist");
            return;
        }
        if (controller.isNodeEnabled(nodeId)) {
            helper.fail("Node should be disabled");
            return;
        }

        controller.onPlayerDisconnectFromNodeForTesting(server, firstPlayerUuid, nodeId);
        controller.onPlayerDisconnectFromNodeForTesting(server, secondPlayerUuid, nodeId);
        assertNodeEvacuated(helper, controller, nodeId, firstPlayerUuid, secondPlayerUuid);

        helper.succeed();
    }

    @GameTest(maxTicks = 4000)
    public void removingNodeClearsMultiplePlayerAffinities(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var controller = MultiFabricServer.clusterController();

        Path configPath = server.getWorldPath(LevelResource.ROOT).resolve("multifabricserver-cluster.json");
        String nodeId = "removenode";
        String firstPlayerUuid = "00000000-0000-0000-0000-000000000221";
        String secondPlayerUuid = "00000000-0000-0000-0000-000000000222";

        try {
            writeConfig(configPath, true, true, new NodeSpec(nodeId, "removenode_world", true));
        } catch (IOException ioException) {
            helper.fail("Failed to write cluster config for test: " + ioException.getMessage());
            return;
        }

        controller.reloadFromDisk(server);
        controller.rememberPlayerCluster(server, firstPlayerUuid, nodeId);
        controller.rememberPlayerCluster(server, secondPlayerUuid, nodeId);
        controller.upsertSharedPlayerPresenceForTesting(firstPlayerUuid, "FirstPlayer", nodeId);
        controller.upsertSharedPlayerPresenceForTesting(secondPlayerUuid, "SecondPlayer", nodeId);

        if (!controller.removeNode(server, nodeId)) {
            helper.fail("Expected node removal to succeed");
            return;
        }

        if (!controller.isNodeStoppingForTesting(nodeId)) {
            helper.fail("Removed node should keep an evacuation marker during the transfer window");
            return;
        }
        if (!controller.hasPendingInternalTravelForTesting(firstPlayerUuid)
                || !controller.hasPendingInternalTravelForTesting(secondPlayerUuid)) {
            helper.fail("Removed node should preserve internal travel markers across config reload");
            return;
        }
        assertNodeEvacuated(helper, controller, nodeId, firstPlayerUuid, secondPlayerUuid);
        if (controller.hasNode(nodeId)) {
            helper.fail("Removed node should no longer exist");
            return;
        }

        controller.onPlayerDisconnectFromNodeForTesting(server, firstPlayerUuid, nodeId);
        controller.onPlayerDisconnectFromNodeForTesting(server, secondPlayerUuid, nodeId);
        assertNodeEvacuated(helper, controller, nodeId, firstPlayerUuid, secondPlayerUuid);

        helper.succeed();
    }

    @GameTest(maxTicks = 4000)
    public void stoppingNodeClearsMultiplePlayerAffinities(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var controller = new IntegratedClusterController();
        String nodeId = "stopnode";
        String firstPlayerUuid = "00000000-0000-0000-0000-000000000231";
        String secondPlayerUuid = "00000000-0000-0000-0000-000000000232";

        controller.configureSingleNodeForTesting(server, nodeId, "stopnode_world", true);
        controller.rememberPlayerCluster(server, firstPlayerUuid, nodeId);
        controller.rememberPlayerCluster(server, secondPlayerUuid, nodeId);
        controller.upsertSharedPlayerPresenceForTesting(firstPlayerUuid, "FirstPlayer", nodeId);
        controller.upsertSharedPlayerPresenceForTesting(secondPlayerUuid, "SecondPlayer", nodeId);

        if (!controller.stopNode(nodeId)) {
            helper.fail("Expected node stop to succeed");
            return;
        }

        if (!controller.isNodeStoppingForTesting(nodeId)) {
            helper.fail("Stopped node should keep an evacuation marker during the transfer window");
            return;
        }
        assertNodeEvacuated(helper, controller, nodeId, firstPlayerUuid, secondPlayerUuid);
        if (!controller.hasNode(nodeId)) {
            helper.fail("Stopped node should still exist");
            return;
        }
        if (!controller.isNodeEnabled(nodeId)) {
            helper.fail("Stopped node should remain enabled");
            return;
        }

        controller.onPlayerDisconnectFromNodeForTesting(server, firstPlayerUuid, nodeId);
        controller.onPlayerDisconnectFromNodeForTesting(server, secondPlayerUuid, nodeId);
        assertNodeEvacuated(helper, controller, nodeId, firstPlayerUuid, secondPlayerUuid);

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

    @GameTest(maxTicks = 200)
    public void dynmapPlayerListDoesNotExposeSyntheticClusterPresences(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var controller = MultiFabricServer.clusterController();

        String hostViewerUuid = "00000000-0000-0000-0000-000000000311";
        String clusterPlayerUuid = "00000000-0000-0000-0000-000000000312";

        controller.upsertSharedPlayerPresenceForTesting(hostViewerUuid, "HostViewer", null);
        controller.upsertSharedPlayerPresenceForTesting(clusterPlayerUuid, "ClusterUser", "testnode");

        if (!controller.dynmapOnlinePlayers(server).isEmpty()) {
            helper.fail("Dynmap should only receive live players from its own server instance");
            return;
        }

        controller.removeSharedPlayerPresenceForTesting(hostViewerUuid);
        controller.removeSharedPlayerPresenceForTesting(clusterPlayerUuid);

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
        writeConfig(configPath, enabled, false, nodeSpecs);
    }

    private static void writeConfig(Path configPath,
            boolean enabled,
            boolean gatewayEnabled,
            NodeSpec... nodeSpecs) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", enabled);
        root.addProperty("gatewayEnabled", gatewayEnabled);

        JsonArray nodes = new JsonArray();
        for (NodeSpec nodeSpec : nodeSpecs) {
            JsonObject node = new JsonObject();
            node.addProperty("id", nodeSpec.id());
            node.addProperty("worldName", nodeSpec.worldName());
            node.addProperty("enabled", nodeSpec.enabled());
            node.addProperty("proxyServerName", nodeSpec.id());
            nodes.add(node);
        }

        root.add("nodes", nodes);

        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, root.toString(), StandardCharsets.UTF_8);
    }

    private static void assertNodeEvacuated(GameTestHelper helper,
            IntegratedClusterController controller,
            String nodeId,
            String... playerUuids) {
        for (String playerUuid : playerUuids) {
            if (controller.playerClusterAffinity(playerUuid).isPresent()) {
                helper.fail("Player should not retain affinity for node " + nodeId + ": " + playerUuid);
                return;
            }

            var route = controller.resolveGatewayRoute("gateway.test", playerUuid);
            if (route.isEmpty() || route.get().nodeId() != null) {
                helper.fail("Player should route to host after node evacuation: " + playerUuid);
                return;
            }
        }

        boolean hasNodePresence = controller.sharedOnlinePlayers().stream()
                .anyMatch(player -> nodeId.equals(player.nodeId()));
        if (hasNodePresence) {
            helper.fail("Node presence should be cleared for " + nodeId);
        }
    }

    private record NodeSpec(String id, String worldName, boolean enabled) {
    }
}
