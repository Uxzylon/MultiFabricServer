package fr.jeanney.cluster;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.jeanney.MultiFabricServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ClusterConfigLoader {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ClusterConfigLoader() {
    }

    public static ClusterConfig load(MinecraftServer server) {
        Path path = resolveConfigPath(server);

        if (!Files.exists(path)) {
            ClusterConfig defaultConfig = ClusterConfig.defaultConfig();
            writeDefault(path, defaultConfig);
            return defaultConfig;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                throw new IllegalStateException("Root must be a JSON object");
            }
            return parseConfig(element.getAsJsonObject());
        } catch (Exception exception) {
            MultiFabricServer.LOGGER.error("Failed to parse cluster config at {}. Using default disabled config.", path,
                    exception);
            return ClusterConfig.defaultConfig();
        }
    }

    public static Path resolveConfigPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("multifabricserver-cluster.json");
    }

    private static ClusterConfig parseConfig(JsonObject root) {
        boolean enabled = readBoolean(root, "enabled", false);
        boolean gatewayEnabled = readBoolean(root, "gatewayEnabled", false);
        String gatewayBindHost = readString(root, "gatewayBindHost", "0.0.0.0");
        int gatewayBindPort = readInt(root, "gatewayBindPort", 25565);
        String hostTransferHost = readString(root, "hostTransferHost", "127.0.0.1");
        int hostTransferPort = readInt(root, "hostTransferPort", 25565);
        boolean seamlessProxySwitchEnabled = readBoolean(root, "seamlessProxySwitchEnabled", false);
        String proxyHostServerName = readString(root, "proxyHostServerName", "host");
        List<ClusterNodeDefinition> nodes = new ArrayList<>();

        JsonArray nodeArray = root.has("nodes") && root.get("nodes").isJsonArray()
                ? root.getAsJsonArray("nodes")
                : new JsonArray();

        for (JsonElement nodeElement : nodeArray) {
            if (!nodeElement.isJsonObject()) {
                continue;
            }
            JsonObject nodeObject = nodeElement.getAsJsonObject();
            String id = readString(nodeObject, "id", "");
            String worldName = readString(nodeObject, "worldName", "");
            int listenPort = readInt(nodeObject, "listenPort", 25565);
            boolean nodeEnabled = readBoolean(nodeObject, "enabled", true);
            boolean autoStart = readBoolean(nodeObject, "autoStart", false);
            String transferHost = readString(nodeObject, "transferHost", "127.0.0.1");
            int transferPort = readInt(nodeObject, "transferPort", listenPort);
            String proxyServerName = readString(nodeObject, "proxyServerName", id);

            if (id.isBlank() || worldName.isBlank()) {
                MultiFabricServer.LOGGER.warn("Skipping invalid cluster node with blank id/worldName: {}", nodeObject);
                continue;
            }
            nodes.add(new ClusterNodeDefinition(id, worldName, listenPort, nodeEnabled, autoStart, transferHost,
                    transferPort, proxyServerName));
        }

        return new ClusterConfig(
                enabled,
                gatewayEnabled,
                gatewayBindHost,
                gatewayBindPort,
                hostTransferHost,
                hostTransferPort,
                seamlessProxySwitchEnabled,
                proxyHostServerName,
                List.copyOf(nodes));
    }

    private static String readString(JsonObject object, String key, String fallback) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return object.get(key).getAsString();
    }

    private static int readInt(JsonObject object, String key, int fallback) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean readBoolean(JsonObject object, String key, boolean fallback) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void writeDefault(Path path, ClusterConfig defaultConfig) {
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("enabled", defaultConfig.enabled());
            root.addProperty("gatewayEnabled", defaultConfig.gatewayEnabled());
            root.addProperty("gatewayBindHost", defaultConfig.gatewayBindHost());
            root.addProperty("gatewayBindPort", defaultConfig.gatewayBindPort());
            root.addProperty("hostTransferHost", defaultConfig.hostTransferHost());
            root.addProperty("hostTransferPort", defaultConfig.hostTransferPort());
            root.addProperty("seamlessProxySwitchEnabled", defaultConfig.seamlessProxySwitchEnabled());
            root.addProperty("proxyHostServerName", defaultConfig.proxyHostServerName());

            JsonArray nodes = new JsonArray();
            for (ClusterNodeDefinition node : defaultConfig.nodes()) {
                JsonObject nodeJson = new JsonObject();
                nodeJson.addProperty("id", node.id());
                nodeJson.addProperty("worldName", node.worldName());
                nodeJson.addProperty("listenPort", node.listenPort());
                nodeJson.addProperty("enabled", node.enabled());
                nodeJson.addProperty("autoStart", node.autoStart());
                nodeJson.addProperty("transferHost", node.transferHost());
                nodeJson.addProperty("transferPort", node.transferPort());
                nodeJson.addProperty("proxyServerName", node.proxyServerName());
                nodes.add(nodeJson);
            }
            root.add("nodes", nodes);

            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }

            MultiFabricServer.LOGGER.info("Created default integrated cluster config at {}", path);
        } catch (IOException ioException) {
            MultiFabricServer.LOGGER.error("Failed to write default cluster config at {}", path, ioException);
        }
    }

    public static void save(MinecraftServer server, ClusterConfig config) {
        Path path = resolveConfigPath(server);
        try {
            Files.createDirectories(path.getParent());

            JsonObject root = new JsonObject();
            root.addProperty("enabled", config.enabled());
            root.addProperty("gatewayEnabled", config.gatewayEnabled());
            root.addProperty("gatewayBindHost", config.gatewayBindHost());
            root.addProperty("gatewayBindPort", config.gatewayBindPort());
            root.addProperty("hostTransferHost", config.hostTransferHost());
            root.addProperty("hostTransferPort", config.hostTransferPort());
            root.addProperty("seamlessProxySwitchEnabled", config.seamlessProxySwitchEnabled());
            root.addProperty("proxyHostServerName", config.proxyHostServerName());

            JsonArray nodes = new JsonArray();
            for (ClusterNodeDefinition node : config.nodes()) {
                JsonObject nodeJson = new JsonObject();
                nodeJson.addProperty("id", node.id());
                nodeJson.addProperty("worldName", node.worldName());
                nodeJson.addProperty("listenPort", node.listenPort());
                nodeJson.addProperty("enabled", node.enabled());
                nodeJson.addProperty("autoStart", node.autoStart());
                nodeJson.addProperty("transferHost", node.transferHost());
                nodeJson.addProperty("transferPort", node.transferPort());
                nodeJson.addProperty("proxyServerName", node.proxyServerName());
                nodes.add(nodeJson);
            }
            root.add("nodes", nodes);

            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException ioException) {
            MultiFabricServer.LOGGER.error("Failed to save cluster config at {}", path, ioException);
        }
    }
}
