package fr.jeanney.cluster;

import fr.jeanney.MultiFabricServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Optional;

public final class ProxyForwardingConfig {

    private static final String FABRIC_PROXY_LITE_CONFIG = "FabricProxy-Lite.toml";

    private ProxyForwardingConfig() {
    }

    public static boolean isFabricProxyLiteConfigured(MinecraftServer server) {
        return findFabricProxyLiteConfig(server).isPresent();
    }

    public static void copyFabricProxyLiteConfig(MinecraftServer hostServer, Path nodeRoot) throws IOException {
        Optional<Path> source = findFabricProxyLiteConfig(hostServer);
        if (source.isEmpty()) {
            return;
        }

        Path target = nodeRoot.resolve("config").resolve(FABRIC_PROXY_LITE_CONFIG);
        Files.createDirectories(target.getParent());
        Files.copy(source.get(), target, StandardCopyOption.REPLACE_EXISTING);
        MultiFabricServer.LOGGER.info(
                "Copied proxy forwarding config '{}' into embedded cluster root '{}'",
                source.get(),
                nodeRoot);
    }

    private static Optional<Path> findFabricProxyLiteConfig(MinecraftServer server) {
        if (server == null) {
            return Optional.empty();
        }

        Path worldRoot;
        try {
            worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        } catch (Exception exception) {
            return Optional.empty();
        }

        for (Path serverDirectory : serverDirectoryCandidates(worldRoot)) {
            Path configPath = serverDirectory.resolve("config").resolve(FABRIC_PROXY_LITE_CONFIG);
            if (Files.isRegularFile(configPath)) {
                return Optional.of(configPath);
            }
        }

        return Optional.empty();
    }

    private static ArrayList<Path> serverDirectoryCandidates(Path worldRoot) {
        ArrayList<Path> candidates = new ArrayList<>(3);
        if (worldRoot == null) {
            return candidates;
        }

        candidates.add(worldRoot);

        Path parent = worldRoot.getParent();
        if (parent != null) {
            candidates.add(parent);

            Path grandParent = parent.getParent();
            if (grandParent != null) {
                candidates.add(grandParent);
            }
        }

        return candidates;
    }
}
