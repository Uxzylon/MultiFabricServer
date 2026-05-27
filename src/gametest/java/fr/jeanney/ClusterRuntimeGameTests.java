package fr.jeanney;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.jeanney.cluster.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

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
    public void nodeEnableCommandKeepsNodeLazy(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var controller = MultiFabricServer.clusterController();

        Path configPath = server.getWorldPath(LevelResource.ROOT).resolve("multifabricserver-cluster.json");
        String nodeId = "testnode";

        try {
            writeConfig(configPath, new NodeSpec(nodeId, false));
        } catch (IOException ioException) {
            helper.fail("Failed to write cluster config for test: " + ioException.getMessage());
            return;
        }

        controller.reloadFromDisk(server);

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

        try {
            writeConfig(configPath);
        } catch (IOException ioException) {
            helper.fail("Failed to write cluster config for test: " + ioException.getMessage());
            return;
        }

        controller.reloadFromDisk(server);

        int commandResult;
        try {
            commandResult = server.getCommands().getDispatcher().execute(
                    "cluster add " + nodeId,
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
        if (!"world".equals(runtime.definition().worldName())) {
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
        try {
            JsonObject root = JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (root.has("enabled")
                    || root.has("gatewayEnabled")
                    || root.has("seamlessProxySwitchEnabled")
                    || root.has("transferHost")
                    || root.has("gatewayBindHost")
                    || root.has("gatewayBindPort")) {
                helper.fail("Saved cluster config should only contain compact runtime settings");
                return;
            }
            if (!ClusterConfig.DEFAULT_PROXY_HOST_SERVER_NAME.equals(root.get("proxyHostServerName").getAsString())) {
                helper.fail("Saved cluster config should keep proxyHostServerName");
                return;
            }

            JsonArray nodes = root.getAsJsonArray("nodes");
            JsonObject node = nodes.get(0).getAsJsonObject();
            if (node.has("worldName") || node.has("proxyServerName")) {
                helper.fail("Saved node config should only contain id/enabled");
                return;
            }
        } catch (Exception exception) {
            helper.fail("Failed to inspect saved node config: " + exception.getMessage());
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
        String hostName = "gateway.test";
        String playerUuid = "00000000-0000-0000-0000-000000000123";

        try {
            writeConfig(configPath, new NodeSpec(nodeId, true));
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
        var routedNodeId = forcedHostRoute.map(GatewayRoute::nodeId).orElse(null);
        if (routedNodeId != null) {
            helper.fail("Expected explicit host travel to bypass affinity and route to host, got node "
                    + routedNodeId);
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
            writeConfig(configPath, new NodeSpec(nodeId, true));
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

        if (controller.isNodeStopMarkerAbsentForTesting(nodeId)) {
            helper.fail("Disabled node should keep an evacuation marker during the transfer window");
            return;
        }
        if (controller.isInternalTravelMarkerAbsentForTesting(firstPlayerUuid)
                || controller.isInternalTravelMarkerAbsentForTesting(secondPlayerUuid)) {
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
        Path nodeRoot = server.getWorldPath(LevelResource.ROOT).resolve("cluster-runtime").resolve(nodeId);
        String firstPlayerUuid = "00000000-0000-0000-0000-000000000221";
        String secondPlayerUuid = "00000000-0000-0000-0000-000000000222";

        try {
            writeConfig(configPath, new NodeSpec(nodeId, true));
            Files.createDirectories(nodeRoot.resolve("world"));
            Files.writeString(nodeRoot.resolve("server.properties"), "level-name=world\n", StandardCharsets.UTF_8);
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

        if (controller.isNodeStopMarkerAbsentForTesting(nodeId)) {
            helper.fail("Removed node should keep an evacuation marker during the transfer window");
            return;
        }
        if (controller.isInternalTravelMarkerAbsentForTesting(firstPlayerUuid)
                || controller.isInternalTravelMarkerAbsentForTesting(secondPlayerUuid)) {
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

        helper.succeedWhen(() -> {
            if (Files.exists(nodeRoot)) {
                helper.fail("Removed node runtime directory should be deleted: " + nodeRoot);
            }
        });
    }

    @GameTest(maxTicks = 4000)
    public void stoppingNodeClearsMultiplePlayerAffinities(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var controller = new IntegratedClusterController();
        String nodeId = "stopnode";
        String firstPlayerUuid = "00000000-0000-0000-0000-000000000231";
        String secondPlayerUuid = "00000000-0000-0000-0000-000000000232";

        controller.configureSingleNodeForTesting(server, nodeId);
        controller.rememberPlayerCluster(server, firstPlayerUuid, nodeId);
        controller.rememberPlayerCluster(server, secondPlayerUuid, nodeId);
        controller.upsertSharedPlayerPresenceForTesting(firstPlayerUuid, "FirstPlayer", nodeId);
        controller.upsertSharedPlayerPresenceForTesting(secondPlayerUuid, "SecondPlayer", nodeId);

        if (!controller.stopNode(nodeId)) {
            helper.fail("Expected node stop to succeed");
            return;
        }

        if (controller.isNodeStopMarkerAbsentForTesting(nodeId)) {
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

    @GameTest(maxTicks = 4000)
    public void evacuatedOfflinePlayerGetsHostReturnNotice(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var controller = new IntegratedClusterController();
        String nodeId = "offlinenode";
        String playerUuid = "00000000-0000-0000-0000-000000000241";

        controller.configureSingleNodeForTesting(server, nodeId);
        controller.rememberPlayerCluster(server, playerUuid, nodeId);

        if (!controller.stopNode(nodeId)) {
            helper.fail("Expected node stop to succeed");
            return;
        }

        var pendingNotice = controller.pendingHostRouteMessageForTesting(playerUuid);
        if (pendingNotice.isEmpty() || !nodeId.equals(pendingNotice.get())) {
            helper.fail("Expected offline player to receive a pending host return notice for " + nodeId);
            return;
        }
        assertNodeEvacuated(helper, controller, nodeId, playerUuid);

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

    @GameTest(maxTicks = 400)
    public void dynmapListsEnabledClusterWorldsBeforeNodeStarts(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var controller = MultiFabricServer.clusterController();

        Path configPath = server.getWorldPath(LevelResource.ROOT).resolve("multifabricserver-cluster.json");
        try {
            writeConfig(configPath, new NodeSpec("testnode", true), new NodeSpec("disablednode", false));
        } catch (IOException ioException) {
            helper.fail("Failed to write cluster config for test: " + ioException.getMessage());
            return;
        }

        controller.reloadFromDisk(server);
        var worlds = controller.enabledDynmapClusterWorlds(server);

        if (worlds.size() != 3) {
            helper.fail("Expected exactly 3 dynmap worlds for one enabled cluster, got " + worlds.size());
            return;
        }
        if (worlds.stream().noneMatch(world -> "testnode".equals(world.name())
                && "testnode (overworld)".equals(world.title())
                && !world.nether()
                && !world.theEnd())) {
            helper.fail("Enabled cluster overworld should be listed for dynmap before startup");
            return;
        }
        if (worlds.stream().noneMatch(world -> "testnode_nether".equals(world.name())
                && "testnode (nether)".equals(world.title())
                && world.nether()
                && !world.theEnd())) {
            helper.fail("Enabled cluster nether should be listed for dynmap before startup");
            return;
        }
        if (worlds.stream().noneMatch(world -> "testnode_the_end".equals(world.name())
                && "testnode (end)".equals(world.title())
                && !world.nether()
                && world.theEnd())) {
            helper.fail("Enabled cluster end should be listed for dynmap before startup");
            return;
        }
        if (worlds.stream().anyMatch(world -> world.name().startsWith("disablednode"))) {
            helper.fail("Disabled cluster worlds should not be listed for dynmap");
            return;
        }

        helper.succeed();
    }

    @GameTest(maxTicks = 400)
    public void dynmapPrunesOnlyOrphanedClusterWorlds(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var controller = MultiFabricServer.clusterController();

        Path configPath = server.getWorldPath(LevelResource.ROOT).resolve("multifabricserver-cluster.json");
        try {
            writeConfig(configPath, new NodeSpec("testnode", true), new NodeSpec("disablednode", false));
        } catch (IOException ioException) {
            helper.fail("Failed to write cluster config for test: " + ioException.getMessage());
            return;
        }

        controller.reloadFromDisk(server);

        if (controller.shouldPruneDynmapSavedWorld(server, "testnode", "testnode (overworld)", false)) {
            helper.fail("Configured enabled cluster overworld should stay in dynmap");
            return;
        }
        if (controller.shouldPruneDynmapSavedWorld(server, "testnode_nether", "testnode (nether)", false)) {
            helper.fail("Configured enabled cluster nether should stay in dynmap");
            return;
        }
        if (!controller.shouldPruneDynmapSavedWorld(server, "creative", "creative (overworld)", false)) {
            helper.fail("Orphaned cluster overworld should be pruned from dynmap");
            return;
        }
        if (!controller.shouldPruneDynmapSavedWorld(server, "disablednode", "disablednode (overworld)", false)) {
            helper.fail("Disabled cluster overworld should be pruned from dynmap");
            return;
        }
        if (controller.shouldPruneDynmapSavedWorld(server, "creative", "creative (overworld)", true)) {
            helper.fail("Loaded dynmap worlds should never be pruned as stale saved entries");
            return;
        }
        if (controller.shouldPruneDynmapSavedWorld(server, "world", "overworld", false)) {
            helper.fail("Host overworld should stay in dynmap");
            return;
        }

        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void localizedMessagesUsePlayerLanguageAndEnglishFallback(GameTestHelper helper) {
        String french = ClusterMessages.component("fr_fr", "cluster.host_return.unavailable",
                ClusterMessages.arg("cluster", "testnode")).getString();
        if (!french.contains("testnode") || !french.contains("pas disponible")) {
            helper.fail("French host-return message was not selected: " + french);
            return;
        }

        String fallback = ClusterMessages.component("zz_zz", "cluster.host_return.unavailable",
                ClusterMessages.arg("cluster", "testnode")).getString();
        if (!"Cluster 'testnode' is unavailable. You were sent back to host.".equals(fallback)) {
            helper.fail("Missing locale should fall back to English, got: " + fallback);
            return;
        }

        String chat = ClusterMessages.component("en_us", "chat.cross_cluster.line",
                ClusterMessages.arg("player", "Player"),
                ClusterMessages.arg("message", "&cnot-colored")).getString();
        if (!"<Player> &cnot-colored".equals(chat)) {
            helper.fail("Message arguments should not be parsed as formatting codes, got: " + chat);
            return;
        }

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

    private static void writeConfig(Path configPath, NodeSpec... nodeSpecs) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("proxyHostServerName", ClusterConfig.DEFAULT_PROXY_HOST_SERVER_NAME);
        root.addProperty("nodeIdleStopSeconds", ClusterConfig.DEFAULT_NODE_IDLE_STOP_SECONDS);

        JsonArray nodes = new JsonArray();
        for (NodeSpec nodeSpec : nodeSpecs) {
            JsonObject node = new JsonObject();
            node.addProperty("id", nodeSpec.id());
            node.addProperty("enabled", nodeSpec.enabled());
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
            if (route.isEmpty() || route.map(GatewayRoute::nodeId).orElse(null) != null) {
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

    private record NodeSpec(String id, boolean enabled) {
    }
}
