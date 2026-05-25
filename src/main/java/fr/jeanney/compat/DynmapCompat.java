package fr.jeanney.compat;

import fr.jeanney.MultiFabricServer;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class DynmapCompat {

    private static Object dynmapPlugin;

    private DynmapCompat() {
    }

    public static synchronized void rememberPlugin(Object plugin) {
        if (plugin != null) {
            dynmapPlugin = plugin;
        }
    }

    public static synchronized void pruneOrphanedClusterWorlds(Object plugin, MinecraftServer server) {
        rememberPlugin(plugin);
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
            boolean loaded = invokeBoolean(world, "isLoaded");
            if (MultiFabricServer.clusterController().shouldPruneDynmapSavedWorld(
                    server,
                    worldName,
                    title,
                    loaded)) {
                worldsToPrune.add(worldName);
            }
        }

        removeWorlds(plugin, worldsToPrune);
    }

    public static synchronized void removeClusterWorlds(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }

        Set<String> worldsToRemove = Set.of(nodeId, nodeId + "_nether", nodeId + "_the_end");
        removeWorlds(dynmapPlugin, worldsToRemove);
    }

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
            MultiFabricServer.LOGGER.info("Removed {} Dynmap cluster world(s): {}",
                    removedWorlds.size(),
                    String.join(", ", removedWorlds));
        }
    }

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

    private static String invokeString(Object target, String methodName) {
        Object value = invokeNoArgument(target, methodName);
        return value instanceof String stringValue ? stringValue : null;
    }

    private static boolean invokeBoolean(Object target, String methodName) {
        Object value = invokeNoArgument(target, methodName);
        return value instanceof Boolean booleanValue && booleanValue;
    }

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
}
