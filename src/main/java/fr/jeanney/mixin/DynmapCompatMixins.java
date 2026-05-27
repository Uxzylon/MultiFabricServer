package fr.jeanney.mixin;

import fr.jeanney.MultiFabricServer;
import fr.jeanney.cluster.ClusterDynmapSyncListeners;
import fr.jeanney.cluster.ClusterDynmapWorld;
import fr.jeanney.cluster.ClusterRemovalListeners;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "org.dynmap.fabric_26_1.DynmapPlugin", remap = false)
public abstract class DynmapCompatMixins {
    @Inject(method = "serverStart", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void multifabricserver$skipDynmapServerStartOnChild(MinecraftServer server, CallbackInfo callbackInfo) {
        if (isClusterChildServer(server)) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "serverStarted", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void multifabricserver$skipDynmapServerStartedOnChild(MinecraftServer server, CallbackInfo callbackInfo) {
        if (isClusterChildServer(server)) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "serverStarted", at = @At("TAIL"), remap = false, require = 0)
    private void multifabricserver$syncClusterWorlds(MinecraftServer server, CallbackInfo callbackInfo) {
        if (!isClusterChildServer(server)) {
            syncClusterWorlds(this, server);
        }
    }

    @Inject(method = "serverStop", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void multifabricserver$skipDynmapServerStopOnChild(MinecraftServer server, CallbackInfo callbackInfo) {
        if (isClusterChildServer(server)) {
            callbackInfo.cancel();
        }
    }

    @Unique
    private static boolean isClusterChildServer(MinecraftServer server) {
        return server != null && MultiFabricServer.clusterController().isClusterChildServerForRuntime(server);
    }

    @Unique
    private static Object dynmapPlugin;
    @Unique
    private static boolean removalListenerRegistered;

    @Unique
    private static synchronized void removeClusterWorlds(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }

        removeWorlds(dynmapPlugin, Set.of(nodeId, nodeId + "_nether", nodeId + "_the_end"));
    }

    @Unique
    private static synchronized void syncClusterWorlds(Object plugin, MinecraftServer server) {
        rememberPlugin(plugin);
        removeDisabledOrOrphanedClusterWorlds(plugin, server);
        ensureEnabledClusterWorlds(plugin, server);
    }

    @Unique
    private static void removeDisabledOrOrphanedClusterWorlds(Object plugin, MinecraftServer server) {
        Object worldsObject = readField(plugin, "worlds");
        if (!(worldsObject instanceof Map<?, ?> worlds)) {
            return;
        }

        Set<String> worldsToPrune = new LinkedHashSet<>();
        for (Map.Entry<?, ?> entry : worlds.entrySet()) {
            if (!(entry.getKey() instanceof String worldName)) {
                continue;
            }

            Object world = entry.getValue();
            String title = invokeString(world, "getTitle");
            if (MultiFabricServer.clusterController().shouldPruneDynmapSavedWorld(server, worldName, title, false)) {
                worldsToPrune.add(worldName);
            }
        }

        removeWorlds(plugin, worldsToPrune);
    }

    @Unique
    private static void ensureEnabledClusterWorlds(Object plugin, MinecraftServer server) {
        Object worldsObject = readField(plugin, "worlds");
        if (!(worldsObject instanceof Map<?, ?> worlds)) {
            return;
        }

        Object core = readField(plugin, "core");
        if (core == null) {
            return;
        }

        List<String> addedWorlds = new ArrayList<>();
        for (ClusterDynmapWorld worldDefinition : MultiFabricServer.clusterController()
                .enabledDynmapClusterWorlds(server)) {
            if (worlds.containsKey(worldDefinition.name())) {
                continue;
            }

            Object dynmapWorld = createSavedFabricWorld(plugin, worldDefinition);
            if (dynmapWorld == null) {
                continue;
            }

            invokeNoArgument(dynmapWorld, "setWorldUnloaded");
            invokeOneArgument(core, "processWorldLoad", dynmapWorld);
            putWorld(worlds, worldDefinition.name(), dynmapWorld);
            addedWorlds.add(worldDefinition.name());
        }

        if (!addedWorlds.isEmpty()) {
            invokeNoArgument(core, "updateConfigHashcode");
            MultiFabricServer.LOGGER.info(
                    "Added {} Dynmap cluster world(s): {}",
                    addedWorlds.size(),
                    String.join(", ", addedWorlds));
        }
    }

    @Unique
    private static void rememberPlugin(Object plugin) {
        if (plugin != null) {
            dynmapPlugin = plugin;
        }
        if (!removalListenerRegistered) {
            ClusterRemovalListeners.register(DynmapCompatMixins::removeClusterWorlds);
            ClusterDynmapSyncListeners.register(server -> syncClusterWorlds(dynmapPlugin, server));
            removalListenerRegistered = true;
        }
    }

    @Unique
    private static Object createSavedFabricWorld(Object plugin, ClusterDynmapWorld worldDefinition) {
        try {
            Class<?> fabricWorldClass = Class.forName(
                    "org.dynmap.fabric_26_1.FabricWorld",
                    false,
                    plugin.getClass().getClassLoader());
            Class<?> dynmapPluginClass = Class.forName(
                    "org.dynmap.fabric_26_1.DynmapPlugin",
                    false,
                    plugin.getClass().getClassLoader());
            return fabricWorldClass
                    .getConstructor(
                            dynmapPluginClass,
                            String.class,
                            int.class,
                            int.class,
                            boolean.class,
                            boolean.class,
                            String.class,
                            int.class)
                    .newInstance(
                            plugin,
                            worldDefinition.name(),
                            worldDefinition.height(),
                            worldDefinition.seaLevel(),
                            worldDefinition.nether(),
                            worldDefinition.theEnd(),
                            worldDefinition.title(),
                            worldDefinition.minY());
        } catch (ReflectiveOperationException exception) {
            MultiFabricServer.LOGGER.warn(
                    "Failed to create saved Dynmap world for enabled cluster '{}'",
                    worldDefinition.name(),
                    exception);
            return null;
        }
    }

    @Unique
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void putWorld(Map<?, ?> worlds, String name, Object world) {
        ((Map) worlds).put(name, world);
    }

    @Unique
    private static void removeWorlds(Object plugin, Set<String> worldNames) {
        if (plugin == null || worldNames == null || worldNames.isEmpty()) {
            return;
        }

        Object worldsObject = readField(plugin, "worlds");
        if (!(worldsObject instanceof Map<?, ?> worlds)) {
            return;
        }

        Object mapManager = readField(plugin, "mapManager");
        if (mapManager == null) {
            Object core = readField(plugin, "core");
            mapManager = invokeNoArgument(core, "getMapManager");
        }

        Set<String> removedWorlds = new LinkedHashSet<>();
        for (String worldName : worldNames) {
            if (worldName == null || worldName.isBlank()) {
                continue;
            }

            boolean removed = worlds.remove(worldName) != null;
            invokeStringArgument(mapManager, "deactivateWorld", worldName);
            if (removed) {
                removedWorlds.add(worldName);
            }
        }

        if (!removedWorlds.isEmpty()) {
            Object core = readField(plugin, "core");
            invokeNoArgument(core, "updateConfigHashcode");
            MultiFabricServer.LOGGER.info(
                    "Removed {} Dynmap cluster world(s): {}",
                    removedWorlds.size(),
                    String.join(", ", removedWorlds));
        }
    }

    @Unique
    private static Object readField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }

        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    @Unique
    private static Object invokeNoArgument(Object target, String methodName) {
        if (target == null) {
            return null;
        }

        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @Unique
    private static String invokeString(Object target, String methodName) {
        Object value = invokeNoArgument(target, methodName);
        return value instanceof String stringValue ? stringValue : null;
    }

    @Unique
    private static void invokeStringArgument(Object target, String methodName, String argument) {
        if (target == null) {
            return;
        }

        try {
            Method method = target.getClass().getMethod(methodName, String.class);
            method.setAccessible(true);
            method.invoke(target, argument);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @Unique
    private static Object invokeOneArgument(Object target, String methodName, Object argument) {
        if (target == null || argument == null) {
            return null;
        }

        for (Method method : target.getClass().getMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (!methodName.equals(method.getName()) || parameterTypes.length != 1
                    || !parameterTypes[0].isAssignableFrom(argument.getClass())) {
                continue;
            }

            try {
                method.setAccessible(true);
                return method.invoke(target, argument);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }

        return null;
    }
}

@Pseudo
@Mixin(targets = "org.dynmap.fabric_26_1.FabricServer", remap = false)
abstract class DynmapFabricServerClusterPlayersMixin {
    @Shadow
    private MinecraftServer server;

    @Inject(method = "tickEvent(Lnet/minecraft/server/MinecraftServer;)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void multifabricserver$skipDynmapTickOnChild(
            MinecraftServer tickingServer, CallbackInfo callbackInfo) {
        if (MultiFabricServer.clusterController().isClusterChildServerForRuntime(tickingServer)) {
            callbackInfo.cancel();
        }
    }

    @Redirect(method = "getOnlinePlayers", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/"
            + "PlayerList;getPlayers()Ljava/util/List;"), remap = false, require = 0)
    private List<ServerPlayer> multifabricserver$includeClusterPlayersForDynmap(PlayerList playerList) {
        List<ServerPlayer> aggregatedPlayers = MultiFabricServer.clusterController().dynmapOnlinePlayers(server);
        if (aggregatedPlayers.isEmpty()) {
            return playerList.getPlayers();
        }
        return aggregatedPlayers;
    }
}

@Pseudo
@Mixin(targets = "org.dynmap.fabric_26_1.FabricWorld", remap = false)
abstract class DynmapFabricWorldClusterNamingMixin {
    @Inject(method = "getWorldName", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void multifabricserver$namespaceChildWorldName(
            @Coerce Object plugin, ServerLevel level, CallbackInfoReturnable<String> callbackInfoReturnable) {
        String resolved = resolveWorldName(level, callbackInfoReturnable.getReturnValue());
        callbackInfoReturnable.setReturnValue(resolved);
    }

    @Inject(method = "<init>(Lorg/dynmap/fabric_26_1/DynmapPlugin;Lnet/"
            + "minecraft/server/level/ServerLevel;)V", at = @At("TAIL"), remap = false, require = 0)
    private void multifabricserver$renameChildWorldTitle(
            @Coerce Object plugin, ServerLevel level, CallbackInfo callbackInfo) {
        String title = resolveWorldTitle(level, null);
        if (title == null || title.isBlank()) {
            return;
        }
        invokeSetTitle(this, title);
    }

    @Unique
    private static void invokeSetTitle(Object worldInstance, String title) {
        try {
            Method setTitle = worldInstance.getClass().getMethod("setTitle", String.class);
            setTitle.invoke(worldInstance, title);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @Unique
    private static String resolveWorldName(ServerLevel level, String fallbackName) {
        String nodeId = resolveClusterNodeId(level);
        if (nodeId == null || nodeId.isBlank()) {
            return fallbackName;
        }

        ResourceKey<Level> dimensionKey = level.dimension();
        if (dimensionKey == Level.OVERWORLD) {
            return nodeId;
        }
        if (dimensionKey == Level.NETHER) {
            return nodeId + "_nether";
        }
        if (dimensionKey == Level.END) {
            return nodeId + "_the_end";
        }

        Identifier identifier = dimensionKey.identifier();
        String suffix = sanitizeSegment(identifier.getNamespace() + "_" + identifier.getPath());
        if (suffix.isBlank()) {
            return nodeId + "_dimension";
        }
        return nodeId + "__" + suffix;
    }

    @Unique
    private static String resolveWorldTitle(ServerLevel level, String fallbackTitle) {
        String nodeId = resolveClusterNodeId(level);
        if (nodeId == null || nodeId.isBlank()) {
            return fallbackTitle;
        }

        ResourceKey<Level> dimensionKey = level.dimension();
        if (dimensionKey == Level.OVERWORLD) {
            return nodeId + " (overworld)";
        }
        if (dimensionKey == Level.NETHER) {
            return nodeId + " (nether)";
        }
        if (dimensionKey == Level.END) {
            return nodeId + " (end)";
        }

        Identifier identifier = dimensionKey.identifier();
        return nodeId + " (" + identifier.getNamespace() + ":" + identifier.getPath() + ")";
    }

    @Unique
    private static String resolveClusterNodeId(ServerLevel level) {
        if (level == null) {
            return null;
        }

        MinecraftServer server = level.getServer();
        if (server == null || !MultiFabricServer.clusterController().isClusterChildServerForRuntime(server)) {
            return null;
        }

        Path rootPath;
        try {
            rootPath = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            return null;
        }

        List<String> segments = new ArrayList<>();
        for (Path segment : rootPath) {
            segments.add(segment.toString());
        }

        for (int index = 0; index < segments.size() - 1; index++) {
            if ("cluster-runtime".equals(segments.get(index))) {
                String candidate = sanitizeSegment(segments.get(index + 1));
                return candidate.isBlank() ? null : candidate;
            }
        }

        return null;
    }

    @Unique
    private static String sanitizeSegment(String value) {
        if (value == null) {
            return "";
        }

        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder sanitized = sanitizedSegmentBuilder(lower);
        while (!sanitized.isEmpty() && sanitized.charAt(0) == '_') {
            sanitized.deleteCharAt(0);
        }
        while (!sanitized.isEmpty() && sanitized.charAt(sanitized.length() - 1) == '_') {
            sanitized.deleteCharAt(sanitized.length() - 1);
        }

        return sanitized.toString();
    }

    @Unique
    private static StringBuilder sanitizedSegmentBuilder(String lower) {
        StringBuilder sanitized = new StringBuilder(lower.length());
        boolean lastWasUnderscore = false;
        for (int index = 0; index < lower.length(); index++) {
            char ch = lower.charAt(index);
            boolean allowed = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
            if (allowed) {
                sanitized.append(ch);
                lastWasUnderscore = false;
                continue;
            }

            if (!lastWasUnderscore) {
                sanitized.append('_');
                lastWasUnderscore = true;
            }
        }
        return sanitized;
    }
}

@Pseudo
@Mixin(targets = "org.dynmap.fabric_26_1.DynmapPlugin$PlayerTracker", remap = false)
abstract class DynmapPlayerTrackerTravelLogoutSuppressMixin {
    @Inject(method = "onPlayerLogout", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void multifabricserver$suppressTravelLogout(ServerPlayer player, CallbackInfo callbackInfo) {
        if (MultiFabricServer.clusterController().consumeDynmapLogoutSuppression(player)) {
            callbackInfo.cancel();
        }
    }
}
