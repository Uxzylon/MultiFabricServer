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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class ClusterRuntimeStateStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ClusterRuntimeStateStore() {
    }

    static RuntimeState load(MinecraftServer server) {
        Path path = resolvePath(server);
        if (!Files.exists(path)) {
            return RuntimeState.empty();
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement rootElement = JsonParser.parseReader(reader);
            if (!rootElement.isJsonObject()) {
                return RuntimeState.empty();
            }

            JsonObject root = rootElement.getAsJsonObject();

            Set<String> activeNodes = new LinkedHashSet<>();
            JsonArray activeArray = root.has("activeNodes") && root.get("activeNodes").isJsonArray()
                    ? root.getAsJsonArray("activeNodes")
                    : new JsonArray();
            for (JsonElement activeNode : activeArray) {
                if (!activeNode.isJsonPrimitive()) {
                    continue;
                }
                String nodeId = activeNode.getAsString();
                if (!nodeId.isBlank()) {
                    activeNodes.add(nodeId);
                }
            }

            Map<String, String> playerClusters = new LinkedHashMap<>();
            JsonObject playerMap = root.has("playerClusters") && root.get("playerClusters").isJsonObject()
                    ? root.getAsJsonObject("playerClusters")
                    : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : playerMap.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                String uuid = entry.getKey();
                String nodeId = entry.getValue().getAsString();
                if (!uuid.isBlank() && !nodeId.isBlank()) {
                    playerClusters.put(uuid, nodeId);
                }
            }

            return new RuntimeState(activeNodes, playerClusters);
        } catch (Exception exception) {
            MultiFabricServer.LOGGER.error("Failed to parse cluster runtime state at {}. Using empty state.", path,
                    exception);
            return RuntimeState.empty();
        }
    }

    static void save(MinecraftServer server, Set<String> activeNodes, Map<String, String> playerClusters) {
        Path path = resolvePath(server);
        try {
            Files.createDirectories(path.getParent());

            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(runtimeStateJson(activeNodes, playerClusters), writer);
            }
        } catch (IOException ioException) {
            MultiFabricServer.LOGGER.error("Failed to persist cluster runtime state at {}", path, ioException);
        }
    }

    private static JsonObject runtimeStateJson(Set<String> activeNodes, Map<String, String> playerClusters) {
        JsonObject root = new JsonObject();

        JsonArray activeArray = new JsonArray();
        for (String nodeId : activeNodes) {
            activeArray.add(nodeId);
        }
        root.add("activeNodes", activeArray);

        JsonObject players = new JsonObject();
        for (Map.Entry<String, String> entry : playerClusters.entrySet()) {
            players.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("playerClusters", players);

        return root;
    }

    private static Path resolvePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("multifabricserver-cluster-state.json");
    }

    record RuntimeState(Set<String> activeNodes, Map<String, String> playerClusters) {
        RuntimeState {
            activeNodes = Set.copyOf(activeNodes);
            playerClusters = Map.copyOf(playerClusters);
        }

        static RuntimeState empty() {
            return new RuntimeState(Set.of(), Map.of());
        }
    }
}
