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

final class ClusterConfigLoader {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ClusterConfigLoader() {
    }

    static ClusterConfig load(MinecraftServer server) {
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
            ClusterConfig config = parseConfig(element.getAsJsonObject());
            writeConfig(path, config);
            return config;
        } catch (Exception exception) {
            MultiFabricServer.LOGGER.error("Failed to parse cluster config at {}. Using default disabled config.", path,
                    exception);
            return ClusterConfig.defaultConfig();
        }
    }

    static Path resolveConfigPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("multifabricserver-cluster.json");
    }

    private static ClusterConfig parseConfig(JsonObject root) {
        String proxyHostServerName = readString(root, "proxyHostServerName",
                ClusterConfig.DEFAULT_PROXY_HOST_SERVER_NAME);
        int nodeIdleStopSeconds = readInt(root, "nodeIdleStopSeconds",
                ClusterConfig.DEFAULT_NODE_IDLE_STOP_SECONDS);
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
            boolean nodeEnabled = readBoolean(nodeObject, "enabled", true);

            if (id.isBlank()) {
                MultiFabricServer.LOGGER.warn("Skipping invalid cluster node with blank id: {}", nodeObject);
                continue;
            }
            nodes.add(new ClusterNodeDefinition(id, nodeEnabled));
        }

        return new ClusterConfig(
                proxyHostServerName,
                nodeIdleStopSeconds,
                List.copyOf(nodes));
    }

    private static String readString(JsonObject object, String key, String fallback) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return object.get(key).getAsString();
    }

    private static boolean readBoolean(JsonObject object, String key, boolean fallback) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int readInt(JsonObject object, String key, int fallback) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            int parsed = object.get(key).getAsInt();
            return parsed < 0 ? fallback : parsed;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static void writeDefault(Path path, ClusterConfig defaultConfig) {
        try {
            writeConfig(path, defaultConfig);
            MultiFabricServer.LOGGER.info("Created default integrated cluster config at {}", path);
        } catch (IOException ioException) {
            MultiFabricServer.LOGGER.error("Failed to write default cluster config at {}", path, ioException);
        }
    }

    static void save(MinecraftServer server, ClusterConfig config) {
        Path path = resolveConfigPath(server);
        try {
            writeConfig(path, config);
        } catch (IOException ioException) {
            MultiFabricServer.LOGGER.error("Failed to save cluster config at {}", path, ioException);
        }
    }

    private static void writeConfig(Path path, ClusterConfig config) throws IOException {
        Files.createDirectories(path.getParent());

        JsonObject root = new JsonObject();
        root.addProperty("proxyHostServerName", config.proxyHostServerName());
        root.addProperty("nodeIdleStopSeconds", config.nodeIdleStopSeconds());

        JsonArray nodes = new JsonArray();
        for (ClusterNodeDefinition node : config.nodes()) {
            JsonObject nodeJson = new JsonObject();
            writeNodeJson(nodeJson, node);
            nodes.add(nodeJson);
        }
        root.add("nodes", nodes);

        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }

    private static void writeNodeJson(JsonObject nodeJson, ClusterNodeDefinition node) {
        nodeJson.addProperty("id", node.id());
        nodeJson.addProperty("enabled", node.enabled());
    }
}
