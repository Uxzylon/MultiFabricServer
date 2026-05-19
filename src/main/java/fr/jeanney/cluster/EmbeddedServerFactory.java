package fr.jeanney.cluster;

import fr.jeanney.MultiFabricServer;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.dedicated.DedicatedServerSettings;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.util.Util;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.PrimaryLevelData;

import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Properties;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class EmbeddedServerFactory {

    private static final long STARTUP_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);
    private static final long STOP_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private EmbeddedServerFactory() {
    }

    public static Optional<EmbeddedServerHandle> tryStartEmbeddedServer(
            MinecraftServer hostServer,
            ClusterNodeDefinition definition,
            Path nodeRoot) {
        MultiFabricServer.LOGGER.info(
                "[cluster-stop-debug] child start begin node={} host={} root={} thread={}",
                definition.id(),
                describeServer(hostServer),
                nodeRoot,
                Thread.currentThread().getName());

        LevelStorageSource.LevelStorageAccess levelStorageAccess = null;
        try {
            Files.createDirectories(nodeRoot);

            int listenPort = resolveListenPort(definition, nodeRoot);

            DedicatedServerSettings settings = prepareServerSettings(hostServer, nodeRoot, definition, listenPort);
            LevelStorageSource levelStorageSource = LevelStorageSource.createDefault(
                    Objects.requireNonNull(nodeRoot.resolve("universe")));
            levelStorageAccess = levelStorageSource.createAccess(Objects.requireNonNull(definition.worldName()));
            PackRepository packRepository = ServerPacksSource.createPackRepository(levelStorageAccess);
            WorldStem worldStem = buildWorldStem(settings.getProperties(), levelStorageAccess, packRepository);

            LevelStorageSource.LevelStorageAccess finalLevelStorageAccess = levelStorageAccess;
            MinecraftServer childServer = MinecraftServer.spin(thread -> instantiateDedicatedServer(
                    thread,
                    hostServer,
                    finalLevelStorageAccess,
                    packRepository,
                    worldStem,
                    settings,
                    listenPort));

            levelStorageAccess = null; // Child server now owns and closes this session.
            waitUntilReady(definition, childServer, listenPort);

            int resolvedListenPort = childServer.getPort() > 0 ? childServer.getPort() : listenPort;
            int resolvedTransferPort = resolvedListenPort;

            MultiFabricServer.LOGGER.info(
                    "Embedded child server '{}' is running for world '{}' on port {}",
                    definition.id(),
                    definition.worldName(),
                    resolvedListenPort);

            MultiFabricServer.LOGGER.info(
                    "[cluster-stop-debug] child start complete node={} child={} port={} running={} stopped={} thread={}",
                    definition.id(),
                    describeServer(childServer),
                    resolvedListenPort,
                    childServer.isRunning(),
                    childServer.isStopped(),
                    Thread.currentThread().getName());

            return Optional.of(new DedicatedEmbeddedServerHandle(
                    definition.id(),
                    childServer,
                    resolvedListenPort,
                    resolvedTransferPort));
        } catch (Exception exception) {
            MultiFabricServer.LOGGER.error(
                    "Failed to start embedded child server '{}' for world '{}'",
                    definition.id(),
                    definition.worldName(),
                    exception);
            MultiFabricServer.LOGGER.error(
                    "[cluster-stop-debug] child start failure node={} host={} thread={}",
                    definition.id(),
                    describeServer(hostServer),
                    Thread.currentThread().getName());
            if (levelStorageAccess != null) {
                try {
                    levelStorageAccess.close();
                } catch (IOException ignored) {
                }
            }
            return Optional.empty();
        }
    }

    private static DedicatedServerSettings prepareServerSettings(
            MinecraftServer hostServer,
            Path nodeRoot,
            ClusterNodeDefinition definition,
            int listenPort)
            throws IOException {
        Path serverPropertiesPath = nodeRoot.resolve("server.properties");
        Files.createDirectories(serverPropertiesPath.getParent());
        ProxyForwardingConfig.copyFabricProxyLiteConfig(hostServer, nodeRoot);

        Properties properties = new Properties();
        if (Files.exists(serverPropertiesPath)) {
            try (Reader reader = Files.newBufferedReader(serverPropertiesPath, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }

        boolean hostSupportsAuthentication = hostServer.services().profileRepository() != null;
        boolean onlineMode = hostSupportsAuthentication
                && readHostServerBooleanProperty(hostServer, "online-mode", true);
        // Default to non-enforced secure profile unless explicitly configured on host.
        boolean enforceSecureProfile = onlineMode
                && readHostServerBooleanProperty(hostServer, "enforce-secure-profile", false);
        boolean acceptsTransfers = true;

        properties.setProperty("level-name", definition.worldName());
        properties.setProperty("server-port", Integer.toString(listenPort));
        properties.setProperty("server-ip", "127.0.0.1");
        properties.setProperty("online-mode", Boolean.toString(onlineMode));
        properties.setProperty("enforce-secure-profile", Boolean.toString(enforceSecureProfile));
        properties.setProperty("accepts-transfers", Boolean.toString(acceptsTransfers));
        properties.setProperty("prevent-proxy-connections", "false");
        properties.setProperty("enable-query", "false");
        properties.setProperty("enable-rcon", "false");
        properties.setProperty("management-server-enabled", "false");
        properties.setProperty("pause-when-empty-seconds", "0");
        properties.setProperty("max-players", "8");

        try (Writer writer = Files.newBufferedWriter(serverPropertiesPath, StandardCharsets.UTF_8)) {
            properties.store(writer, "Generated by MultiFabricServer integrated cluster runtime");
        }

        DedicatedServerSettings settings = new DedicatedServerSettings(serverPropertiesPath);
        settings.forceSave();
        return settings;
    }

    private static int resolveListenPort(ClusterNodeDefinition definition, Path nodeRoot) throws IOException {
        Path serverPropertiesPath = nodeRoot.resolve("server.properties");
        if (Files.exists(serverPropertiesPath)) {
            Properties existing = new Properties();
            try (Reader reader = Files.newBufferedReader(serverPropertiesPath, StandardCharsets.UTF_8)) {
                existing.load(reader);
            }

            String existingPort = existing.getProperty("server-port");
            if (existingPort != null) {
                try {
                    int parsed = Integer.parseInt(existingPort.trim());
                    if (parsed > 0) {
                        return parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // Fall back to dynamic assignment below.
                }
            }
        }

        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            int dynamicPort = socket.getLocalPort();
            if (dynamicPort <= 0) {
                throw new IllegalStateException("Failed to allocate an available dynamic port");
            }
            return dynamicPort;
        }
    }

    private static boolean readHostServerBooleanProperty(MinecraftServer hostServer, String key,
            boolean fallbackValue) {
        Path hostWorldRoot;
        try {
            hostWorldRoot = hostServer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        } catch (Exception exception) {
            return fallbackValue;
        }

        Path hostPropertiesPath = resolveHostServerPropertiesPath(hostWorldRoot);
        if (hostPropertiesPath == null || !Files.exists(hostPropertiesPath)) {
            return fallbackValue;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(hostPropertiesPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ioException) {
            return fallbackValue;
        }

        return Boolean.parseBoolean(properties.getProperty(key, Boolean.toString(fallbackValue)));
    }

    private static Path resolveHostServerPropertiesPath(Path hostWorldRoot) {
        if (hostWorldRoot == null) {
            return null;
        }

        for (Path serverDirectory : resolveHostServerDirectoryCandidates(hostWorldRoot)) {
            Path propertiesPath = serverDirectory.resolve("server.properties");
            if (Files.exists(propertiesPath)) {
                return propertiesPath;
            }
        }

        return null;
    }

    private static ArrayList<Path> resolveHostServerDirectoryCandidates(Path hostWorldRoot) {
        ArrayList<Path> candidates = new ArrayList<>(3);
        if (hostWorldRoot == null) {
            return candidates;
        }

        candidates.add(hostWorldRoot);

        Path parent = hostWorldRoot.getParent();
        if (parent != null) {
            candidates.add(parent);

            Path grandParent = parent.getParent();
            if (grandParent != null) {
                candidates.add(grandParent);
            }
        }

        return candidates;
    }

    private static Services resolveServices(MinecraftServer hostServer) {
        return hostServer.services();
    }

    private static MinecraftServer instantiateDedicatedServer(
            Thread thread,
            MinecraftServer hostServer,
            LevelStorageSource.LevelStorageAccess levelStorageAccess,
            PackRepository packRepository,
            WorldStem worldStem,
            DedicatedServerSettings settings,
            int listenPort) {
        try {
            for (Constructor<?> constructor : DedicatedServer.class.getConstructors()) {
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length == 7) {
                    Object instance = constructor.newInstance(
                            thread,
                            levelStorageAccess,
                            packRepository,
                            worldStem,
                            settings,
                            hostServer.getFixerUpper(),
                            resolveServices(hostServer));
                    MinecraftServer server = (MinecraftServer) Objects.requireNonNull(
                            instance,
                            "DedicatedServer constructor returned null");
                    server.setPort(listenPort);
                    return server;
                }
                if (parameterTypes.length == 8) {
                    Object instance = constructor.newInstance(
                            thread,
                            levelStorageAccess,
                            packRepository,
                            worldStem,
                            Optional.empty(),
                            settings,
                            hostServer.getFixerUpper(),
                            resolveServices(hostServer));
                    MinecraftServer server = (MinecraftServer) Objects.requireNonNull(
                            instance,
                            "DedicatedServer constructor returned null");
                    server.setPort(listenPort);
                    return server;
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to instantiate DedicatedServer reflectively", exception);
        }

        throw new IllegalStateException("No supported DedicatedServer constructor was found");
    }

    private static WorldStem buildWorldStem(
            DedicatedServerProperties dedicatedServerProperties,
            LevelStorageSource.LevelStorageAccess levelStorageAccess,
            PackRepository packRepository) throws Exception {
        WorldLoader.InitConfig initConfig = createInitConfig(dedicatedServerProperties, packRepository);
        Dynamic<?> existingWorldData = null;
        if (levelStorageAccess.hasWorldData()) {
            existingWorldData = levelStorageAccess.getUnfixedDataTagWithFallback();
        }

        Dynamic<?> finalExistingWorldData = existingWorldData;

        return Util.blockUntilDone(executor -> WorldLoader.load(
                Objects.requireNonNull(initConfig),
                dataLoadContext -> {
                    Registry<LevelStem> levelStemRegistry = dataLoadContext
                            .datapackDimensions()
                            .lookupOrThrow(Registries.LEVEL_STEM);

                    if (finalExistingWorldData != null) {
                        LevelDataAndDimensions levelDataAndDimensions = LevelStorageSource.getLevelDataAndDimensions(
                                levelStorageAccess,
                                finalExistingWorldData,
                                dataLoadContext.dataConfiguration(),
                                levelStemRegistry,
                                dataLoadContext.datapackWorldgen());

                        return new WorldLoader.DataLoadOutput<>(
                                levelDataAndDimensions.worldDataAndGenSettings(),
                                levelDataAndDimensions.dimensions().dimensionsRegistryAccess());
                    }

                    return Objects.requireNonNull(
                            createNewWorldData(dedicatedServerProperties, dataLoadContext, levelStemRegistry));
                },
                WorldStem::new,
                Util.backgroundExecutor(),
                Objects.requireNonNull(executor))).get();
    }

    private static WorldLoader.InitConfig createInitConfig(
            DedicatedServerProperties dedicatedServerProperties,
            PackRepository packRepository) {
        WorldDataConfiguration worldDataConfiguration = new WorldDataConfiguration(
                dedicatedServerProperties.initialDataPackConfiguration,
                FeatureFlags.DEFAULT_FLAGS);

        WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(
                Objects.requireNonNull(packRepository),
                worldDataConfiguration,
                false,
                true);

        return new WorldLoader.InitConfig(
                packConfig,
                Commands.CommandSelection.DEDICATED,
                dedicatedServerProperties.functionPermissions);
    }

    private static WorldLoader.DataLoadOutput<LevelDataAndDimensions.@NonNull WorldDataAndGenSettings> createNewWorldData(
            DedicatedServerProperties dedicatedServerProperties,
            WorldLoader.DataLoadContext dataLoadContext,
            Registry<LevelStem> levelStemRegistry) {
        LevelSettings levelSettings = new LevelSettings(
                dedicatedServerProperties.levelName,
                dedicatedServerProperties.gameMode.get(),
                new LevelSettings.DifficultySettings(
                        dedicatedServerProperties.difficulty.get(),
                        dedicatedServerProperties.hardcore,
                        false),
                false,
                dataLoadContext.dataConfiguration());

        WorldDimensions worldDimensions = dedicatedServerProperties
                .createDimensions(dataLoadContext.datapackWorldgen());
        WorldDimensions.Complete complete = worldDimensions.bake(Objects.requireNonNull(levelStemRegistry));
        Lifecycle lifecycle = complete.lifecycle().add(dataLoadContext.datapackWorldgen().allRegistriesLifecycle());
        PrimaryLevelData primaryLevelData = new PrimaryLevelData(
                levelSettings,
                complete.specialWorldProperty(),
                Objects.requireNonNull(lifecycle));

        LevelDataAndDimensions.WorldDataAndGenSettings worldDataAndGenSettings = new LevelDataAndDimensions.WorldDataAndGenSettings(
                primaryLevelData,
                new WorldGenSettings(dedicatedServerProperties.worldOptions, worldDimensions));

        return new WorldLoader.DataLoadOutput<>(
                worldDataAndGenSettings,
                complete.dimensionsRegistryAccess());
    }

    private static void waitUntilReady(ClusterNodeDefinition definition, MinecraftServer server, int listenPort)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MILLIS;
        int loopCount = 0;
        while (System.currentTimeMillis() < deadline) {
            loopCount++;
            if (server.isRunning()
                    && !server.isStopped()
                    && server.getTickCount() > 0
                    && isEndpointReachable("127.0.0.1", listenPort)) {
                MultiFabricServer.LOGGER.info(
                        "[cluster-stop-debug] child ready node={} child={} port={} loops={} running={} stopped={} tick={} thread={}",
                        definition.id(),
                        describeServer(server),
                        listenPort,
                        loopCount,
                        server.isRunning(),
                        server.isStopped(),
                        server.getTickCount(),
                        Thread.currentThread().getName());
                return;
            }
            if (server.isStopped()) {
                MultiFabricServer.LOGGER.warn(
                        "[cluster-stop-debug] child stopped during startup node={} child={} loops={} thread={}",
                        definition.id(),
                        describeServer(server),
                        loopCount,
                        Thread.currentThread().getName());
                throw new IllegalStateException(
                        "Child server stopped during startup for node '" + definition.id() + "'");
            }
            Thread.sleep(100L);
        }
        MultiFabricServer.LOGGER.error(
                "[cluster-stop-debug] child startup timeout node={} child={} loops={} running={} stopped={} thread={}",
                definition.id(),
                describeServer(server),
                loopCount,
                server.isRunning(),
                server.isStopped(),
                Thread.currentThread().getName());
        server.halt(false);
        throw new IllegalStateException("Timeout while waiting for embedded child server to become ready");
    }

    private static boolean isEndpointReachable(String host, int port) {
        if (host == null || host.isBlank() || port <= 0) {
            return false;
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 150);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private record DedicatedEmbeddedServerHandle(String nodeId, MinecraftServer server, int resolvedListenPort,
            int resolvedTransferPort)
            implements EmbeddedServerHandle {
        @Override
        public boolean isAlive() {
            return server.isRunning() && !server.isStopped();
        }

        @Override
        public java.util.OptionalInt listenPort() {
            return resolvedListenPort > 0
                    ? java.util.OptionalInt.of(resolvedListenPort)
                    : java.util.OptionalInt.empty();
        }

        @Override
        public java.util.OptionalInt transferPort() {
            return resolvedTransferPort > 0
                    ? java.util.OptionalInt.of(resolvedTransferPort)
                    : listenPort();
        }

        @Override
        public void tick() {
            if (!isAlive()) {
                MultiFabricServer.LOGGER.warn("Embedded child server '{}' is no longer alive", nodeId);
            }
        }

        @Override
        public void close() {
            if (server.isStopped()) {
                MultiFabricServer.LOGGER.info(
                        "[cluster-stop-debug] child close skipped node={} child={} alreadyStopped=true thread={}",
                        nodeId,
                        describeServer(server),
                        Thread.currentThread().getName());
                return;
            }

            MultiFabricServer.LOGGER.info(
                    "[cluster-stop-debug] child close begin node={} child={} running={} stopped={} tick={} thread={}",
                    nodeId,
                    describeServer(server),
                    server.isRunning(),
                    server.isStopped(),
                    server.getTickCount(),
                    Thread.currentThread().getName());
            server.halt(false);

            Thread childThread = resolveServerThread(server);

            long deadline = System.currentTimeMillis() + STOP_TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                boolean childThreadAlive = childThread != null ? childThread.isAlive() : !server.isStopped();
                if (!childThreadAlive) {
                    break;
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            boolean childThreadAlive = childThread != null ? childThread.isAlive() : !server.isStopped();
            if (childThreadAlive) {
                MultiFabricServer.LOGGER.warn(
                        "[cluster-stop-debug] child close timeout node={} child={} running={} stopped={} childThreadAlive={} tick={} thread={}",
                        nodeId,
                        describeServer(server),
                        server.isRunning(),
                        server.isStopped(),
                        childThreadAlive,
                        server.getTickCount(),
                        Thread.currentThread().getName());
                logServerThreadStack(nodeId, server);
            }

            MultiFabricServer.LOGGER.info(
                    "[cluster-stop-debug] child close end node={} child={} running={} stopped={} childThreadAlive={} tick={} thread={}",
                    nodeId,
                    describeServer(server),
                    server.isRunning(),
                    server.isStopped(),
                    childThreadAlive,
                    server.getTickCount(),
                    Thread.currentThread().getName());
        }
    }

    private static String describeServer(MinecraftServer server) {
        return server.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(server));
    }

    private static Thread resolveServerThread(MinecraftServer server) {
        try {
            Field serverThreadField = MinecraftServer.class.getDeclaredField("serverThread");
            serverThreadField.setAccessible(true);
            Object threadObj = serverThreadField.get(server);
            if (threadObj instanceof Thread thread) {
                return thread;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void logServerThreadStack(String nodeId, MinecraftServer server) {
        try {
            Thread serverThread = resolveServerThread(server);
            if (serverThread == null) {
                return;
            }

            StackTraceElement[] stack = serverThread.getStackTrace();
            if (stack.length == 0) {
                MultiFabricServer.LOGGER.info(
                        "[cluster-stop-debug] child close timeout stack empty node={} childThread={}",
                        nodeId,
                        serverThread.getName());
                return;
            }

            StringBuilder stackTrace = new StringBuilder();
            for (StackTraceElement element : stack) {
                stackTrace.append("\n    at ").append(element);
            }

            MultiFabricServer.LOGGER.warn(
                    "[cluster-stop-debug] child close timeout thread dump node={} childThread={} state={}{}",
                    nodeId,
                    serverThread.getName(),
                    serverThread.getState(),
                    stackTrace);
        } catch (Exception exception) {
            MultiFabricServer.LOGGER.warn(
                    "[cluster-stop-debug] failed to capture child thread stack node={} child={}",
                    nodeId,
                    describeServer(server),
                    exception);
        }
    }
}
